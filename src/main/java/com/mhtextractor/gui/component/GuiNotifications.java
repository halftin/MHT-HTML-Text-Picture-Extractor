package com.mhtextractor.gui.component;

import com.mhtextractor.app.ExtractionPreconditions;
import com.mhtextractor.app.Texts;
import javafx.scene.Node;
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid;

import java.util.function.Consumer;

/**
 * 界面提醒的<b>语义门面</b>：把"这条提醒该用哪个图标、该用哪个 i18n 键"这两个决策
 * 收口到 <b>一个地方</b>，调用点只表达<b>意图</b>（"导入失败了" / "文件不见了" / "要清缓存了"）。
 *
 * <p><b>为什么值得一个门面</b>：{@link NotificationHost} 的 27 个调用点原先各自把
 * {@code Texts.get("error.X")} 与 {@code FontAwesomeSolid.Y} 写死在实参里。于是
 * "同一个用途必须给同一个图标"这条约定（{@code CLAUDE.md}「交互提醒约定」那张映射表）
 * 只能靠 22 处人工一致来维持 —— 这正是"两处用的其实是同一件事，却给了两个图标"的温床。
 * 收口后这类不一致在<b>编译期就写不出来</b>。</p>
 *
 * <p>🚨 <b>本类是纯语义门面，不是 {@link NotificationHost} 的替身</b>。这里的每个方法体
 * <b>只能</b>做两件事：① 挑图标 ② 挑 i18n 键，然后转调 {@link NotificationHost}。
 * 本类<b>不得</b>出现 {@code ModalPane} / {@code install} / {@code setContent} / {@code applyCss} /
 * {@code setPersistent} / {@code Stage} / {@code Scene}，也不得自建线程或定时器。</p>
 *
 * <p><b>为什么这条红线值得钉死</b>：{@code NotificationHost} 的
 * "先 {@code applyCss()} 让皮肤就位 → 再 {@code setContent()} → 最后 {@code show()}"
 * 顺序、以及 {@code persistent} 语义，都是<b>踩出来的</b>（见 {@code showModal} 的注释：
 * 顺序反了的现象是 {@code display=true} 但高度 0、屏幕上什么都没有 —— "卡片设了却看不见"）。
 * 在门面里再包一层壳 = 把这些机制<b>复制成第二份实现</b>，两份迟早不一致，而失效是静默的。
 * 本类由 {@code GuiNotificationsIsSemanticFacadeOnlyTest} 用源码扫描持续强制
 * （并配 {@link NotificationHost} 作控制组）。</p>
 *
 * <p><b>锚点为什么显式传</b>：{@link NotificationHost} 的入口是静态的，且刻意让控制器
 * "不持有宿主字段"（这是保住 {@code MainController} 只减不增的关键）。若本门面持有锚点字段，
 * 控制器就得多一个字段 + 一处初始化 + 一个"初始化时机"问题；而这些与运行平台无关的
 * 静态转发没有任何状态可持有。故本类<b>全部是静态方法</b>，锚点与 {@link NotificationHost} 同形。</p>
 *
 * <p><b>线程</b>：本类<b>不做</b>线程适配（那是调用点的语境），语义与 {@link NotificationHost} 一致 ——
 * 只有 {@link #cacheClearing} 可从任意线程调用（{@code busy} 内部会自行转投 FX 线程），
 * 其余入口都要求 FX 线程。</p>
 *
 * <h2>图标映射（与 {@code CLAUDE.md}「交互提醒约定」的表一一对应）</h2>
 * <ul>
 *   <li>{@link #fileMissing} → {@code FILE}；{@link #largeFileConfirm} / {@link #readFileInfoFailed} → {@code FILE_ALT}</li>
 *   <li>{@link #fileDirMissing} / {@link #openFolderFailed} → {@code FOLDER_OPEN}
 *       （<b>刻意同图标</b>：两者都是"文件夹这一路"出的问题，不是"一码一图标"）</li>
 *   <li>{@link #browserLaunchFailed} / {@link #browserViewFailed} → {@code GLOBE}（同上，同一用途）</li>
 *   <li>{@link #importFailed} → {@code FILE_IMPORT}；导入配额卡（B5-1 起经 {@link GuiConfirmer} 渲染）→ {@code CLIPBOARD_LIST}</li>
 *   <li>{@link #driveRootConfirm} / {@link #cacheStepWarning} → {@code HDD}</li>
 *   <li>{@link #cacheClearing} → {@code BROOM}；{@link #paramDialogOpenFailed} / {@link #paramTargetConfirm} /
 *       {@link #collapseDraftKept} / {@link #draftBlocksStart} → {@code SLIDERS_H}</li>
 *   <li>{@link #recursionDepthConfirm} → {@code SITEMAP}；{@link #removeAllConfirm} → {@code TRASH_ALT}</li>
 *   <li>{@link #removeGroupConfirm} / {@link #unnamedGroupConfirm} → {@code OBJECT_GROUP}；{@link #listEmpty} → {@code LIST}</li>
 *   <li>{@link #preconditionDenied} → 按级别：ERROR → {@code EXCLAMATION_CIRCLE}（居中卡片）、
 *       WARNING → {@code EXCLAMATION_TRIANGLE}（右上角 toast）</li>
 * </ul>
 *
 * @see NotificationHost
 */
