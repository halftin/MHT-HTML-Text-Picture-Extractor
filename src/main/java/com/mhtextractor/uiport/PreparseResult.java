package com.mhtextractor.uiport;

import com.mhtextractor.model.ItemStatus;

/** 单项解析结果；assigned=false 必须保留原缓存值，包括已有标题与命名。 */
public record PreparseResult(String path, ItemStatus status, String errorKey, String errorDetail,
                             String text, boolean textAssigned, String title, boolean titleAssigned) {}
