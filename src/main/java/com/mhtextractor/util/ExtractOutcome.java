package com.mhtextractor.util;

/**
 * 单文件提取结果（《提取结果状态需求文档.md》）：NONE 无结果；SUCCESS 成功；
 * FAILED 失败；SKIPPED 跳过；SUCCESS_WARNED 成功但有警告（列表背景左 1/3 红右 2/3 蓝）。
 */
public enum ExtractOutcome {
    NONE, SUCCESS, FAILED, SKIPPED, SUCCESS_WARNED
}
