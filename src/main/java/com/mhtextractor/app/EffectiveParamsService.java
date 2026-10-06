package com.mhtextractor.app;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.GroupItem;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.model.FingerprintEncoder;
import com.mhtextractor.model.ParamScope;

import java.util.ArrayList;
import java.util.List;

/**
 * 生效参数服务（R8-①）：把「当前模式下某目标的生效参数是什么」「一组目标的生效参数是否统一 /
 * 组内是否混合」「目标是否存在独立参数」这三件事收敛为<b>全仓库唯一实现点</b>。
 *
 * <p><b>本类零 JavaFX、零第三方依赖、不调 {@code I18n.get}</b>：它只读参数缓存与模型字段，
 * 不产用户文案（{@code app} 层由 {@code ArchitectureTest} 强制）。</p>
 *
 * <h2>为什么是无状态 + 显式 {@link Context}（而不是直接持有 GUI 控件）</h2>
 * <p>两个全局参数缓存（{@code textParamsCache} / {@code imageParamsCache}）在 GUI 侧是<b>可变字段</b>
 * （参数确认 / 应用到全局 / 撤销都会整体替换它们，见 {@code MainController}）。若让本服务持有
 * 这两个引用，就必须处理"换模式、参数被替换后服务读到陈旧值"的问题，且服务会被钉在 GUI 生命周期上。</p>
 * <p>因此本服务<b>不持有任何可变状态</b>：模式与两个全局缓存由调用方每次经 {@link Context} 传入
 * （本质是两个缓存的<b>快照</b> + 一个模式位）。这样服务是纯函数式的、可由测试直接驱动，
 * 而 GUI 侧的"从控件取当前值"这件事收敛到 {@code MainController.effectiveContext()} 一处。</p>
 *
 * <h2>参数解析必须经 {@link ParamScope}（不得绕过、不得复制）</h2>
 * <p>图片模式的生效参数解析必须且只能经 {@link ParamScope#effectiveImageParams} —— 预览落盘与提取
 * （{@code OutputUnitPlanner.resolve}）解析出同一套参数是缓存复用（P10）的前提。本类不复制该逻辑，
 * 只做「按目标类型取出三个候选值 → 交给 {@code ParamScope}」的适配。</p>
 *
 * <h2>值语义口径（"是否统一"怎么比）</h2>
 * <p>{@link AppConfig} 是 Lombok {@code @Data} 可变对象，其 javadoc 明确禁止用 {@code equals} 当
 * 判定键（值会随外部修改而变化）。故本类<b>不调用 {@code equals}</b>，而是把每个配置渲染成
 * <b>字段级内容签名</b>（固定字段顺序 + 长度前缀编码，见 {@link #signatureOf}），再比签名是否相同。
 * 这与 {@code AppConfig.imageExtractionFingerprint()} 是同一套"固定顺序 + 长度前缀"编码风格，
 * 但<b>覆盖范围不同且更宽</b>：</p>
 * <ul>
 *   <li>{@code imageExtractionFingerprint()} 是<b>缓存命中</b>口径，只含影响"提取出哪些图片"的 8 个字段
 *       （P2 明确排除归档与内存模式）；</li>
 *   <li>本类的签名是<b>用户感知</b>口径，覆盖全部用户可设置参数 —— 包括
 *       {@code enableZipPackaging} / {@code enableCbzPackaging} / {@code highSpeedMemoryMode}，
 *       以及文本模式的全部字段。理由：R7 的判据是"多选后存在多个不同生效参数 → 禁用复制/应用"，
 *       用户看到的是"这些设置不一样"，而不是"缓存能不能复用"。</li>
 *   <li>仅排除 4 个<b>非"用户设置的提取参数"</b>的字段：{@code inputFolder} / {@code outputFolder} /
 *       {@code openFolderAfterComplete}（GUI 运行时状态；前两者属 {@code gui-state.json}）与
 *       {@code imageModeEnabled}（{@code AppConfig} javadoc 明写"提取模式属于主窗口 GUI 状态，
 *       运行时由主窗口模式开关写入"—— 它标的是"当前在哪个模式"，而"是否统一"本就只在单一模式内比较）。</li>
 * </ul>
 * <p>签名是 {@code imageExtractionFingerprint()} 的<b>超集</b>：图片模式下签名相同 ⇒ 指纹必然相同
 * （反之不成立），故"统一"判定比"缓存可复用"判定更严格 —— 不会出现"判定为统一却各自缓存不命中"
 * 的不一致方向（该方向才是危险的）。</p>
 */
public final class EffectiveParamsService {

    /** 无生效参数的固定标记（真实签名至少含一个模式位字符，故与任何真实签名都不同） */
    public static final String NO_PARAMS = "<none>";