public final class GuiNotifications {

    /** 工具类：不实例化（全部是静态转发）。 */
    private GuiNotifications() {
    }

    // ==================== 缓存 / 容量 ====================

    /**
     * 关窗前清空预览缓存的<b>无按钮等待卡片</b>（{@link NotificationHost#busy}）：
     * 标题与说明文案在此拼好（调用点原先自己拼 {@code Texts.get("cache.clearingBeforeExit")}）。
     *
     * @param anchor 锚点（主窗口的任意节点）
     * @param root   预览缓存根目录（展示给用户，让"清的是什么"一目了然）
     * @param work   后台清空动作；{@code onDone} 在清完（成功或失败）后于 FX 线程执行（关窗）
     */
    public static void cacheClearing(Node anchor, Object root, Runnable work, Runnable onDone) {
        String description = Texts.get("cache.clearingBeforeExit") + "\n" + root;
        NotificationHost.busy(anchor, Texts.get("cache.clearingTitle"), description,
                FontAwesomeSolid.BROOM, work, onDone);
    }

    /** 预览缓存跨过大小台阶：右上角非阻塞警告 toast（连续跨台阶由 toast 层自行限流）。 */
    public static void cacheStepWarning(Node anchor, String message) {
        NotificationHost.toastWarning(anchor, message, FontAwesomeSolid.HDD);
    }

    /** 仅拖拽来源 + 盘符根目录：居中确认卡片（BC-8 防误拖整个盘）。 */
    public static void driveRootConfirm(Node anchor, String message, Consumer<Boolean> onDecision) {
        NotificationHost.confirm(anchor, message, FontAwesomeSolid.HDD, onDecision);
    }

    // ==================== 导入 / 参数 ====================

    /** 导入扫描失败：居中错误卡片（必须点"确定"）。 */
    public static void importFailed(Node anchor) {
        NotificationHost.error(anchor, Texts.get("error.IMPORT_FAILED"), FontAwesomeSolid.FILE_IMPORT);
    }

    /** 参数设置窗口打开失败：居中错误卡片。 */
    public static void paramDialogOpenFailed(Node anchor) {
        NotificationHost.error(anchor, Texts.get("error.PARAM_DIALOG_OPEN_FAILED"), FontAwesomeSolid.SLIDERS_H);
    }

    /** 当前模式参数未确认（或检测到模式切换）：居中确认卡片，确认后继续提取。 */
    public static void paramTargetConfirm(Node anchor, String message, Consumer<Boolean> onDecision) {
        NotificationHost.confirm(anchor, message, FontAwesomeSolid.SLIDERS_H, onDecision);
    }

    /** R1.8（步骤 4 · 决策 2.6 起降级为 toast）：收起侧边栏、草稿已保留 —— 文案指向右侧圆钮（非阻塞）。 */
    public static void collapseDraftKept(Node anchor) {
        NotificationHost.toastInfo(anchor, Texts.get("sidepanel.collapseDraftHint"), FontAwesomeSolid.SLIDERS_H);
    }

