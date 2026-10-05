package com.mhtextractor.baseline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * P0 闸门 2/3 扫描器（《UI可切换重构方案》§8 新增不变式 2–3，P0-1 落地）。
 *
 * <p><b>闸门 2 —— gui 业务状态度量</b>：扫描 {@code src/main/java/com/mhtextractor/gui/**}，
 * 统计四类点（基线 9 = 6+2+1+0，只减不增）：</p>
 * <ul>
 *   <li>(a) 字段声明：类型为 {@code AppConfig} / {@code Map<String, AppConfig>} 的字段
 *       （不是局部变量、不是方法参数）；</li>
 *   <li>(b) 预览缓存构造点：{@code new PreviewCacheStore(} / {@code new PreviewCacheLifecycle(} /
 *       {@code new PreviewCacheKey(} / {@code new PreviewCacheMeta(}；</li>
 *   <li>(c) 参数剪贴板构造点：{@code new ParamClipboard(}；</li>
 *   <li>(d) 撤销载荷构造点：{@code new UndoManager.Record(...)}（含 {@code <>}/泛型实参）。</li>
 * </ul>
 *
 * <p><b>字段检测的作用域判断</b>：复用同包 {@link TestAccessSurface.SrcFile#parseSource(String, String)}
 * 的类体成员解析 —— 其 {@code parseClassBody} 产出每个成员（字段 / 方法 / 构造器 / 初始化块 /
 * 嵌套类型）的 token 区间；具名方法/构造器的区间以方法名命名，字段与初始化块的区间以
 * {@code <init>} / {@code <clinit>} 命名、且初始化块区间以 {@code {} 开头。因此在
 * 「区间名为 {@code <init>}/{@code <clinit>} 且首 token 不是 {@code {}」的区间内找类型声明，
 * 方法里的局部变量与方法参数天然不会命中。两个补充判别：</p>
 * <ol>
 *   <li>字段区间只扫到第一个 {@code =} 之前（类型声明必在其前），字段初始化表达式里的
 *       局部变量/匿名类体不会误计；</li>
 *   <li>声明区（首个 {@code =} 之前）若含<b>深度 0 的 {@code (}</b>（方法参数表，注解括号
 *       {@code @Name(...)} 除外），则是<b>抽象方法声明</b>（接口/抽象类里以 {@code ;} 结尾、
 *       无方法体，同样落入 {@code <init>}/{@code <clinit>} 区间），必须排除 —— 方法参数里的
 *       {@code AppConfig}（如 {@code apply(..., AppConfig params)}）也靠这条排除。</li>
 * </ol>
 *
 * <p><b>闸门 3 —— gui 直连业务包计数</b>：对 gui 源码<b>去除注释与字符串字面量</b>后，匹配
 * {@code com.mhtextractor.(core|extractor|processor|output|util|cache|config|i18n).<类型名>}，
 * 取 (包, 类型) 的 distinct 集合（基线 35，只减不增）。{@link #businessImportCount} 是
 * 非门禁诊断（基线 119 条 import 语句）。</p>
 */
public final class GuiCoupling {

    private GuiCoupling() {
    }

    /** gui 主源集目录（相对仓库根）。 */
    public static final Path GUI_SOURCES = Path.of("src", "main", "java", "com", "mhtextractor", "gui");

    /** 业务包段（与 ArchitectureTest.BUSINESS_PACKAGES 同口径，不含 model/app/platform）。 */
    static final String BUSINESS_SEGMENTS = "(core|extractor|processor|output|util|cache|config|i18n)";

    /** 直连引用匹配：com.mhtextractor.<业务包>.<大写开头类型名>。 */
    private static final Pattern BUSINESS_TYPE_REF = Pattern.compile(
            "com\\.mhtextractor\\." + BUSINESS_SEGMENTS + "\\.([A-Z][A-Za-z0-9_]*)");

    /** (b) 类预览缓存类型。 */
    private static final Set<String> CACHE_CTOR_TYPES = Set.of(
            "PreviewCacheStore", "PreviewCacheLifecycle", "PreviewCacheKey", "PreviewCacheMeta");

    /** 单个状态点：类别 + 相对路径:行号 + 文本。 */
    public record StatePoint(String category, String relPath, int line, String text) {
    }

    /** 一次闸门 2 扫描结果：四类计数 + 逐条清单。 */
    public record StateScan(int fields, int cacheConstructors, int clipboardConstructors,
                            int undoRecordConstructors, List<StatePoint> points) {
        public int total() {
            return fields + cacheConstructors + clipboardConstructors + undoRecordConstructors;
        }
    }

    /** 区间 [start, end] 内 k 处的 '(' 是否属于注解（{@code @Name(...)} / {@code @pkg.Name(...)}），而非方法参数表。 */
    private static boolean isAnnotationParen(List<TestAccessSurface.Tok> toks, int start, int k) {
        int q = k - 1;
        if (q < start || !toks.get(q).isIdent()) {
            return false;
        }
        while (q - 2 >= start && toks.get(q - 1).is(".") && toks.get(q - 2).isIdent()) {
            q -= 2;
        }
        return q - 1 >= start && toks.get(q - 1).is("@");
    }

    /**
     * (d) 判定：{@code Record} 之后的构造实参表 —— {@code (} 直接命中（{@code new UndoManager.Record(}），
     * 或先跳过<b>可选的泛型实参</b> {@code <...>} 再要求 {@code (}（{@code new UndoManager.Record<>(} /
     * {@code new UndoManager.Record<PreviewItem>(}）。用 tokenizer 的 {@link TestAccessSurface.Tok} 判断；
     * {@code <} / {@code >} 按深度配对（嵌套泛型的 {@code >>} 单 token 计两次闭合），
     * 未闭合即撞上 {@code (} / {@code ;} / {@code {} 视为不是构造调用。
     */
    private static boolean recordCtorParenAt(List<TestAccessSurface.Tok> toks, int k) {
        if (k >= toks.size()) {
            return false;
        }
        if (toks.get(k).is("(")) {
            return true;
        }
        if (!toks.get(k).is("<")) {
            return false;
        }
        int depth = 0;
        for (int i = k; i < toks.size(); i++) {
            TestAccessSurface.Tok t = toks.get(i);
            if (t.is("<")) {
                depth++;
            } else if (t.is(">>")) {
                depth -= 2;
            } else if (t.is(">")) {
                depth--;
            } else if (t.is("(") || t.is(";") || t.is("{")) {
                return false;
            }
            if (t.is(">") || t.is(">>")) {
                if (depth <= 0) {
                    return i + 1 < toks.size() && toks.get(i + 1).is("(");
                }
            }
        }
        return false;
    }

    /** 闸门 2：扫描 gui 业务状态持有/构造点（四类合计）。 */
    public static StateScan scanBusinessState(Path guiSources) throws IOException {
        List<StatePoint> points = new ArrayList<>();
        int fields = 0;
        int cacheCtors = 0;
        int clipboardCtors = 0;
        int undoCtors = 0;
        try (Stream<Path> walk = Files.walk(guiSources)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String rel = guiSources.relativize(p).toString().replace('\\', '/');
                String text = Files.readString(p, StandardCharsets.UTF_8);
                List<TestAccessSurface.Tok> toks = TestAccessSurface.tokenize(text);
                TestAccessSurface.SrcFile f = TestAccessSurface.SrcFile.parseSource(rel, text);

                // (a) 字段声明：只认类体成员区间（<init>/<clinit> 且不以 '{' 开头 = 字段，
                //     初始化块以 '{' 开头被排除），且只扫到首个 '=' 之前。
                for (TestAccessSurface.Span s : f.spans) {
                    if (!(s.name().equals("<init>") || s.name().equals("<clinit>"))) {
                        continue;   // 具名方法/构造器区间
                    }
                    if (toks.get(s.start()).is("{")) {
                        continue;   // 实例/静态初始化块，不是字段
                    }
                    int windowEnd = s.end();
                    for (int k = s.start(); k <= s.end(); k++) {
                        if (toks.get(k).is("=")) {
                            windowEnd = k - 1;   // 类型声明必在首个 '=' 之前
                            break;
                        }
                    }
                    // 声明区含深度 0 的 '('（方法参数表，注解括号除外）→ 抽象方法声明，不是字段
                    boolean methodDecl = false;
                    int depth = 0;
                    for (int k = s.start(); k <= windowEnd && !methodDecl; k++) {
                        TestAccessSurface.Tok tk = toks.get(k);
                        if (tk.is("(")) {
                            if (depth == 0 && !isAnnotationParen(toks, s.start(), k)) {
                                methodDecl = true;
                            } else {
                                depth++;
                            }
                        } else if (tk.is(")")) {
                            depth = Math.max(0, depth - 1);
                        }
                    }
                    if (methodDecl) {
                        continue;
                    }
                    for (int k = s.start(); k <= windowEnd; k++) {
                        TestAccessSurface.Tok t = toks.get(k);
                        if (!t.isIdent()) {
                            continue;
                        }
                        if (t.text().equals("AppConfig")) {
                            boolean simpleType = k == 0 || !toks.get(k - 1).is(".");
                            boolean qualifiedType = k >= 4 && toks.get(k - 1).is(".")
                                    && toks.get(k - 2).text().equals("config")
                                    && toks.get(k - 3).is(".") && toks.get(k - 4).text().equals("mhtextractor");
                            if ((simpleType || qualifiedType) && k + 1 <= windowEnd
                                    && toks.get(k + 1).isIdent()) {
                                points.add(new StatePoint("a-字段", rel, t.line(),
                                        "AppConfig " + toks.get(k + 1).text()));
                                fields++;
                                k++;
                            }
                        } else if (t.text().equals("Map") && k + 6 <= windowEnd
                                && toks.get(k + 1).is("<") && toks.get(k + 2).text().equals("String")
                                && toks.get(k + 3).is(",") && toks.get(k + 4).text().equals("AppConfig")
                                && toks.get(k + 5).is(">") && toks.get(k + 6).isIdent()) {
                            points.add(new StatePoint("a-字段", rel, t.line(),
                                    "Map<String, AppConfig> " + toks.get(k + 6).text()));
                            fields++;
                            k += 6;
                        }
                    }
                }

                // (b)(c)(d) 构造点：tokenizer 自动排除注释；字符串字面量是 STRING token，天然不命中。
                for (int k = 0; k + 1 < toks.size(); k++) {
                    TestAccessSurface.Tok t = toks.get(k);
                    if (!t.is("new")) {
                        continue;
                    }
                    TestAccessSurface.Tok c = toks.get(k + 1);
                    if (!c.isIdent()) {
                        continue;
                    }
                    String name = c.text();
                    if (k + 2 < toks.size() && toks.get(k + 2).is("(")) {
                        if (CACHE_CTOR_TYPES.contains(name)) {
                            points.add(new StatePoint("b-缓存构造", rel, c.line(), "new " + name + "("));
                            cacheCtors++;
                        } else if (name.equals("ParamClipboard")) {
                            points.add(new StatePoint("c-剪贴板构造", rel, c.line(), "new ParamClipboard("));
                            clipboardCtors++;
                        }
                    }
                    if (name.equals("UndoManager") && k + 4 < toks.size()
                            && toks.get(k + 2).is(".") && toks.get(k + 3).text().equals("Record")
                            && recordCtorParenAt(toks, k + 4)) {
                        points.add(new StatePoint("d-撤销载荷构造", rel, c.line(), "new UndoManager.Record("));
                        undoCtors++;
                    }
                }
            }
        }
        return new StateScan(fields, cacheCtors, clipboardCtors, undoCtors, points);
    }

    /** 闸门 3：gui 直连业务包的 distinct (包, 类型) 集合（剥注释与字符串字面量后）。 */
    public static Set<String> distinctBusinessTypes(Path guiSources) throws IOException {
        TreeSet<String> types = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(guiSources)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String code = SourceScanUtils.stripCommentsAndLiterals(
                        Files.readString(p, StandardCharsets.UTF_8));
                Matcher m = BUSINESS_TYPE_REF.matcher(code);
                while (m.find()) {
                    types.add(m.group(1) + "." + m.group(2));
                }
            }
        }
        return types;
    }

    /** 诊断（非门禁）：gui 层对业务包的 import 语句条数（按行，基线 119）。 */
    public static int businessImportCount(Path guiSources) throws IOException {
        Pattern importLine = Pattern.compile("^import com\\.mhtextractor\\." + BUSINESS_SEGMENTS + "\\.");
        int n = 0;
        try (Stream<Path> walk = Files.walk(guiSources)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    if (importLine.matcher(line.strip()).find()) {
                        n++;
                    }
                }
            }
        }
        return n;
    }
}
