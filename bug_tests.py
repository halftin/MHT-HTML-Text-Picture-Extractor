"""
MHT Extractor Bug 复现测试（独立脚本：不参与构建、不被测试框架加载）

在打包机上的用法：
    1) 先确保已编译（产出 target\\classes 与 target\\lib）：
         build-native.cmd --prepare-only      （或 mvn package -DskipTests）
    2) 填好下面「环境参数」一节（直接改脚本，或用同名环境变量覆盖）
    3) python bug_tests.py

环境参数（都可留空，由同名环境变量提供）：
    MHT_TEST_DIR     待测 .mht 文件所在目录
    NIK25_HOME       BellSoft Liberica NIK 25 安装目录（其下有 bin\\javac.exe 与 bin\\java.exe）
    MHT_OUTPUT_DIR   产物输出目录，默认 <仓库>\\dist
    MSVC_ENV         vcvars64.bat 完整路径（本脚本自身不调用 MSVC，仅留给 native-image 相关步骤参考）

下面由仓库布局推导的路径**不需要改**：仓库结构不变时零配置可用。
"""
import subprocess
import os
import sys

REPO_ROOT = os.path.dirname(os.path.abspath(__file__))


def _cfg(env_name: str, default: str) -> str:
    """环境变量优先，为空则用默认值（默认值是占位符时会在主流程被拦下并给出提示）"""
    value = os.environ.get(env_name)
    return value if value else default


# ══════════════════════════════════════════════════════════════════
# 环境参数 —— 打包机上只需改这一段（或设同名环境变量）
# ══════════════════════════════════════════════════════════════════
TEST_MHT_DIR = _cfg("MHT_TEST_DIR", r"<打包机上填写：待测 MHT 文件所在目录>")
NIK25_HOME = _cfg("NIK25_HOME", r"<打包机上填写：Liberica NIK 25 安装目录>")
OUTPUT_DIR = _cfg("MHT_OUTPUT_DIR", os.path.join(REPO_ROOT, "dist"))
MSVC_ENV = _cfg("MSVC_ENV", r"<打包机上填写：vcvars64.bat 完整路径>")

# ── 以下由仓库布局推导，正常不需要改 ──
JAVA_SRC = os.path.join(REPO_ROOT, "src", "main", "java")
TARGET_CLASSES = os.path.join(REPO_ROOT, "target", "classes")
TARGET_LIB = os.path.join(REPO_ROOT, "target", "lib")
TEST_CLASSES_DIR = os.path.join(REPO_ROOT, "target", "test-classes")
JAVAC = os.path.join(NIK25_HOME, "bin", "javac.exe")
JAVA = os.path.join(NIK25_HOME, "bin", "java.exe")


def run_java_test(test_class_name: str, test_source: str) -> str:
    """编译并执行一段 Java 测试代码，返回标准输出"""
    test_dir = TEST_CLASSES_DIR
    os.makedirs(test_dir, exist_ok=True)

    # 测试源码里的 @@MHT_TEST_DIR@@ 占位符换成实际目录（Java 源码里用正斜杠更稳）
    test_source = test_source.replace("@@MHT_TEST_DIR@@", TEST_MHT_DIR.replace("\\", "/"))

    src_file = os.path.join(test_dir, f"{test_class_name}.java")
    with open(src_file, "w", encoding="utf-8") as f:
        f.write(test_source)

    classpath = os.pathsep.join([
        TARGET_CLASSES,
        *[os.path.join(TARGET_LIB, f) for f in os.listdir(TARGET_LIB) if f.endswith(".jar")]
    ])

    compile_cmd = [JAVAC, "-encoding", "UTF-8", "-cp", classpath, "-d", test_dir, src_file]
    r = subprocess.run(compile_cmd, capture_output=True, text=True, timeout=30)
    if r.returncode != 0:
        return f"[COMPILE ERROR]\n{r.stderr}"

    run_cmd = [JAVA, "-cp", f"{test_dir}{os.pathsep}{classpath}", test_class_name]
    r = subprocess.run(run_cmd, capture_output=True, text=True, timeout=60)
    return r.stdout + ("\n" + r.stderr if r.stderr else "")


