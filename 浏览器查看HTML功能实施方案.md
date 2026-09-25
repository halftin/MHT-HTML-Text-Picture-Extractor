# 浏览器查看 HTML 功能 — 实施方案（交 Claude Code 执行）

> 版本：1.0  
> 日期：2026-09-24  
> 状态：**方案已拍板，待实现**。本文档是唯一实现依据，实施过程中如发现与代码实际不符，**以代码为准并把差异回写到本文档**。  
> 取代：`项目需求文档.md` 中"一、待实施需求 → 1. HTML查看器"整节（含 Q6/Q9–Q13 决策点）。

---

## 0. 一页速览

| 项 | 结论 |
|---|---|
| 做什么 | 右键 MHT 条目 → **用系统默认浏览器查看该 MHT 内嵌的页面 HTML** |
| 怎么做 | 自己用 `MimeParser` 抽出 HTML → 落成临时目录里的 `index.html`（cid 图片落成 `img/` 子目录 + 相对路径、强制 UTF-8）→ 启动浏览器打开 |
| 关键结论 | **浏览器不能直接打开 `.mht`**（Edge/Chrome 只会当纯文本或下载），必须先由我们生成普通 `.html` |
| 新增依赖 | **无**（不新增任何第三方依赖、**不改 pom.xml**） |
| 新增文件 | 2 个 Java 类 + 1 个测试类 |
| 修改文件 | `MainController.java`（菜单 2 处接入）+ 2 个文档 |
| Native 影响 | **零**：不新增 FXML / 不新增 Controller 注册 / 不动 `reflect-config.json`、`resource-config.json`（故本机无 GraalVM 也不阻塞交付） |
| 收益 | 富文本渲染、Ctrl+F 查找、Ctrl+± 字号缩放、链接下划线、选中复制 —— 全部由浏览器免费提供 |

---

## 1. 需求与拍板结论

### 1.1 需求（用户原话归纳）

> 不自己构建 HTML 查看器；沿用现有右键菜单"打开"的思路，通过浏览器指令，直接用浏览器查看对应 mht 文件的 HTML 内容。

### 1.2 拍板结果（2026-09-24）

| 编号 | 决策点 | 拍板 |
|---|---|---|
| D1 | 数据来源 | 点击时用 `MimeParser.parse(path, textOnly=false, ...)` **重新解析**（需要 cid 图片字节）；不往 `PreviewItem` 加 HTML 缓存 |
| D2 | 图片处理 | cid 图片写入同目录 `img/` 子目录，HTML 中 `cid:` 重写为**相对路径**（不用 data URI：避免体积膨胀与源码不可读） |
| D3 | 源码视图 | ~~用浏览器 `view-source:` 源码视图~~（**2026-09-24 实机两轮修订**：① 命令行传入的 `view-source:` 导航被 Chromium 拦截（file 与 https 均重定向默认首页，已实测）；② 但**经 CDP 调试端口的浏览器内部导航不被拦截**（`PUT /json/new?view-source:file:///...` 已实测打开原生源码查看器）。最终方案：**CDP 原生源码查看器优先，失败回退自产源码页**，见 §3.2.3） |
| D4 | 浏览器选择 | **系统默认浏览器**（不提供浏览器选择配置项） |
| D5 | HTML 内容 | **原样保留**，只做两件事：① 强制 UTF-8（注入/替换 `<meta charset>`）② `cid:` → 相对路径。不剥离 `<script>`、不做 `prettyPrint` |
| D6 | 大文件 | 不截断（浏览器渲染无压力），仅在日志记录体积 |
| D7 | 缓存 | 导出目录按 `(绝对路径 + mtime + size)` 的 SHA-256 哈希复用；命中且完成标记存在则跳过重新生成 |
| Q9（旧） | 查看器实现方案 | **自建查看器整体取消**（含孵化 `RichTextArea` 与 RichTextFX 方案，均不实施） |
| Q11（旧） | 查找功能 | 由浏览器 `Ctrl+F` 提供，不再自行实现 |
| Q12（旧） | 原始/格式化切换 | 不做 `prettyPrint` 切换；"源码视图"= 对生成页用 `view-source:` 打开 |
| Q13（旧） | 窗口形态 | 交给浏览器多标签，天然可多开 |

### 1.3 明确不做（范围边界）

- ❌ 不生成 native image 相关的任何新配置
- ❌ 不引入 `jfx-incubator-richtext` / RichTextFX / WebView
- ❌ 不实现应用内 HTML 渲染、应用内查找、应用内字号调整
- ❌ 不注入 `<base href="...">` 修复站内相对链接（原始 MHT 里的相对 URL 失效属可接受行为）
- ❌ 不处理 `locationDataMap`（`src` 为 http(s) 的图片保持原样，能否加载取决于网络）
- ❌ 不新增参数弹窗选项、不新增 `AppConfig` 字段

---

## 2. 方案总览

### 2.1 数据流

