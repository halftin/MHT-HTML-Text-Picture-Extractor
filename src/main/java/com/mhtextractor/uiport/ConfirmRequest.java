package com.mhtextractor.uiport;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 确认请求（不可变；集合字段防御性不可变拷贝）。
 *
 * @param key         文案键
 * @param namedParams 具名参数（{@link Map#copyOf} 防御性拷贝）
 * @param actionKeys  动作键清单（渲染为按钮；顺序即展示顺序，{@link List#copyOf} 防御性拷贝）
 * @param timeoutMs   超时兜底毫秒数（≤ 0 = 不超时）
 * @param message     预渲染完整文案（B5-1：复合文案由后端拼装的场景，如配额卡三段拼接）；
 *                    非 {@code null} 时前端<b>直接展示</b>（不按键渲染），{@code null} 时按
 *                    key + namedParams 渲染
 */
public record ConfirmRequest(String key, Map<String, String> namedParams,
                             List<String> actionKeys, long timeoutMs, String message) {

    /** 便捷构造（{@code message = null}）：前端按 key + namedParams 渲染文案。 */
    public ConfirmRequest(String key, Map<String, String> namedParams,
                          List<String> actionKeys, long timeoutMs) {
        this(key, namedParams, actionKeys, timeoutMs, null);
    }

    public ConfirmRequest {
        Objects.requireNonNull(key, "key");
        namedParams = Map.copyOf(Objects.requireNonNull(namedParams, "namedParams"));
        actionKeys = List.copyOf(Objects.requireNonNull(actionKeys, "actionKeys"));
    }
}
