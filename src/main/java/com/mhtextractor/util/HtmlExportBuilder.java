package com.mhtextractor.util;

import com.mhtextractor.core.MimeParser;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.ExtractWarning;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 MHT 内嵌 HTML 导出为浏览器可直接打开的本地页面（含 cid 图片落地）。
 * 纯逻辑，不依赖 JavaFX、不启动浏览器，可在单元测试中直接调用。
 * 产物布局：{@code %TEMP%\mht-extractor-view\<缓存键>\{index.html, img\0001.png, .complete}}。
 */
@Slf4j
public final class HtmlExportBuilder {

    /** 临时根目录名（位于 java.io.tmpdir 下） */
    public static final String TEMP_ROOT_NAME = "mht-extractor-view";
    /** 图片子目录名 */
    public static final String IMG_DIR_NAME = "img";
    /** 完成标记文件名（存在即视为可复用） */
    public static final String COMPLETE_MARKER = ".complete";
    /** 缓存目录保留天数 */
    private static final int RETENTION_DAYS = 7;
    /** 缓存格式版本：导出产物结构或内容语义变化（如 MimeParser 双解码修复、页面改按 MHT 文件名命名）时递增，旧缓存自然失效 */
    private static final String CACHE_FORMAT_VERSION = "3";

    /** 强制注入的 UTF-8 声明（浏览器只扫描前 1024 字节，须尽早出现） */
    private static final String META_UTF8 = "<meta charset=\"utf-8\">";

    /** cid: 引用扫描（大小写不敏感，覆盖 src/href/background/style/url() 等位置） */
    private static final Pattern CID_REF_PATTERN = Pattern.compile("(?i)cid:([^\"'\\s)]+)");

    private HtmlExportBuilder() {}

    /** 导出结果（不抛异常，失败信息放 error） */
    public record ExportResult(boolean success, Path htmlFile, int imageCount,
                               List<ExtractWarning> warnings, String error) {
        public static ExportResult failure(String msg) {
            return new ExportResult(false, null, 0, List.of(), msg);
        }
    }

    /**
     * 把 MHT 内嵌 HTML 导出为本地页面。
     * 缓存命中（.complete 标记存在）时直接复用既有产物，不重新生成。
     */
    public static ExportResult export(String mhtPath, MimeParser.ParsedMht parsed) {
        if (parsed == null || parsed.getHtmlContent() == null) {
            return ExportResult.failure(parsed == null ? I18n.get("browser.exportParseEmpty") : I18n.get("browser.exportUnparseable"));
        }
        try {
            Path root = Paths.get(System.getProperty("java.io.tmpdir"), TEMP_ROOT_NAME);
            Path dir = root.resolve(cacheKey(mhtPath));
            Path htmlFile = dir.resolve(pageName(mhtPath));

            // 缓存命中：完成标记存在且页面文件在 → 直接复用
            if (Files.exists(dir.resolve(COMPLETE_MARKER)) && Files.exists(htmlFile)) {
                log.info(I18n.msg("log.htmlexportbuilder.01", htmlFile));
                return new ExportResult(true, htmlFile, countImages(dir), List.of(), null);
            }

            // 重新生成：清掉旧目录残留（含上次半途而废的产物），完成后写标记
            FileUtils.deleteDirectory(dir.toFile());
            Path imgDir = dir.resolve(IMG_DIR_NAME);
            Files.createDirectories(imgDir);

            List<ExtractWarning> warnings = new ArrayList<>();
            Map<String, byte[]> cidIndex = buildCidIndex(parsed.getCidMap());
            String rewritten = rewriteCidRefs(parsed.getHtmlContent(), cidIndex, imgDir, warnings);
            String finalHtml = forceUtf8Meta(rewritten);

            Files.writeString(htmlFile, finalHtml, StandardCharsets.UTF_8);
            Files.createFile(dir.resolve(COMPLETE_MARKER));
            log.info(I18n.msg("log.htmlexportbuilder.02", htmlFile, finalHtml.length(), countImages(dir)));

            pruneOldDirs(root, dir);
            return new ExportResult(true, htmlFile, countImages(dir), warnings, null);
        } catch (Exception e) {
            log.error(I18n.msg("log.htmlexportbuilder.03", mhtPath), e);
            return ExportResult.failure(I18n.get("browser.exportFailed", "reason", e.getMessage()));
        }
    }