```
右键「在浏览器中查看」
  │  (FX 线程：取 contextTarget → PreviewItem)
  ├─ 后台线程 "html-browser-export"（daemon）
  │   ├─ 快速校验文件存在（不存在 → 回到 FX 线程弹警告，与 openMhtFile 同风格）
  │   ├─ MimeParser.parse(path, textOnly=false, () -> false, null, null)
  │   │     └─ htmlContent 为 null → 失败
  │   ├─ HtmlExportBuilder.export(path, parsed)
  │   │     ├─ 计算缓存键 → %TEMP%\mht-extractor-view\<hash>\
  │   │     ├─ 命中 .complete → 直接返回
  │   │     ├─ 构建 cid 索引（归一化 key）
  │   │     ├─ 顺序扫描 HTML 中的 cid: 引用 → 去重编号 → 写 img/0001.ext
  │   │     ├─ 重写 cid: → img/0001.ext
  │   │     ├─ 强制 UTF-8 meta（删除原 charset 声明 + 插入 <meta charset="utf-8">）
  │   │     ├─ 以 UTF-8 无 BOM 写 index.html
  │   │     └─ 写 .complete 标记；顺带清理 7 天前的兄弟目录（best-effort）
  │   └─ 记日志（生成路径 / 图片数 / 警告数 / 体积）
  └─ FX 线程
      ├─ 渲染模式：BrowserLauncher.openHtml(index.html)
      └─ 源码模式：BrowserLauncher.openViewSource(index.html)
           ├─ 优先：CDP 调试端口（固定 29333 + 独立 profile）内部导航 view-source:file
           │        → Edge/Chrome 原生源码查看器（独立调试实例窗口内打开）
           └─ 回退：生成 source-view.html（行号+高亮+链接下划线）→ 按渲染视图三级启动打开
              （修订 2026-09-24：命令行 view-source 被 Chromium 拦截，CDP 内部导航不受限，见 §1.2 D3）
```

### 2.2 目录布局

```
%TEMP%\mht-extractor-view\                     ← 根（java.io.tmpdir + mht-extractor-view）
  └─ 9f2c1ab73d4e05f6\                         ← 缓存键（SHA-256 前 16 位十六进制）
       ├─ <MHT 文件名>.html                     ← 交给浏览器的页面（按 MHT 文件名命名，浏览器标签页显示该名称；修订：原为 index.html，见 §3.1）
       ├─ img\0001.png                         ← cid 图片，按 HTML 中出现顺序编号
       ├─ img\0002.jpg
       ├─ .complete                            ← 完成标记（存在即视为可复用）
       └─ source-view.html                     ← 源码视图页（源码转义 <pre>；修订：源码视图固定生成，见 §3.2.3）
```

### 2.3 菜单接入位置

现有 `ContextMenu`（`MainController.buildContextMenu()`）原为 4 组 **15 项**，本方案改为 **16 项**：新增 2 项、**移除「打开」**（浏览器无法渲染 .mht，"打开"仅显示原始 MIME 文本，已被「在浏览器中打开」取代——用户验收拍板），渲染视图项更名「在浏览器中打开」：

```
ctxSelect, ctxSelectAll
────
ctxOpenInBrowser, ctxOpenFolder, ctxViewSource      ← 浏览器查看HTML需求后：移除 ctxOpen、更名、顺序调整
────
ctxParams, ctxClearParams, ctxApplyGlobal
────
ctxGroup, ctxUngroup
────
ctxRemove, ctxRemoveGroup
```

**按钮行入口（用户验收拍板）**：列表下方按钮行末位新增「查看HTML源码」按钮（原 `MainView.fxml` 隐藏占位按钮转正，`fx:id=viewSourceButton`），对列表当前选中条目打开源码视图；禁用规则与右键菜单一致（选中有效 PreviewItem 时可用），随 `updateListButtons()` 刷新。

> 备选：合并为一个子菜单「在浏览器中查看 ▸ 渲染视图 / 源码视图」。**默认按两个平铺项实现**（与现有扁平风格一致，且子菜单需要额外处理 `Menu` 而非 `MenuItem`）。

---

## 3. 详细设计

### 3.1 新类 1：`com.mhtextractor.util.HtmlExportBuilder`（纯逻辑，可单测）

职责：把 `ParsedMht` 落成可被浏览器打开的目录产物。**不依赖 JavaFX、不启动浏览器、可在单测中直接调用。**

```java
package com.mhtextractor.util;

import com.mhtextractor.core.MimeParser;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** 把 MHT 内嵌 HTML 导出为浏览器可直接打开的本地页面（含 cid 图片落地） */
@Slf4j
public final class HtmlExportBuilder {

    /** 临时根目录名（位于 java.io.tmpdir 下） */
    public static final String TEMP_ROOT_NAME = "mht-extractor-view";
    /** 图片子目录名 */
    public static final String IMG_DIR_NAME = "img";
    /** 完成标记文件名 */
    public static final String COMPLETE_MARKER = ".complete";
    /** 页面文件名 */
    public static final String INDEX_NAME = "index.html";
    /** 缓存目录保留天数 */
    private static final int RETENTION_DAYS = 7;

    private HtmlExportBuilder() {}

    /** 导出结果（不抛异常，失败信息放 error） */
    public record ExportResult(boolean success, Path htmlFile, int imageCount,
                               List<String> warnings, String error) {
        public static ExportResult failure(String msg) {
            return new ExportResult(false, null, 0, List.of(), msg);
        }
    }

    public static ExportResult export(String mhtPath, MimeParser.ParsedMht parsed) { ... }
    // 内部：rootDir / cacheKey / buildCidIndex(Normalize) / rewriteCidRefs /
    //       writeImages / forceUtf8Meta / pruneOldDirs
}
```

#### 3.1.1 缓存键

```java
static String cacheKey(String mhtPath) throws Exception {
    Path p = Paths.get(mhtPath).toAbsolutePath().normalize();
    long size = Files.exists(p) ? Files.size(p) : -1L;
    long mtime = Files.exists(p) ? Files.getLastModifiedTime(p).toMillis() : -1L;
    String raw = p + "|" + size + "|" + mtime + "|v" + CACHE_FORMAT_VERSION;
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    byte[] d = md.digest(raw.getBytes(StandardCharsets.UTF_8));
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
    return sb.toString();   // 16 位十六进制
}
```

> **实施回写（2026-09-24）**：缓存键追加**格式版本** `CACHE_FORMAT_VERSION`（当前 `"3"`）。原公式只含路径+大小+mtime——修复 MimeParser quoted-printable 双解码乱码（见 §8）后旧缓存仍是乱码产物；页面改按 MHT 文件名命名（用户反馈标签页应显示 MHT 名称而非 index.html）后旧缓存目录里的页面文件名对不上。版本号参与哈希后旧目录自然失效（7 天清理回收）。今后导出产物内容语义变化时递增该常量。

