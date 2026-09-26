package com.mhtextractor.baseline;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 产物基线的规范化与摘要工具（会话 18 · E0.3）。
 *
 * <p><b>为什么需要规范化</b>：基线必须"环境无关"——不得包含绝对路径、时间戳、会话目录名。
 * 直接把产物目录序列化进 JSON 会让基线在不同机器/不同时刻失效（假失败），
 * 从而失去"重构前后逐字节一致"这一唯一的强证据。</p>
 *
 * <p><b>三类产物的摘要口径</b>：</p>
 * <ol>
 *   <li><b>目录树</b>：相对路径（'/' 归一）→ 文件字节 SHA-256，按键排序；
 *       归档文件（.zip/.cbz）替换为"归档内条目名 + 各条目<b>解压后</b>字节 SHA-256"——
 *       归档字节含时间戳，直接比归档本身会产生假差异（会话 17 测试方法设计 §3.4 已指出）。</li>
 *   <li><b>文本产物</b>：先跑 {@link #normalizeText}（去来源装饰行与绝对值），再取 SHA-256。</li>
 *   <li><b>统计</b>：成功/失败/跳过/警告计数 + 每个 FileResult 的 outcome。</li>
 * </ol>
 *
 * <p>本类**零 JavaFX 依赖**，与既有 304 单测同风格。</p>
 */
public final class BaselineHasher {

    /** 来源装饰行前缀：FileWriter 的章节头是纯文本，但部分日志行会以这些字符开头 */
    private static final String[] DECORATION_PREFIXES = {"# ", "[", "来源："};

    /** 绝对值归一占位符 */
    public static final String ABS_PLACEHOLDER = "<ABS>";

    private BaselineHasher() {
    }

    // ==================== 摘要 ====================

    /** 递归摘要一个目录：相对路径（'/' 分隔）→ SHA-256；目录不存在返回空列表 */
    public static List<TreeEntry> hashTree(Path root) throws IOException {
        if (!Files.exists(root)) return List.of();
        List<TreeEntry> entries = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk.filter(Files::isRegularFile).sorted().toList();
            for (Path p : files) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                entries.add(new TreeEntry(rel, hashFileOrArchive(p), null));
            }
        }
        entries.sort((a, b) -> a.path().compareTo(b.path()));
        return entries;
    }

    /** 归档（.zip/.cbz）→ 条目名 + 解压后字节摘要；其余 → 文件字节摘要 */
    private static String hashFileOrArchive(Path p) throws IOException {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip") || name.endsWith(".cbz")) {
            return hashArchive(p);
        }
        return sha256(Files.readAllBytes(p));
    }

    /**
     * 归档摘要：条目名（排序）+ 各条目解压后字节 SHA-256。
     * 刻意<b>不</b>包含归档文件自身字节（含写入时间戳，会假失败）。
     */
    public static String hashArchive(Path archive) throws IOException {
        List<String> lines = new ArrayList<>();
        byte[] raw = Files.readAllBytes(archive);
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(raw))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                byte[] data = zis.readAllBytes();
                lines.add(e.getName().replace('\\', '/') + "=" + sha256(data));
            }
        }
        lines.sort(String::compareTo);
        return sha256(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    /** 归档条目的结构化清单（用于人工核对，不参与摘要） */
    public static List<TreeEntry> listArchiveEntries(Path archive) throws IOException {
        List<TreeEntry> out = new ArrayList<>();
        byte[] raw = Files.readAllBytes(archive);
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(raw))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                out.add(new TreeEntry(e.getName().replace('\\', '/'), sha256(zis.readAllBytes()), e.getSize()));
            }
        }
        out.sort((a, b) -> a.path().compareTo(b.path()));
        return out;
    }

    // ==================== 文本规范化 ====================

    /**
     * 文本产物规范化：CRLF→LF、去空白行差异、去绝对值。
     *
     * <p>刻意<b>只</b>做"环境相关"的替换，不做内容层面的裁剪——否则基线会掩盖真实行为变更。</p>
     */
    public static String normalizeText(String text, Path absRootToStrip) {
        if (text == null) return "";
        String s = text.replace("\r\n", "\n");
        if (absRootToStrip != null) {
            s = s.replace(absRootToStrip.toString().replace('\\', '/'), ABS_PLACEHOLDER);
            s = s.replace(absRootToStrip.toString(), ABS_PLACEHOLDER);
        }
        return s;
    }

    /** 判断是否为"来源装饰行"（含绝对值/时间戳，不适合进基线文本摘要） */
    public static boolean isDecorationLine(String line) {
        String t = line.stripLeading();
        for (String p : DECORATION_PREFIXES) {
            if (t.startsWith(p)) return true;
        }
        return false;
    }

    // ==================== 基础 ====================

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    /** 目录树条目：相对路径 + 摘要 + 可选字节数 */
    public record TreeEntry(String path, String sha256, Long size) {
    }
}
