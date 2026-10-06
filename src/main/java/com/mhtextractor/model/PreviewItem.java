package com.mhtextractor.model;

import com.mhtextractor.cache.PreviewCacheMeta;
import com.mhtextractor.config.AppConfig;
import com.mhtextractor.extractor.ImageExtractor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 单个 MHT 文件的预览条目（会话 18 · E2.1 从 {@code MainController} 内部类外迁为顶层模型类）。
 *
 * <p><b>为什么外迁</b>：它此前是 GUI 控制器的内部类，导致"列表条目模型"与"GUI 控制器"绑定，
 * 业务/应用层无法脱离 UI 复用（耦合点 #3）。外迁后本类**零 JavaFX 依赖**，
 * 可被将来的无头应用层（{@code com.mhtextractor.app}）直接使用 —— 这是验收 V4 与目标 ② 的前置。</p>
 *
 * <p><b>会话 18 的分步改造记录</b>：</p>
 * <ul>
 *   <li>E1.3a：5 个 JavaFX 属性（{@code status}/{@code errorMsg}/{@code chapterEnabled}/
 *       {@code selected}/{@code badge}）→ 普通字段 + 自研 {@link ChangeListener}；</li>
 *   <li>E1.4：{@code cachedThumbs} 由 JavaFX {@code Image} → {@link ThumbnailData}（纯像素）；</li>
 *   <li>E2.1：本类从 {@code MainController} 内部类外迁到 {@code com.mhtextractor.model}。</li>
 * </ul>
 *
 * <p><b>字段可见性的说明（重要）</b>：字段目前是<b>包级/公开</b>的，而非全部 private + getter。
 * 这忠实保留了外迁前的访问形态（控制器与演练代码大量直接读写这些字段），把"改访问方式"
 * 与"改所属层"两件事分开做，避免一次性引入行为变更。访问方式的收紧（getter/setter 化）
 * 列为后续清理项，见 {@code docs/handoff/H17.md}。</p>
 */
public class PreviewItem {

    /**
     * 预览状态机：未解析 / 就绪 / 内容为空 / 解析失败。
     *
     * <p>原本是 {@code MainController} 内与 {@code PreviewItem} 平级的嵌套枚举；E2.1 一并迁入 model 包，
     * 并归到 {@code PreviewItem} 之下（它是条目的状态，不是控制器的状态）。</p>
     */
    public enum Status { WAITING, READY, EMPTY, FAILED }

    // ==================== 身份（不可变） ====================

    public final String path;
    public final String name;
    public final LocalDateTime modifiedTime;
    /** 输入来源目录：随导入写入，不随输入目录切换变化（F3/R1） */
    public final String sourceFolder;

    // ==================== UI 状态（普通字段，E1.3a 去 JavaFX 属性） ====================

    public Status status = Status.WAITING;
    public String errorMsg = "";
    public boolean chapterEnabled = true;
    /** 复选框勾选状态（F1）：默认未选中，仅用于批量选择，不影响提取名单 */
    public boolean selected = false;
    public String badge = null;

    /**
     * 勾选变更监听器（懒初始化）。等价替换原 {@code BooleanProperty selected} 的 observable 语义 ——
     * 原用途仅两处：① 控制器注册 onSelectionChanged()；② 单元格 checkBox 双向绑定。
     * 两者都只关心"值变了"，不需要属性名/旧值。仅在值确实变化时通知（与 JavaFX 属性 set 语义一致）。
     */
    private List<ChangeListener> selectedChangeListeners;

    /** 注册勾选变更监听器（同步回调；与替换前的 BooleanProperty 行为等价） */
    public void addSelectedChangeListener(ChangeListener listener) {
        if (listener == null) return;
        if (selectedChangeListeners == null) {
            selectedChangeListeners = new ArrayList<>(1);
        }
        selectedChangeListeners.add(listener);
    }

    /**
     * 移除勾选变更监听器。
     *
     * <p>替换原 {@code BooleanProperty} 的 {@code unbindBidirectional} —— 单元格复用必须能切断
     * 旧条目的联动，否则会串值/泄漏监听器。</p>
     */
    public void removeSelectedChangeListener(ChangeListener listener) {
        if (selectedChangeListeners != null && listener != null) {
            selectedChangeListeners.remove(listener);
        }
    }