#### 3.1.2 cid 归一化索引（**最容易出错的一环**）

MHT 里的引用写法五花八门，必须统一归一化后再查表：

| 原始写法 | 归一化步骤 |
|---|---|
| `cid:image001.png@01D9.ABC` | 去 `cid:` 前缀（大小写不敏感） |
| `<image001.png@01D9.ABC>` | 去首尾尖括号 |
| `%3Cimage001.png%4001D9.ABC%3E` | URL 解码（`URLDecoder.decode(s, UTF_8)`） |
| `"cid:..."` / `'cid:...'` / `cid:...`（无引号） | 去引号 |
| `src=cid:image001.png>`（无引号属性） | 剥掉捕获组尾随的 `>`（**实施时发现并回写**，见下） |
| 前后空白、大小写差异 | `trim()` + `toLowerCase(Locale.ROOT)` |

> **实施回写（2026-09-24）**：无引号属性 `src=cid:xxx>` 中正则 `(?i)cid:([^"'\s)]+)` 会把 `>` 一并捕获，归一化需额外剥掉"不以 `<` 开头但以 `>` 结尾"的尾随尖括号；非法百分号编码（`URLDecoder` 抛 `IllegalArgumentException`）保持原样继续，查不到即计入 warnings。

索引构建：`Map<归一化key, byte[]>`,`cidMap` 的**每个 key 都按同一规则归一化**后入表；查表时用同规则归一化引用串。

补充兜底：若归一化后查不到，再尝试用引用串的**文件名部分**与 `cidMap` key 的文件名部分做一次匹配 —— 仅做一次，仍失败则视为未解析。**文件名部分取法（实施回写 2026-09-24，原文"最后一个 `/` 或 `@` 之前的部分"有误）**：先取最后一个 `/` **之后**的部分，再取第一个 `@` **之前**的部分（`image001.png@01D9.ABC` → `image001.png`），两者均小写化后比较。

#### 3.1.3 引用重写范围（顺序扫描，按首次出现编号）

必须覆盖的引用位置（用大小写不敏感正则，**不要**用 Jsoup 重新序列化，以保留原始 HTML 结构）：

```
src="cid:XXX" / src='cid:XXX' / src=cid:XXX           ← img / input[type=image]
href="cid:XXX"                                        ← link[rel=stylesheet]
background="cid:XXX"                                  ← 老式 body/table 属性
style="... url(cid:XXX) ..."                          ← 内联样式
<style> ... url(cid:XXX) ... </style>                 ← 样式块
```

实现建议：单趟正则 `(?i)cid:([^"'\s)]+)` 找出所有引用，逐个归一化查表；命中则替换为 `img/%04d.%s`（序号按**去重后的首次出现顺序**，`%04d` 从 1 开始），未命中则**原样保留**并把该串计入 `warnings`。

#### 3.1.4 图片落盘

```java
String ext = SanitizeUtils.detectExtensionByMagicNumber(data, "png");   // 复用现成魔数检测
Files.write(imgDir.resolve(String.format("%04d.%s", idx, ext)), data);
```

- 同一 cid 被多次引用 → **只写一次**，复用同一文件名
- 魔数检测不了的类型（如 svg）→ 回退 `png`（`detectExtensionByMagicNumber` 在 fallback 为 `svg` 时才有 SVG 分支，这里显式传 `"png"` 即可；**不要传 null**）
- webp / avif 原样落盘即可，主流浏览器都能渲染，**不要**调用 `org.glavo.webp` 解码

#### 3.1.5 强制 UTF-8（**不做就是中文乱码**）

`MimeParser` 已按 UTF-8/GBK 回退链把字节解成了 `String`，但 HTML 里通常还留着原编码声明。若按 UTF-8 写盘而浏览器按 `gb2312` 解码 → 全文乱码。处理：

1. 删除/替换所有 charset 声明（大小写不敏感，两种情况都要覆盖）：
   - `<meta charset="gb2312">`、`<meta charset=gb2312>`
   - `<meta http-equiv="Content-Type" content="text/html; charset=gb2312">`
   - 统一替换为 `<meta charset="utf-8">`
2. 把该 `<meta charset="utf-8">` 放到 **`<head>` 开始标签之后**；若无 `<head>`，则置于文档最前（Chrome 只扫描前 1024 字节）
3. 写盘：`Files.writeString(p, html, StandardCharsets.UTF_8)` —— **不写 BOM**（BOM 会污染 `view-source` 首行）

> 注意：第 1 步的正则不要贪婪跨标签匹配，避免误删正文；建议用 `<meta[^>]*>` 定位后再判断是否含 `charset`。

#### 3.1.6 清理策略

- 写盘前：删除完成标记（**实施回写 2026-09-24**：实现为未命中标记时先用 `FileUtils.deleteDirectory` 整体清空该缓存目录再重建，避免上次半途而废的残留图片混入本次产物），写完后再创建 `.complete`
- 每次导出后：扫描根目录下的兄弟目录，`Files.getLastModifiedTime` 超过 7 天的用 `FileUtils.deleteDirectory(dir.toFile())` 删除（`FileUtils.java:98` 已有现成实现），**任何异常都只记 debug 日志，不影响主流程**
- 不做退出时清理（交给系统临时目录策略）

### 3.2 新类 2：`com.mhtextractor.gui.util.BrowserLauncher`

职责：只负责"把某个本地 html 交给系统默认浏览器打开"，含 `view-source:` 与兜底。