    /**
     * 一次判定所需的参数解析上下文：当前是否图片模式 + 该模式与另一模式的全局已确认参数缓存
     * （{@code null} = 该模式尚未确认过参数）。
     *
     * <p>不可变快照：调用方在每次判定前重新构造即可（GUI 侧 {@code MainController.effectiveContext()}）。</p>
     */
    public record Context(boolean imageMode, AppConfig textGlobal, AppConfig imageGlobal) {
    }

    /**
     * 生效参数（唯一入口）：
     * <ul>
     *   <li>{@link GroupItem} → 只按组解析（组 &gt; 全局）—— 条目级参数与组目标无关；</li>
     *   <li>{@link PreviewItem} → 三层覆盖：{@code parentGroup} 的组参数 &gt; 条目级参数 &gt; 全局；</li>
     *   <li>null / 其他类型 → {@code null}（无生效参数）。</li>
     * </ul>
     *
     * <p>图片模式经 {@link ParamScope#effectiveImageParams}、文本模式经
     * {@link ParamScope#effectiveParams} —— 与迁移前 {@code MainController.effectiveForObject}
     * 的解析路径<b>逐语义等价</b>（同一个 {@code ParamScope} 分支，不再保留第二份）。</p>
     */
    public AppConfig effectiveFor(Object target, Context ctx) {
        GroupItem<?> parent = null;
        AppConfig itemText = null;
        AppConfig itemImage = null;
        if (target instanceof PreviewItem it) {
            parent = it.parentGroup;
            itemText = it.textItemParams;
            itemImage = it.imageItemParams;
        } else if (target instanceof GroupItem<?> g) {
            parent = g;
        }
        return ctx.imageMode()
                ? ParamScope.effectiveImageParams(parent, itemImage, ctx.imageGlobal())
                : ParamScope.effectiveParams(parent, itemText, itemImage, false,
                        ctx.textGlobal(), ctx.imageGlobal());
    }

    /**
     * 生效参数的来源状态（{@link ParamScope.Status}）：{@code GROUP} = 存在独立参数
     * （组参数或条目级参数），{@code GLOBAL} = 生效参数就是全局参数，{@code NONE} = 无生效参数。
     *
     * <p>这是 {@link #hasIndependentParams} 的原始形态，供视图层需要三态（而不仅二态）时使用。</p>
     */
    public ParamScope.Status effectiveSourceOf(Object target, Context ctx) {
        GroupItem<?> parent = target instanceof PreviewItem it ? it.parentGroup
                : (target instanceof GroupItem<?> g ? g : null);
        AppConfig itemText = target instanceof PreviewItem it ? it.textItemParams : null;
        AppConfig itemImage = target instanceof PreviewItem it ? it.imageItemParams : null;
        return ParamScope.backgroundStatus(parent, itemText, itemImage, ctx.imageMode(),
                ctx.textGlobal(), ctx.imageGlobal());
    }

    /**
     * 目标是否存在<b>独立参数</b>（组参数或条目级参数）—— 即拍板语义"仅此状态『应用到全局』可用"。
     *
     * <p>等价于 {@code effectiveSourceOf(target, ctx) == ParamScope.Status.GROUP}；单留一个实现点
     * （本方法委托 {@link #effectiveSourceOf}），故视图层的"清除参数"/"应用到全局"启用判定与
     * 背景色三态判定<b>共用同一份来源判定</b>，不会再各自演化。</p>
     */
    public boolean hasIndependentParams(Object target, Context ctx) {
        return effectiveSourceOf(target, ctx) == ParamScope.Status.GROUP;
    }