# ══════════════════════════════════════════════════════════════════
# TEST 1: Bug 1 — 不勾选内容区域限定时提取不到图片
# ══════════════════════════════════════════════════════════════════
TEST1_SOURCE = r"""
import com.mhtextractor.core.MimeParser;
import com.mhtextractor.extractor.ImageExtractor;
import java.io.File;
import java.util.List;

public class Bug1_RegionLimitTest {
    public static void main(String[] args) throws Exception {
        File viewDir = new File("@@MHT_TEST_DIR@@");
        File[] mhtFiles = viewDir.listFiles((d, n) -> n.toLowerCase().endsWith(".mht"));
        if (mhtFiles == null || mhtFiles.length == 0) {
            System.out.println("ERROR: No MHT files found");
            return;
        }

        MimeParser parser = new MimeParser();
        File testFile = mhtFiles[0];
        System.out.println("=== Bug 1: 区域限定逻辑测试 ===");
        System.out.println("测试文件: " + testFile.getName());

        MimeParser.ParsedMht parsed = parser.parse(testFile.getAbsolutePath(), () -> false, null, null);
        System.out.println("图片总数 (imageInfoList): " +
            (parsed.getImageInfoList() != null ? parsed.getImageInfoList().size() : "null"));
        System.out.println("cidMap 大小: " + parsed.getCidMap().size());
        System.out.println("locationDataMap 大小: " + parsed.getLocationDataMap().size());

        // -----------------------------------------------------------------
        // 场景 A: 模拟「不勾选区域限定」— 传入空字符串 "" (当前代码的 bug 行为)
        // -----------------------------------------------------------------
        System.out.println("\n--- 场景 A: start=\"\", end=\"\" (模拟不勾选限定时，文本框被清空) ---");
        ImageExtractor extractorA = new ImageExtractor(parsed, null, null, "", "");
        try {
            List<ImageExtractor.ImageData> imagesA = extractorA.extractImages();
            System.out.println("提取到图片数: " + imagesA.size());
            if (imagesA.isEmpty()) {
                System.out.println(">>> BUG 确认: 空字符串标记导致提取不到图片！");
                System.out.println("    原因: html.indexOf(\"\") 返回 0, substring(0,0) 返回 \"\",");
                System.out.println("    导致 extractContentArea 返回空字符串，extractImages 返回空列表。");
            }
        } catch (Exception exA) {
            System.out.println("异常: " + exA.getMessage());
        }

        // -----------------------------------------------------------------
        // 场景 B: 模拟「不勾选区域限定」— 传入 null (应当的正确行为)
        // -----------------------------------------------------------------
        System.out.println("\n--- 场景 B: start=null, end=null (应当的正确行为) ---");
        ImageExtractor extractorB = new ImageExtractor(parsed, null, null, null, null);
        try {
            List<ImageExtractor.ImageData> imagesB = extractorB.extractImages();
            System.out.println("提取到图片数: " + imagesB.size());
            for (int i = 0; i < Math.min(5, imagesB.size()); i++) {
                System.out.println("  [" + i + "] filename=" + imagesB.get(i).getFilename());
            }
            if (!imagesB.isEmpty()) {
                System.out.println(">>> 对比: null 参数 → 正常提取 " + imagesB.size() + " 张图片");
            }
        } catch (Exception exB) {
            System.out.println("异常: " + exB.getMessage());
        }

        // -----------------------------------------------------------------
        // 场景 C: 使用 HTML 中的真实标记 (用户当前的 workaround)
        // -----------------------------------------------------------------
        System.out.println("\n--- 场景 C: 用 HTML 中真实标记做区域限定 ---");
        String html = parsed.getHtmlContent();
        int bodyStart = html != null ? html.indexOf("<body") : -1;
        int bodyEnd = html != null ? html.indexOf("</body>", bodyStart + 1) : -1;
        System.out.println("  body 起始位置: " + bodyStart + ", 结束位置: " + bodyEnd);
        ImageExtractor extractorC = new ImageExtractor(parsed, null, null, "<body", "</body>");
        try {
            List<ImageExtractor.ImageData> imagesC = extractorC.extractImages();
            System.out.println("提取到图片数: " + imagesC.size());
            System.out.println(">>> <body> 标记限定 → 正常提取 " + imagesC.size() + " 张图片");
        } catch (Exception exC) {
            System.out.println("异常: " + exC.getMessage());
        }

        // -----------------------------------------------------------------
        // 结论
        // -----------------------------------------------------------------
        System.out.println("\n============================================");
        System.out.println("Bug 1 结论:");
        System.out.println("  MainController 在取消勾选时把 imageRegionStartField/EndField 清空为 \"\"");
        System.out.println("  HtmlUtils.extractRegion 传入空字符串: indexOf(\"\") 返回 0");
        System.out.println("  → substring(0, 0) 返回空字符串");
        System.out.println("  → ImageExtractor 认为没有内容 → 返回 0 张图片");
        System.out.println("============================================");
    }
}
"""