```java
package com.mhtextractor.gui.util;

/** 用系统默认浏览器打开本地 HTML（支持 view-source 源码视图 + 多级兜底） */
@Slf4j
public final class BrowserLauncher {

    public static boolean openHtml(Path htmlFile) { ... }        // 渲染视图
    public static boolean openViewSource(Path htmlFile) { ... }  // 源码视图（view-source:）
    // 内部：detectDefaultBrowserExe / runExe / cmdStart / desktopBrowse /
    //       writeSourceFallbackPage / toFileUrl
}
```

#### 3.2.1 默认浏览器 exe 探测（按序，命中即返回）

1. **注册表 UserChoice**（最准）  
   `reg query "HKCU\Software\Microsoft\Windows\Shell\Associations\UrlAssociations\http\UserChoice" /v ProgId`  
   → 取到 `ProgId`（如 `MSEdgeHTM` / `ChromeHTML` / `FirefoxURL-308046B0AF4A39CB`）  
   → `reg query "HKCR\<ProgId>\shell\open\command" /ve`
2. **注册表 http 协议默认命令**  
   `reg query "HKCR\http\shell\open\command" /ve`
3. **固定候选路径**（存在即用）
   - `C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe`
   - `C:\Program Files\Microsoft\Edge\Application\msedge.exe`
   - `C:\Program Files\Google\Chrome\Application\chrome.exe`
   - `C:\Program Files (x86)\Google\Chrome\Application\chrome.exe`
   - `%LOCALAPPDATA%\Google\Chrome\Application\chrome.exe`
   - `C:\Program Files\Mozilla Firefox\firefox.exe`
4. `where.exe msedge` / `where.exe chrome` / `where.exe firefox`
5. 全部失败 → 返回 `Optional.empty()`，走 3.2.3 兜底

**命令行解析规则**（注册表值形如 `"C:\...\msedge.exe" --single-argument %1`）：
- 若以 `"` 开头 → 取到下一个 `"` 之间的内容
- 否则 → 取第一个空格前的内容
- 结果必须 `Files.isRegularFile` 校验，否则视为失败继续下一档

**输出编码坑**：`reg.exe` 输出是控制台代码页（中文系统为 GBK）。读取方式：`ProcessBuilder` 起进程后按字节读，先试 `StandardCharsets.UTF_8` 严格解码（`CodingErrorAction.REPORT`），失败则用 `Charset.forName("GBK")`。**不要**假设 UTF-8。

#### 3.2.2 三级启动兜底

```java
// ① 直接起浏览器 exe（不经过 shell：可绕过 UIPI/DDE 问题，见 MainController.java:1414 的既有经验）
new ProcessBuilder(exe, url).start();
// ② cmd start（整串作为单个参数传入，沿用既有写法）
new ProcessBuilder("cmd", "/c", "start \"\" \"" + url + "\"").start();
// ③ Desktop API
Desktop.getDesktop().browse(URI.create(url));
```

- URL 统一用 `htmlFile.toUri().toASCIIString()`（自动百分号编码中文路径），**不要**手工拼 `file:///`
- ~~源码视图 URL：`"view-source:" + toFileUrl(htmlFile)`~~ **修订（2026-09-24 实机验证）**：Edge 会把命令行传入的 `view-source:` URL（file 与 https 两种均已实测）重定向到默认首页，view-source 方案整体废弃；源码视图改为生成转义源码页（§3.2.3）后按渲染视图三级启动打开
- 渲染视图 ①②③ 都可用，按顺序兜底
- ③ `java.awt.Desktop` 在 GraalVM native-image 下不保证可用（AWT 支持有限），**故只能作末位兜底**；这也正是把"直接起浏览器 exe"放在首位的原因
- 每个分支失败只记 `log.warn`，不要抛异常打断 UI

#### 3.2.3 源码视图（2026-09-24 三修：CDP 原生优先 + 源码页回退）

**主路径 — CDP 原生源码查看器**（用户拍板 2026-09-24，实测驱动）：

- 原理：Chromium 拦截**命令行传入**的 view-source: 导航，但**经 CDP 调试端口的内部导航**（`PUT /json/new?view-source:file:///...`）不被拦截（已实测：标签页 URL 保持在 view-source:file，原生查看器打开）
- 调试实例：固定端口 `29333` + 独立 profile（`%TEMP%\mht-extractor-edge-profile`，与日常 Edge 隔离——日常实例运行时不接受调试参数）；实例由 `BrowserLauncher` 按需启动，初始标签页直接打开渲染页（file://），随后 PUT 开源码标签，**源码标签打开后自动关闭渲染标签**（`/json/close`，每次点击都执行，无渲染标签则静默跳过——精简为单标签）
- 端点状态机（实测）：健康=秒回直接复用；**后台挂起**（窗口被全关）= 端口监听但不应答 → 用 netstat 找 PID + wmic 校验命令行含本 profile → `taskkill /PID /T /F` 结束后干净重启（暖 profile 重启约 0.6 秒）；**端口拒绝**= 实例已退出 → 直接新启动；启动等待截止 15 秒
- **HTTP 响应读取（关键坑，实测驱动）**：Edge DevTools 服务端响应后**保持 keep-alive 不关闭连接**，客户端若用 `readAllBytes()` 等 EOF 会把已到达的响应拖到超时丢弃——此前大量"端点挂起/响应丢失/误判回退/重复标签/一次点击 90 秒"的假象全部源于此。正确做法：按 HTTP 语义读响应（状态行 + 头 + Content-Length 定长读体）。修复后一次点击实测 **411ms**、单窗口单源码标签、无回退
- **标签打开去重（实测缺陷回写）**：以 `/json/list` 中标签真实存在为唯一裁决：存在即成功（响应丢失不算失败）；仅列表确认 ABSENT 才重发 PUT（防重复标签）；列表不可得（UNKNOWN）只等待复查、绝不重发；实例已死（DEAD）立即回退（PUT 未落地，回退不会双窗口）；总预算约 30 秒，最终无法确认时按已打开处理（避免回退页双窗口）并记 warn
- HTTP 客户端为原生 Socket 手写请求（connect 700~800ms / read 1.2~3s 超时），零依赖、native-image 安全
- 仅 Chromium 系（msedge/chrome/chromium exe 名）走此路径；Firefox 等直接回退