    /**
     * 一组目标的生效参数是否<b>混合</b>（存在多种取值）。
     *
     * <p><b>判定口径（本步明确决定）：{@code null}（无生效参数）算作一种独立取值</b> ——
     * {@code [A, null]} 视为混合。理由：用户在多选列表里看到的确实是"不统一"（一部分条目有参数、
     * 一部分没有），而 R7 的动作是"存在多个不同生效参数 → 禁用复制/应用参数"。若把 {@code null}
     * 忽略，则"一半有参数一半没有"会被判为统一，进而允许把参数复制到无参数条目上 —— 与"复制"的
     * 用户预期相反。</p>
     *
     * <p>边界：<b>空列表与单元素列表均为"不混合"</b>（0 或 1 种取值）。</p>
     */
    public boolean hasMixedEffectiveParams(List<?> targets, Context ctx) {
        if (targets == null || targets.size() < 2) {
            return false;
        }
        // 用有序列存已见签名（选择集规模是"同组/同选择集"，元素极少）：
        // 必须能区分 null 与"某套参数"，故 NO_PARAMS 作为一个独立取值参与去重。
        List<String> seen = new ArrayList<>();
        for (Object target : targets) {
            String sig = signatureOf(effectiveFor(target, ctx));
            if (!seen.contains(sig)) {
                seen.add(sig);
                if (seen.size() > 1) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 一组目标的生效参数是否<b>统一</b>（{@code !hasMixedEffectiveParams}，只此一个实现点）。
     *
     * <p>边界（与 {@link #hasMixedEffectiveParams} 一致）：空列表与单元素列表均为统一。</p>
     */
    public boolean isUniform(List<?> targets, Context ctx) {
        return !hasMixedEffectiveParams(targets, ctx);
    }

    /** 任一目标存在生效参数（非 null）。空列表 → {@code false}。 */
    public boolean anyHasEffectiveParams(List<?> targets, Context ctx) {
        if (targets == null) {
            return false;
        }
        for (Object target : targets) {
            if (effectiveFor(target, ctx) != null) {
                return true;
            }
        }
        return false;
    }

    /** 全部存在生效参数的目标（保持输入顺序）；供"参数目标信息栏"等展示用途。 */
    public List<Object> targetsWithEffectiveParams(List<?> targets, Context ctx) {
        List<Object> out = new ArrayList<>();
        if (targets != null) {
            for (Object target : targets) {
                if (effectiveFor(target, ctx) != null) {
                    out.add(target);
                }
            }
        }
        return out;
    }

    // ==================== 值语义：字段级内容签名 ====================

    /**
     * 配置的字段级内容签名（{@code null} → 固定标记 {@link #NO_PARAMS}）。
     *
     * <p>固定字段顺序 + 字符串长度前缀编码，不依赖 Map/Set 迭代顺序、不依赖 Lombok 生成顺序，
     * 且<b>不用 {@code equals}/{@code toString}/反射</b> —— 新增字段时签名会变（这正是我们要的：
     * 新参数一旦影响行为就必须参与"是否统一"判定），而不是静默沿用旧判定。
     * {@code AppConfigTest} 有一条"字段守护"用例会提醒同步本方法。</p>
     */
    public static String signatureOf(AppConfig c) {
        if (c == null) {
            return NO_PARAMS;
        }
        StringBuilder sb = new StringBuilder(256);
        // 通用
        sb.append(c.isSeparateFiles() ? '1' : '0');
        // 文本提取
        sb.append(c.isEnableContentLimit() ? '1' : '0');
        FingerprintEncoder.lengthPrefixed(sb, c.getStartMarker());
        FingerprintEncoder.lengthPrefixed(sb, c.getEndMarker());
        sb.append(c.isUseRegex() ? '1' : '0');
        sb.append(c.isEnableTemplate() ? '1' : '0');
        FingerprintEncoder.lengthPrefixed(sb, c.getStartTemplate());
        FingerprintEncoder.lengthPrefixed(sb, c.getEndTemplate());
        sb.append(c.isAddChapterNumber() ? '1' : '0');
        sb.append(c.isAutoFixEncoding() ? '1' : '0');
        sb.append(c.isEnableTitleExtraction() ? '1' : '0');
        FingerprintEncoder.lengthPrefixed(sb, c.getTitleStartMarker());
        FingerprintEncoder.lengthPrefixed(sb, c.getTitleEndMarker());
        sb.append(c.isTitleUseRegex() ? '1' : '0');
        // 图片提取
        sb.append(c.isEnableNameExtraction() ? '1' : '0');
        FingerprintEncoder.lengthPrefixed(sb, c.getImageNameStartMarker());
        FingerprintEncoder.lengthPrefixed(sb, c.getImageNameEndMarker());
        sb.append(c.isEnableRegionExtraction() ? '1' : '0');
        FingerprintEncoder.lengthPrefixed(sb, c.getImageRegionStartMarker());
        FingerprintEncoder.lengthPrefixed(sb, c.getImageRegionEndMarker());
        sb.append(c.isEnableBatchRenaming() ? '1' : '0');
        sb.append(c.isEnableZipPackaging() ? '1' : '0');
        sb.append(c.isEnableCbzPackaging() ? '1' : '0');
        sb.append(c.isSingleOutputMode() ? '1' : '0');
        // 处理模式
        sb.append(c.isHighSpeedMemoryMode() ? '1' : '0');
        // 兼容旧版废弃字段（保留反序列化兼容；仍参与判定，避免"设置里看得见却不影响判定"）
        FingerprintEncoder.lengthPrefixed(sb, c.getNameLimitStart());
        FingerprintEncoder.lengthPrefixed(sb, c.getNameLimitEnd());
        FingerprintEncoder.lengthPrefixed(sb, c.getContentLimitStart());
        FingerprintEncoder.lengthPrefixed(sb, c.getContentLimitEnd());
        return sb.toString();
    }

    /**
     * 仅供签名一致性守护测试使用：本签名刻意排除的 AppConfig 字段
     * （纯运行时/GUI 状态，不属于"用户设置的提取参数"）。
     *
     * <p>返回<b>不可变副本</b>，避免测试或调用方改动内部集合。</p>
     */
    public static List<String> runtimeOnlyFields() {
        return List.copyOf(RUNTIME_ONLY_FIELDS);
    }

    private static final List<String> RUNTIME_ONLY_FIELDS =
            List.of("inputFolder", "outputFolder", "imageModeEnabled", "openFolderAfterComplete");
}