    /** 设置勾选状态；仅在值确实变化时通知监听器（与 JavaFX 属性 set 语义一致） */
    public void setSelected(boolean value) {
        if (this.selected == value) return;
        this.selected = value;
        if (selectedChangeListeners != null) {
            // 复制后遍历：回调内增删监听器不会触发 ConcurrentModificationException
            for (ChangeListener l : new ArrayList<>(selectedChangeListeners)) {
                l.onValueChanged();
            }
        }
    }

    // ==================== 结构状态 ====================

    /**
     * 移除标记（F2）：隐藏式移除——不实际从列表删除，仅隐藏并排除在序号/徽标/提取名单外
     */
    public boolean removed = false;
    /**
     * 所属组（F5）：null = 顶层未分组；由 rebuildDisplay 统一维护，勿手工赋值
     * （volatile：预解析线程读取）
     */
    public volatile GroupItem<PreviewItem> parentGroup = null;

    // ==================== 预览缓存（内存驻留） ====================

    public volatile String cachedText;
    public volatile String cachedTitle;
    /**
     * 用户编辑的提取标题（需求：查看器「提取标题」行改为可编辑文本框）。
     * <p>null/空白 = 使用提取值；非空 = 最终输出命名优先采用该值。</p>
     * <p><b>为什么不在 {@link #resetPreviewState()} 里清它</b>：它是<b>用户手工编辑</b>的结果，
     * 不是派生缓存；切换参数触发的重新预解析只应清提取派生值（{@code cachedTitle}），
     * 清掉用户输入会造成"改一次参数就丢标题"。</p>
     */
    public volatile String titleOverride;
    /**
     * 全尺寸图片（历史遗留：会话 18 实测**只被写 null、从不被读取**，唯一用途是 C2 断言
     * {@code cachedImages == null}）。其元素类型 {@code ImageExtractor.ImageData} 零 JavaFX，
     * 不构成分层障碍；删除它属行为变更，故按 R3 保留并登记为清理项。
     */
    public volatile List<ImageExtractor.ImageData> cachedImages;
    /** 缩略图（纯像素，E1.4）：C2「内存只留 meta + 缩略图」 */
    public volatile List<ThumbnailData> cachedThumbs;
    /** 预览缓存元数据（需求 2 会话 9）：磁盘落盘后的 meta（含图片清单，不含字节） */
    public volatile PreviewCacheMeta cachedMeta;

    /**
     * 清空预览态（E1.2 的唯一实现，生效参数切换后重新预解析）：五个内存缓存字段全部清空 +
     * 回到 {@link Status#WAITING}。磁盘缓存由 {@code PreviewCacheLifecycle} 的四条规则管理，不在此处触碰。
     *
     * <p><b>字段清单只许有一份</b>：任何"清预览态"语义都必须走本方法，不得在别处再手写
     * 这五个字段的赋值（曾因 {@code ParamDomainController} 手写清单漏掉 {@code cachedMeta}
     * 造成陈旧 meta 残留）。</p>
     */
    public void resetPreviewState() {
        cachedText = null;
        cachedTitle = null;
        cachedImages = null;
        cachedThumbs = null;
        cachedMeta = null;
        status = Status.WAITING;
    }

    /**
     * 释放图片数据（E1.2 的唯一实现）：只清 {@code cachedImages}/{@code cachedThumbs}，
     * 文本、标题、meta 与状态保持不变。这是 {@code ListInteractionController.onItemSelected}
     * 的"仅当前文件的图片数据驻留内存"优化语义（慎重起见不并入 {@link #resetPreviewState}，
     * 注释保留在原调用点）。
     */
    public void releaseImageData() {
        cachedImages = null;
        cachedThumbs = null;
    }

    // ==================== 提取结果 ====================

    /** 最近一次提取结果（提取结果状态需求）：NONE 无结果；仅在 FX 线程回写/清除 */
    public ExtractOutcome extractOutcome = ExtractOutcome.NONE;
    /** 提取结果详情（失败/跳过原因 + 警告，分号拼接）；点击含红条目时预览区 banner 展示 */
    public String extractDetail;

    // ==================== 条目级独立参数 ====================

    /** 条目级独立参数（条目独立参数需求）：文本/图片各一套；null = 未设置；
     *  仅顶层项目持有（入组/合并时清除）；volatile：预解析线程读取 */
    public volatile AppConfig textItemParams;
    public volatile AppConfig imageItemParams;

    public PreviewItem(String path, String name, LocalDateTime modifiedTime, String sourceFolder) {
        this.path = path;
        this.name = name;
        this.modifiedTime = modifiedTime;
        this.sourceFolder = sourceFolder;
    }
}