    /**
     * R1.7（步骤 4 · 语义变更，决策 D）：点「开始提取」时存在未提交草稿的拦截提醒 ——
     * 居中、只有一个「确定」按钮，文案 {@code sidepanel.draftBlocksStart}。
     *
     * <p>为什么走 {@link NotificationHost#error}：{@code NotificationHost} 现有入口里
     * 只有 {@code error} 是"居中 + 单按钮"形态（{@code confirm} 恒有两钮，{@code busy} 零按钮）；
     * 语义上是阻断说明而非错误，但卡片形态必须满足决策 D。它是<b>信息性</b>卡片：
     * 不注册未答决策（{@code hasPendingDecision} 行为不变，与 R3.6「dirty 草稿不拦关窗」一致），
     * 「确定」只关卡片。</p>
     */
    public static void draftBlocksStart(Node anchor) {
        NotificationHost.error(anchor, Texts.get("sidepanel.draftBlocksStart"), FontAwesomeSolid.SLIDERS_H);
    }

    // ==================== 文件 / 文件夹 / 浏览器 ====================

    /** 文件所在目录不存在：右上角警告 toast（占位符 {@code path} = 条目路径）。 */
    public static void fileDirMissing(Node anchor, String path) {
        NotificationHost.toastWarning(anchor, Texts.get("error.FILE_DIR_MISSING", "path", path),
                FontAwesomeSolid.FOLDER_OPEN);
    }

    /** 打开所在文件夹失败：居中错误卡片（与 {@link #fileDirMissing} <b>同图标</b>：都属"文件夹这一路"）。 */
    public static void openFolderFailed(Node anchor) {
        NotificationHost.error(anchor, Texts.get("error.OPEN_FOLDER_FAILED"), FontAwesomeSolid.FOLDER_OPEN);
    }

    /** 文件不存在（可能已被移动或删除）：右上角警告 toast（占位符 {@code path} = 条目路径）。 */
    public static void fileMissing(Node anchor, String path) {
        NotificationHost.toastWarning(anchor, Texts.get("error.FILE_MISSING", "path", path), FontAwesomeSolid.FILE);
    }

    /**
     * 未能启动浏览器（页面已生成）：右上角警告 toast（占位符 {@code file} = 已生成页面路径）。
     *
     * <p>{@code file} 收 {@code Object} 而不是 {@code String}/{@code Path}：调用点的实参是
     * {@code HtmlExportBuilder.ExportResult.htmlFile()}（{@link java.nio.file.Path}），而
     * {@link Texts#get} 本就按 {@code String.valueOf} 渲染 —— 与 {@code Texts.get(String, Object...)}
     * 的既有形态一致，收 {@code Object} 可让调用点原样透传、不多一次转换。</p>
     */
    public static void browserLaunchFailed(Node anchor, Object file) {
        NotificationHost.toastWarning(anchor, Texts.get("error.BROWSER_LAUNCH_FAILED", "file", file),
                FontAwesomeSolid.GLOBE);
    }

    /** 生成浏览器查看页失败：居中错误卡片（与 {@link #browserLaunchFailed} <b>同图标</b>：都属"浏览器这一路"）。 */
    public static void browserViewFailed(Node anchor) {
        NotificationHost.error(anchor, Texts.get("error.BROWSER_VIEW_FAILED"), FontAwesomeSolid.GLOBE);
    }

    /** 计算缓存键 / 读取文件信息失败：居中错误卡片。 */
    public static void readFileInfoFailed(Node anchor) {
        NotificationHost.error(anchor, Texts.get("error.READ_FILE_INFO_FAILED"), FontAwesomeSolid.FILE_ALT);
    }

    /** 大文件预览确认：居中确认卡片（确认后才解码，避免卡界面）。 */
    public static void largeFileConfirm(Node anchor, String message, Consumer<Boolean> onDecision) {
        NotificationHost.confirm(anchor, message, FontAwesomeSolid.FILE_ALT, onDecision);
    }

