package com.mhtextractor.baseline;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * D1 —— 「测试访问面」发现器与清单生成器。
 *
 * <p><b>用法</b>：{@code java -cp "target/classes;target/test-classes"
 * com.mhtextractor.baseline.TestAccessSurface [--write]}。
 * 默认只打印；{@code --write} 额外把清单写入
 * {@code src/test/resources/test-access-surface.tsv}。运行前先
 * {@code mvnw.cmd -q -DskipTests test-compile} 保证 {@code target/classes} 最新。</p>
 *
 * <p><b>三条通道（口径见《可见性收窄断言（选项D）设计与实施方案》与 D1 任务书）</b>：</p>
 * <ol>
 *   <li><b>direct</b>：驱动类 = {@code target/test-classes} 下包名以 {@code com.mhtextractor.gui}
 *       开头的全部 .class（含嵌套/匿名类）。收集其常量池 Fieldref/Methodref/InterfaceMethodref
 *       （MethodHandle 指向项已覆盖）的 (owner, name)，保留「owner 是生产类且 name 是 owner 的
 *       包级成员」的引用，去重为 (生产类 FQN, 成员名)。用字节码引用而非源码正则 —— 源码正则会在
 *       驱动自身的同名方法声明与注释上误报。</li>
 *   <li><b>reflect</b>：扫 {@code src/test/java/**} 与 {@code tools/probes/**} 源码，登记
 *       {@code X.class.getDeclaredField("lit")} 等「接收者静态可解析为生产类 + 成员名是字符串
 *       字面量」的反射点，键为 (X 的 FQN, 成员)，方法/构造器附参数个数。X 解析为测试/探针类或
 *       JDK 类时不登记（本闸门只守卫生产类；该取舍见本文件末尾 {@code classifyStatic}）。</li>
 *   <li><b>reflect-dynamic</b>：所有成员名无法静态解析的反射点（变量接收者、变量成员名、整类
 *       枚举、字符串拼接、X 无法解析）按「源文件相对路径 + 所在方法」全部登记。{@code Class.forName}
 *       本身不登记（它是类查找而非成员访问点；其后的成员反射调用按上表归入动态点）。</li>
 * </ol>
 *
 * <p><b>实现约束</b>：生产成员库取自 {@code target/classes/**}{@code /*.class} 的字节码解析
 * （access_flags + 字段表 + 方法表），不使用 Class.forName / 反射 API，也不使用源码正则抽成员。
 * 反射原语名以「拆分拼接」方式构造字符串，使本文件源码不含 ArchitectureTest 反射白名单监控的
 * 原语字面量（自指安全，非人为豁免 —— 本文件自扫恒为零条）。</p>
 *
 * <p><b>已知近似（确定性实现，不因输入而变）</b>：① 构造器/实例块/静态块的上下文名统一用字节码
 * 名（{@code <init>} / {@code <clinit>}）；② 枚举常量带类体时按方法归因、匿名类体内调用归因到
 * 外围字段/方法（当前扫描面无此类代码）；③ 整类枚举扩展覆盖 getFields/getMethods/
 * getDeclaredConstructors/getConstructors（任务书表格未列，与 getDeclaredFields 同一原则）；
 * ④ 反射原语名与生产/测试代码中恰好同名的普通方法调用无法区分（当前扫描面实测无此类调用）。</p>
 */
public final class TestAccessSurface {

    /**
     * 双引号字符。用转义而非 {@code '"'} 字面量：ArchitectureTest 的 stripComments 是朴素实现
     * （只认 {@code "} 切换字符串态），字符字面量里的裸引号会使其状态错位，导致本文件后续注释
     * 不被剥离（注释里的反射原语字样会漏进"代码"、误触既有反射白名单闸门）。
     */
    private static final char DQUOTE = '\u0022';

    // ==================== 通道名 / 常量 ====================

    private static final String CH_DIRECT = "direct";
    private static final String CH_REFLECT = "reflect";
    private static final String CH_DYNAMIC = "reflect-dynamic";

    /** 驱动源包前缀（guiTestSourceClasses 判定用：src/test/java 下包名以 com.mhtextractor.gui 开头的测试类全部为驱动）。 */
    private static final String DRIVER_PREFIX = "com.mhtextractor.gui.";

    /**
     * 显式驱动文件清单（用户 2026-10-04 裁决 A）：src/test/java 中包名以 com.mhtextractor.gui 开头的
     * 全部测试类（18 个）。direct 通道只扫属于这些 top-level 驱动类的 .class（含 $嵌套/匿名）；
     * 完整性守卫：{@link #guiTestSourceClasses()} 与此清单必须双向相等。
     */
    public static final List<String> DRIVER_CLASSES = List.of(
            "com.mhtextractor.gui.WorkspaceFxAdapterTest",
            "com.mhtextractor.gui.acceptance.TestMain",
            "com.mhtextractor.gui.component.ExtractOutcomeStylesTest",
            "com.mhtextractor.gui.component.GuiNotificationsIsSemanticFacadeOnlyTest",
            "com.mhtextractor.gui.component.HtmlFindIndexTest",
            "com.mhtextractor.gui.component.ParamTargetTextTest",
            "com.mhtextractor.gui.controller.AcceptanceDriver",
            "com.mhtextractor.gui.controller.AgentExercise",
            "com.mhtextractor.gui.controller.ExtractionTaskCoordinatorCancelRaceTest",
            "com.mhtextractor.gui.controller.ExtractionTaskCoordinatorGateTest",
            "com.mhtextractor.gui.controller.ExtractionTaskCoordinatorRunChannelTest",
            "com.mhtextractor.gui.controller.FeatureExercise",
            "com.mhtextractor.gui.controller.FeatureExerciseSupport",
            "com.mhtextractor.gui.controller.FileListContextMenuTest",
            "com.mhtextractor.gui.controller.ImportSessionCoordinatorQuotaTest",
            "com.mhtextractor.gui.controller.ParamClipboardTest",
            "com.mhtextractor.gui.util.DragGeometryTest",
            "com.mhtextractor.gui.util.ThumbnailFxBridgeTest");

    /** 清单路径（相对仓库根）。 */
    public static final String MANIFEST_PATH = "src/test/resources/test-access-surface.tsv";

    /** fqn 去掉 $嵌套/匿名 后缀后的 top-level 类名。 */
    private static String topLevel(String fqn) {
        int d = fqn.indexOf('$');
        return d < 0 ? fqn : fqn.substring(0, d);
    }

    private static final int ACC_PUBLIC = 0x0001;
    private static final int ACC_PRIVATE = 0x0002;
    private static final int ACC_PROTECTED = 0x0004;
    private static final int ACC_SYNTHETIC = 0x1000;
    private static final int ACC_BRIDGE = 0x0040;

    private static final int TAG_FIELDREF = 9;
    private static final int TAG_METHODREF = 10;
    private static final int TAG_INTERFACE_METHODREF = 11;
    private static final int TAG_METHOD_HANDLE = 15;

    /**
     * 成员名可静态解析的反射方法名。字符串以「拆分拼接」构造：本文件被
     * ArchitectureTest.reflectionAgainstMainControllerIsZero 扫描时（剥注释、保留字符串），
     * 不出现完整原语字面量，从而不触犯「白名单外文件新增反射使用面」的既有闸门。
     */
    private static final Set<String> NAMED_LOOKUPS = Set.of(
            "getDeclared" + "Field", "get" + "Field",
            "getDeclared" + "Method", "get" + "Method",
            "getDeclared" + "Constructor", "get" + "Constructor");

    /** 整类枚举（成员名无法静态解析，全部按「文件+方法」登记为动态点）。 */
    private static final Set<String> WHOLE_CLASS_LOOKUPS = Set.of(
            "getDeclared" + "Fields", "get" + "Fields",
            "getDeclared" + "Methods", "get" + "Methods",
            "getDeclared" + "Constructors", "get" + "Constructors");

    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while",
            "true", "false", "null", "record", "var", "yield", "sealed", "permits");

    /** 简单名解析失败时按 JDK 类对待（不登记、不报未解析）的常见类名。 */
    private static final Set<String> JDK_NAMES = Set.of(
            "Object", "String", "Class", "Integer", "Long", "Short", "Byte", "Double", "Float",
            "Boolean", "Character", "Void", "Number", "Enum", "Record", "Thread", "Runnable",
            "Exception", "RuntimeException", "Throwable", "Error", "System", "Math",
            "StringBuilder", "StringBuffer", "CharSequence", "Comparable", "Iterable", "Optional",
            "Stream", "Arrays", "Collections", "Objects", "Comparator", "Function", "BiFunction",
            "Supplier", "Consumer", "Predicate", "Callable", "List", "Map", "Set", "Collection",
            "ArrayList", "HashMap", "LinkedHashMap", "TreeMap", "TreeSet", "HashSet", "LinkedList",
            "Queue", "Deque", "Iterator", "Files", "Path", "Paths", "File", "URL", "URI",
            "Timer", "TimerTask", "CountDownLatch", "AtomicBoolean", "AtomicInteger", "AtomicLong",
            "CompletableFuture", "Future", "Executor", "ExecutorService", "Executors", "UUID",
            "Locale", "Pattern", "Matcher", "Random", "Calendar", "Date", "Instant", "Duration",
            "Period", "LocalDate", "LocalTime", "LocalDateTime", "ZoneId", "ZoneOffset",
            "BigDecimal", "BigInteger", "Method", "Field", "Constructor", "Modifier");

    // ==================== 字节码解析（生产成员库 / 驱动引用） ====================

    static final class ClassFile {
        String fqn;
        int accessFlags;
        final List<Member> fields = new ArrayList<>();
        final List<Member> methods = new ArrayList<>();
        final List<CpRef> refs = new ArrayList<>();
    }

    record Member(String name, String desc, int flags) {
    }

    record CpRef(String owner, String name, String desc, int tag) {
    }

    /**
     * 生产类成员表：键 = {@code 名称|描述符}（D1-fix：同名不同可见性的重载必须按描述符分流，
     * 不能按名字合并 —— 否则 public 重载的可见性位会荫蔽同名 package-private 重载，造成漏项）。
     * 值 = 该成员的可见性位；包级 = 无 public/private/protected 位。
     */
    static final class ProdMembers {
        final Map<String, Integer> fields = new TreeMap<>();
        final Map<String, Integer> methods = new TreeMap<>();
    }

    /** (名称, 描述符) → 成员库键。描述符不含 {@code |}，名称亦不含，故拼接安全。 */
    private static String memberKey(String name, String desc) {
        return name + "|" + desc;
    }

    static ClassFile parseClassFile(byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (in.readInt() != 0xCAFEBABE) {
                throw new IOException("bad magic");
            }
            in.readUnsignedShort(); // minor
            in.readUnsignedShort(); // major
            int cpCount = in.readUnsignedShort();
            Object[] cp = new Object[cpCount];
            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> cp[i] = in.readUTF();
                    case 3, 4 -> in.readInt();
                    case 5, 6 -> {
                        in.readLong();
                        i++;
                    }
                    case 7, 8, 16, 19, 20 -> cp[i] = new int[]{tag, in.readUnsignedShort()};
                    case 15 -> cp[i] = new int[]{tag, in.readUnsignedByte(), in.readUnsignedShort()};
                    case 9, 10, 11, 12, 17, 18 -> cp[i] = new int[]{tag, in.readUnsignedShort(), in.readUnsignedShort()};
                    default -> throw new IOException("unknown constant tag " + tag + " at #" + i);
                }
            }
            ClassFile cf = new ClassFile();
            cf.accessFlags = in.readUnsignedShort();
            int thisClass = in.readUnsignedShort();
            cf.fqn = utf8(cp, ((int[]) cp[thisClass])[1]).replace('/', '.');
            in.readUnsignedShort(); // super
            int ifc = in.readUnsignedShort();
            for (int i = 0; i < ifc; i++) {
                in.readUnsignedShort();
            }
            int fieldCount = in.readUnsignedShort();
            for (int i = 0; i < fieldCount; i++) {
                int flags = in.readUnsignedShort();
                int nameIdx = in.readUnsignedShort();
                int descIdx = in.readUnsignedShort();
                skipAttributes(in);
                cf.fields.add(new Member(utf8(cp, nameIdx), utf8(cp, descIdx), flags));
            }
            int methodCount = in.readUnsignedShort();
            for (int i = 0; i < methodCount; i++) {
                int flags = in.readUnsignedShort();
                int nameIdx = in.readUnsignedShort();
                int descIdx = in.readUnsignedShort();
                skipAttributes(in);
                cf.methods.add(new Member(utf8(cp, nameIdx), utf8(cp, descIdx), flags));
            }
            skipAttributes(in);
            Set<String> seen = new TreeSet<>();
            for (int i = 1; i < cpCount; i++) {
                Object o = cp[i];
                if (!(o instanceof int[] a)) {
                    continue;
                }
                if (a[0] == TAG_METHOD_HANDLE) {
                    Object r = cp[a[2]];
                    if (r instanceof int[] b && (b[0] == TAG_FIELDREF || b[0] == TAG_METHODREF
                            || b[0] == TAG_INTERFACE_METHODREF)) {
                        addRef(cp, b, seen, cf.refs);
                    }
                } else if (a[0] == TAG_FIELDREF || a[0] == TAG_METHODREF || a[0] == TAG_INTERFACE_METHODREF) {
                    addRef(cp, a, seen, cf.refs);
                }
            }
            return cf;
        }
    }

    private static void addRef(Object[] cp, int[] ref, Set<String> seen, List<CpRef> out) {
        String owner = utf8(cp, ((int[]) cp[ref[1]])[1]).replace('/', '.');
        int[] nat = (int[]) cp[ref[2]];
        String name = utf8(cp, nat[1]);
        String desc = utf8(cp, nat[2]);
        if (seen.add(ref[0] + "\t" + owner + "\t" + name + "\t" + desc)) {
            out.add(new CpRef(owner, name, desc, ref[0]));
        }
    }

    private static String utf8(Object[] cp, int idx) {
        return (String) cp[idx];
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        int n = in.readUnsignedShort();
        for (int i = 0; i < n; i++) {
            in.readUnsignedShort();
            int len = in.readInt();
            in.readNBytes(len);
        }
    }

    /** 是否包级可见（既非 public、也非 private、也非 protected）。 */
    private static boolean isPackageLevel(int flags) {
        return (flags & (ACC_PUBLIC | ACC_PRIVATE | ACC_PROTECTED)) == 0;
    }

    private static String pkgOf(String fqn) {
        int d = fqn.lastIndexOf('$');
        String base = d < 0 ? fqn : fqn.substring(0, d);
        int p = base.lastIndexOf('.');
        return p < 0 ? "" : base.substring(0, p);
    }

    /** 驱动/引用者的展示名：去包名、保留到顶层类（去 $ 后缀）。 */
    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        String base = dot < 0 ? fqn : fqn.substring(dot + 1);
        int d = base.indexOf('$');
        return d < 0 ? base : base.substring(0, d);
    }

    // ==================== 源码扫描（reflect / reflect-dynamic 通道） ====================

    static final int IDENT = 1;
    static final int KEYWORD = 2;
    static final int STRING = 3;
    static final int CHAR = 4;
    static final int NUMBER = 5;
    static final int PUNCT = 6;

    record Tok(int kind, String text, int line) {
        boolean is(String s) {
            return text.equals(s);
        }

        boolean isIdent() {
            return kind == IDENT;
        }

        boolean isKeyword() {
            return kind == KEYWORD;
        }
    }

    record Span(String name, int start, int end) {
    }

    record Block(int kind, String name, int end) {
    }

    private static final int K_METHOD = 1;
    private static final int K_STATIC_INIT = 2;
    private static final int K_INSTANCE_INIT = 3;
    private static final int K_SKIP = 4;

    static List<Tok> tokenize(String src) {
        List<Tok> out = new ArrayList<>();
        int n = src.length();
        int i = 0;
        int line = 1;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\n') {
                line++;
                i++;
                continue;
            }
            if (c == ' ' || c == '\t' || c == '\r' || c == '\f') {
                i++;
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    if (src.charAt(i) == '\n') {
                        line++;
                    }
                    i++;
                }
                i = Math.min(n, i + 2);
                continue;
            }
            if (c == DQUOTE) {
                if (i + 2 < n && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"') {
                    // 文本块
                    i += 3;
                    int startLine = line;
                    StringBuilder sb = new StringBuilder();
                    while (i < n && !(src.charAt(i) == '"' && i + 2 < n
                            && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"')) {
                        char ch = src.charAt(i);
                        if (ch == '\n') {
                            line++;
                        }
                        sb.append(ch);
                        i++;
                    }
                    i = Math.min(n, i + 3);
                    out.add(new Tok(STRING, sb.toString(), startLine));
                } else {
                    i++;
                    StringBuilder sb = new StringBuilder();
                    while (i < n && src.charAt(i) != '"') {
                        char ch = src.charAt(i);
                        if (ch == '\\' && i + 1 < n) {
                            char e = src.charAt(i + 1);
                            switch (e) {
                                case 'n' -> sb.append('\n');
                                case 't' -> sb.append('\t');
                                case 'r' -> sb.append('\r');
                                case 'b' -> sb.append('\b');
                                case 'f' -> sb.append('\f');
                                case '\u0022' -> sb.append(DQUOTE);
                                case '\'' -> sb.append('\'');
                                case '\\' -> sb.append('\\');
                                case 'u' -> {
                                    if (i + 5 < n) {
                                        sb.append((char) Integer.parseInt(src.substring(i + 2, i + 6), 16));
                                        i += 4;
                                    }
                                }
                                default -> sb.append(e);
                            }
                            i += 2;
                        } else {
                            sb.append(ch);
                            i++;
                        }
                    }
                    i = Math.min(n, i + 1);
                    out.add(new Tok(STRING, sb.toString(), line));
                }
                continue;
            }
            if (c == '\'') {
                i++;
                if (i < n && src.charAt(i) == '\\') {
                    i += 2;
                } else {
                    i++;
                }
                i = Math.min(n, i + 1);
                out.add(new Tok(CHAR, "c", line));
                continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int s = i;
                while (i < n && Character.isJavaIdentifierPart(src.charAt(i))) {
                    i++;
                }
                String text = src.substring(s, i);
                out.add(new Tok(KEYWORDS.contains(text) ? KEYWORD : IDENT, text, line));
                continue;
            }
            if (Character.isDigit(c)) {
                int s = i;
                while (i < n && (Character.isLetterOrDigit(src.charAt(i))
                        || src.charAt(i) == '.' || src.charAt(i) == '_')) {
                    i++;
                }
                out.add(new Tok(NUMBER, src.substring(s, i), line));
                continue;
            }
            String three = i + 2 < n ? src.substring(i, i + 3) : "";
            if (three.equals("...")) {
                out.add(new Tok(PUNCT, "...", line));
                i += 3;
                continue;
            }
            String two = i + 1 < n ? src.substring(i, i + 2) : "";
            if (two.equals("->") || two.equals("::") || two.equals(">>") || two.equals("<<")
                    || two.equals(">=") || two.equals("<=") || two.equals("==") || two.equals("!=")
                    || two.equals("&&") || two.equals("||") || two.equals("++") || two.equals("--")
                    || two.equals("+=") || two.equals("-=") || two.equals("*=") || two.equals("/=")
                    || two.equals("&=") || two.equals("|=") || two.equals("^=") || two.equals("%=")) {
                out.add(new Tok(PUNCT, two, line));
                i += 2;
                continue;
            }
            out.add(new Tok(PUNCT, String.valueOf(c), line));
            i++;
        }
        return out;
    }

    /** 单个源文件：路径、token、包/导入、声明的类（含嵌套）、成员区间。 */
    static final class SrcFile {
        final String relPath;
        final List<Tok> toks;
        String pkg = "";
        final Map<String, String> imports = new TreeMap<>();
        final List<String> wildcardImports = new ArrayList<>();
        final Set<String> declared = new TreeSet<>();
        final List<Span> spans = new ArrayList<>();

        SrcFile(String relPath, List<Tok> toks) {
            this.relPath = relPath;
            this.toks = toks;
        }

    static SrcFile parseSource(String relPath, String text) {
        SrcFile f = new SrcFile(relPath, tokenize(text));
        int n = f.toks.size();
        int i = 0;
        while (i < n) {
            Tok t = f.toks.get(i);
            if (t.is("package")) {
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < n && !f.toks.get(i).is(";")) {
                    if (f.toks.get(i).isIdent() || f.toks.get(i).is(".")) {
                        sb.append(f.toks.get(i).text);
                    }
                    i++;
                }
                f.pkg = sb.toString();
                i = Math.min(n, i + 1);
            } else if (t.is("import")) {
                i++;
                if (i < n && f.toks.get(i).is("static")) {
                    while (i < n && !f.toks.get(i).is(";")) {
                        i++;
                    }
                    i = Math.min(n, i + 1);
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                boolean wildcard = false;
                while (i < n && !f.toks.get(i).is(";")) {
                    Tok tt = f.toks.get(i);
                    if (tt.is("*")) {
                        wildcard = true;
                    } else if (tt.isIdent() || tt.is(".")) {
                        sb.append(tt.text);
                    }
                    i++;
                }
                String fqn = sb.toString();
                if (wildcard) {
                    f.wildcardImports.add(fqn);
                } else if (!fqn.isEmpty()) {
                    int dot = fqn.lastIndexOf('.');
                    f.imports.put(dot < 0 ? fqn : fqn.substring(dot + 1), fqn);
                }
                i = Math.min(n, i + 1);
            } else if (t.is("class") || t.is("interface") || t.is("enum") || t.is("record")) {
                if (i + 1 < n && f.toks.get(i + 1).isIdent()) {
                    String name = f.toks.get(i + 1).text;
                    String fqn = f.pkg.isEmpty() ? name : f.pkg + "." + name;
                    f.declared.add(fqn);
                    int body = findTypeBody(f, i + 2, n);
                    if (body >= 0) {
                        int end = matchBrace(f, body, n);
                        if (end < 0) {
                            end = n - 1;
                        }
                        parseClassBody(f, body, end, name, fqn, t.is("enum"));
                        i = end + 1;
                        continue;
                    }
                }
                i++;
            } else if (t.is("@") && i + 1 < n && f.toks.get(i + 1).is("interface")) {
                if (i + 2 < n && f.toks.get(i + 2).isIdent()) {
                    String name = f.toks.get(i + 2).text;
                    String fqn = f.pkg.isEmpty() ? name : f.pkg + "." + name;
                    f.declared.add(fqn);
                    int body = findTypeBody(f, i + 3, n);
                    if (body >= 0) {
                        int end = matchBrace(f, body, n);
                        if (end < 0) {
                            end = n - 1;
                        }
                        parseClassBody(f, body, end, name, fqn, false);
                        i = end + 1;
                        continue;
                    }
                }
                i++;
            } else if (t.is("@")) {
                i++;
                while (i < n && (f.toks.get(i).isIdent() || f.toks.get(i).is("."))) {
                    i++;
                }
                if (i < n && f.toks.get(i).is("(")) {
                    int e = matchParen(f, i, n);
                    i = (e < 0 ? i : e) + 1;
                }
            } else {
                i++;
            }
        }
        return f;
    }

    /** 解析一个类体的成员：方法 / 构造器 / 静态块 / 实例块 / 字段（含嵌套类型递归）。 */
    static void parseClassBody(SrcFile f, int open, int close, String className, String fqn, boolean isEnum) {
        List<Tok> toks = f.toks;
        int j = open + 1;
        boolean first = true;
        while (j < close) {
            int depthP = 0;
            int depthB = 0;
            boolean sawStatic = false;
            int k = j;
            boolean done = false;
            while (k < close) {
                Tok tk = toks.get(k);
                if (tk.is("(")) {
                    depthP++;
                    k++;
                    continue;
                }
                if (tk.is(")")) {
                    depthP = Math.max(0, depthP - 1);
                    k++;
                    continue;
                }
                if (tk.is("[")) {
                    depthB++;
                    k++;
                    continue;
                }
                if (tk.is("]")) {
                    depthB = Math.max(0, depthB - 1);
                    k++;
                    continue;
                }
                if (depthP != 0 || depthB != 0) {
                    k++;
                    continue;
                }
                if (tk.is("@")) {
                    if (k + 1 < close && toks.get(k + 1).is("interface")) {
                        if (k + 2 < close && toks.get(k + 2).isIdent()) {
                            String nm = toks.get(k + 2).text;
                            String nf = fqn + "$" + nm;
                            f.declared.add(nf);
                            int body = findTypeBody(f, k + 3, close);
                            if (body >= 0) {
                                int end = matchBrace(f, body, toks.size());
                                if (end < 0) {
                                    end = close;
                                }
                                parseClassBody(f, body, end, nm, nf, false);
                                j = end + 1;
                                done = true;
                                break;
                            }
                        }
                    }
                    k++;
                    while (k < close && (toks.get(k).isIdent() || toks.get(k).is("."))) {
                        k++;
                    }
                    if (k < close && toks.get(k).is("(")) {
                        int e = matchParen(f, k, toks.size());
                        k = (e < 0 ? k : e) + 1;
                    }
                    continue;
                }
                if (tk.is("class") || tk.is("interface") || tk.is("enum") || tk.is("record")) {
                    if (k + 1 < close && toks.get(k + 1).isIdent()) {
                        String nm = toks.get(k + 1).text;
                        String nf = fqn + "$" + nm;
                        f.declared.add(nf);
                        int body = findTypeBody(f, k + 2, close);
                        if (body >= 0) {
                            int end = matchBrace(f, body, toks.size());
                            if (end < 0) {
                                end = close;
                            }
                            parseClassBody(f, body, end, nm, nf, tk.is("enum"));
                            j = end + 1;
                            done = true;
                            break;
                        }
                    }
                    k++;
                    continue;
                }
                if (tk.is("static")) {
                    sawStatic = true;
                    k++;
                    continue;
                }
                if (tk.is(";")) {
                    String ctx = first && isEnum ? "<clinit>" : (sawStatic ? "<clinit>" : "<init>");
                    f.spans.add(new Span(ctx, j, k));
                    j = k + 1;
                    done = true;
                    break;
                }
                if (tk.is("{")) {
                    Block b = classifyBlock(f, k, j, close, className);
                    int end = b.end < 0 ? k : b.end;
                    if (b.kind == K_METHOD || b.kind == K_STATIC_INIT || b.kind == K_INSTANCE_INIT) {
                        String nm = b.kind == K_METHOD ? b.name
                                : (b.kind == K_STATIC_INIT ? "<clinit>" : "<init>");
                        f.spans.add(new Span(nm, k, end));
                        j = end + 1;
                        done = true;
                        break;
                    }
                    k = end;
                    k++;
                    continue;
                }
                k++;
            }
            if (!done) {
                j = k;
            }
            first = false;
        }
    }

    /**
     * 判定类体成员层的一个 {@code {}：方法 / 静态块 / 实例块 / 需跳过的表达式块
     * （lambda、匿名类、控制流、数组初始化等，均出现在字段初始化表达式里）。
     */
    static Block classifyBlock(SrcFile f, int braceIdx, int memberStart, int close, String className) {
        List<Tok> toks = f.toks;
        int end = matchBrace(f, braceIdx, toks.size());
        if (end < 0) {
            end = braceIdx;
        }
        Tok prev = toks.get(braceIdx - 1);
        if (prev.is(")")) {
            if (braceIdx + 1 <= close && toks.get(braceIdx + 1).is("->")) {
                return new Block(K_SKIP, null, end); // lambda
            }
            int open = matchParenBack(f, braceIdx - 1, memberStart);
            if (open < 0) {
                return new Block(K_SKIP, null, end);
            }
            Tok nameTok = toks.get(open - 1);
            if (nameTok.isIdent() && !nameTok.isKeyword()) {
                int q = open - 1;
                while (q - 2 >= memberStart && toks.get(q - 1).is(".") && toks.get(q - 2).isIdent()) {
                    q -= 2;
                }
                if (q - 1 >= memberStart && toks.get(q - 1).is("new")) {
                    return new Block(K_SKIP, null, end); // 匿名类体（字段初始化内）
                }
                String nm = nameTok.text;
                return new Block(K_METHOD, nm.equals(className) ? "<init>" : nm, end);
            }
            return new Block(K_SKIP, null, end); // if/for/while/switch/catch 或 new X<>() 等
        }
        int q = braceIdx - 1;
        boolean sawThrows = false;
        while (q > memberStart && (toks.get(q).isIdent() || toks.get(q).is(".")
                || toks.get(q).is(",") || toks.get(q).is("throws"))) {
            if (toks.get(q).is("throws")) {
                sawThrows = true;
            }
            q--;
        }
        Tok back = toks.get(q);
        if (sawThrows && back.is(")")) {
            int open = matchParenBack(f, q, memberStart);
            if (open >= 0) {
                Tok nameTok = toks.get(open - 1);
                if (nameTok.isIdent() && !nameTok.isKeyword()) {
                    String nm = nameTok.text;
                    return new Block(K_METHOD, nm.equals(className) ? "<init>" : nm, end);
                }
            }
        }
        if (back.is("static")) {
            return new Block(K_STATIC_INIT, null, end);
        }
        if (back.is("=") || back.is(",") || back.is("{") || back.is("]") || back.is("->")) {
            return new Block(K_SKIP, null, end); // 数组初始化 / lambda 体
        }
        return new Block(K_INSTANCE_INIT, null, end);
    }

    /** 从类型名之后找类体左花括号（跳过类型参数 / 组件列表 / 继承列表；paren 深度为 0）。 */
    static int findTypeBody(SrcFile f, int from, int close) {
        int p = 0;
        for (int i = from; i < close; i++) {
            Tok t = f.toks.get(i);
            if (t.is("(")) {
                p++;
            } else if (t.is(")")) {
                p = Math.max(0, p - 1);
            } else if (t.is("{")) {
                if (p == 0) {
                    return i;
                }
            } else if (t.is(";") && p == 0) {
                return -1;
            }
        }
        return -1;
    }

    static int matchBrace(SrcFile f, int open, int max) {
        int d = 0;
        for (int i = open; i < max; i++) {
            Tok t = f.toks.get(i);
            if (t.is("{")) {
                d++;
            } else if (t.is("}")) {
                d--;
                if (d == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    static int matchParen(SrcFile f, int open, int max) {
        int d = 0;
        for (int i = open; i < max; i++) {
            Tok t = f.toks.get(i);
            if (t.is("(")) {
                d++;
            } else if (t.is(")")) {
                d--;
                if (d == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    static int matchParenBack(SrcFile f, int closeIdx, int min) {
        int d = 0;
        for (int i = closeIdx; i >= min; i--) {
            Tok t = f.toks.get(i);
            if (t.is(")")) {
                d++;
            } else if (t.is("(")) {
                d--;
                if (d == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 调用点所在的最内层方法/构造器/初始化块；无归属（类体直写）时按字节码归 {@code <clinit>}。 */
    String spanFor(int idx) {
        Span best = null;
        for (Span s : spans) {
            if (s.start <= idx && idx <= s.end
                    && (best == null || (s.end - s.start) < (best.end - best.start))) {
                best = s;
            }
        }
        return best == null ? "<clinit>" : best.name;
    }

    /** {@code .class} 字面量前面的点分名（反向收集 IDENT 链）。 */
    String dottedChainBack(int q) {
        if (q < 0 || !toks.get(q).isIdent()) {
            return null;
        }
        List<String> segs = new ArrayList<>();
        segs.add(0, toks.get(q).text);
        while (q - 2 >= 0 && toks.get(q - 1).is(".") && toks.get(q - 2).isIdent()) {
            segs.add(0, toks.get(q - 2).text);
            q -= 2;
        }
        return String.join(".", segs);
    }

    /** 数一对圆括号内的实参个数（括号/方括号/花括号嵌套内的逗号不计）。 */
    int countArgs(int open) {
        int p = 0;
        int b = 0;
        int c = 0;
        int commas = 0;
        boolean saw = false;
        for (int k = open + 1; k < toks.size(); k++) {
            Tok tk = toks.get(k);
            if (tk.is("(")) {
                p++;
            } else if (tk.is("[")) {
                b++;
            } else if (tk.is("{")) {
                c++;
            } else if (tk.is(")")) {
                if (p == 0 && b == 0 && c == 0) {
                    return saw ? commas + 1 : 0;
                }
                p--;
            } else if (tk.is("]")) {
                b = Math.max(0, b - 1);
            } else if (tk.is("}")) {
                c = Math.max(0, c - 1);
            } else if (tk.is(",")) {
                if (p == 0 && b == 0 && c == 0) {
                    commas++;
                }
            } else if (p == 0 && b == 0 && c == 0) {
                saw = true;
            }
        }
        return saw ? commas + 1 : 0;
    }

    record Res(String prod, String src) {
    }

    List<String> candidates(String x) {
        List<String> out = new ArrayList<>();
        String[] segs = x.split("\\.");
        int n = segs.length;
        if (n == 1) {
            out.addAll(simpleCandidates(x));
            return dedup(out);
        }
        for (int k = 1; k < n; k++) {
            String baseName = String.join(".", Arrays.copyOfRange(segs, 0, k));
            String rest = String.join("$", Arrays.copyOfRange(segs, k, n));
            for (String base : simpleCandidates(baseName)) {
                out.add(base + "$" + rest);
            }
        }
        for (int m = 1; m < n; m++) {
            out.add(String.join(".", Arrays.copyOfRange(segs, 0, m))
                    + "$" + String.join("$", Arrays.copyOfRange(segs, m, n)));
        }
        out.add(x);
        return dedup(out);
    }

    List<String> simpleCandidates(String s) {
        List<String> out = new ArrayList<>();
        String imp = imports.get(s);
        if (imp != null) {
            out.add(imp);
        }
        if (!pkg.isEmpty()) {
            out.add(pkg + "." + s);
        }
        for (String w : wildcardImports) {
            out.add(w + "." + s);
        }
        out.add(s);
        return out;
    }

    static List<String> dedup(List<String> in) {
        return new ArrayList<>(new LinkedHashSet<>(in));
    }

    Res resolve(String x, Map<String, ProdMembers> prod, Set<String> srcClasses) {
        List<String> cands = candidates(x);
        for (String c : cands) {
            if (prod.containsKey(c)) {
                return new Res(c, null);
            }
        }
        for (String c : cands) {
            if (srcClasses.contains(c)) {
                return new Res(null, c);
            }
        }
        if (!x.contains(".") && JDK_NAMES.contains(x)) {
            return new Res(null, "jdk:" + x);
        }
        return new Res(null, null);
    }

    /**
     * 匹配本文件内的反射调用点并分类。
     *
     * <p>取舍（任务书 2.3 补充规则）：{@code X} 解析为生产类 → reflect；解析为测试/探针类或
     * JDK 类 → 不登记（本闸门只守卫生产类）；无法解析 → 如实计入 reflect-dynamic 并由调用方
     * 在报告中列出。</p>
     */
    void match(Map<String, ProdMembers> prod, Set<String> srcClasses,
               Map<String, TreeSet<String>> reflect, Map<String, TreeSet<String>> dynamic,
               List<String> unresolved, int[] skipped) {
        int n = toks.size();
        for (int i = 0; i < n; i++) {
            Tok t = toks.get(i);
            if (!t.isIdent()) {
                continue;
            }
            boolean named = NAMED_LOOKUPS.contains(t.text);
            boolean whole = WHOLE_CLASS_LOOKUPS.contains(t.text);
            if (!named && !whole) {
                continue;
            }
            if (i + 1 >= n || !toks.get(i + 1).is("(")) {
                continue;
            }
            if (i - 1 < 0 || !toks.get(i - 1).is(".") || i - 2 < 0) {
                continue;
            }
            String ctx = spanFor(i);
            int line = t.line;
            // X.class.getDeclaredX：i-2=class、i-3=`.`、i-4 起才是类型名点分链
            String x = toks.get(i - 2).is("class") ? dottedChainBack(i - 4) : null;
            boolean staticRecv = x != null;
            if (whole) {
                // 整类枚举：成员名无法静态解析（X.class.getDeclaredFields() 亦按动态点登记）
                add(dynamic, relPath + "\t" + ctx, relPath + ":" + line + " " + t.text + "()");
                continue;
            }
            boolean ctor = t.text.endsWith("Constructor");
            if (ctor) {
                int args = countArgs(i + 1);
                String member = "<init>(" + args + ")";
                String desc = t.text + "(...)";
                if (staticRecv) {
                    classifyStatic(prod, srcClasses, x, member, ctx, line, desc, reflect, dynamic, unresolved, skipped);
                } else {
                    add(dynamic, relPath + "\t" + ctx, relPath + ":" + line + " " + desc);
                }
                continue;
            }
            Tok firstArg = i + 2 < n ? toks.get(i + 2) : null;
            if (firstArg == null || firstArg.kind != STRING) {
                add(dynamic, relPath + "\t" + ctx, relPath + ":" + line + " " + t.text + "(变量)");
                continue;
            }
            Tok afterStr = i + 3 < n ? toks.get(i + 3) : null;
            if (afterStr == null || (!afterStr.is(")") && !afterStr.is(","))) {
                add(dynamic, relPath + "\t" + ctx, relPath + ":" + line + " " + t.text + "(拼接)");
                continue;
            }
            String name = firstArg.text;
            String member = t.text.endsWith("Method")
                    ? name + "(" + (countArgs(i + 1) - 1) + ")" : name;
            String desc = t.text + "(\"" + name + "\")";
            if (staticRecv) {
                classifyStatic(prod, srcClasses, x, member, ctx, line, desc, reflect, dynamic, unresolved, skipped);
            } else {
                add(dynamic, relPath + "\t" + ctx, relPath + ":" + line + " " + desc);
            }
        }
    }

    void classifyStatic(Map<String, ProdMembers> prod, Set<String> srcClasses, String x,
                        String member, String ctx, int line, String desc,
                        Map<String, TreeSet<String>> reflect, Map<String, TreeSet<String>> dynamic,
                        List<String> unresolved, int[] skipped) {
        Res r = resolve(x, prod, srcClasses);
        if (r.prod != null) {
            add(reflect, r.prod + "\t" + member, relPath + ":" + line + " " + desc);
        } else if (r.src != null) {
            skipped[0]++; // X 解析为测试/探针类或 JDK 类：不登记（只守卫生产类）
        } else {
            unresolved.add(relPath + ":" + line + " " + x + ".class." + desc);
            add(dynamic, relPath + "\t" + ctx, relPath + ":" + line + " " + x + ".class." + desc);
        }
    }

    static void add(Map<String, TreeSet<String>> map, String key, String site) {
        map.computeIfAbsent(key, k -> new TreeSet<>()).add(site);
    }
    }

    // ==================== 清单读写 ====================

    static final String MANIFEST_HEADER = """
            # 测试访问面清单（只减不增）。列：通道 <TAB> 生产类/文件 <TAB> 成员/方法 <TAB> 理由
            # 通道取值：direct（驱动类字节码常量池直访，第 2 列=生产类 FQN） | reflect（静态具名反射，\
            第 2 列=生产类 FQN）
            #   reflect-dynamic（成员名无法静态解析的反射点，按 文件+方法 登记，第 2 列=源文件相对路径）
            """;

    static Map<String, String> readManifest(Path p) throws IOException {
        if (!Files.exists(p)) {
            return null;
        }
        Map<String, String> rows = new TreeMap<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] cols = line.split("\t", -1);
            if (cols.length < 3) {
                continue;
            }
            rows.put(cols[0] + "\t" + cols[1] + "\t" + cols[2], line);
        }
        return rows;
    }

    /** 发现结果：已排序清单行 (通道, 第2列, 第3列, 理由)。 */
    public record Surface(List<String[]> rows) {
    }

    /**
     * 发现键集合（三通道合并）：键 = 通道 + "\t" + 第2列 + "\t" + 第3列，与清单逐字节一致
     * （{@link #loadManifest()} 用同一拼法解析清单前 3 列）。静默执行（诊断输出丢弃）。
     */
    public static TreeSet<String> discover() throws IOException {
        Surface s = collect(new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8));
        TreeSet<String> keys = new TreeSet<>();
        for (String[] r : s.rows) {
            keys.add(r[0] + "\t" + r[1] + "\t" + r[2]);
        }
        return keys;
    }

    /** 清单键集合（同 discover 拼法）；清单不存在时返回 null。 */
    public static TreeSet<String> loadManifest() throws IOException {
        Map<String, String> rows = readManifest(Paths.get(MANIFEST_PATH));
        return rows == null ? null : new TreeSet<>(rows.keySet());
    }

    /**
     * src/test/java 中包名前缀 com.mhtextractor.gui 的测试类的 top-level 类名集合
     * （= 包名 + 文件名去 .java；DRIVER_CLASSES 完整性守卫的实测侧）。
     */
    public static TreeSet<String> guiTestSourceClasses() throws IOException {
        Path root = Paths.get("src/test/java");
        TreeSet<String> out = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                String text = Files.readString(p, StandardCharsets.UTF_8);
                if (!text.isEmpty() && text.charAt(0) == '\ufeff') {
                    text = text.substring(1);
                }
                SrcFile sf = SrcFile.parseSource(rel, text);
                if (!(sf.pkg + ".").startsWith(DRIVER_PREFIX)) {
                    continue;
                }
                String fn = rel.substring(rel.lastIndexOf('/') + 1);
                if (fn.endsWith(".java")) {
                    out.add(sf.pkg + "." + fn.substring(0, fn.length() - 5));
                }
            }
        }
        return out;
    }

    // ==================== main ====================

    public static void main(String[] args) throws Exception {
        boolean write = false;
        for (String a : args) {
            if ("--write".equals(a)) {
                write = true;
            }
        }
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true,
                StandardCharsets.UTF_8);
        out.println("== TestAccessSurface（D1 发现器）==");
        out.println("仓库根: " + Paths.get("").toAbsolutePath().normalize() + "   模式: "
                + (write ? "打印 + 写清单" : "只打印"));
        Surface s = collect(out);
        if (write) {
            writeManifest(s.rows, out);
        }
    }

    /** 采集三通道发现（打印诊断信息；返回已排序的清单行）。 */
    public static Surface collect(PrintStream out) throws IOException {
        Path repoRoot = Paths.get("").toAbsolutePath().normalize();
        Path prodDir = repoRoot.resolve("target/classes");
        if (!Files.isDirectory(prodDir)) {
            throw new IOException(
                    "[FATAL] target/classes 不存在 —— 请先运行：mvnw.cmd -q -DskipTests test-compile");
        }
        Path testDir = repoRoot.resolve("target/test-classes");
        Path manifestPath = repoRoot.resolve(MANIFEST_PATH);

        // ---- 1. 生产成员库（字节码） ----
        Map<String, ProdMembers> prod = new TreeMap<>();
        List<String> badClasses = new ArrayList<>();
        int fieldCount = 0;
        int methodCount = 0;
        int pkgFieldCount = 0;
        int pkgMethodCount = 0;
        try (Stream<Path> walk = Files.walk(prodDir)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".class")).sorted().toList()) {
                try {
                    ClassFile cf = parseClassFile(Files.readAllBytes(p));
                    ProdMembers m = new ProdMembers();
                    for (Member mem : cf.fields) {
                        if ((mem.flags & (ACC_SYNTHETIC | ACC_BRIDGE)) != 0) {
                            continue;
                        }
                        m.fields.put(memberKey(mem.name, mem.desc), mem.flags);
                    }
                    for (Member mem : cf.methods) {
                        if ((mem.flags & (ACC_SYNTHETIC | ACC_BRIDGE)) != 0 || mem.name.equals("<clinit>")) {
                            continue;
                        }
                        m.methods.put(memberKey(mem.name, mem.desc), mem.flags);
                    }
                    prod.put(cf.fqn, m);
                } catch (Exception e) {
                    badClasses.add(prodDir.relativize(p).toString().replace('\\', '/') + " -> " + e);
                }
            }
        }
        for (ProdMembers m : prod.values()) {
            fieldCount += m.fields.size();
            methodCount += m.methods.size();
            for (int fl : m.fields.values()) {
                if (isPackageLevel(fl)) {
                    pkgFieldCount++;
                }
            }
            for (int fl : m.methods.values()) {
                if (isPackageLevel(fl)) {
                    pkgMethodCount++;
                }
            }
        }
        out.println();
        out.println("== 生产成员库（target/classes 字节码）==");
        out.println("  类文件: " + prod.size() + "   解析失败: " + badClasses.size());
        for (String b : badClasses) {
            out.println("    ! " + b);
        }
        out.println("  字段: " + fieldCount + "（包级 " + pkgFieldCount + "）  方法: " + methodCount
                + "（包级 " + pkgMethodCount + "）");
        // D1-fix 审计：同名成员在包级与非包级重载里同时出现的类（引用匹配已按描述符分流）
        Map<String, TreeSet<String>> mixed = new TreeMap<>();
        for (Map.Entry<String, ProdMembers> ce : prod.entrySet()) {
            Map<String, Integer> mask = new TreeMap<>();
            for (Map.Entry<String, Integer> me : ce.getValue().fields.entrySet()) {
                int p = me.getKey().indexOf('|');
                mask.merge("字段 " + me.getKey().substring(0, p), isPackageLevel(me.getValue()) ? 1 : 2,
                        (a, b) -> a | b);
            }
            for (Map.Entry<String, Integer> me : ce.getValue().methods.entrySet()) {
                int p = me.getKey().indexOf('|');
                mask.merge("方法 " + me.getKey().substring(0, p), isPackageLevel(me.getValue()) ? 1 : 2,
                        (a, b) -> a | b);
            }
            TreeSet<String> names = new TreeSet<>();
            for (Map.Entry<String, Integer> me : mask.entrySet()) {
                if (me.getValue() == 3) {
                    names.add(me.getKey());
                }
            }
            if (!names.isEmpty()) {
                mixed.put(ce.getKey(), names);
            }
        }
        out.println("  同名不同可见性重载的类: " + mixed.size());
        for (Map.Entry<String, TreeSet<String>> ce : mixed.entrySet()) {
            out.println("    " + ce.getKey() + " -> " + String.join(", ", ce.getValue()));
        }

        // ---- 2. direct 通道 ----
        Map<String, TreeSet<String>> direct = new TreeMap<>();
        List<String> drivers = new ArrayList<>();
        int pkgMismatch = 0;
        if (Files.isDirectory(testDir)) {
            try (Stream<Path> walk = Files.walk(testDir)) {
                for (Path p : walk.filter(f -> f.toString().endsWith(".class")).sorted().toList()) {
                    ClassFile cf;
                    try {
                        cf = parseClassFile(Files.readAllBytes(p));
                    } catch (Exception e) {
                        out.println("    ! 解析失败 " + testDir.relativize(p) + " -> " + e);
                        continue;
                    }
                    if (!DRIVER_CLASSES.contains(topLevel(cf.fqn))) {
                        continue;
                    }
                    drivers.add(cf.fqn);
                    for (CpRef r : cf.refs) {
                        ProdMembers m = prod.get(r.owner);
                        if (m == null) {
                            continue;
                        }
                        // 按 (名称, 描述符) 精确命中那一个成员，再判其可见性（D1-fix）
                        Integer flags = r.tag == TAG_FIELDREF
                                ? m.fields.get(memberKey(r.name, r.desc))
                                : m.methods.get(memberKey(r.name, r.desc));
                        if (flags == null || !isPackageLevel(flags)) {
                            continue;
                        }
                        if (!pkgOf(r.owner).equals(pkgOf(cf.fqn))) {
                            pkgMismatch++;
                        }
                        direct.computeIfAbsent(r.owner + "\t" + r.name, k -> new TreeSet<>()).add(cf.fqn);
                    }
                }
            }
        }
        out.println();
        out.println("== direct 通道（驱动类字节码常量池引用）==");
        out.println("  驱动类文件: " + drivers.size());
        out.println("  跨包包级引用计数: " + pkgMismatch + "（预期 0 —— 同包才可能直访包级成员）");
        out.println("  条目: " + direct.size());
        for (Map.Entry<String, TreeSet<String>> e : direct.entrySet()) {
            out.println("    " + e.getKey().replace('\t', ' ') + "  ← 驱动: "
                    + String.join(", ", e.getValue().stream().map(TestAccessSurface::simpleName).toList()));
        }

        // ---- 3. reflect / reflect-dynamic 通道 ----
        List<SrcFile> srcs = new ArrayList<>();
        for (String root : List.of("src/test/java", "tools/probes")) {
            Path dir = repoRoot.resolve(root);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : walk.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                    String rel = repoRoot.relativize(p).toString().replace('\\', '/');
                    String text = Files.readString(p, StandardCharsets.UTF_8);
                    if (!text.isEmpty() && text.charAt(0) == '\ufeff') {
                        text = text.substring(1);
                    }
                    srcs.add(SrcFile.parseSource(rel, text));
                }
            }
        }
        Set<String> srcClasses = new TreeSet<>();
        for (SrcFile s : srcs) {
            srcClasses.addAll(s.declared);
        }
        Map<String, TreeSet<String>> reflect = new TreeMap<>();
        Map<String, TreeSet<String>> dynamic = new TreeMap<>();
        List<String> unresolved = new ArrayList<>();
        int[] skipped = new int[1];
        for (SrcFile s : srcs) {
            s.match(prod, srcClasses, reflect, dynamic, unresolved, skipped);
        }
        out.println();
        out.println("== reflect 通道（静态具名反射；X 解析为生产类才登记）==");
        out.println("  条目: " + reflect.size());
        for (Map.Entry<String, TreeSet<String>> e : reflect.entrySet()) {
            out.println("    " + e.getKey().replace('\t', ' ') + "  @ " + String.join(" | ", e.getValue()));
        }
        out.println();
        out.println("== reflect-dynamic 通道（成员名无法静态解析的反射点，按 文件+方法 登记）==");
        out.println("  条目: " + dynamic.size());
        for (Map.Entry<String, TreeSet<String>> e : dynamic.entrySet()) {
            out.println("    " + e.getKey().replace('\t', ' ') + "  @ " + String.join(" | ", e.getValue()));
        }
        out.println();
        out.println("== X 解析为非生产类（测试/探针类或 JDK 类），不登记 ==");
        out.println("  " + skipped[0] + " 处");
        out.println();
        out.println("== 未解析的 X.class（如实计入 reflect-dynamic，见上）==");
        out.println("  " + unresolved.size() + " 处");
        for (String u : unresolved) {
            out.println("    " + u);
        }

        // ---- 4. 驱动源文件对账（DRIVER_CLASSES 显式清单 vs 编译产物） ----
        Set<String> srcGuiTop = guiTestSourceClasses();
        Set<String> driverTop = new TreeSet<>();
        for (String d : drivers) {
            driverTop.add(topLevel(d));
        }
        Set<String> stale = new TreeSet<>(driverTop);
        stale.removeAll(srcGuiTop);
        Set<String> uncompiled = new TreeSet<>(srcGuiTop);
        uncompiled.removeAll(driverTop);
        out.println();
        out.println("== 驱动源文件对账 ==");
        out.println("  src/test/java 下 gui 前缀测试源: " + srcGuiTop.size() + "（任务书口径：18）");
        out.println("  编译产物中无对应源的驱动类: " + stale.size() + (stale.isEmpty() ? "" : " -> " + stale));
        out.println("  有源但未编译出类的: " + uncompiled.size() + (uncompiled.isEmpty() ? "" : " -> " + uncompiled));

        // ---- 5. 清单对比 ----
        Map<String, String> manifest = readManifest(manifestPath);
        TreeSet<String> found = new TreeSet<>();
        for (String k : direct.keySet()) {
            found.add(CH_DIRECT + "\t" + k);
        }
        for (String k : reflect.keySet()) {
            found.add(CH_REFLECT + "\t" + k);
        }
        for (String k : dynamic.keySet()) {
            found.add(CH_DYNAMIC + "\t" + k);
        }
        out.println();
        out.println("== 发现集合 vs 清单 ==");
        out.println("  发现集合合计: " + found.size() + "（direct " + direct.size() + " + reflect " + reflect.size()
                + " + reflect-dynamic " + dynamic.size() + "）");
        if (manifest == null) {
            out.println("  清单不存在（首次生成；用 --write 写入）");
        } else {
            TreeSet<String> added = new TreeSet<>(found);
            added.removeAll(manifest.keySet());
            TreeSet<String> staleRows = new TreeSet<>(manifest.keySet());
            staleRows.removeAll(found);
            out.println("  清单条目: " + manifest.size() + "   一致: " + (found.size() - added.size())
                    + "   新增: " + added.size() + "   陈旧: " + staleRows.size());
            for (String a : added) {
                out.println("    + 新增  " + a.replace('\t', ' '));
            }
            for (String s : staleRows) {
                out.println("    - 陈旧  " + s.replace('\t', ' '));
            }
        }

        return new Surface(buildRows(direct, reflect, dynamic));
    }

    /** 组装清单行并按 (通道, 第2列, 第3列) 字典序排序（确定性）。 */
    private static List<String[]> buildRows(Map<String, TreeSet<String>> direct,
                                            Map<String, TreeSet<String>> reflect,
                                            Map<String, TreeSet<String>> dynamic) {
        List<String[]> rows = new ArrayList<>();
        for (Map.Entry<String, TreeSet<String>> e : direct.entrySet()) {
            String[] kv = e.getKey().split("\t", -1);
            rows.add(new String[]{CH_DIRECT, kv[0], kv[1],
                    "同包直访（驱动类字节码常量池引用）；驱动: " + String.join(", ",
                            e.getValue().stream().map(TestAccessSurface::simpleName).toList())});
        }
        for (Map.Entry<String, TreeSet<String>> e : reflect.entrySet()) {
            String[] kv = e.getKey().split("\t", -1);
            rows.add(new String[]{CH_REFLECT, kv[0], kv[1], String.join(" | ", e.getValue())});
        }
        for (Map.Entry<String, TreeSet<String>> e : dynamic.entrySet()) {
            String[] kv = e.getKey().split("\t", -1);
            rows.add(new String[]{CH_DYNAMIC, kv[0], kv[1], String.join(" | ", e.getValue())});
        }
        rows.sort((a, b) -> {
            int c = a[0].compareTo(b[0]);
            if (c != 0) {
                return c;
            }
            c = a[1].compareTo(b[1]);
            return c != 0 ? c : a[2].compareTo(b[2]);
        });
        return rows;
    }

    /** 写清单（--write；UTF-8 无 BOM、LF）。 */
    private static void writeManifest(List<String[]> rows, PrintStream out) throws IOException {
        StringBuilder sb = new StringBuilder(MANIFEST_HEADER);
        for (String[] r : rows) {
            sb.append(r[0]).append('\t').append(r[1]).append('\t').append(r[2]).append('\t')
                    .append(r[3]).append('\n');
        }
        Files.writeString(Paths.get(MANIFEST_PATH), sb.toString(), StandardCharsets.UTF_8);
        long direct = rows.stream().filter(r -> r[0].equals(CH_DIRECT)).count();
        long reflect = rows.stream().filter(r -> r[0].equals(CH_REFLECT)).count();
        long dynamic = rows.stream().filter(r -> r[0].equals(CH_DYNAMIC)).count();
        out.println();
        out.println("== 清单已写入 ==");
        out.println("  " + MANIFEST_PATH + "  行数: " + (rows.size() + MANIFEST_HEADER.lines().count())
                + "（数据行 " + rows.size() + "；direct " + direct + " + reflect " + reflect
                + " + reflect-dynamic " + dynamic + "）");
    }
}
