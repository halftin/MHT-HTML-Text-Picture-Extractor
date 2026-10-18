package com.mhtextractor.model;

import java.util.List;

/** 中性配置值比较；不解析参数层级或生效规则。 */
public final class ParamSignature {
    public static final String NO_PARAMS = "<none>";
    private ParamSignature() { }

    // ==================== 值语义：字段级内容签名 ====================

    /**
     * 配置的字段级内容签名（{@code null} → 固定标记 {@link #NO_PARAMS}）。
     *
     * <p>固定字段顺序 + 字符串长度前缀编码，不依赖 Map/Set 迭代顺序、不依赖 Lombok 生成顺序，
     * 且<b>不用 {@code equals}/{@code toString}/反射</b> —— 新增字段时签名会变（这正是我们要的：
     * 新参数一旦影响行为就必须参与"是否统一"判定），而不是静默沿用旧判定。
     * {@code AppConfigTest} 有一条"字段守护"用例会提醒同步本方法。</p>
     */
    public static String signatureOf(AppConfig c) {
        if (c == null) {
            return NO_PARAMS;
        }
        StringBuilder sb = new StringBuilder(256);
        // 通用
        sb.append(c.isSeparateFiles() ? '1' : '0');
        // 文本提取
        sb.append(c.isEnableContentLimit() ? '1' : '0');
        FingerprintEncoder.lengthPrefixed(sb, c.getStartMarker());
        FingerprintEncoder.lengthPrefixed(sb, c.getEndMarker());
        sb.append(c.isUseRegex() ? '1' : '0');
        sb.append(c.isEnableTemplate() ? '1' : '0');
        FingerprintEncoder.lengthPrefixed(sb, c.getStartTemplate());
        FingerprintEncoder.lengthPrefixed(sb, c.getEndTemplate());
        sb.append(c.isAddChapterNumber() ? '1' : '0');
        sb.append(c.isAutoFixEncoding() ? '1' : '0');
        sb.append(c.isEnableTitleExtraction() ? '1' : '0');
        FingerprintEncoder.lengthPrefixed(sb, c.getTitleStartMarker());
        FingerprintEncoder.lengthPrefixed(sb, c.getTitleEndMarker());
        sb.append(c.isTitleUseRegex() ? '1' : '0');
        // 图片提取
        sb.append(c.isEnableNameExtraction() ? '1' : '0');
        FingerprintEncoder.lengthPrefixed(sb, c.getImageNameStartMarker());
        FingerprintEncoder.lengthPrefixed(sb, c.getImageNameEndMarker());
        sb.append(c.isEnableRegionExtraction() ? '1' : '0');
        FingerprintEncoder.lengthPrefixed(sb, c.getImageRegionStartMarker());
        FingerprintEncoder.lengthPrefixed(sb, c.getImageRegionEndMarker());
        sb.append(c.isEnableBatchRenaming() ? '1' : '0');
        sb.append(c.isEnableZipPackaging() ? '1' : '0');
        sb.append(c.isEnableCbzPackaging() ? '1' : '0');
        sb.append(c.isSingleOutputMode() ? '1' : '0');
        // 处理模式
        sb.append(c.isHighSpeedMemoryMode() ? '1' : '0');
        // 兼容旧版废弃字段（保留反序列化兼容；仍参与判定，避免"设置里看得见却不影响判定"）
        FingerprintEncoder.lengthPrefixed(sb, c.getNameLimitStart());
        FingerprintEncoder.lengthPrefixed(sb, c.getNameLimitEnd());
        FingerprintEncoder.lengthPrefixed(sb, c.getContentLimitStart());
        FingerprintEncoder.lengthPrefixed(sb, c.getContentLimitEnd());
        return sb.toString();
    }

    /**
     * 仅供签名一致性守护测试使用：本签名刻意排除的 AppConfig 字段
     * （纯运行时/GUI 状态，不属于"用户设置的提取参数"）。
     *
     * <p>返回<b>不可变副本</b>，避免测试或调用方改动内部集合。</p>
     */
    public static List<String> runtimeOnlyFields() {
        return List.copyOf(RUNTIME_ONLY_FIELDS);
    }

    private static final List<String> RUNTIME_ONLY_FIELDS =
            List.of("inputFolder", "outputFolder", "imageModeEnabled", "openFolderAfterComplete");
}