    /**
     * 页面文件名 = 源文件名（剥掉<b>已知来源扩展名</b>后清洗非法字符）+ .html：浏览器标签页显示该名称，
     * 而非笼统的 index.html。
     *
     * <p>A1：剥离口径收敛到 {@link SourceKinds#stripKnownExtension}，不再用"去掉最后一个点之后的一切"
     * 的通用剥离 —— 那种口径与"只剥已知扩展名"冲突（如 {@code output.v2} 会被剥成 {@code output}）。</p>
     */
    private static String pageName(String mhtPath) {
        String base = SanitizeUtils.sanitize(SourceKinds.stripKnownExtension(new File(mhtPath).getName()));
        return base + ".html";
    }

    /** 缓存键 =（绝对路径 + 大小 + 修改时间 + 格式版本）的 SHA-256 前 16 位十六进制 */
    static String cacheKey(String mhtPath) throws Exception {
        Path p = Paths.get(mhtPath).toAbsolutePath().normalize();
        long size = Files.exists(p) ? Files.size(p) : -1L;
        long mtime = Files.exists(p) ? Files.getLastModifiedTime(p).toMillis() : -1L;
        String raw = p + "|" + size + "|" + mtime + "|v" + CACHE_FORMAT_VERSION;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(raw.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append(String.format(Locale.ROOT, "%02x", d[i]));
        }
        return sb.toString();
    }

    /**
     * cid 引用归一化：去 cid: 前缀、URL 解码（%40 %3C）、去尖括号/引号/尾随 &gt;、
     * trim + 转小写。cidMap 的 key 与 HTML 中的引用都按同一规则归一化后比对。
     */
    static String normalizeCidRef(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.regionMatches(true, 0, "cid:", 0, 4)) {
            s = s.substring(4).trim();
        }
        try {
            s = URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // 非法百分号编码：保持原样继续（查不到就计入 warnings）
        }
        if (s.length() > 1 && s.startsWith("<") && s.endsWith(">")) {
            s = s.substring(1, s.length() - 1);
        }
        if (s.length() > 1 && ((s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("'") && s.endsWith("'")))) {
            s = s.substring(1, s.length() - 1);
        }
        // 无引号属性（src=cid:xxx>）会把 > 带进捕获组，剥掉尾随尖括号
        if (s.length() > 1 && s.endsWith(">") && !s.startsWith("<")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.trim().toLowerCase(Locale.ROOT);
    }

    /** 文件名部分：最后一个 / 之后、第一个 @ 之前（兜底匹配用） */
    private static String filenamePart(String s) {
        int slash = s.lastIndexOf('/');
        String base = slash >= 0 ? s.substring(slash + 1) : s;
        int at = base.indexOf('@');
        return (at > 0 ? base.substring(0, at) : base).trim();
    }

    /** 构建归一化 cid 索引（cidMap 的 key 统一归一化后入表） */
    private static Map<String, byte[]> buildCidIndex(Map<String, byte[]> cidMap) {
        Map<String, byte[]> index = new LinkedHashMap<>();
        if (cidMap == null) {
            return index;
        }
        for (Map.Entry<String, byte[]> e : cidMap.entrySet()) {
            String norm = normalizeCidRef(e.getKey());
            if (norm != null && !norm.isEmpty()) {
                index.putIfAbsent(norm, e.getValue());
            }
        }
        return index;
    }

