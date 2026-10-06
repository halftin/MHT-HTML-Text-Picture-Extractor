package com.mhtextractor.uiport;

import com.mhtextractor.model.PreviewItem;

/** 单项解析结果；assigned=false 必须保留原缓存值，包括已有标题与命名。 */
public record PreparseResult(String path, PreviewItem.Status status, String errorKey, String errorDetail,
                             String text, boolean textAssigned, String title, boolean titleAssigned) {}
