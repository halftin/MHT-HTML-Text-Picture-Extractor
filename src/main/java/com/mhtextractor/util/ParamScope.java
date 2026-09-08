package com.mhtextractor.util;

import com.mhtextractor.config.AppConfig;

/**
 * 参数作用域纯逻辑（F5 阶段 5 / 6.5 / 8.1 / 8.5）：
 * 参数目标路由（全局/组）、弹窗初值解析、生效参数覆盖层解析、条目背景色三态计算。
 *
 * <p>本类不感知 GUI 状态，全部方法为纯函数，供 MainController、ParamDialogController 与单元测试共用。</p>
 */
public final class ParamScope {

    private ParamScope() {
    }

    /** 条目背景色三态（8.5）：NONE 无生效参数（现状色）；GLOBAL 生效参数为全局参数（浅绿）；GROUP 生效参数为组参数（深绿） */
    public enum Status { NONE, GLOBAL, GROUP }

    /** 参数目标（8.1）：全局或某个组 */
    public sealed interface Target permits GlobalTarget, GroupTarget {
    }

    /** 全局参数目标 */
    public record GlobalTarget() implements Target {
    }

    /** 组参数目标 */
    public record GroupTarget(GroupItem<?> group) implements Target {
    }

    /** 全局目标常量（无状态，可复用） */
    public static final GlobalTarget GLOBAL = new GlobalTarget();

    /**
     * 目标路由（8.1 修订，拍板）：只认选中项——选中组条目 → 该组目标；
     * 选中项目且有所属组 → 其所在组目标；其余（顶层项目/未选中）→ 全局目标。
     * parentGroup 为选中项是项目时的所属组（非项目或顶层项目传 null）。
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
     * 组目标未设置时回退全局已确认参数；均无 → null（弹窗以默认参数展示）。
     */
    public static AppConfig initialParams(Target target, boolean imageMode,
                                          AppConfig textGlobal, AppConfig imageGlobal) {
        AppConfig own = target instanceof GroupTarget gt
                ? (imageMode ? gt.group().getImageParamsCache() : gt.group().getTextParamsCache())
                : (imageMode ? imageGlobal : textGlobal);
        return own != null ? own : (imageMode ? imageGlobal : textGlobal);
    }

    /**
     * 生效参数（6.5 覆盖层）：parentGroup 非空且组已设置该模式参数 → 组参数；
     * 否则继承全局参数；全局未确认 → null（无生效参数）。
     */
    public static AppConfig effectiveParams(GroupItem<?> parentGroup, boolean imageMode,
                                            AppConfig textGlobal, AppConfig imageGlobal) {
        if (parentGroup != null) {
            AppConfig groupParams = imageMode
                    ? parentGroup.getImageParamsCache() : parentGroup.getTextParamsCache();
            if (groupParams != null) {
                return groupParams;
            }
        }
        return imageMode ? imageGlobal : textGlobal;
    }

    /** 背景色三态（8.5）：按生效参数来源区分——无生效参数 / 全局参数 / 组参数（存在独立配置） */
    public static Status backgroundStatus(GroupItem<?> parentGroup, boolean imageMode,
                                          AppConfig textGlobal, AppConfig imageGlobal) {
        if (parentGroup != null
                && (imageMode ? parentGroup.getImageParamsCache()
                              : parentGroup.getTextParamsCache()) != null) {
            return Status.GROUP;
        }
        return (imageMode ? imageGlobal : textGlobal) != null ? Status.GLOBAL : Status.NONE;
    }
}
