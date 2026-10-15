# 截图比较（C 档工具）

`screenshot-compare.ps1` 用 JDK 自带 `javax.imageio` 读取 PNG；不依赖产品类、JavaFX、Maven或第三方库。
需要完整 JDK 17+（本仓库使用 JDK 25）、Windows PowerShell 5.1+。
优先使用 `JAVA_HOME/bin/java.exe`，未设置时使用 PATH 上的 `java`。
Java 以源码启动模式运行，不在仓库写 `.class`。

## 参数与判定

```powershell
# 先在当前会话配置 JAVA_HOME，并将 TEMP/TMP/JAVA_TOOL_OPTIONS 指向验收临时目录。
$env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir=' + $env:TEMP
powershell -NoProfile -ExecutionPolicy Bypass -File tools/verify/screenshot-compare.ps1 `
    -Baseline $baseline -Current $current -Threshold 0 -DiffDir $diff
$compareExit = $LASTEXITCODE
```

| 参数 | 含义 |
|---|---|
| `-Baseline` | 必填，基线 PNG 目录，可传绝对/相对路径 |
| `-Current` | 必填，当前 PNG 目录 |
| `-Threshold` | 默认 `0`，有限十进制数，范围 `[0,1]`；每个文件独立判定 `差异像素数 / 总像素数 <= Threshold` |
| `-DiffDir` | 可选，独立输出目录，不能与任一输入目录重叠；也拒绝解析到输入目录的链接输出 |

递归按相对路径和文件名一一配对；`.png` 扩展名不区分大小写，其余相对路径保留原样，其他扩展名忽略。
不遍历目录链接。相同 PNG 编码字节不是判据：比较解码后每个像素的完整 ARGB，任一通道不同就计一个差异像素（包括透明像素的 RGB）。
没有缩放、模糊、每通道容差或 `-IgnoreRegions`。尺寸不一致始终失败，即使 `Threshold=1`。
缺图/多图、两目录无 PNG、目录不存在、损坏或伪装 PNG、读写失败、非法参数均失败。
输入目录只读；指定 DiffDir 时为每个成功解码且同尺寸的文件写一张同名差异 PNG（黑色=相同，洋红=不同），包括阈值内通过的文件。
再次运行覆盖本次同名差异图，不清理旧图；建议每次使用独立 DiffDir，并以本次报告的文件集为准。

每个文件输出 `PASS/FAIL`、尺寸、`same`（相同数）、`different`（差异数）、`ratio`（差异比例）。
不可比较的文件输出 `N/A` 及原因。汇总包含全部文件通过/失败数；像素数和加权比例只含可比较文件，
**加权比例不能替代逐文件门禁**。比例显示四舍五入到 12 位小数，阈值判断使用未舍入的整数乘法与十进制比较。
全部文件通过返回 **0**，任一失败返回 **1**。用 `$LASTEXITCODE` 取外部命令退出码，不用 `$proc.ExitCode`。
包装器缺少必填参数等 PowerShell 启动/绑定错误由 PowerShell 自身报告为非零退出码。

## 基线位置、生成与更新

工具不内置机器路径，也不自动接受当前结果为基线。
项目验收数据按工程约定放在 H 盘产物根目录下，例如 `screenshots/<场景>/<构建标识>/<locale>/<尺寸>/baseline`、
`current`、`diff`；用参数传入。需要随仓库审阅的基线可人工放 `tools/verify/baselines/`，本次不提交未经确认的 PNG。
运行日志/夹具/临时数据不要写入基线。应在截图清单另记 Git revision、JDK/JavaFX/native 配方、系统缩放、
字体、主题、locale、窗口大小、输入夹具、捕获方法、阈值和人工确认人。

既有 `tools/probes/run-probe.ps1 UiProbe` 会用真实 FXML/控制器装配导出 `probe.png`（JavaFX scene snapshot，
不含系统窗口边框）。每次运行用新的输出目录、独立运行目录，固定 locale 与窗口条件：

```powershell
# $out1/$out2 为产物根下两个全新目录；$runtime1/$runtime2 也放产物根，先创建。
# 仓库机器按工程约定把 TEMP/TMP 和 java.io.tmpdir 设在 H 盘临时目录。
# run-probe 可按需调用 Maven，JAVA_TOOL_OPTIONS 必须在运行前设置并透传子 JVM。
powershell -NoProfile -ExecutionPolicy Bypass -File tools/probes/run-probe.ps1 UiProbe `
    -Locale en_US -OutDir $out1 -WorkingDirectory $runtime1
powershell -NoProfile -ExecutionPolicy Bypass -File tools/probes/run-probe.ps1 UiProbe `
    -Locale en_US -OutDir $out2 -WorkingDirectory $runtime2
# 将各自新生成且核对过 mtime 的 probe.png 放进独立 PNG 比较目录（同名）。
powershell -NoProfile -ExecutionPolicy Bypass -File tools/verify/screenshot-compare.ps1 `
    -Baseline $baseline -Current $current -Threshold 0 -DiffDir $diff
```

native 验收也可使用既有 native 冒烟/截图链输出的 PNG，按场景重命名后配对。
scene snapshot 必须与 scene snapshot 比，带窗口边框的屏幕截图与相同捕获方法比。
两次 JVM 探针输出只能证明工具用于真实截图的能力；不能替代 JVM/native 全场景视觉矩阵。

首次建立基线和每次更新基线**必须人工检查截图与差异图并明确确认**，记录原因和确认人后才能拷入 baseline。
比较失败时保留原基线，不用自动更新消除失败。
若有渲染抖动/光标/时间戳，先固定环境、输入与捕获时机，检查差异图；确需放宽时，人工确认后在运行命令和报告里
明确记录新阈值、观察到的差异比例和原因。阈值是不同像素的数量比例，不能辨别“可接受抖动”和“真实回归”。

## 可复跑夹具与红控制

```powershell
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
# $fixtureRoot 按工程约定指向 H 盘产物根下 p5b5-fixtures；每次自动创建 run-UUID。
& $java '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' `
    tools/verify/ScreenshotCompareSelfTest.java $fixtureRoot `
    (Join-Path $PWD 'tools/verify/screenshot-compare.ps1')
$selfTestExit = $LASTEXITCODE
```

自测生成 10x10 相同图、仅一像素不同、100 像素不同、11x10 尺寸对照、透明通道图等，
通过外部 PowerShell 包装器检查真实退出码和计数，同时独立读取差异 PNG 验证洋红像素数。
夹具、逐命令原始日志全部留在指定产物目录。失败返回 1，通过返回 0。

红控制：备份 `ScreenshotCompare.java` **原始字节**并计算 SHA256；只把 `countDifferent` 末尾带
`mutation-target` 注释的 `return different;` 临时改为 `return 0;`，复跑同一自测。
比较器将错误返回 0，但自测必须对 `one-reject`、`far-over` 的预期 exit=1 报 `ASSERT ... FAIL`，且自测返回 1。
务必用 `finally` 还原字节；**先复核还原后的 SHA256 与备份完全相同，再 touch 修改时间**，复跑自测必须返回 0。
源码启动每次读取当前源码，避免旧 `.class` 掩盖注入/还原效果。红控制期间不要并发运行比较器。

## 验收范围

本工具把像素比较变成可复跑且有退出码的门禁；基线的视觉正确性、基线更新审批及完整 JVM/native 场景覆盖仍需分别验收。
真实运行的原始命令、输出、退出码、PNG SHA256 和剩余人工项应随每次 C 档报告保存。
