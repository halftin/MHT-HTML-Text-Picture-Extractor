package com.mhtextractor.app;

import com.mhtextractor.model.ExtractWarning;
import com.mhtextractor.model.ExtractionStats;
import com.mhtextractor.model.PreviewItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 提取结果回写与详情拼接（P3-3b-13）：{@code gui.WorkspaceInfoPresenter} 的
 * {@code applyExtractResults} / {@code buildExtractDetail} <b>原样下沉</b> ——
 * 按 {@code path} 建 map、遍历条目命中即写 {@code extractOutcome} 与 {@code extractDetail}，
 * 匹配口径、赋值顺序、null/空回落、分隔符语义逐字不变；警告渲染与分隔符经入参注入
 * （本类<b>零 JavaFX、零 I18n</b>，属 {@code app} 层）。
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
     * @param renderWarning 警告渲染（调用方注入 {@code I18n::render}，按当前 locale）
     * @param separator     详情分隔符（调用方注入 {@code I18n.get("list.itemSeparator")}）
     * @return 命中路径数（{@code byPath.size()}，原日志 {@code log.maincontroller.29} 的实参口径）
     */
    public static int apply(ExtractionStats stats, List<PreviewItem> items,
                            Function<ExtractWarning, String> renderWarning, String separator) {
        Map<String, ExtractionStats.FileOutcome> byPath = new HashMap<>();
        for (ExtractionStats.FileOutcome r : stats.files()) {
            byPath.put(r.path(), r);
        }
        for (PreviewItem it : items) {
            ExtractionStats.FileOutcome r = byPath.get(it.path);
            if (r != null) {
                it.extractOutcome = r.outcome();
                it.extractDetail = buildExtractDetail(r, renderWarning, separator);
            }
        }
        return byPath.size();
    }

    /** 结果详情拼接：失败/跳过原因 + 警告（逐条按当前 locale 渲染），分隔符分隔；无内容返回 null */
    private static String buildExtractDetail(ExtractionStats.FileOutcome r,
                                             Function<ExtractWarning, String> renderWarning,
                                             String separator) {
        List<String> parts = new ArrayList<>();
        if (r.message() != null && !r.message().isEmpty()) {
            parts.add(r.message());
        }
        for (ExtractWarning warning : r.warnings()) {
            parts.add(renderWarning.apply(warning));
        }
        return parts.isEmpty() ? null : String.join(separator, parts);
    }
}
