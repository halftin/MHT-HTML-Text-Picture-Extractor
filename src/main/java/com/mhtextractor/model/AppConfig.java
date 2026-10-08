package com.mhtextractor.model;

import com.mhtextractor.model.FingerprintEncoder;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 精简后的应用配置类
 * 使用 Lombok @Data 替代手写 Getter/Setter/equals/hashCode
 * 使用 @JsonProperty 保证JSON序列化兼容性
 *
 * <p>命名配置（config/）只保存提取参数；输入/输出目录、提取模式与"完成后打开输出文件夹"
 * 属于主界面 GUI 状态，由 logs/gui-state.json 持久化（见 {@link GuiState}），
 * 故以下字段标注 @JsonIgnore 不参与命名配置序列化（运行时仍正常使用）。</p>
 */
@Data
public class AppConfig {

    // ==================== 通用字段（GUI 状态，不随命名配置保存） ====================
    @JsonIgnore
    private String inputFolder;
    @JsonIgnore
    private String outputFolder;
    private boolean separateFiles;

    // ==================== 文本提取字段 ====================
    @JsonProperty("enableLimit")
    private boolean enableContentLimit;
    private String startMarker;
    private String endMarker;
    private boolean useRegex;

    @JsonProperty("enableCustomTemplate")
    private boolean enableTemplate;
    private String startTemplate;
    private String endTemplate;

    private boolean addChapterNumber;
    private boolean autoFixEncoding = true;

    @JsonProperty("enableTitleExtract")
    private boolean enableTitleExtraction;
    private String titleStartMarker;
    private String titleEndMarker;
    private boolean titleUseRegex;

    // ==================== 图片提取字段 ====================
    /** 提取模式属于主窗口 GUI 状态，不随命名配置保存（运行时由主窗口模式开关写入） */
    @JsonIgnore
    private boolean imageModeEnabled;

    @JsonProperty("imageNameExtractEnabled")
    private boolean enableNameExtraction;
    private String imageNameStartMarker;
    private String imageNameEndMarker;

    @JsonProperty("imageRegionExtractEnabled")
    private boolean enableRegionExtraction;
    private String imageRegionStartMarker;
    private String imageRegionEndMarker;

    @JsonProperty("imageRenameEnabled")
    private boolean enableBatchRenaming;
    @JsonProperty("imageZipEnabled")
    private boolean enableZipPackaging;
    @JsonProperty("imageCbzEnabled")
    private boolean enableCbzPackaging;

    // ==================== 图片输出模式（兼容旧版字段名） ====================
    @JsonProperty("singleOutputMode")
    private boolean singleOutputMode;

    // ==================== 处理模式 ====================
    @JsonProperty("legacyMemoryMode")
    private boolean highSpeedMemoryMode;

    // ==================== 运行时状态（GUI 状态，不随命名配置保存） ====================
    @JsonIgnore
    private boolean openFolderAfterComplete;

    // ==================== 兼容旧版废弃字段（保留反序列化兼容） ====================
    @Deprecated
    private String nameLimitStart;
    @Deprecated
    private String nameLimitEnd;
    @Deprecated
    private String contentLimitStart;
    @Deprecated
    private String contentLimitEnd;

    // ==================== 图片提取参数指纹（需求 2 P2/P10，会话 7） ====================

    /** 图片提取规则版本：改 AD_KEYWORDS / 去重口径等"提取出哪些图片"的规则时必须 +1（会让旧预览缓存一次性失效）。 */
    private static final int IMAGE_EXTRACTION_RULES_VERSION = 2;

    /**
     * 图片提取参数指纹：只含影响「提取出哪些图片、以什么名字、什么扩展名」的字段与规则版本，
     * 按固定顺序拼接后取 SHA-256（64 位十六进制）。缓存复用判定只比对本字符串（P10），
     * 不得持 AppConfig 引用或用 equals 比较（AppConfig 是 @Data 可变对象）。
     *
     * <p>参与字段（8 个）：</p>
     * <ol>
     *   <li>{@code enableNameExtraction} + {@code imageNameStartMarker} / {@code imageNameEndMarker}
     *       —— 名称区域限定，决定合并输出目录名与文件名（ImageBatchProcessor:216/310）；</li>
     *   <li>{@code enableRegionExtraction} + {@code imageRegionStartMarker} / {@code imageRegionEndMarker}
     *       —— 内容区域限定，决定提取哪些图片（ImageExtractor 区域过滤）；</li>
     *   <li>{@code enableBatchRenaming} —— 批量重命名，决定输出文件名（ImageWriter 序号命名）；</li>
     *   <li>{@code singleOutputMode} —— 合并/单独输出，决定输出目录结构。</li>
     * </ol>
     *
     * <p>不参与字段及理由：</p>
     * <ul>
     *   <li>{@code inputFolder} / {@code outputFolder} / {@code openFolderAfterComplete} / {@code imageModeEnabled}
     *       —— 运行时/GUI 状态字段，不影响图片字节（P2 明确排除）；</li>
     *   <li>{@code enableZipPackaging} / {@code enableCbzPackaging} —— 归档只改变最终容器形态，
     *       缓存内图片字节与命名不受影响（P2 明确排除）；</li>
     *   <li>{@code highSpeedMemoryMode} —— 仅切换内存策略（预加载/流式），解析与提取逻辑相同，产物一致；</li>
     *   <li>文本模式字段（{@code startMarker} 等）与废弃兼容字段（{@code nameLimitStart} 等，主代码零使用）
     *       —— 与图片提取无关。</li>
     * </ul>
     *
     * <p>广告过滤关键词为 {@code ImageExtractor.AD_KEYWORDS} 硬编码常量（本步不参数化），
     * 但其<b>规则版本</b> {@code IMAGE_EXTRACTION_RULES_VERSION} 参与指纹 —— 改关键词清单 /
     * 去重口径等「提取出哪些图片」的规则时必须 +1，让旧预览缓存一次性失效。</p>
     *
     * <p>顺序稳定：固定字段顺序 + 长度前缀编码（String 先写长度再写内容），
     * 不依赖 Map/Set 迭代顺序；null 与空串可区分。规则版本同样以长度前缀编码追加在末尾。</p>
     */
    public String imageExtractionFingerprint() {
        return imageExtractionFingerprint(IMAGE_EXTRACTION_RULES_VERSION);
    }