# ══════════════════════════════════════════════════════════════════
# TEST 2: Bug 2 — 图片命名测试
# ══════════════════════════════════════════════════════════════════
TEST2_SOURCE = r"""
import com.mhtextractor.core.MimeParser;
import com.mhtextractor.extractor.ImageExtractor;
import com.mhtextractor.output.ImageWriter;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Bug2_ImageNamingTest {
    public static void main(String[] args) throws Exception {
        File viewDir = new File("@@MHT_TEST_DIR@@");
        File[] mhtFiles = viewDir.listFiles((d, n) -> n.toLowerCase().endsWith(".mht"));
        if (mhtFiles == null || mhtFiles.length == 0) {
            System.out.println("ERROR: No MHT files found");
            return;
        }

        MimeParser parser = new MimeParser();
        File testFile = mhtFiles[0];
        System.out.println("=== Bug 2: 图片命名逻辑测试 ===");
        System.out.println("测试文件: " + testFile.getName());

        MimeParser.ParsedMht parsed = parser.parse(testFile.getAbsolutePath(), () -> false, null, null);
        ImageExtractor extractor = new ImageExtractor(parsed, null, null, null, null);
        List<ImageExtractor.ImageData> images = extractor.extractImages();
        System.out.println("提取到图片数: " + images.size());

        // 显示全部图片的原始文件名和来源
        System.out.println("\n--- 所有图片的「原始」文件名和 CID ---");
        for (int i = 0; i < images.size(); i++) {
            ImageExtractor.ImageData img = images.get(i);
            String cid = img.getContentId() != null ? img.getContentId() : "(无CID)";
            System.out.println(String.format("  [%02d] filename=\"%s\"  cid=%s  size=%d",
                i, img.getFilename(), cid,
                img.getData() != null ? img.getData().length : 0
            ));
        }

        // 测试 rename=false
        Path tmpDir1 = Files.createTempDirectory("mht_test_rename_off_");
        System.out.println("\n--- rename=false (不重命名) 保存结果 ---");
        int saved1 = ImageWriter.saveImages(images, tmpDir1.toString(), "jpg", false);
        File[] savedFiles1 = tmpDir1.toFile().listFiles();
        if (savedFiles1 != null) {
            java.util.Arrays.sort(savedFiles1, (a, b) -> a.getName().compareTo(b.getName()));
            for (File f : savedFiles1) {
                System.out.println("  " + f.getName());
            }
        }
        System.out.println("保存成功: " + saved1 + " 张");

        // 测试 rename=true
        Path tmpDir2 = Files.createTempDirectory("mht_test_rename_on_");
        System.out.println("\n--- rename=true (重命名) 保存结果 ---");
        int saved2 = ImageWriter.saveImages(images, tmpDir2.toString(), "jpg", true);
        File[] savedFiles2 = tmpDir2.toFile().listFiles();
        if (savedFiles2 != null) {
            java.util.Arrays.sort(savedFiles2, (a, b) -> a.getName().compareTo(b.getName()));
            for (File f : savedFiles2) {
                System.out.println("  " + f.getName());
            }
        }
        System.out.println("保存成功: " + saved2 + " 张");

        // 对比
        System.out.println("\n--- 对比结论 ---");
        boolean same = true;
        if (savedFiles1 != null && savedFiles2 != null && savedFiles1.length == savedFiles2.length) {
            for (int i = 0; i < savedFiles1.length; i++) {
                if (!savedFiles1[i].getName().equals(savedFiles2[i].getName())) {
                    same = false;
                    break;
                }
            }
        } else {
            same = false;
        }
        if (same) {
            System.out.println(">>> BUG: rename=false 和 rename=true 输出相同文件名！");
        } else {
            System.out.println("OK: rename=false 和 rename=true 输出不同文件名（逻辑层正确）");
            System.out.println("但 rename=false 时部分图片仍显示为数字名（如0001.jpg），");
            System.out.println("原因是这些图片在 MHT 中的「原始」文件名本就是 CID/编号。");
        }

        // 清理
        for (File f : (tmpDir1.toFile().listFiles() != null ? tmpDir1.toFile().listFiles() : new File[0])) f.delete();
        for (File f : (tmpDir2.toFile().listFiles() != null ? tmpDir2.toFile().listFiles() : new File[0])) f.delete();
        tmpDir1.toFile().delete();
        tmpDir2.toFile().delete();
    }
}
"""