**回退路径 — 自产源码页**（下述全部保留，仅在 CDP 失败时使用）。页面观感尽量贴近 Edge/Chromium 原生源码查看器（行号栏 + 语法高亮 + 链接下划线 + 行折返）：

1. 读取 `index.html` 全文，换行先归一化（`\r\n` / `\r` → `\n`），**逐行渲染**：每行一条 `.row`（左浮行号栏 + 内容块），行号右对齐灰色底栏、宽度按总行数位数自适应
2. **语法高亮**为纯 Java 单趟扫描器（无第三方依赖）：标签 `<...>` 蓝色、属性名红褐、属性值绿色、注释 `<!-- -->` 灰斜体、DOCTYPE 灰色；标签结束判定尊重引号内的 `>`；跨行标签/注释在行边界处闭合 span、下一行重开（单层 span 跟踪，高亮段不嵌套）
3. **链接下划线**：文本段与属性值内的 `http(s)://` URL 加 `.hl-link` 样式（蓝色 + 下划线；尾随句读号不入链接）
4. 写 `source-view.html`（样式随页面内联，无 JS）：

```html
<!DOCTYPE html><html><head><meta charset="utf-8"><title>HTML 源码</title>
<style>body{margin:0;background:#fff;}
.row{clear:both;font-family:Consolas,"Courier New",monospace;font-size:13px;line-height:1.5;}
.ln{float:left;text-align:right;color:#9e9e9e;background:#f7f7f7;border-right:1px solid #e5e5e5;padding-right:8px;margin-right:10px;user-select:none;}
.lc{display:block;white-space:pre-wrap;min-height:1.5em;color:#000;}
.hl-tag{color:#0000ff;}.hl-attr{color:#a31515;}.hl-val{color:#008000;}
.hl-cm{color:#808080;font-style:italic;}.hl-dt{color:#808080;}
.hl-link{color:#0000ee;text-decoration:underline;}</style></head>
<body><div class="row"><span class="ln" style="width:4em">1</span><span class="lc">…第 1 行…</span></div>…</body></html>
```

5. 按渲染视图的三级启动顺序打开它（直接起 exe → `cmd start` → `Desktop.browse`，全部只处理普通 http/file 链接，必定可用）

> **实施回写（2026-09-24，用户两轮实测反馈驱动）**：① 首版为无配色转义页——用户反馈"全部为黑色"，加语法高亮；② 次版加高亮后用户反馈"换行信息丢失、链接缺下划线、与 Chromium 原生观感有区别"——改为逐行渲染 + 行号栏 + 链接下划线 + 按词折返（去掉 `word-break:break-all`）。逐行保真已验证：生成页行数与 index.html 行数一致、逐行内容一致。

### 3.3 `MainController` 改动点（**仅 4 处**）

| # | 位置 | 改动 |
|---|---|---|
| 1 | 字段区（`MainController.java:421` 附近，`ctxOpenFolder` 之后） | 新增 `private MenuItem ctxOpenInBrowser;`、`private MenuItem ctxViewSource;`、`private final AtomicBoolean browserExporting = new AtomicBoolean(false);` |
| 2 | `buildContextMenu()`（`MainController.java:1212–1246`） | 创建两个 `MenuItem`（文案：`在浏览器中查看` / `在浏览器中查看源码`）、绑定 `setOnAction`、插入 `ContextMenu` 构造列表；把注释"恒 15 项同序"改为 **17 项** |
| 3 | `updateContextMenuState()`（`MainController.java:1301–1321`） | 新增两行：`ctxOpenInBrowser.setDisable(!(topItem || member));`、`ctxViewSource.setDisable(!(topItem || member));` |
| 4 | 动作方法区（`onContextOpen()` / `onContextOpenFolder()` 附近，`MainController.java:1372–1407`） | 新增 `onContextOpenInBrowser()`、`onContextViewSource()`、`openHtmlInBrowser(PreviewItem, boolean sourceMode)` |

动作实现骨架（**照抄风格**：警告/错误用私有 `showAlert(...)`，`MainController.java:4649`）：

```java
private void onContextOpenInBrowser() {
    if (contextTarget instanceof PreviewItem it && !it.removed) openHtmlInBrowser(it, false);
}

private void onContextViewSource() {
    if (contextTarget instanceof PreviewItem it && !it.removed) openHtmlInBrowser(it, true);
}

/** 在系统默认浏览器中查看该 MHT 内嵌 HTML（sourceMode=true 走 view-source 源码视图） */
private void openHtmlInBrowser(PreviewItem it, boolean sourceMode) {
    File f = new File(it.path);
    if (!f.exists()) {
        log.warn("浏览器查看失败：文件不存在（可能已被移动或删除）{}", it.path);
        showAlert(Alert.AlertType.WARNING, "文件不存在，可能已被移动或删除：\n" + it.path);
        return;
    }
    if (!browserExporting.compareAndSet(false, true)) {
        log.info("已有查看页生成任务在进行中，忽略本次请求");
        return;
    }
    statusLabel.setText("正在生成浏览器查看页…");
    Thread t = new Thread(() -> {
        try {
            MimeParser.ParsedMht parsed = new MimeParser().parse(it.path, false, () -> false, null, null);
            if (parsed.getHtmlContent() == null) {
                throw new IllegalStateException("无法解析文件（HTML 内容缺失）");
            }
            HtmlExportBuilder.ExportResult r = HtmlExportBuilder.export(it.path, parsed);
            if (!r.success()) {
                throw new IllegalStateException(r.error());
            }
            log.info("已生成浏览器查看页：{}（图片 {} 张，警告 {} 条）",
                    r.htmlFile(), r.imageCount(), r.warnings().size());
            Platform.runLater(() -> {
                boolean ok = sourceMode
                        ? BrowserLauncher.openViewSource(r.htmlFile())
                        : BrowserLauncher.openHtml(r.htmlFile());
                if (!ok) {
                    log.warn("浏览器启动失败：{}", r.htmlFile());
                    showAlert(Alert.AlertType.WARNING,
                            "未能启动浏览器，页面已生成：\n" + r.htmlFile());
                }
            });
        } catch (Exception e) {
            log.error("生成浏览器查看页失败：{}", it.path, e);
            Platform.runLater(() -> showAlert(Alert.AlertType.ERROR, "生成查看页失败：" + e.getMessage()));
        } finally {
            browserExporting.set(false);
            Platform.runLater(() -> statusLabel.setText("就绪"));
        }
    }, "html-browser-export");
    t.setDaemon(true);
    t.start();
}
```