    /**
     * 指纹计算体（包级重载，E4.2）：规则版本作为显式参数。
     * 供同包测试直接断言「版本变化 ⇒ 指纹变化」—— 若有人把版本从摘要输入里删掉，
     * {@code AppConfigTest.fingerprintChangesWhenRulesVersionChanges} 会立刻变红
     * （不做 golden hex 值钉死，避免版本正常递增时测试脆弱）。
     */
    String imageExtractionFingerprint(int rulesVersion) {
        StringBuilder sb = new StringBuilder();
        sb.append(enableNameExtraction ? '1' : '0');
        FingerprintEncoder.lengthPrefixed(sb, imageNameStartMarker);
        FingerprintEncoder.lengthPrefixed(sb, imageNameEndMarker);
        sb.append(enableRegionExtraction ? '1' : '0');
        FingerprintEncoder.lengthPrefixed(sb, imageRegionStartMarker);
        FingerprintEncoder.lengthPrefixed(sb, imageRegionEndMarker);
        sb.append(enableBatchRenaming ? '1' : '0');
        sb.append(singleOutputMode ? '1' : '0');
        FingerprintEncoder.lengthPrefixed(sb, Integer.toString(rulesVersion));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ==================== 提取参数深拷贝（R7.7） ====================

    /**
     * <b>可持久化提取参数</b>的深拷贝（R7.7）：返回<b>新实例</b>，字段值逐项复制。
     *
     * <p><b>为什么必须深拷贝</b>：本类是 Lombok {@code @Data} 可变对象，其 javadoc 明禁把
     * {@code equals} 当判定键 —— 共享同一个实例会让"后续就地修改"串味（{@code buildRuntimeConfig}
     * 与提取前补运行时字段都是就地 set）。</p>
     *
     * <p><b>不参与的字段</b>：4 个 {@code @JsonIgnore} 运行时/GUI 字段
     * （{@code inputFolder} / {@code outputFolder} / {@code imageModeEnabled} /
     * {@code openFolderAfterComplete}）—— 它们属主窗口 GUI 状态（见 {@link GuiState}），
     * 复制它们会在"应用到所选"时把用户当前目录/模式写进别的条目（真 bug 级错误）。
     * 副本里这 4 个字段保持 {@code null/false} 的初值。</p>
     *
     * <p><b>覆盖范围 = 提取参数全集</b>：与
     * {@link com.mhtextractor.app.EffectiveParamsService#signatureOf} 的字段集合<b>完全相同</b>
     * （{@code AppConfig} 全部字段减去该服务的 {@code runtimeOnlyFields()}）；两侧都有守护测试
     * （{@code AppConfigCopyTest}），漏一个字段会立刻红。</p>
     *
     * <p>字段逐个赋值而<b>不用反射/序列化</b>：本类要进 GraalVM native 镜像，显式赋值零元数据负担，
     * 且编译期可查（少写一个字段会被上面那条覆盖范围断言拦下）。</p>
     */
    public AppConfig copyExtractionParams() {
        AppConfig c = new AppConfig();
        // 通用
        c.separateFiles = separateFiles;
        // 文本提取
        c.enableContentLimit = enableContentLimit;
        c.startMarker = startMarker;
        c.endMarker = endMarker;
        c.useRegex = useRegex;
        c.enableTemplate = enableTemplate;
        c.startTemplate = startTemplate;
        c.endTemplate = endTemplate;
        c.addChapterNumber = addChapterNumber;
        c.autoFixEncoding = autoFixEncoding;
        c.enableTitleExtraction = enableTitleExtraction;
        c.titleStartMarker = titleStartMarker;
        c.titleEndMarker = titleEndMarker;
        c.titleUseRegex = titleUseRegex;
        // 图片提取
        c.enableNameExtraction = enableNameExtraction;
        c.imageNameStartMarker = imageNameStartMarker;
        c.imageNameEndMarker = imageNameEndMarker;
        c.enableRegionExtraction = enableRegionExtraction;
        c.imageRegionStartMarker = imageRegionStartMarker;
        c.imageRegionEndMarker = imageRegionEndMarker;
        c.enableBatchRenaming = enableBatchRenaming;
        c.enableZipPackaging = enableZipPackaging;
        c.enableCbzPackaging = enableCbzPackaging;
        c.singleOutputMode = singleOutputMode;
        // 处理模式
        c.highSpeedMemoryMode = highSpeedMemoryMode;
        // 兼容旧版废弃字段（保留反序列化兼容，仍参与"参数是否统一"判定）
        c.nameLimitStart = nameLimitStart;
        c.nameLimitEnd = nameLimitEnd;
        c.contentLimitStart = contentLimitStart;
        c.contentLimitEnd = contentLimitEnd;
        return c;
    }
}
