package com.mhtextractor.baseline;

import com.mhtextractor.i18n.I18n;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer A：产物黄金基线（会话 18 · E0.3）。**重构每一步都必须全绿。**
 *
 * <p><b>它证明什么、不证明什么</b>：基线只证明"没变"，不证明"对"。因此必须建立在
 * <b>已验收的正确版本</b>上（本项目已通过 C1–C11 + I1–I10 验收）。已知缺陷会被固化 ——
 * 每例的 {@code knownIssues} 字段显式记录（会话 17 测试方法设计 §3.6）。</p>
 *
 * <p><b>为什么走无头路径而非 GUI</b>：走 {@code TextBatchProcessor} / {@code ImageBatchProcessor}
 * 入口，与 GUI 提取链<b>同源</b>（GUI 的 {@code processUnit} 调用同一入口），但不依赖 JavaFX 运行时。
 * 现有 30 项 GUI 断言只验<b>控件状态</b>，本测试补的是<b>落盘产物内容</b>。</p>
 *
 * <p><b>三层保障</b>：</p>
 * <ol>
 *   <li><b>确定性自检</b>：每例跑两遍（独立工作目录），两遍摘要必须逐字节一致 ——
 *       否则基线本身是噪声，后续每一步都会假失败（风险 R4）。</li>
 *   <li><b>基线比对</b>：与 {@code src/test/resources/baselines/<case>.<loc>.json} 比对。</li>
 *   <li><b>双 locale</b>：zh_CN 与 en_US 各一套（章名/默认输出名随 locale 变化，需求 §3.5 I5）。</li>
 * </ol>
 *
 * <p><b>生成/更新基线</b>：{@code mvn test -Dtest=ProductBaselineTest -Dmht.baseline.capture=true}。
 * <b>更新基线等于声明行为变更</b>，必须显式记录（硬规则 R4）。</p>
 */
class ProductBaselineTest {

    private static final Path BASELINE_DIR = Path.of("src", "test", "resources", "baselines");

    @TempDir
    Path tmp;

    @Test
    void allProductBaselinesMatch() throws Exception {
        boolean capture = BaselineCases.captureRequested();
        List<BaselineCases.Case> cases = BaselineCases.all();
        String onlyCase = System.getProperty("mht.baseline.case");
        if (onlyCase != null) cases = cases.stream().filter(c -> c.name().equals(onlyCase)).toList();
        assertTrue(!cases.isEmpty(), "filter selected no baselines");

        Locale originalLocale = I18n.currentLocale();
        List<String> failures = new ArrayList<>();
        int passed = 0;

        try {
            for (BaselineCases.Case c : cases) {
                for (String loc : List.of("zh", "en")) {
                    I18n.setLocale(loc.equals("zh") ? Locale.SIMPLIFIED_CHINESE : Locale.US);

                    // 确定性自检：同例同 locale 跑两遍（独立目录），摘要必须完全一致
                    BaselineCases.RunResult a = BaselineCases.run(c, tmp.resolve(c.name() + "-" + loc + "-a"));
                    BaselineCases.RunResult b = BaselineCases.run(c, tmp.resolve(c.name() + "-" + loc + "-b"));
                    if (!a.allEquals(b)) {
                        failures.add("[" + c.name() + "/" + loc + "] 确定性自检失败：同输入两遍摘要不一致"
                                + "\n  A.products=" + a.products()
                                + "\n  B.products=" + b.products()
                                + "\n  A.stats=" + a.stats()
                                + "\n  B.stats=" + b.stats());
                        continue;
                    }

                    Path baselineFile = BASELINE_DIR.resolve(c.name() + "." + loc + ".json");
                    String actualJson = BaselineCases.toJson(c, loc, a);

                    if (capture) {
                        Files.createDirectories(BASELINE_DIR);
                        Files.writeString(baselineFile, actualJson, StandardCharsets.UTF_8);
                        passed++;
                    } else {
                        assertTrue(Files.exists(baselineFile), "基线文件缺失：" + baselineFile
                                + "（首次建立请用 -Dmht.baseline.capture=true 重跑）");
                        String expectedJson = Files.readString(baselineFile, StandardCharsets.UTF_8);
                        if (!BaselineCases.normalizeJson(expectedJson)
                                .equals(BaselineCases.normalizeJson(actualJson))) {
                            failures.add("[" + c.name() + "/" + loc + "] 产物基线不一致"
                                    + "\n--- 期望 ---\n" + expectedJson + "\n--- 实际 ---\n" + actualJson);
                        } else {
                            passed++;
                        }
                    }
                }
            }
        } finally {
            I18n.setLocale(originalLocale);
        }

        if (capture) {
            System.out.println("[baseline] 重新捕获 " + passed + " 份基线（等于声明行为变更，须显式记录）");
            return;
        }

        System.out.println("[baseline] 通过 " + passed + " 份（zh+en × " + cases.size() + " 例 × 2 遍确定性自检）");
        assertEquals(0, failures.size(),
                "产物基线不一致 " + failures.size() + " 处（重构引入行为变更，须查清）：\n\n"
                        + String.join("\n\n", failures));
    }
}
