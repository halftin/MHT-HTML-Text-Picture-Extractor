package com.mhtextractor.extractor;

import com.mhtextractor.model.ExtractWarning;

import java.util.List;

/**
 * 警告信息提供者接口，用于统一的警告收集和报告
 */
public interface WarningProvider {
    List<ExtractWarning> getLastWarnings();
}