    // ==================== 提取前置校验 ====================

    /**
     * 提取前置校验拒绝：<b>级别决定形态</b> —— ERROR 走居中错误卡片（必须确认），
     * WARNING 走右上角非阻塞 toast。
     *
     * <p>这个 if/else 就是本门面存在的理由之一：接收方原先在调用点自己判
     * {@code denial.level()} 并配两个不同的图标（{@code EXCLAMATION_CIRCLE} /
     * {@code EXCLAMATION_TRIANGLE}），任何一处漏判都会让"必须确认的错误"退化成
     * "会自己消失的 toast"。</p>
     */
    public static void preconditionDenied(Node anchor, ExtractionPreconditions.Denial denial) {
        if (denial.level() == ExtractionPreconditions.Level.ERROR) {
            NotificationHost.error(anchor, denial.code().message(), FontAwesomeSolid.EXCLAMATION_CIRCLE);
        } else {
            NotificationHost.toastWarning(anchor, denial.code().message(), FontAwesomeSolid.EXCLAMATION_TRIANGLE);
        }
    }

    public static void preconditionDenied(Node anchor, com.mhtextractor.uiport.PreStartStateVm.Denial denial) {
        Object[] params = denial.namedParams().entrySet().stream()
                .flatMap(entry -> java.util.stream.Stream.of(entry.getKey(), entry.getValue())).toArray();
        String message = Texts.get(denial.key(), params);
        if ("ERROR".equals(denial.level())) {
            NotificationHost.error(anchor, message, FontAwesomeSolid.EXCLAMATION_CIRCLE);
        } else {
            NotificationHost.toastWarning(anchor, message, FontAwesomeSolid.EXCLAMATION_TRIANGLE);
        }
    }

    public static void backendRequestFailed(Node anchor, String detail) {
        NotificationHost.error(anchor, detail, FontAwesomeSolid.EXCLAMATION_TRIANGLE);
    }

    // ==================== 列表 / 移除 ====================

    /** 递归深度超限：居中确认卡片（等待方是提取后台线程，回答经回调回传）。 */
    public static void recursionDepthConfirm(Node anchor, String message, Consumer<Boolean> onDecision) {
        NotificationHost.confirm(anchor, message, FontAwesomeSolid.SITEMAP, onDecision);
    }

    /** 移除全选（含已展开组成员）：居中确认卡片。 */
    public static void removeAllConfirm(Node anchor, String message, Consumer<Boolean> onDecision) {
        NotificationHost.confirm(anchor, message, FontAwesomeSolid.TRASH_ALT, onDecision);
    }

    /** 整组移除：居中确认卡片。 */
    public static void removeGroupConfirm(Node anchor, String message, Consumer<Boolean> onDecision) {
        NotificationHost.confirm(anchor, message, FontAwesomeSolid.OBJECT_GROUP, onDecision);
    }

    /** 未命名组提取确认（步骤 3 · 功能一：确定 = 以默认名提取；取消 = 不提取）。与整组移除同族 {@code OBJECT_GROUP}。 */
    public static void unnamedGroupConfirm(Node anchor, String message, Consumer<Boolean> onDecision) {
        NotificationHost.confirm(anchor, message, FontAwesomeSolid.OBJECT_GROUP, onDecision);
    }

    /**
     * 功能一：开启"只提取选中的列表项"且本次存在合并单元时，提示"合并输出的文件名/目录名可能变化"。
     * 确定 = 继续提取；取消 = 中止本次启动（与 {@link #paramTargetConfirm} 同形居中确认卡）。
     */
    public static void subsetMergedNameConfirm(Node anchor, String message, Consumer<Boolean> onDecision) {
        NotificationHost.confirm(anchor, message, FontAwesomeSolid.FILE_EXPORT, onDecision);
    }

    /** 移除后列表已空：右上角信息 toast（不是错误 —— 用户自己移除的）。 */
    public static void listEmpty(Node anchor) {
        NotificationHost.toastInfo(anchor, Texts.get("error.KEEP_AT_LEAST_ONE"), FontAwesomeSolid.LIST);
    }
}
