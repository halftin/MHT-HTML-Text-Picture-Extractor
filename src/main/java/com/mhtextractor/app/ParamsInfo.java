package com.mhtextractor.app;

/**
 * 复制/应用两项的输入快照（R7.2/R7.3/R7.6）—— 全部由宿主判定后传入，本类只做布尔组合：
 *
 * @param independent 目标生效参数来源是否为独立参数（既有 13 个启用位用；决定清除参数/应用到全局）
 * @param hasEffective 目标集合里是否有生效参数（R7.2 理由一的<b>反面</b>）
 * @param uniform     目标集合的生效参数是否统一（R7.2 理由二的<b>反面</b>）
 * @param copied      是否已复制过参数（R7.3）
 * @param topLevelCount     功能二：实际作用集合里的<b>组外项数</b>（顶层项目条目）
 * @param distinctGroupCount 功能二：实际作用集合里涉及的<b>去重组数</b>
 */
public record ParamsInfo(boolean independent, boolean hasEffective, boolean uniform, boolean copied,
                         int topLevelCount, int distinctGroupCount) {
}