# ══════════════════════════════════════════════════════════════════
# TEST 3: Bug 3 — 进度条回调测试
# ══════════════════════════════════════════════════════════════════
TEST3_SOURCE = r"""
import com.mhtextractor.core.MimeParser;
import com.mhtextractor.extractor.ImageExtractor;
import com.mhtextractor.config.AppConfig;
import com.mhtextractor.processor.ImageBatchProcessor;
import com.mhtextractor.processor.BatchProcessor;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class Bug3_ProgressBarTest {
    public static void main(String[] args) throws Exception {
        File viewDir = new File("@@MHT_TEST_DIR@@");
        File[] mhtFiles = viewDir.listFiles((d, n) -> n.toLowerCase().endsWith(".mht"));
        if (mhtFiles == null || mhtFiles.length == 0) {
            System.out.println("ERROR: No MHT files found");
            return;
        }

        System.out.println("=== Bug 3: 进度条回调测试 ===");
        System.out.println("测试文件数: " + mhtFiles.length);

        List<String> progressLog = new ArrayList<>();
        double[] finalProgress = {0.0};
        double[] maxProgress = {0.0};

        BatchProcessor.ProgressCallback callback = (current, total, fileName, subProgress) -> {
            double overall = (current - 1.0 + subProgress) / total;
            if (overall > maxProgress[0]) {
                maxProgress[0] = overall;
            }
            // 只记录每次文件完成时和最终值
            if (subProgress >= 0.95 || overall >= 1.0) {
                progressLog.add(String.format("current=%d total=%d sub=%.3f overall=%.4f",
                    current, total, subProgress, overall));
            }
            finalProgress[0] = overall;
        };

        AppConfig config = new AppConfig();
        config.setInputFolder(viewDir.getAbsolutePath());
        config.setImageRegionStartMarker(null);
        config.setImageRegionEndMarker(null);
        config.setEnableBatchRenaming(false);
        config.setEnableZipPackaging(false);
        config.setEnableCbzPackaging(false);
        config.setSingleOutputMode(false);
        config.setHighSpeedMemoryMode(false);

        ImageBatchProcessor processor = new ImageBatchProcessor(config);
        processor.setCancelSupplier(() -> false);

        BatchProcessor.ProcessStats stats = processor.process(
            viewDir.getAbsolutePath(),
            System.getProperty("java.io.tmpdir") + "/mht_test_output",
            callback
        );

        System.out.println("\n--- 关键进度点（sub>=0.95 或 完成） ---");
        for (String line : progressLog) {
            System.out.println("  " + line);
        }

        System.out.println("\n--- 最终进度 ---");
        System.out.println("finalProgress = " + finalProgress[0]);
        System.out.println("maxProgress   = " + maxProgress[0]);

        boolean reachedOne = Math.abs(finalProgress[0] - 1.0) < 0.001;

        System.out.println("\n--- Bug 3 分析 ---");
        if (reachedOne) {
            System.out.println("进度回调层: finalProgress 达到 1.0 ✓");
        } else {
            System.out.println("进度回调层: finalProgress 未达到 1.0 ✗ (差值=" + (1.0 - finalProgress[0]) + ")");
        }
        System.out.println("GUI 层: progressBar 通过 SmoothProgressProperty 绑定 Task.progress");
        System.out.println("  当 Task 完成时 setOnSucceeded 调用 smoothProgress.set(1.0) 后立即 unbind()");
        System.out.println("  + stopSmoothProgress() 可能中断动画，但如果 smoothProgress.set(1.0) 先执行则应显示 100%");
        System.out.println("  需要实际运行 GUI 确认是否存在竞态条件。");
    }
}
"""