    /** 归一化后查表；查不到再用文件名部分兜底匹配一次 */
    private static byte[] resolveCid(Map<String, byte[]> index, String norm) {
        byte[] data = index.get(norm);
        if (data != null) {
            return data;
        }
        String fn = filenamePart(norm);
        if (fn.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, byte[]> e : index.entrySet()) {
            if (filenamePart(e.getKey()).equals(fn)) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * 顺序扫描 HTML 中的 cid: 引用：去重后按首次出现顺序编号，图片写入 img/0001.ext；
     * 命中的引用改写为相对路径，未命中的原样保留并计入 warnings。
     */
    private static String rewriteCidRefs(String html, Map<String, byte[]> cidIndex,
                                         Path imgDir, List<ExtractWarning> warnings) throws IOException {
        LinkedHashSet<String> uniqueRefs = new LinkedHashSet<>();
        Matcher collect = CID_REF_PATTERN.matcher(html);
        while (collect.find()) {
            uniqueRefs.add(normalizeCidRef(collect.group(1)));
        }

        Map<String, String> relByNorm = new LinkedHashMap<>();
        int seq = 0;
        for (String norm : uniqueRefs) {
            byte[] data = resolveCid(cidIndex, norm);
            if (data == null) {
                continue;
            }
            seq++;
            String ext = SanitizeUtils.detectExtensionByMagicNumber(data, "png");
            String fileName = String.format(Locale.ROOT, "%04d.%s", seq, ext);
            relByNorm.put(norm, IMG_DIR_NAME + "/" + fileName);
            Files.write(imgDir.resolve(fileName), data);
        }

        Matcher rewrite = CID_REF_PATTERN.matcher(html);
        StringBuffer out = new StringBuffer();
        while (rewrite.find()) {
            String rel = relByNorm.get(normalizeCidRef(rewrite.group(1)));
            if (rel != null) {
                rewrite.appendReplacement(out, Matcher.quoteReplacement(rel));
            } else {
                warnings.add(new ExtractWarning("browser.unresolvedCid",
                        Map.of("ref", rewrite.group(1))));
                rewrite.appendReplacement(out, Matcher.quoteReplacement(rewrite.group()));
            }
        }
        rewrite.appendTail(out);
        return out.toString();
    }

    /**
     * 强制 UTF-8：删除所有含 charset 的 meta 声明（meta charset 与 http-equiv 两种形态），
     * 再在 &lt;head&gt; 开始标签之后注入 &lt;meta charset="utf-8"&gt;；无 head 则置于文档最前。
     */
    private static String forceUtf8Meta(String html) {
        Pattern metaPattern = Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher metaMatcher = metaPattern.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (metaMatcher.find()) {
            String tag = metaMatcher.group();
            if (tag.toLowerCase(Locale.ROOT).contains("charset")) {
                metaMatcher.appendReplacement(sb, "");
            } else {
                metaMatcher.appendReplacement(sb, Matcher.quoteReplacement(tag));
            }
        }
        metaMatcher.appendTail(sb);
        String cleaned = sb.toString();

        Matcher headMatcher = Pattern.compile("<head\\b[^>]*>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(cleaned);
        if (headMatcher.find()) {
            return new StringBuilder(cleaned).insert(headMatcher.end(), META_UTF8).toString();
        }
        return META_UTF8 + cleaned;
    }

    /** 统计已落盘的图片文件数 */
    private static int countImages(Path dir) {
        Path imgDir = dir.resolve(IMG_DIR_NAME);
        if (!Files.isDirectory(imgDir)) {
            return 0;
        }
        try (var stream = Files.list(imgDir)) {
            return (int) stream.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0;
        }
    }

    /** 顺带清理超过保留期的兄弟缓存目录（best-effort：任何异常只记 debug 日志） */
    private static void pruneOldDirs(Path root, Path currentDir) {
        try {
            if (!Files.isDirectory(root)) {
                return;
            }
            long cutoff = System.currentTimeMillis() - RETENTION_DAYS * 24L * 3600_000L;
            try (var stream = Files.list(root)) {
                for (Path sibling : stream.filter(Files::isDirectory).toList()) {
                    if (sibling.equals(currentDir)) {
                        continue;
                    }
                    try {
                        if (Files.getLastModifiedTime(sibling).toMillis() < cutoff) {
                            log.debug(I18n.msg("log.htmlexportbuilder.04", sibling));
                            FileUtils.deleteDirectory(sibling.toFile());
                        }
                    } catch (Exception e) {
                        log.debug(I18n.msg("log.htmlexportbuilder.05", sibling), e);
                    }
                }
            }
        } catch (Exception e) {
            log.debug(I18n.msg("log.htmlexportbuilder.06", root), e);
        }
    }
}
