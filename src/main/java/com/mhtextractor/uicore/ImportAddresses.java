package com.mhtextractor.uicore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 导入地址的纯路径处理；不扫描文件，不编排导入。 */
public final class ImportAddresses {
    private ImportAddresses() { }

    // ==================== 地址规范化 ====================

    /**
     * 一条被丢弃的地址。
     *
     * @param address  被丢弃的原始地址串
     * @param ancestor {@code null} = 与已保留地址完全重复；非 {@code null} = 被该祖先地址覆盖
     */
    public record DroppedAddress(String address, String ancestor) {
    }

    /**
     * 地址规范化结果。
     *
     * <p><b>为什么既要有序的 {@code dropped} 又要两个便利视图</b>：调用方要按<b>输入顺序</b>产出日志
     * （{@code log.maincontroller.06} 重复 / {@code .07} 被祖先覆盖），而这两类事件在原实现里是<b>同一个循环</b>
     * 里交替发出的 —— 例如输入 {@code [A, A的子目录, A]} 的事件顺序是"被祖先覆盖"在前、"重复"在后。
     * 若只回传两个独立的列表，调用方就无法复原这个相对顺序（会变成"所有重复在前"）。
     * 故 {@link #dropped()} 是<b>权威的有序事件序列</b>，两个便利视图只供断言与展示。</p>
     */
    public record NormalizeResult(List<String> kept, List<DroppedAddress> dropped) {

        public NormalizeResult {
            kept = List.copyOf(kept);
            dropped = List.copyOf(dropped);
        }

        /** 完全重复（与已保留地址同串）而被合并掉的地址，按输入顺序 */
        public List<String> duplicates() {
            return dropped.stream().filter(d -> d.ancestor() == null)
                    .map(DroppedAddress::address).toList();
        }

        /** 被祖先地址覆盖而被合并掉的地址（含其祖先），按输入顺序 */
        public List<DroppedAddress> coveredByAncestor() {
            return dropped.stream().filter(d -> d.ancestor() != null).toList();
        }
    }

    /**
     * 地址规范化（RISK-6）：重复地址合并；祖先/后代包含关系合并为祖先（丢弃后代）。
     *
     * <p>与原实现逐字等价的规则：</p>
     * <ul>
     *   <li>{@code null} / 空串 / 全空白 → 忽略（<b>不计入</b> {@code dropped}，原实现也没有日志）；</li>
     *   <li>与已保留地址同串 → 丢入 {@code dropped(ancestor = null)}；</li>
     *   <li>是某个已保留地址的后代 → 丢入 {@code dropped(ancestor = 该祖先)}；</li>
     *   <li>反过来（祖先出现在后代<b>之后</b>）→ 悄悄移除先前保留的后代 —— 原实现这里<b>没有日志</b>，
     *       本类同样不记事件（<b>不得</b>为它补一条日志，那会引入用户可见的新日志行）；</li>
     *   <li>无效路径格式（{@code Path.of} 抛 {@code InvalidPathException}）→ <b>原样保留</b>，
     *       由扫描器按"不可读"计数。</li>
     * </ul>
     */
    public static NormalizeResult normalizeAddresses(List<String> addresses) {
        List<String> kept = new ArrayList<>();
        List<DroppedAddress> dropped = new ArrayList<>();
        if (addresses == null) {
            return new NormalizeResult(kept, dropped);
        }
        for (String a : addresses) {
            if (a == null || a.isBlank()) {
                continue;
            }
            if (kept.stream().anyMatch(a::equals)) {
                dropped.add(new DroppedAddress(a, null));
                continue;
            }
            String ancestor = kept.stream().filter(k -> isDescendantOf(a, k)).findFirst().orElse(null);
            if (ancestor != null) {
                dropped.add(new DroppedAddress(a, ancestor));
                continue;
            }
            kept.removeIf(k -> isDescendantOf(k, a));
            kept.add(a);
        }
        return new NormalizeResult(kept, dropped);
    }

    /** 路径包含判定（纯函数，无副作用）：{@code child} 是 {@code ancestor} 的后代且不等于它 */
    public static boolean isDescendantOf(String child, String ancestor) {
        try {
            Path cp = Path.of(child);
            Path ap = Path.of(ancestor);
            return !cp.equals(ap) && cp.startsWith(ap);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 需要盘符根目录确认的地址（toPath().getParent() == null，对 "D:\" 与 UNC 成立） */
    public static List<String> driveRoots(List<String> addresses) {
        if (addresses == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String a : addresses) {
            if (a == null || a.isBlank()) {
                continue;
            }
            try {
                Path p = Path.of(a);
                if (p.getParent() == null) {
                    out.add(a);
                }
            } catch (RuntimeException e) {
                // 非法路径格式：不视为盘符根目录
            }
        }
        return out;
    }

}
