package com.mhtextractor.app;

import com.mhtextractor.model.GroupItem;
import com.mhtextractor.model.PreviewItem;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B4（处置项 4）：「勾选优先」目标解析纯函数（{@link ParamTargets}，原
 * {@code ParamDomainController.resolveTargets} 逐字迁入）的无头单测。
 * 口径：勾选集合（可见条目）优先 → 回退单目标（右键命中 / 当前选中，可为 null）。
 */
class ParamTargetsTest {

    private static PreviewItem item(String name) {
        return new PreviewItem("C:/x/" + name, name, LocalDateTime.now(), "C:/x");
    }

    @Test
    void checkedItemsWinOverFallback() {
        PreviewItem a = item("a.mht"), b = item("b.mht");
        a.setSelected(true);
        List<Object> resolved = ParamTargets.resolve(List.of(a, b), b);
        assertEquals(List.of(a), resolved, "勾选优先：勾选集合非空时回退目标被忽略");
    }

    @Test
    void fallbackUsedWhenNothingChecked() {
        PreviewItem a = item("a.mht");
        GroupItem<PreviewItem> g = new GroupItem<>(1, "组1");
        assertEquals(List.of(g), ParamTargets.resolve(List.of(a), g),
                "无勾选 → 回退单目标（组也按原样回退）");
        assertEquals(List.of(a), ParamTargets.resolve(List.of(a), a), "无勾选 → 回退单目标（条目）");
    }

    @Test
    void nullFallbackAndNullVisibleYieldEmpty() {
        PreviewItem a = item("a.mht");
        assertTrue(ParamTargets.resolve(null, null).isEmpty(), "无勾选 + 空回退 → 空集合");
        assertEquals(List.of(a), ParamTargets.resolve(null, a),
                "可见集合为 null 时勾选恒为空 → 回退目标仍生效（与搬移前逐字一致）");
        assertTrue(ParamTargets.resolve(List.of(), null).isEmpty());
    }

    @Test
    void removedItemsStillCountWhenChecked() {
        PreviewItem a = item("a.mht"), b = item("b.mht");
        a.setSelected(true);
        b.removed = true;   // 可见集合是调用方已过滤的（removed 不参与勾选），此处只证解析不另做过滤
        assertEquals(List.of(a), ParamTargets.resolve(List.of(a, b), b));
    }
}
