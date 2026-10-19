package com.mhtextractor.backend;

import com.mhtextractor.engine.MhtExtractorApp;
import com.mhtextractor.engine.WorkspaceModel;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.GroupItem;
import com.mhtextractor.model.PreviewItem;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code --once} 管线（P4-1 骨架）：导入 → 建组（可选）→ 设参 → 提取 → 退出码。
 *
 * <p><b>与 GUI 的关系</b>：驱动 {@link MhtExtractorApp}（无头门面）+ {@link WorkspaceModel}（数据序唯一所有者），
 * 与 {@code StubUiAcceptanceTest} 的"桩 UI"同构 —— 产物与 GUI 侧黄金基线逐字节一致由
 * {@code BackendOnceSmokeTest} 断言。</p>
 *
 * <p><b>骨架期边界（诚实标注）</b>：</p>
 * <ul>
 *   <li>提取按"工作区可见条目顺序"直驱门面（单份全局参数）；按输出单元规划/逐单元参数
 *       （{@code ExtractionPlanService} + 端口协调器）接入属后续批次（P4-2 起）；</li>
 *   <li>图片提取不复用预览缓存（lifecycle = null，与 GUI 未注入时一致）；</li>
 *   <li>CLI 自身的用户可见文案暂为字面量（bundle 键集本批冻结，不得新增 i18n 键）。</li>
 * </ul>
 */
@Slf4j
public final class OnceRunner {

    /** 合并文本模式输出文件名（与 {@link MhtExtractorApp#DEFAULT_MERGED_TEXT_NAME} 同口径） */
    private static final String MERGED_TEXT_NAME = "merged.txt";

    public int run(CliArgs args) {
        I18n.setLocale(args.locale());
        log.info("once mode: {} input address(es)", args.inputs().size());

        Path out = args.output();
        try {
            Files.createDirectories(out);
        } catch (IOException e) {
            System.err.println("error: cannot create output directory " + out + ": " + e.getMessage());
            return BackendCli.EXIT_FAILURE;
        }

        // ① 导入（收集 + 去重 + 排序，口径与 GUI 导入链共用同一份实现）
        MhtExtractorApp app = new MhtExtractorApp();
        List<Path> files = app.importPaths(args.inputs().stream().map(Path::toString).toList());
        if (files.isEmpty()) {
            System.err.println("error: no importable sources found in: " + args.inputs());
            return BackendCli.EXIT_FAILURE;
        }

        // 工作区模型 = 数据序唯一所有者（与 GUI 同一模型；导入走复制型命令）
        WorkspaceModel ws = new WorkspaceModel();
        List<PreviewItem> items = files.stream()
                .map(p -> new PreviewItem(p.toString(), p.getFileName().toString(),
                        LocalDateTime.now(), p.getParent().toString()))
                .toList();
        ws.addItems(items);

        // ② 建组（可选；组号/组名唯一化由模型独占）
        GroupItem<PreviewItem> group = null;
        if (args.group()) {
            group = ws.group(ws.allItems().stream().filter(i -> !i.removed).toList());
            if (group == null) {
                System.err.println("error: grouping failed");
                return BackendCli.EXIT_FAILURE;
            }
            System.out.println("grouped " + group.getMembers().size() + " item(s) into " + group.getName());
        }

        // ③ 设参（命令行 → AppConfig；建组时同步写组参数缓存，与 GUI 三层覆盖口径一致）
        AppConfig params = buildParams(args);
        if (group != null) {
            if (args.imageMode()) {
                group.setImageParamsCache(params);
            } else {
                group.setTextParamsCache(params);
            }
        }

        // ④ 提取（按工作区可见条目顺序；进度打印到 stdout）
        List<Path> targets = ws.allItems().stream()
                .filter(i -> !i.removed)
                .map(i -> Path.of(i.path))
                .toList();
        MhtExtractorApp.ExtractResult result;
        try {
            result = args.imageMode()
                    ? app.extractImages(targets, params, out, this::progress, () -> false, null)
                    : app.extractText(targets, params, out, MERGED_TEXT_NAME, this::progress, () -> false);
        } catch (Exception e) {
            log.error("extraction failed", e);
            System.err.println("error: extraction failed: " + e);
            return BackendCli.EXIT_FAILURE;
        }

        var stats = com.mhtextractor.engine.WorkspaceExtractionService.statsFrom(result.stats());
        stats.files().forEach(r -> System.out.println("result: "
                + Path.of(r.path()).getFileName() + "=" + r.outcome()));
        System.out.println("done: total=" + stats.totalFiles() + " success=" + stats.successCount()
                + " fail=" + stats.failCount() + " skip=" + stats.skipCount()
                + " warn=" + stats.warnCount() + " -> " + out);
        if (stats.failCount() > 0 || stats.successCount() == 0) {
            System.err.println("error: extraction incomplete (total=" + stats.totalFiles()
                    + " success=" + stats.successCount() + " fail=" + stats.failCount() + ")");
            return BackendCli.EXIT_FAILURE;
        }
        return BackendCli.EXIT_OK;
    }

    /** 上次已打印的进度键：逐文件一次（回调会以内部小步多次触发同一文件） */
    private String lastProgressKey;

    /** 逐文件进度（stdout；每文件一行，骨架期字面量格式，后续批次与日志文案一起本地化） */
    private void progress(int index, int total, String fileName, double subProgress) {
        String key = index + "/" + total + " " + fileName;
        if (key.equals(lastProgressKey)) {
            return;
        }
        lastProgressKey = key;
        System.out.println("[" + index + "/" + total + "] " + fileName);
    }

    private static AppConfig buildParams(CliArgs args) {
        AppConfig p = args.config() == null ? new AppConfig() : args.config().copyExtractionParams();
        if (args.separate()) p.setSeparateFiles(true);
        p.setImageModeEnabled(args.imageMode());
        if (args.templateStart() != null || args.templateEnd() != null) {
            // 换行转义（\\n → 换行）由 FileWriter.processTemplate 统一处理，这里原样透传
            p.setEnableTemplate(true);
            if (args.templateStart() != null) p.setStartTemplate(args.templateStart());
            if (args.templateEnd() != null) p.setEndTemplate(args.templateEnd());
        }
        return p;
    }
}
