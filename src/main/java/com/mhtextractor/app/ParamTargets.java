package com.mhtextractor.app;

import com.mhtextractor.model.PreviewItem;

import java.util.ArrayList;
import java.util.List;

/**
 * 参数目标的「勾选优先」解析（B4，处置项 4）：原 {@code gui.controller.ParamDomainController.resolveTargets}
 * 纯函数整体迁入 app 层（只搬不改）。
 *
 * <p>口径（与「移除」的勾选优先拍板一致）：勾选集合（可见条目）优先 → 回退单目标
 * （右键命中 / 当前选中，可为 {@code null}）。复制 / 应用到所选（前端）与清除参数
 * （后端端口命令 {@code WorkspaceParamCommandService.clearParams}）共用同一份实现。</p>
 *
 * <p>零 JavaFX、零 I18n；纯函数、不持有任何状态。</p>
 */
public final class ParamTargets {

    private ParamTargets() {
    }

    /**
     * "实际作用集合"解析的纯函数形态（勾选集合优先 → 回退目标）。
     *
     * @param visible  当前可见（未移除）条目
     * @param fallback 勾选为空时的回退目标（右键命中的条目/组，可为 null）
     */
    public static List<Object> resolve(List<PreviewItem> visible, Object fallback) {
        List<PreviewItem> checked = visible == null ? List.of()
                : visible.stream().filter(i -> i.selected).toList();
        if (!checked.isEmpty()) {
            return new ArrayList<>(checked);
        }
        return fallback == null ? new ArrayList<>() : new ArrayList<>(List.of(fallback));
    }
}
