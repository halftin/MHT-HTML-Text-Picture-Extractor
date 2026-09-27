package com.mhtextractor.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务层文案契约测试（会话 18 · E3 前置安全网）。
 *
 * <p><b>为什么需要它</b>：业务层（{@code processor}/{@code core}/{@code extractor}/
 * {@code util}/{@code config}）目前直接用 {@link I18n#get} 产出<b>用户可见的警告/跳过/失败文案</b>
 * （`stats.addWarnDetail(...)`、`addSkipDetail(...)`、`addFailDetail(...)`、`warnings.add(...)`）。
 * 这些文案会显示在 GUI 的提取 banner 与条目详情里。</p>
 *
 * <p>但实测发现：<b>产物黄金基线只记录 outcome 枚举，不记录这些文案</b>；
 * 30 项 GUI 断言也不校验文案内容。也就是说，<b>当前没有任何测试能发现这些文案被改坏</b> ——
 * 一旦日后做"业务层改产警告码、文案渲染收口到表现层"的重构（{@code docs/重构-耦合点分类表.md} 耦合点 #6），
 * 文案静默变化不会被任何断言拦下。</p>
 *
 * <p><b>本测试做什么</b>：把当前业务层实际用到的键<b>逐条固定下来</b>（键存在、可用所需占位符渲染、
 * 中英双语都不缺键）。这样：
 * <ol>
 *   <li>键被改名/删除 → 立刻失败（现在会静默变成 {@code !key!}）；</li>
 *   <li>为后续"警告码"重构提供<b>前置安全网</b>：重构必须保证这些键最终仍产出同样文案。</li>
 * </ol>
 *
 * <p><b>本测试刻意不做的事</b>：不把文案内容写死（那会与 bundle 重复且每次改文案都要改测试），
 * 只断言"键可解析、占位符齐备、双语无缺键"。文案内容的逐字比对由"改产码值"重构时另行锁定。</p>
 */
class I18nUsageContractTest {

    private Locale original;

    @AfterEach
    void restoreLocale() {
        if (original != null) {
            I18n.setLocale(original);
        }
    }

    /**
     * 业务层当前使用的键 → 渲染所需的占位符名。
     *
     * <p>数据来源：对 {@code src/main/java/com/mhtextractor} 下<b>非</b> {@code gui}/{@code i18n}
     * 包中所有 {@code I18n.get(...)} 调用点的静态清点（会话 18 实测 36 处）。</p>
     */
    private record Usage(String key, List<String> placeholders) {
    }

    private static final List<Usage> BUSINESS_LAYER_KEYS = List.of(
            // core：内容限定/标题提取的区域标记与提取失败
            new Usage("core.areaMarkersNotFound", List.of("start", "end")),
            new Usage("core.areaExtractFailed", List.of("reason")),
            new Usage("core.startMarkerNotFound", List.of("marker")),
            new Usage("core.endMarkerNotFound", List.of("marker")),
            // core：默认组名（产物命名，属"产物本地化"而非 UI 文案 —— 必须继续由业务层产出）
            new Usage("core.groupDefault", List.of()),
            // extractor：区域引用解析失败 / 广告过滤
            new Usage("extract.areaImageRefFailed", List.of()),
            new Usage("extract.adFiltered", List.of("name")),
            // processor：警告/跳过/失败明细
            new Usage("extract.fileWarned", List.of("file", "message")),
            new Usage("extract.unknownError", List.of()),
            new Usage("extract.titleFallback", List.of("name")),
            new Usage("extract.fileSkippedUnparseable", List.of("file")),
            new Usage("extract.fileSkippedNoImages", List.of("file")),
            new Usage("extract.fileSkippedNoExtracted", List.of("file")),
            new Usage("extract.fileSkippedEmpty", List.of("file")),
            new Usage("extract.reasonUnparseable", List.of()),
            new Usage("extract.reasonNoImages", List.of()),
            new Usage("extract.reasonNoExtracted", List.of()),
            new Usage("extract.reasonEmpty", List.of()),
            new Usage("extract.reasonWriteFailed", List.of()),
            new Usage("extract.allImagesWriteFailed", List.of("count")),
            new Usage("extract.fileFailed", List.of("file", "reason")),
            new Usage("extract.fileFailedWrite", List.of("file")),
            new Usage("extract.fileWarnedNoTitle", List.of("file", "start", "end")),
            new Usage("extract.noTitleMarkers", List.of("start", "end")),
            new Usage("extract.archiveFailedKeep", List.of("dir")),
            // util：HTML 导出（浏览器查看）的失败与未解析引用
            new Usage("browser.exportParseEmpty", List.of()),
            new Usage("browser.exportUnparseable", List.of()),
            new Usage("browser.exportFailed", List.of("reason")),
            new Usage("browser.unresolvedCid", List.of("ref"))
    );

    /**
     * 业务层产出的<b>产物本地化 / 汇总展示</b>文案（2026-09-27 新增一档，与上面那档分开计数）。
     *
     * <p><b>为什么要分开</b>：{@link #BUSINESS_LAYER_KEYS} 拦的是"业务层自己拼用户数据文案"
     * （应按耦合点 #6 改成码值 + 表现层渲染），而这一档是另一回事 —— 章节序号、默认组名、
     * 未分组单元名、处理汇总行、导入汇总：它们要么是<b>产物内容</b>（写进 txt 的"第一章"/
     * "Chapter 1"）、要么是<b>随语言变化的展示串</b>，没有"交给表现层渲染"的替代路径
     * （{@code core.groupDefault} 早就被同样理由豁免，见其上方注释）。</p>
     *
     * <p>2026-09-27 之前这批键是硬编码中文（{@code "第"}/{@code "章"}/{@code "一"}…、
     * {@code "未分组"}、{@code String.format("处理完成 - 总计…")}）—— 正是"英文界面显示中文"
     * 的残留来源。本档把它们固定下来，防止再次退化。</p>
     */
    private static final List<Usage> PRODUCT_LOCALIZATION_KEYS = List.of(
            // core：章节序号的数字表与前后缀（ChapterNamingStrategy / WesternChapterNamingStrategy）
            new Usage("core.chapter.prefix", List.of()),
            new Usage("core.chapter.suffix", List.of()),
            new Usage("core.chapter.westernPrefix", List.of()),
            new Usage("core.digit.zero", List.of()),
            new Usage("core.digit.one", List.of()),
            new Usage("core.digit.two", List.of()),
            new Usage("core.digit.three", List.of()),
            new Usage("core.digit.four", List.of()),
            new Usage("core.digit.five", List.of()),
            new Usage("core.digit.six", List.of()),
            new Usage("core.digit.seven", List.of()),
            new Usage("core.digit.eight", List.of()),
            new Usage("core.digit.nine", List.of()),
            new Usage("core.digit.ten", List.of()),
            new Usage("core.digit.hundred", List.of()),
            new Usage("core.digit.thousand", List.of()),
            // util：未分组输出单元名
            new Usage("core.ungrouped", List.of()),
            // processor：处理完成汇总行（GUI 状态栏与日志共用）
            new Usage("extract.summary", List.of("total", "success", "failed",
                    "skipped", "warned", "elapsed")),
            // processor：文本模式名（进日志）
            new Usage("extract.mergedMode", List.of()),
            new Usage("extract.separateMode", List.of()),
            // processor：逐文件归档失败但保留目录（会作为"失败原因"呈现）
            new Usage("extract.fileArchiveFailedKeep", List.of("dir")),
            // processor：高速内存模式预加载失败（会作为"失败原因"呈现）
            new Usage("error.LOAD_MHT_FAILED", List.of("reason")),
            // util：导入扫描汇总（两段拼接，逐字等于历史单串）
            new Usage("import.summaryFiles", List.of("files", "groups", "ignored", "hidden")),
            new Usage("import.summaryDirs", List.of("empty", "unreadable", "tooDeep"))
    );

    /** 造一组能填满所有占位符的假参数（值本身不重要，只要非 null 即可完成替换） */
    private static Object[] argsFor(Usage u) {
        Object[] args = new Object[u.placeholders().size() * 2];
        for (int i = 0; i < u.placeholders().size(); i++) {
            args[i * 2] = u.placeholders().get(i);
            args[i * 2 + 1] = "X" + i;
        }
        return args;
    }

    @Test
    void businessLayerKeysResolveInBothLocales() {
        for (String loc : List.of("zh", "en")) {
            original = I18n.currentLocale();
            I18n.setLocale(loc.equals("zh") ? Locale.SIMPLIFIED_CHINESE : Locale.US);

            List<Usage> all = new java.util.ArrayList<>(BUSINESS_LAYER_KEYS);
            all.addAll(PRODUCT_LOCALIZATION_KEYS);
            for (Usage u : all) {
                String rendered = I18n.get(u.key(), argsFor(u));
                assertFalse(rendered.startsWith("!") && rendered.endsWith("!"),
                        "[" + loc + "] 业务层使用的键缺失：I18n.get(\"" + u.key() + "\") -> " + rendered);
                assertTrue(!rendered.isBlank(),
                        "[" + loc + "] 键渲染为空：" + u.key());
                // 占位符必须被替换掉（缺参会保留 {name} 原样，属静默错误）
                for (String ph : u.placeholders()) {
                    assertFalse(rendered.contains("{" + ph + "}"),
                            "[" + loc + "] 键 " + u.key() + " 的占位符 {" + ph + "} 未被替换（渲染结果："
                                    + rendered + "）");
                }
            }
        }
    }

    /**
     * 业务层"产文案"的规模基线（会话 18 实测 = 36 处 {@code I18n.} 引用）。
     *
     * <p>与 {@code ArchitectureTest.businessLayerI18nUsageDoesNotGrow} 互补：那条断言源码引用数，
     * 本条断言<b>键集合</b>不增长 —— 新增键意味着又有一处业务文案没走码值，
     * 是"该重构还没做完"的显式信号。</p>
     */
    @Test
    void businessLayerKeySetDoesNotGrowSilently() {
        assertTrue(BUSINESS_LAYER_KEYS.size() <= 30,
                "业务层使用的键集合增长到 " + BUSINESS_LAYER_KEYS.size()
                        + "（>30）。新增业务文案前请先确认它是否应改为警告码 + 表现层渲染"
                        + "（见 docs/重构-耦合点分类表.md 耦合点 #6）。");
    }

    /**
     * 产物本地化 / 汇总展示键集合的规模基线（2026-09-27 实测 = 24）。
     *
     * <p>与上一档同理只减不增：这些键是"产物内容/随语言变化的展示串"，允许留在业务层，
     * 但每加一个都应当是有意的决定（例如新增一种章节命名策略），而不是顺手再拼一句中文。</p>
     */
    @Test
    void productLocalizationKeySetDoesNotGrowSilently() {
        assertTrue(PRODUCT_LOCALIZATION_KEYS.size() <= 24,
                "产物本地化键集合增长到 " + PRODUCT_LOCALIZATION_KEYS.size() + "（>24）。"
                        + "若非有意新增（如新的章节命名策略/新的汇总口径），请改走码值 + 表现层渲染。");
    }}