注意事项：
- **不要**在 FX 线程做解析（大文件会卡界面），后台线程名固定为 `html-browser-export`（与既有 `preview-preparse` / `preview-image-load` 命名风格一致）
- 解析回调参数与既有预解析保持一致：`() -> false, null, null`（见 `MainController.java:4530`）
- 成功后**不要**弹确认框（与"打开"行为一致，静默成功）

### 3.4 常量与命名

| 常量 | 值 |
|---|---|
| 临时根 | `System.getProperty("java.io.tmpdir") + "mht-extractor-view"` |
| 页面名 | `<MHT 文件名>.html`（`SanitizeUtils.sanitize` 清洗 + `.html` 后缀；浏览器标签页显示 MHT 名称） |
| 图片目录 | `img` |
| 完成标记 | `.complete` |
| 保留天数 | 7 |
| 线程名 | `html-browser-export` |
| 菜单文案 | `在浏览器中打开`（渲染视图）、`在浏览器中查看源码`（源码视图）；按钮行按钮文案 `查看HTML源码` |
| CDP 调试端口 | `29333`（固定，复用同一调试实例） |
| CDP 独立 profile | `%TEMP%\mht-extractor-edge-profile` |

> 输入框/日志文案统一用中文（与项目现有风格一致）。

---

## 4. 边界与异常处理清单（逐条实现 + 逐条测）

| # | 场景 | 期望行为 |
|---|---|---|
| E1 | MHT 文件被移动/删除 | 警告弹窗（文案与 `openMhtFile` 一致）+ `log.warn`，不启动线程 |
| E2 | 解析抛异常 | `log.error` + 错误弹窗（含异常消息），`browserExporting` 复位 |
| E3 | `htmlContent == null` | 同上，消息"无法解析文件（HTML 内容缺失）" |
| E4 | `htmlContent` 为空串 | 仍生成页面（浏览器显示空白），**不报错** |
| E5 | `cidMap` 为空 | 正常生成，`cid:` 引用原样保留，日志记警告数 |
| E6 | cid 大小写 / 尖括号 / `%40` `%3C` 编码差异 | 归一化后仍能命中（**必须有单测**） |
| E7 | 同一 cid 被引用多次 | 只写 1 个图片文件，多处引用同一相对路径 |
| E8 | 魔数无法识别（例如 svg 字节流） | 回退扩展名 `png`，不抛异常 |
| E9 | HTML 无 `<head>` | meta 插到文档最前，仍在前 1024 字节内 |
| E10 | 原 HTML 同时含 `<meta charset=gb2312>` 与 `<meta http-equiv=...charset=gb18030>` | 两处都被替换，最终只剩一个 `<meta charset="utf-8">` |
| E11 | 临时目录不可写 / 磁盘满 | 捕获 IOException → 错误弹窗，日志含路径 |
| E12 | 连续快速点击两次 | 第二次被 `browserExporting` 拦截，无并发生成 |
| E13 | 注册表读取失败 / 无浏览器 exe | 源码视图：CDP 不可用（非 Chromium 浏览器 / 端点超时 / 实例启动失败）时回退 `<pre>` 源码页；渲染视图走 `cmd start` → `Desktop.browse` |
| E14 | 中文路径（如 `D:\我的书\第一章.mht`） | `toUri().toASCIIString()` 正确编码，浏览器正常打开 |
| E15 | 组条目 / 列表空白处右键 | 两个新菜单项为**禁用**状态 |
| E16 | 大文件（>50MB MHT） | 正常生成（可能耗时数秒），后台线程不阻塞 UI，日志记录体积 |

---

## 5. 测试要求

### 5.1 JUnit（新增 `src/test/java/com/mhtextractor/util/HtmlExportBuilderTest.java`）

> 复用现有 `junit-jupiter 6.0.3`；**不需要 JavaFX 运行时**（`HtmlExportBuilder` 是纯逻辑）。  
> 用 `@TempDir` 或直接调用 `HtmlExportBuilder.export(...)`（临时根目录由 `java.io.tmpdir` 决定，测试后可自行清理）。

| # | 用例 | 断言 |
|---|---|---|
| T1 | charset 替换 | 输入含 `charset=gb2312` 与 `http-equiv` 两种声明 → 输出文件**不含** `gb2312`，且含 `<meta charset="utf-8">` |
| T2 | 中文不乱码 | 正文含"第一章 测试" → 以 UTF-8 读回文件内容一致（字节级验证无 BOM：首字节非 `EF`） |
| T3 | cid 重写 + 图片落盘 | `src="cid:image001.png@01D9"` + `cidMap` 含该 key（PNG 魔数字节） → HTML 含 `img/0001.png`，文件存在且字节与输入一致 |
| T4 | cid 变体归一化 | 引用写作 `CID:%3CImage001.PNG%4001d9%3E` → 仍命中同一图片 |
| T5 | 未命中 cid | 引用未知 cid → HTML 中该串**原样保留**，`warnings` 非空 |
| T6 | 去重编号 | 同一 cid 出现 3 次 → 只写 1 个文件，3 处引用相同路径 |
| T7 | 无 head 插入 | 输入 `<html><body>x</body></html>` → 输出前 200 字符内含 `<meta charset="utf-8">` |
| T8 | 魔数回退 | `cidMap` 值为非图片字节 → 扩展名回退 `png`，不抛异常 |
| T9 | 缓存复用 | 用同一路径 + 相同 mtime/size 调用两次 → 第二次不重写（记录首次文件 `lastModifiedTime` 并比较），返回同一路径 |
| T10 | 失败路径 | `htmlContent == null` → `success()==false` 且 `error()` 非空 |

