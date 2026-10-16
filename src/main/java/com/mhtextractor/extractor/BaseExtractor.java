package com.mhtextractor.extractor;

import com.mhtextractor.core.MimeParser;

import java.util.ArrayList;
import java.util.List;

/**
 * 提取器抽象基类
 * 提供解析结果引用和警告信息的统一管理
 */
public abstract class BaseExtractor implements WarningProvider {

    protected final MimeParser.ParsedMht parsedMht;
    protected final List<String> lastWarnings = new ArrayList<>();

    public BaseExtractor(MimeParser.ParsedMht parsedMht) {
        this.parsedMht = parsedMht;
    }

    @Override
    public List<String> getLastWarnings() {
        return lastWarnings;
    }
}
