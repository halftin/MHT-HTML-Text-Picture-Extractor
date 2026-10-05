package com.mhtextractor.uiport;

/**
 * 勾选三态：无选中 / 部分选中 / 全选。
 *
 * <p>口径：候选数或已选数为 0 → {@link #NONE}；已选数达到候选数 → {@link #ALL}；
 * 其余 → {@link #SOME}。</p>
 */
public enum TriState {
    NONE,
    SOME,
    ALL
}