# ── 主流程 ──────────────────────────────────────────────────────
if __name__ == "__main__":
    print("=" * 60)
    print("MHT Extractor Bug 复现测试")
    print("=" * 60)

    # 占位符没填就先拦下：否则会"编译过了但一个文件都没测"的假绿
    missing = []
    if "<打包机上填写" in TEST_MHT_DIR:
        missing.append("MHT_TEST_DIR  —— 待测 MHT 文件所在目录")
    if "<打包机上填写" in NIK25_HOME:
        missing.append("NIK25_HOME    —— Liberica NIK 25 安装目录")
    if missing:
        print("[ERROR] 还没配置下列环境参数（改脚本顶部，或设同名环境变量）：")
        for m in missing:
            print("        - " + m)
        sys.exit(2)

    if not os.path.isfile(JAVAC):
        print(f"[ERROR] 找不到 javac：{JAVAC}")
        print("        请确认 NIK25_HOME 指向 NIK 25 安装目录（其下应有 bin\\javac.exe）。")
        sys.exit(1)

    if not os.path.isdir(TARGET_CLASSES) or not os.path.isdir(TARGET_LIB):
        print("[ERROR] 项目未编译。请先执行：build-native.cmd --prepare-only")
        print("        （或 mvn package -DskipTests；需要 target\\classes 与 target\\lib）")
        sys.exit(1)

    if not os.path.isdir(TEST_MHT_DIR):
        print(f"[ERROR] 待测 MHT 目录不存在：{TEST_MHT_DIR}")
        sys.exit(1)

    print(f"待测目录 : {TEST_MHT_DIR}")
    print(f"JDK      : {NIK25_HOME}")
    print(f"产物目录 : {OUTPUT_DIR}")
    print(f"MSVC     : {MSVC_ENV}")

    print("\n>>> 运行 Bug 1 测试: 区域限定逻辑...")
    output1 = run_java_test("Bug1_RegionLimitTest", TEST1_SOURCE)
    print(output1)

    print("\n>>> 运行 Bug 2 测试: 图片命名逻辑...")
    output2 = run_java_test("Bug2_ImageNamingTest", TEST2_SOURCE)
    print(output2)

    print("\n>>> 运行 Bug 3 测试: 进度条回调...")
    output3 = run_java_test("Bug3_ProgressBarTest", TEST3_SOURCE)
    print(output3)

    print("\n" + "=" * 60)
    print("测试完成")
    print("=" * 60)
