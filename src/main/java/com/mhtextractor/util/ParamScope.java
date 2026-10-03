package com.mhtextractor.util;

import com.mhtextractor.config.AppConfig;

/**
 * 参数作用域纯逻辑（F5 阶段 5 / 6.5 / 8.1 / 8.5 + 条目独立参数需求）：
 * 参数目标（全局/组/条目）、弹窗初值解析、生效参数三层覆盖（组 &gt; 条目 &gt; 全局）、条目背景色三态计算。
 *
 * <p>本类不感知 GUI 状态，全部方法为纯函数，供 MainController、参数表单（ParamFormPane）与单元测试共用。</p>
 */
public final class ParamScope {

    private ParamScope() {
    }

    /** 条目背景色三态（8.5）：NONE 无生效参数（现状色）；GLOBAL 生效参数为全局参数（深蓝）；
     *  GROUP 生效参数为组参数/条目级参数（存在独立配置，浅蓝） */
    public enum Status { NONE, GLOBAL, GROUP }

    /** 参数目标（8.1 + 条目独立参数需求）：全局或某个组或某个条目 */
    public sealed interface Target permits GlobalTarget, GroupTarget, ItemTarget {
    }

    /** 全局参数目标 */
    public record GlobalTarget() implements Target {
    }

    /** 组参数目标 */
    public record GroupTarget(GroupItem<?> group) implements Target {
    }

    /** 条目级参数目标（条目独立参数需求）：item 为调用方条目类型（本类不感知），双模式参数快照供弹窗初值解析 */
    public record ItemTarget<T>(T item, AppConfig textParams, AppConfig imageParams) implements Target {
    }

    /** 全局目标常量（无状态，可复用） */
    public static final GlobalTarget GLOBAL = new GlobalTarget();

    /**
     * 目标路由（8.1 修订，拍板）：只认选中项——选中组条目 → 该组目标；
     * 选中项目且有所属组 → 其所在组目标；其余（顶层项目/未选中）→ 全局目标。
     * 条目级目标（ItemTarget）由调用方构造（需持有条目引用与参数快照）。
     */
    public static Target route(Object selected, GroupItem<?> parentGroup) {
        if (selected instanceof GroupItem<?> g) {
            return new GroupTarget(g);
        }
        if (parentGroup != null) {
            return new GroupTarget(parentGroup);
        }
        return GLOBAL;
    }

    /**
     * 参数弹窗初值（6.5）：目标已有该模式已确认参数 → 用之；
     * 组/条目目标未设置时回退全局已确认参数；均无 → null（弹窗以默认参数展示）。
     */
    public static AppConfig initialParams(Target target, boolean imageMode,
                                          AppConfig textGlobal, AppConfig imageGlobal) {
        AppConfig own = switch (target) {
            case GroupTarget gt -> imageMode ? gt.group().getImageParamsCache() : gt.group().getTextParamsCache();
            case ItemTarget<?> it -> imageMode ? it.imageParams() : it.textParams();
            case GlobalTarget ignored -> imageMode ? imageGlobal : textGlobal;
        };
        return own != null ? own : (imageMode ? imageGlobal : textGlobal);
    }

    /**
     * 生效参数唯一入口（图片模式，会话 7 收敛）：组参数 &gt; 条目级图片参数 &gt; 全局图片参数；
     * 均未确认 → null。预览（MainController.effectiveForObject）与提取（OutputUnitPlanner.resolve）
     * 的图片参数解析必须且只能经由本方法——缓存复用（需求 2 P10）依赖两者解析出同一套参数。
     */
    public static AppConfig effectiveImageParams(GroupItem<?> parentGroup, AppConfig itemImage,
                                                 AppConfig imageGlobal) {
        if (parentGroup != null) {
            AppConfig groupParams = parentGroup.getImageParamsCache();
            if (groupParams != null) {
                return groupParams;
            }
        }
        if (itemImage != null) {
            return itemImage;
        }
        return imageGlobal;
    }

    /**
     * 生效参数三层覆盖（条目独立参数需求拍板）：组参数优先，其次条目级参数，最后继承全局；
     * 全局未确认 → null（无生效参数）。组内成员无条目级参数（itemText/itemImage 传 null）。
     * 图片模式委托 {@link #effectiveImageParams}（唯一入口，会话 7 收敛）。
     */
    public static AppConfig effectiveParams(GroupItem<?> parentGroup, AppConfig itemText, AppConfig itemImage,
                                            boolean imageMode, AppConfig textGlobal, AppConfig imageGlobal) {
        if (imageMode) {
            return effectiveImageParams(parentGroup, itemImage, imageGlobal);
        }
        if (parentGroup != null) {
            AppConfig groupParams = parentGroup.getTextParamsCache();
            if (groupParams != null) {
                return groupParams;
            }
        }
        if (itemText != null) {
            return itemText;
        }
        return textGlobal;
    }

    /**
     * 背景色三态（8.5 + 条目独立参数需求）：按生效参数来源区分——组参数/条目级参数（存在独立配置）
     * /全局参数/无生效参数。组内成员无条目级参数（itemText/itemImage 传 null）。
     */
    public static Status backgroundStatus(GroupItem<?> parentGroup, AppConfig itemText, AppConfig itemImage,
                                          boolean imageMode, AppConfig textGlobal, AppConfig imageGlobal) {
        if (parentGroup != null
                && (imageMode ? parentGroup.getImageParamsCache()
                              : parentGroup.getTextParamsCache()) != null) {
            return Status.GROUP;
        }
        if (imageMode ? itemImage != null : itemText != null) {
            return Status.GROUP;
        }
        return (imageMode ? imageGlobal : textGlobal) != null ? Status.GLOBAL : Status.NONE;
    }
}
