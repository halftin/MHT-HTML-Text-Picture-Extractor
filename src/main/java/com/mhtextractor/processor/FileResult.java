package com.mhtextractor.processor;

import com.mhtextractor.model.ExtractWarning;
import com.mhtextractor.model.ExtractOutcome;

import java.util.List;
import java.util.Map;

/**
 * 单文件提取结果（《提取结果状态需求文档.md》）：绝对路径 + 结果类型 + 详情 + 警告列表。
 * 由批量处理器逐文件记录，提取结束后按路径回写列表条目（背景色 + 预览区 banner）。
 */
public record FileResult(String path, ExtractOutcome outcome, String messageKey,
                         Map<String, String> messageParams, String messageDetail,
                         List<ExtractWarning> warnings) {
    public FileResult {
        messageParams = messageParams == null ? null : Map.copyOf(messageParams);
        warnings = List.copyOf(warnings);
    }
}
