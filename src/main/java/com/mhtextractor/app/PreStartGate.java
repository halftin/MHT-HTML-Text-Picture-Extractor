package com.mhtextractor.app;

import java.util.function.Consumer;

/**
 * 启动前顺序门（决策 C 的可插拔顺序点；P1-5 从 {@code ExtractionTaskCoordinator} 原样下沉到 app 层）。
 *
 * <p>提取启动编排在 deny 链 + R1.6 参数未确认卡之后、真正启动之前，按注册顺序逐个调用
 * {@link #ask}。门在决定已做出时调用 {@code proceed}（<b>恰好一次</b>；异步确认卡在
 * UI 线程回调里交付）：{@code proceed.accept(true)} 放行到下一道门（链空则启动），
 * {@code proceed.accept(false)} 中止本次启动 —— <b>后续门不再被询问</b>（串行：前一张卡
 * 取消即 return，不回退到后一张）。生产链的顺序即优先级（决策 C）：
 * <b>草稿门（步骤 4）→ 未命名组门（步骤 3）→ 合并名门</b>，草稿卡先于未命名组卡。
 *
 * <p><b>零 JavaFX、零 I18n</b>：门只产数据与判定，展示经 {@link PreStartGateContext} 的回调留在 GUI 层。</p>
 */
@FunctionalInterface
public interface PreStartGate {
    /** 按序询问本道门；决定做出后以 {@code proceed} 交付（true = 放行，false = 中止）。 */
    void ask(Consumer<Boolean> proceed);
}
