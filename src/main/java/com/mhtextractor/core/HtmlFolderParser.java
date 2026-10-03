package com.mhtextractor.core;

import com.mhtextractor.i18n.I18n;
import com.mhtextractor.util.EncodingUtils;
import com.mhtextractor.util.SanitizeUtils;
import com.mhtextractor.util.SourceKinds;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 纯 HTML / HTML + 同目录侧车文件夹（浏览器"另存为 → 网页，完整"的产物）解析器（R6.2/R6.3）。
 *
 * <h2>产物形态与支持的侧车形态</h2>
 * <pre>
 * 某个页面.html
 * 某个页面_files/        ← 侧车：与 HTML <b>同目录、同名</b> + 后缀 {@code _files}
 *     图片1.png
 * </pre>
 * <table border="1">
 *   <caption>支持矩阵（不支持的形态如实列出，不做静默猜测）</caption>
 *   <tr><th>形态</th><th>支持</th></tr>
 *   <tr><td>{@code page.html} + 同目录 {@code page_files/}（Chrome/Edge/Firefox 默认）</td><td>✅</td></tr>
 *   <tr><td>{@code page.html}，图片与 HTML <b>同目录</b>（无侧车 / 手工另存的扁平形态）</td><td>✅</td></tr>
 *   <tr><td>纯 HTML，无任何本地图片（只有文字 / 只引用 {@code data:}）</td><td>✅</td></tr>
 *   <tr><td>侧车目录名与 HTML 基名不匹配（{@code 图片/}、{@code assets/}，即"网页，仅 HTML + 单独资源目录"）</td><td>❌ 读不到即跳过（见下"一致语义"）</td></tr>
 *   <tr><td>{@code _files} 的子目录（{@code page_files/word/media/}）</td><td>❌ 跳过（约束在侧车<b>本层</b>，防目录穿越）</td></tr>
 *   <tr><td>{@code http(s)://} 绝对 URL / 协议相对 {@code //host/x.png} / 绝对路径 / 盘符路径</td><td>❌ 跳过（不联网、不出 HTML 目录）</td></tr>
 *   <tr><td>{@code file://} URL</td><td>❌ 跳过（同"绝对路径"一类：可能指向 HTML 目录之外）</td></tr>
 * </table>
 *
 * <h2>🚨 与既有链路"同形"的关键口径（R6.2：下游零改动的全部依据）</h2>
 * <ol>
 *   <li><b>{@code contentLocation} = {@code <img src>} 的<b>原样字符串</b></b>（不绝对化、不归一化）。
 *       依据是 {@code ImageExtractor.isInfoAllowed}：区域限定路径用 <b>{@code img[src]} 的原文</b>
 *       经 {@code allowedRefs.contains(info.getContentLocation())} 精确比对
 *       （{@code ImageExtractor:220/249}），模糊匹配也是"双向 contains"（:261）。
 *       若这里把 src 绝对化成 {@code D:\...\page_files\a.png}，精确比对失败、包含比对也失败
 *       （HTML 里没有盘符），图片会被整体丢弃。改用原样后 <b>MHT 与 HTML 两条路的 key 语义一致</b>
 *       （都是"HTML 里怎么引用，key 就是什么"）。</li>
 *   <li><b>{@code data:} URI <u>不</u>入 {@code imageInfoList}</b>：与 {@link MimeParser} 一致
 *       （MHT 里 Data URI 也不占 MIME part），交给 {@code ImageExtractor.extractDataUriImages}
 *       从 HTML 里自己提取（{@code ImageExtractor:328-346}）。重复放入会让同一张图被计两次。</li>
 *   <li><b>{@code cidMap} 恒为空</b>：HTML 场景没有 Content-ID。若某条 src 以 {@code cid:} 开头，
 *       说明这份 HTML 原本属于一个 MHT，本解析器<b>不猜</b>（无数据可取）→ 按"读不到"跳过。</li>
 *   <li><b>顺序</b>：{@code imageInfoList} 按 {@code <img>} 在 HTML 中的出现顺序（与 MIME part 顺序口径一致）。</li>
 * </ol>
 *
 * <h2>一致语义：读不到 = 跳过（不抛异常、不产用户文案）</h2>
 * <p>下列情况一律<b>跳过该张图、继续解析其它图</b>，整批不中断：文件缺失 / 是目录 / 不可读 /
 * 路径逃逸 / 绝对 URL / 协议相对 URL / {@code cid:} 引用。跳过原因走 {@code log.debug}
 * （复用既有 {@code log.mimeparser.01} 键，<b>不新增</b> log 键、<b>不产用户文案</b>，满足 R6.8）。
 * 因此"HTML 里引用了 3 张图、只有 2 张存在"→ 提取出 2 张，与 MHT 缺 part 的表现同构。</p>
 *
 * <h2>安全边界（真安全面，逐条实现）</h2>
 * <ul>
 *   <li><b>路径约束</b>：解析后的真实路径必须落在"HTML 所在目录本层"或"该 HTML 的侧车目录本层"之内
 *       （{@link #resolveLocalFile}）；{@code ../} 逃逸一律拒绝。</li>
 *   <li><b>不联网</b>：带 scheme 的 URL、{@code //host/...} 协议相对 URL 直接判为非本地。</li>
 *   <li><b>不读绝对路径</b>：{@code /etc/passwd}、{@code C:\Windows\x.png} 一律拒绝。</li>
 *   <li><b>符号链接</b>：拒绝真实路径落在允许目录之外的符号链接（{@code toRealPath} 复核）。</li>
 * </ul>
 */
public class HtmlFolderParser implements SourceParser {

    private static final Logger logger = LoggerFactory.getLogger(HtmlFolderParser.class);

    /** 侧车目录后缀（Chrome / Edge / Firefox "网页，完整" 的约定） */
    private static final String SIDECAR_SUFFIX = "_files";

    /** 复用既有 debug 日志键（不属于本步新增，避免 log.* 计数变化） */
    private static final String LOG_PART_FAILED = "log.mimeparser.01";

    /** 编码探测候选（与 {@link MimeParser#extractHtmlFromPart} 的降级序列同精神，顺序更适合 HTML） */
    private static final String[] FALLBACK_CHARSETS = {"UTF-8", "GBK", "GB2312", "GB18030", "ISO-8859-1"};

    @Override
    public MimeParser.ParsedMht parse(String path, boolean textOnly, Supplier<Boolean> continueCallback,
                                      Consumer<MimeParser.ParsingStage> stageCallback,
                                      Consumer<Double> byteProgressCallback) throws IOException {
        Path htmlFile = Path.of(path);
        byte[] raw = Files.readAllBytes(htmlFile);

        MimeParser.ParsedMht result = new MimeParser.ParsedMht();
        result.setHtmlContent(decodeHtml(raw));
        result.setCidMap(new LinkedHashMap<>());          // HTML 场景无 Content-ID（恒为空，见类 javadoc）
        result.setLocationMap(new LinkedHashMap<>());
        result.setLocationDataMap(new LinkedHashMap<>());
        result.setImageInfoList(new ArrayList<>());

        if (stageCallback != null) {
            stageCallback.accept(MimeParser.ParsingStage.FILE_READ);
            stageCallback.accept(MimeParser.ParsingStage.HTML_EXTRACTED);
        }
        if (byteProgressCallback != null) byteProgressCallback.accept(0.15);

        // 与 MimeParser 的 textOnly 语义一致：只取文本时不做图片 IO（HTML 文本已就绪）
        if (!textOnly) {
            collectLocalImages(htmlFile, result.getHtmlContent(), result, stageCallback, byteProgressCallback);
        }

        if (stageCallback != null) stageCallback.accept(MimeParser.ParsingStage.MIME_TRAVERSED);
        if (byteProgressCallback != null) byteProgressCallback.accept(1.0);
        return result;
    }

    // ==================== HTML 文本 ====================

    /**
     * 解码 HTML 字节。顺序：HTML 自声明的 charset → BOM → 常见中文编码。
     * 判据与 {@link MimeParser} 一致：无 U+FFFD 替换字符者优先（替换字符正是"解错了"的证据）。
     */
    private static String decodeHtml(byte[] raw) {
        List<String> candidates = new ArrayList<>();
        String declared = EncodingUtils.detectCharsetFromHtml(new String(raw, StandardCharsets.ISO_8859_1));
        if (declared != null) {
            candidates.add(declared);
        }
        if (raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF) {
            candidates.add("UTF-8");                       // BOM 是比 meta 更强的证据：提到最前
            candidates.add(0, candidates.remove(candidates.size() - 1));
        }
        for (String cs : FALLBACK_CHARSETS) {
            if (!candidates.contains(cs)) {
                candidates.add(cs);
            }
        }

        String first = null;
        for (String name : candidates) {
            String text;
            try {
                text = new String(raw, Charset.forName(name));
            } catch (RuntimeException e) {
                continue;                                  // 未知/不支持的 charset 名：换下一个
            }
            if (first == null) {
                first = text;
            }
            if (!EncodingUtils.containsGarbled(text)) {
                return text;                               // 无替换字符：声明可信，直接用
            }
        }
        return first == null ? new String(raw, StandardCharsets.UTF_8) : first;
    }

    // ==================== 本地图片收集 ====================

    /** 扫描 {@code <img src>}：能读到的入 map + 列表，读不到/不允许的跳过（见类 javadoc 的一致语义） */
    private void collectLocalImages(Path htmlFile, String html, MimeParser.ParsedMht result,
                                    Consumer<MimeParser.ParsingStage> stageCallback,
                                    Consumer<Double> byteProgressCallback) {
        Document doc;
        try {
            doc = Jsoup.parse(html);
        } catch (RuntimeException e) {
            logger.debug(I18n.msg(LOG_PART_FAILED, String.valueOf(e.getMessage())), e);
            return;                                        // HTML 解析失败：没有图片可取，但文本仍可用
        }

        Path htmlDir = htmlFile.toAbsolutePath().normalize().getParent();
        String htmlName = htmlFile.getFileName() == null ? "" : htmlFile.getFileName().toString();
        Elements imgs = doc.select("img[src]");
        int read = 0;
        for (Element img : imgs) {
            String src = img.attr("src");
            if (src.isEmpty() || src.regionMatches(true, 0, "data:", 0, 5)) {
                continue;                                  // data: 交给 ImageExtractor（口径 2）
            }
            Path file = resolveLocalFile(htmlDir, htmlName, src);
            if (file == null) {
                continue;                                  // 非本地 / 逃逸 / 非法 / 缺失：跳过
            }
            byte[] data;
            try {
                data = Files.readAllBytes(file);
            } catch (IOException | RuntimeException e) {
                logger.debug(I18n.msg(LOG_PART_FAILED, src + " -> " + e.getMessage()));
                continue;                                  // 读失败：跳过，不炸整批
            }
            String filename = deriveFilename(file, src, result.getImageInfoList().size());
            result.getLocationDataMap().put(src, data);
            result.getLocationMap().put(src, filename);
            result.getImageInfoList().add(new MimeParser.ImageInfo(null, src, filename, data));
            read++;
            if (stageCallback != null) {
                stageCallback.accept(MimeParser.ParsingStage.IMAGES_EXTRACTED);
            }
            if (byteProgressCallback != null && read % 5 == 0) {
                byteProgressCallback.accept(0.2);
            }
        }
    }

    /**
     * 把 {@code <img src>} 解析为允许读取的本地文件；不允许/不可达返回 null。
     *
     * <p>允许范围：<b>HTML 所在目录本层</b> 与 <b>该 HTML 的侧车目录（{@code <基名>_files}）本层</b>。
     * 刻意<b>不</b>允许侧车子目录：浏览器"另存为"把图片直接放在侧车本层，允许子目录只会扩大逃逸面
     * 而没有真实需求（"网页，仅 HTML"的 {@code page_files/word/media/...} 属不支持形态，如实列入类 javadoc）。</p>
     */
    static Path resolveLocalFile(Path htmlDir, String htmlName, String src) {
        if (htmlDir == null) {
            return null;
        }
        if (src.isEmpty() || src.isBlank() || src.startsWith("/") || src.startsWith("\\")
                || src.startsWith("//") || hasScheme(src)) {
            return null;                                   // 绝对路径 / 协议相对 / 带 scheme（含 file:、cid:）
        }
        Path ref = safePath(stripQueryAndFragment(src));
        if (ref == null || ref.isAbsolute() || ref.getRoot() != null) {
            return null;                                   // 盘符相对路径（C:foo）等
        }
        Path resolved = htmlDir.resolve(ref).normalize();
        Path parent = resolved.getParent();
        if (parent == null) {
            return null;
        }
        Path sidecar = htmlDir.resolve(
                SourceKinds.stripKnownExtension(htmlName) + SIDECAR_SUFFIX).normalize();
        if (!parent.equals(htmlDir) && !parent.equals(sidecar)) {
            return null;                                   // ../ 逃逸或落入其它目录
        }
        if (!Files.isRegularFile(resolved)) {
            return null;                                   // 缺失 / 是目录
        }
        return escapesViaSymlink(htmlDir, sidecar, resolved) ? null : resolved;
    }

    /** 符号链接复核：真实路径仍须落在允许目录内（防止 {@code link -> C:\...} 这类逃逸） */
    private static boolean escapesViaSymlink(Path htmlDir, Path sidecar, Path resolved) {
        try {
            Path real = resolved.toRealPath();
            Path realParent = real.getParent();
            return !(realParent.equals(realOf(htmlDir)) || realParent.equals(realOf(sidecar)));
        } catch (IOException e) {
            return true;                                   // 复核不了就不读（保守）
        }
    }

    private static Path realOf(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p;
        }
    }

    /** {@code http://} / {@code file:} / {@code cid:} / {@code C:} 之类的 scheme 前缀（一律拒绝） */
    private static boolean hasScheme(String s) {
        int colon = s.indexOf(':');
        if (colon <= 0) {
            return false;
        }
        // 与 RFC 3986 的 scheme 一致：字母开头，后接字母/数字/+/-/.；单字母视为盘符，同样拒绝
        if (!Character.isLetter(s.charAt(0))) {
            return false;
        }
        for (int i = 0; i < colon; i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '+' || c == '-' || c == '.')) {
                return false;
            }
        }
        return true;
    }

    /** 去掉 {@code ?query} 与 {@code #fragment}（只影响文件名推导；key 仍用原样 src） */
    private static String stripQueryAndFragment(String src) {
        int cut = src.length();
        int q = src.indexOf('?');
        if (q >= 0) {
            cut = Math.min(cut, q);
        }
        int h = src.indexOf('#');
        if (h >= 0) {
            cut = Math.min(cut, h);
        }
        return src.substring(0, cut).trim();
    }

    /** 百分号解码 + 路径构造；任一环节非法返回 null（不抛） */
    private static Path safePath(String s) {
        String decoded = s;
        try {
            decoded = URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // 非法百分号编码：用原文继续（路径里可能本来就含 % 字符）
        }
        try {
            return Path.of(decoded.replace('/', File.separatorChar).replace('\\', File.separatorChar));
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** 文件名：优先取引用路径最后一段（清洗后非空），否则 {@code image_N.ext}（与 MHT 兜底口径同构） */
    private static String deriveFilename(Path file, String src, int index) {
        String base = file.getFileName() == null ? "" : file.getFileName().toString();
        String clean = SanitizeUtils.sanitize(base);
        if (!clean.isEmpty()) {
            return clean;
        }
        String ext = SanitizeUtils.getExtensionFromUrl(stripQueryAndFragment(src));
        return "image_" + (index + 1) + "." + (ext == null || ext.isEmpty() ? "png" : ext);
    }
}