### 5.2 手工验收（GUI）

| # | 步骤 | 期望 |
|---|---|---|
| A1 | 导入含图片的 MHT，右键条目 | 菜单在"打开所在文件夹"之后出现 2 个新项，可用 |
| A2 | 右键组条目 / 空白处 | 2 个新项禁用，其余项行为不变 |
| A3 | 点"在浏览器中查看" | 系统默认浏览器打开；**中文正常、图片正常**、链接带下划线、Ctrl+F/Ctrl+± 可用 |
| A4 | 点"在浏览器中查看源码" | 打开 `view-source:` 页面，源码可读（无 base64 噪音），Ctrl+F 可用 |
| A5 | 生成期间操作主界面 | UI 不卡顿；日志区出现"已生成浏览器查看页：…" |
| A6 | 再次点击同一文件 | 命中缓存，日志显示秒开，浏览器立即打开 |
| A7 | 改一个字符保存 MHT 后再点 | 缓存键变化 → 重新生成 |
| A8 | 把 MHT 改名为 `.bak` 后点击（列表条目仍在） | 警告弹窗 + 日志，无异常堆栈 |
| A9 | 提取功能回归 | 文本/图片提取、组操作、撤销、参数弹窗全部正常 |

### 5.3 可选：接入既有自动化测试

- `runAutomatedTests()`（`MainController.java:5062`）中新增用例时，**必须**让浏览器启动部分走 dry-run（例如新增包级可见的开关或直接断言 `HtmlExportBuilder` 产物），**不要在自动化测试里真的拉起浏览器**，否则会在桌面弹出一堆窗口、且 `System.exit` 前无法收敛
- `exerciseAllFeatures()`（`MainController.java:4657`，native tracing 用）**可以**调用 `openHtmlInBrowser(it, false)` 以捕获新代码路径的反射元数据；如担心弹浏览器，同样只调用到 `HtmlExportBuilder.export(...)` 为止

---

## 6. 本机环境与构建命令（**已实测**）

| 组件 | 路径 / 状态 |
|---|---|
| 编译 JDK | `D:\install\bellsoft-liberica-vm-full-openjdk25-25.0.3`（**Liberica NIK 25，必须用它**；`JAVA_HOME` 当前指向 Oracle 25.0.2，**不可用于编译**——该项目在 Oracle JDK 25 上 Lombok 注解处理会静默失效，全项目报"找不到符号 log"） |
| Maven | `C:\Users\Admin\.m2\wrapper\dists\apache-maven-3.9.16-bin\5grr65jo27hi51sujmtcldfovl\apache-maven-3.9.16\bin\mvn.cmd`（**PATH 里没有 `mvn`**） |
| 依赖缓存 | 完整（javafx 25.0.3、junit 6.0.3、lombok、surefire、shade、jsoup、jackson、logback、atlantafx、ikonli、webp、jakarta.mail 全部在 `.m2`）→ 可 `-o` 离线构建 |
| GraalVM native-image | ❌ 不存在 |
| VS Build Tools | ❌ 不存在 |

标准验证命令（PowerShell）：

```powershell
$env:JAVA_HOME = "D:\install\bellsoft-liberica-vm-full-openjdk25-25.0.3"
$mvn = "C:\Users\Admin\.m2\wrapper\dists\apache-maven-3.9.16-bin\5grr65jo27hi51sujmtcldfovl\apache-maven-3.9.16\bin\mvn.cmd"
& $mvn -o test                       # 编译 + 全部 JUnit（含新增 T1–T10）
& $mvn -o package -DskipTests        # 产出 target/mhtExtractor-2.6-beta.jar
& "D:\install\bellsoft-liberica-vm-full-openjdk25-25.0.3\bin\java.exe" `
    -Dtest.mode=true -jar target\mhtExtractor-2.6-beta.jar    # 可选：GUI 全流程回归（会弹窗，结果在 logs/test-results.txt）
