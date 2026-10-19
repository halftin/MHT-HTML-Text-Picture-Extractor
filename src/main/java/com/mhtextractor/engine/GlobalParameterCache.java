package com.mhtextractor.engine;

import com.mhtextractor.model.AppConfig;

/**
 * 两套全局「已确认参数」缓存（P1-1）：从 {@code gui.controller.ParamDomainController} 的包级字段
 * {@code textParamsCache} / {@code imageParamsCache} 原样搬入 app 层（只搬不改）。
 *
 * <p>语义与搬入前逐字等价：{@code null} 表示该模式尚未确认过参数；写入是<b>整体替换引用</b>，
 * 确认 / 应用到全局 / 撤销三分支读写的一直是同一个引用；本类<b>不参与</b>撤销压栈、三层覆盖判定
 * 或预览缓存失效 —— 那些规则仍全部留在调用方（{@code ParamDomainController}）。</p>
 *
 * <p>本类零 JavaFX、零 I18n，属 app 层（UI 可切换重构 P1 第 1 类「参数域」的下沉物）。</p>
 */
public final class GlobalParameterCache {

    /** 文本模式已确认参数缓存（null = 该模式尚未确认）。 */
    private AppConfig text;

    /** 图片模式已确认参数缓存（null = 该模式尚未确认）。 */
    private AppConfig image;

    /** 文本模式缓存（原 {@code textParamsCache} 字段读）。 */
    public AppConfig text() {
        return text;
    }

    /** 图片模式缓存（原 {@code imageParamsCache} 字段读）。 */
    public AppConfig image() {
        return image;
    }

    /** 按模式取缓存（{@code imageMode=true} → 图片，否则文本）。 */
    public AppConfig forMode(boolean imageMode) {
        return imageMode ? image : text;
    }

    /** 按模式整体替换缓存引用（原字段写；传 null 即该模式回到未确认）。 */
    public void set(boolean imageMode, AppConfig config) {
        if (imageMode) {
            image = config;
        } else {
            text = config;
        }
    }

    /** 该模式是否已确认过参数（缓存非 null）。 */
    public boolean isConfirmed(boolean imageMode) {
        return forMode(imageMode) != null;
    }
}
