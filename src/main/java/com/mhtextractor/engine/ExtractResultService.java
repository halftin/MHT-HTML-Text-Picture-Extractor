package com.mhtextractor.engine;

import com.mhtextractor.model.ExtractionStats;
import com.mhtextractor.model.PreviewItem;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按路径回写中立的提取结果：原因键、参数、原始诊断与结构化警告。
 * 不渲染文案；同路径最后一条结果覆盖前值，未命中的条目保持不变。
 */
public final class ExtractResultService {

    private ExtractResultService() {
    }

    /**
     * 提取结果一次性回写（拍板：提取结束/取消后统一回写，非逐文件实时更新）：
     * 按路径匹配条目写入结果字段，列表一次刷新，选中条目 banner 同步（刷新与日志由调用方负责）。
     *
     * @param stats         提取统计快照（含单文件结果列表）
     * @param items         待回写的列表条目（全量预览项投影）
     * @return 命中路径数（{@code byPath.size()}，原日志 {@code log.maincontroller.29} 的实参口径）
     */
    public static int apply(ExtractionStats stats, List<PreviewItem> items) {
        Map<String, ExtractionStats.FileOutcome> byPath = new HashMap<>();
        for (ExtractionStats.FileOutcome r : stats.files()) {
            byPath.put(r.path(), r);
        }
        for (PreviewItem it : items) {
            ExtractionStats.FileOutcome r = byPath.get(it.path);
            if (r != null) {
                it.extractOutcome = r.outcome();
                it.extractMessageKey = r.messageKey();
                it.extractMessageParams = r.messageParams();
                it.extractMessageDetail = r.messageDetail();
                it.extractWarnings = r.warnings();
            }
        }
        return byPath.size();
    }

}
