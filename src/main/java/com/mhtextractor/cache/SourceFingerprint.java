package com.mhtextractor.cache;

import com.mhtextractor.util.SourceKinds;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * 「来源附属文件」指纹（R6.5）—— 预览缓存键的第五个要素。
 *
 * <h2>为什么需要它</h2>
 * <p>R6 之前每个来源就是一个自包含的 {@code .mht}，四要素（绝对路径 + size + mtime + 参数指纹）
 * 足以判定"内容变了没有"。HTML 打破了这个前提：图片在<b>同目录的侧车文件夹</b>里
 * （{@code page.html} + {@code page_files/*.png}）。只替换侧车里的一张图，
 * <b>HTML 文件的 size/mtime 完全不变</b> ⇒ 四要素判定"没变" ⇒ 命中陈旧缓存，
 * 用户看到改动前的图且没有任何提示。本类把侧车资源的摘要折进键里。</p>
 *
 * <h2>算法（内容摘要，不是 mtime 摘要）</h2>
 * <p>对侧车目录（{@code <基名>_files}）下<b>全部常规文件</b>（不含子目录 —— 与解析器的支持面一致，
 * 见 {@code HtmlFolderParser}）按<b>相对文件名排序</b>，逐个把
 * {@code 相对名 + '\0' + 文件字节 + '\0'} 喂进一个 SHA-256，返回 64 位十六进制。</p>
 * <p>刻意用<b>文件内容</b>而不是 size+mtime：一是内容变了 key 必变（哪怕 size 巧合相同），
 * 二是免受文件系统时间戳精度影响（同一秒内两次写入在某些卷上 mtime 相同）。
 * 代价是要读一遍侧车字节 —— 但侧车目录正是预览随后就要解码的内容，量级相同。
 * 为保证极端情况下的响应性，单文件超过 {@link #MAX_BYTES_PER_FILE} 时退化为
 * "相对名 + size + mtime"参与摘要（并在 javadoc 与本类注释里明示该退化）。</p>
 *
 * <p><b>MHT 来源恒返回空串</b>（没有配套侧车目录），因此 MHT 的缓存键与 R6 之前<b>一致</b>——
 * 唯一的差别是 {@link PreviewCacheMeta#FORMAT_VERSION} 已递增（R6.5 要求），
 * 旧缓存自然整体失效。</p>
 *
 * <p>本类在 {@code cache} 包内，零 JavaFX、零新增依赖（NIO + JCA），可无头单测。</p>
 */
public final class SourceFingerprint {

    /** 空指纹（MHT 来源 / 无侧车） */
    public static final String EMPTY = "";

    /** 单文件参与"内容摘要"的体积上限（超过则退化为 名称+size+mtime 摘要），16 MiB */
    static final long MAX_BYTES_PER_FILE = 16L * 1024 * 1024;

    private static final String SIDECAR_SUFFIX = "_files";

    private SourceFingerprint() {
    }

    /**
     * 计算某来源文件的附属指纹：HTML → 侧车目录内容摘要；其它扩展名（MHT）→ {@link #EMPTY}。
     *
     * <p><b>契约</b>：只对 {@code .html}/{@code .htm}（大小写不敏感）计算，且
     * {@link SourceKinds#isHtml(String)} 是唯一判据 —— 与导入识别、解析器选择同一份口径，
     * 不会出现"能导入但不算指纹"的漏网。</p>
     *
     * <p>IO 失败一律返回当时算出的摘要（能读到几个算几个）而不是抛异常：缓存键计算失败会让
     * 预览整体不可用，而"摘要少了一个读不到的文件"只会多一次缓存重建（安全方向）。</p>
     */
    public static String of(Path source) {
        if (source == null || !SourceKinds.isHtml(source.getFileName().toString())) {
            return EMPTY;
        }
        Path dir = source.toAbsolutePath().normalize().getParent();
        if (dir == null) {
            return EMPTY;
        }
        String base = SourceKinds.stripKnownExtension(source.getFileName().toString());
        Path sidecar = dir.resolve(base + SIDECAR_SUFFIX);
        if (!Files.isDirectory(sidecar)) {
            return EMPTY;
        }
        List<Path> files = listRegularFiles(sidecar);
        if (files.isEmpty()) {
            return EMPTY;                                  // 空侧车 = 无附属内容，与"没有侧车"等价
        }
        return digest(sidecar, files);
    }

    /** 侧车<b>本层</b>常规文件（不递归；与解析器只读本层的口径一致），按相对名排序保证确定性 */
    private static List<Path> listRegularFiles(Path sidecar) {
        List<Path> out = new ArrayList<>();
        try (var stream = Files.list(sidecar)) {
            for (Path p : stream.toList()) {
                if (Files.isRegularFile(p)) {
                    out.add(p);
                }
            }
        } catch (IOException e) {
            return out;                                    // 列举失败：以已看到的为准
        }
        out.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
        return out;
    }

    private static String digest(Path sidecar, List<Path> files) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
        for (Path f : files) {
            String rel = sidecar.relativize(f).toString().replace('\\', '/');
            md.update(rel.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            updateFile(md, f);
            md.update((byte) 0);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** 小文件喂内容；大文件退化为 size+mtime（见类 javadoc 的取舍说明） */
    private static void updateFile(MessageDigest md, Path f) {
        try {
            long size = Files.size(f);
            if (size > MAX_BYTES_PER_FILE) {
                md.update(("big:" + size + ":" + Files.getLastModifiedTime(f).toMillis())
                        .getBytes(StandardCharsets.UTF_8));
                return;
            }
            try (InputStream in = Files.newInputStream(f)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
        } catch (IOException e) {
            // 读不到就退化为"名字参与摘要"（rel 已喂入），不抛：键失真只会多一次重建
            md.update("unreadable".getBytes(StandardCharsets.UTF_8));
        }
    }
}
