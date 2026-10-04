package com.mhtextractor.model;

import java.util.Map;

/**
 * 结构化提取警告（决策 #6 / E4.1）：业务层只产<b>码值 + 具名参数</b>，不再产已解析文案。
 * 渲染统一由 i18n 包的 {@code render} 入口执行（展示层或日志实参处按当前 locale 渲染）。
 *
 * <p>落进预览缓存的警告从此<b>语言中立</b>：meta.json 里存的是键与参数，
 * 读取时的界面语言决定文案，不再受"写入时 locale"固化（遗留目标「业务层仍在产已解析文案」的修复）。</p>
 *
 * <p><b>放 model 的理由</b>：它是跨层共享的纯值类型（extractor/core/util/processor/cache 生产、
 * app 转发、gui 渲染、i18n 渲染入口引用），与 {@link PreviewItem} / {@link ThumbnailData}
 * 同为"零 JavaFX、无行为"的共享数据；util 包已有依赖 model 的先例（{@code util.GroupStructure}
 * 引 {@link PreviewItem}），不引入新的依赖方向。</p>
 *
 * @param key  i18n 键（与 {@code get} 同一键空间，占位符为具名 {@code {name}} 形式）
 * @param args 具名参数（占位符名 → 值，可为 null 视为空）
 */
public record ExtractWarning(String key, Map<String, String> args) {
    public ExtractWarning {
        args = args == null ? Map.of() : Map.copyOf(args);
    }
}