```

**注意**：以上命令在受限沙箱下会因"启动工作区外 exe"被拒绝；Claude Code 如遇 `Access is denied`，需以完整权限运行。

---

## 7. 交付物清单与验收标准（DoD）

**新增**
1. `src/main/java/com/mhtextractor/util/HtmlExportBuilder.java`
2. `src/main/java/com/mhtextractor/gui/util/BrowserLauncher.java`
3. `src/test/java/com/mhtextractor/util/HtmlExportBuilderTest.java`（T1–T10）

**修改**
4. `src/main/java/com/mhtextractor/gui/controller/MainController.java`（4 处，见 §3.3）
5. `CLAUDE.md`：如需，补一行"浏览器查看 HTML"的实现位置（可选）

> **已完成项（2026-09-24，不在本次实现范围内，勿重复执行）**
> - `项目需求文档.md` 已改版（版本 2.1）："HTML查看器"整节已替换为"浏览器查看 HTML（D1–D7）"，含被否决方案与旧 Q6/Q9–Q13 的关闭记录，实施计划表已更新
> - 版本号统一：`build-native.cmd` / `build-native.ps1` / `build-native.sh` / `run-native-image.bat` 全部改为从 `pom.xml` 动态读取版本号，硬编码的 `mhtExtractor-2.3.1.jar` / `-2.2-SNAPSHOT.jar` 已消除；《基于graalvm的...打包工作流程.md》头部已写明"pom.xml 是版本号唯一来源"

**DoD**
- [ ] `mvn -o test` 全绿（原有 11 个测试类 + 新增 T1–T10）
- [ ] 编译无警告级别的 Lombok 失效（构建日志里不应出现"找不到符号 log/getter"）
- [ ] A1–A9 手工验收通过，其中 A3/A4 必须在**中文正文 + 含图片**的真实 MHT 上通过
- [ ] `pom.xml`、`*.fxml`、`src/main/resources/META-INF/native-image/**` **零改动**（diff 校验）
- [ ] 未新增任何第三方依赖
- [ ] 新增类均带中文 Javadoc；日志统一走 `@Slf4j` 的 `log`

---

## 8. 已知陈旧点（实施时注意，**不要**顺手大改）

| 位置 | 问题 | 处理建议 |
|---|---|---|
| ~~`MimeParser.extractHtmlFromPart`~~ | ~~`part.getInputStream()` 已按 Content-Transfer-Encoding 解码，再叠加 `MimeUtility.decode` 造成 quoted-printable/base64 双解码：`=3D` 转义二次解析成乱码（如 `width=3Ddevice-width` → `width=device-width` → 再解 `=de` 成 U+FFFD）~~ | **已修复（2026-09-24，用户实测乱码驱动）**：改用 `MimeBodyPart/MimeMessage.getRawInputStream()` 取原始流，`MimeUtility.decode` 只解一次。该修复同时改善文本/图片提取与预览对 quoted-printable 文件的解析；旧导出缓存因格式版本（§3.1.1）失效 |
| ~~`build-native.cmd:58`~~ | ~~引用 `target/mhtExtractor-2.3.1.jar`，而 pom 版本是 `2.6-beta`~~ | **已修复（2026-09-24）**：`build-native.cmd` / `.ps1` / `.sh` / `run-native-image.bat` 统一改为**从 pom.xml 动态读取版本号**，脚本内不再出现硬编码 jar 名；`build-native.cmd` 的 `javafx-graphics-<ver>-win.jar` 亦改为从 pom 读取 `javafx.version`。规则已写入《基于graalvm的...打包工作流程.md》头部 |
| `build-native.cmd:12-18`、`bug_tests.py:9-11` | 写死 `X:\...`、`X:\123\...`，那是另一台打包机的布局（本机是 `D:\install\...`，且无 X: 盘） | 本次不动（属路径可移植性问题，与版本号无关） |
| `CLAUDE.md` | 架构描述落后于实际代码（实际已有 `GroupItem`/`OutputUnitPlanner`/`UndoManager`/`MhtImportPlanner`/`IncrementalImport`/`DropResolver` 等） | 本次不动 |
| `MainView.fxml:146` | 隐藏占位按钮"查看HTML源码" | 保留隐藏（本方案走右键菜单，不复用它）；如需清理，单独提 PR |

---

## 附录 A：可复用的现有 API 索引

| API | 位置 | 用途 |
|---|---|---|
| `MimeParser.parse(String, boolean textOnly, Supplier<Boolean>, Consumer<ParsingStage>, Consumer<Double>)` | `MimeParser.java:81` | 抽 HTML + cidMap（**textOnly 必须传 false**，否则拿不到图片字节） |
| `MimeParser.ParsedMht`（`htmlContent` / `cidMap` / `locationMap` / `locationDataMap` / `imageInfoList`） | `MimeParser.java:335` | 数据来源 |
| `SanitizeUtils.detectExtensionByMagicNumber(byte[], String fallback)` | `SanitizeUtils.java:61` | 图片扩展名判定 |
| `SanitizeUtils.sanitize(String)` | `SanitizeUtils.java:30` | 文件名清洗（如需按 MHT 名命名页面） |
| `FileUtils.deleteDirectory(File)` | `FileUtils.java:98` | 过期缓存目录清理 |
| `FileUtils.getBaseName(File)` | `FileUtils.java:78` | 取不带扩展名的文件名 |
| `MainController.buildContextMenu()` / `updateContextMenuState()` / `onContextOpen()` / `onContextOpenFolder()` / `showAlert()` | `MainController.java:1212` / `1301` / `1372` / `1403` / `4649` | 菜单接入与错误提示 |
| `MainController.openMhtFile()` 的失败处理风格（Desktop 优先 + `cmd start` 兜底 + 警告弹窗） | `MainController.java:1378` | 启动兜底与文案模板 |
| `MainController.java:1414` 注释 | — | **提权进程下 ShellExecute/DDE 会被 UIPI 拦截**，故浏览器启动以"直接起 exe"为首选 |

## 附录 B：参考链接（决策依据）

- 浏览器无法打开 MHT（Edge/Chrome 只当文本或下载，仅 IE 模式可渲染）：[Microsoft Q&A — Viewing MHT files](https://learn.microsoft.com/en-us/answers/questions/2378169/viewing-mht-files)
- JavaFX 25 Incubator `RichTextArea`（**本方案不采用**，仅备查）：[jfx.incubator.richtext 模块](https://openjfx.io/javadoc/25/jfx.incubator.richtext/module-summary.html)、[RichTextArea](https://openjfx.io/javadoc/25/jfx.incubator.richtext/jfx/incubator/scene/control/richtext/RichTextArea.html)
- JavaFX native image 不支持 WebView（**排除 WebView 的依据**）：[gluonhq/substrate#515](https://github.com/gluonhq/substrate/issues/515)
- JavaFX 25 新增 `TextFlow` 几何 API（**备选方案 B 的依据**，本方案不采用）：[JavaFX 25 Highlights](https://inside.java/2025/09/23/javafx-25/)
