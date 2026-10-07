package com.mhtextractor.uiport;

import java.util.List;
import java.util.Objects;

/**
 * 导入计划的端口视图模型（B5-1）：承载 {@code MhtImportPlanner.Plan} / 批次的可观察面，
 * <b>逐字段对照</b>（{@code batches / ignoredNonMht / hiddenDirs / emptyDirs / unreadable /
 * depthLimited / quotaHit / hasMore}，不要漏）。文件以绝对路径字符串承载（端口层不依赖
 * {@code java.io.File} 之上的业务构造），批次形态（顶层平铺 / 建组）经
 * {@link ImportBatchVm#grouped()} 保留。
 *
 * <p>本类零 JavaFX、零 I18n、零业务类型（受 {@code ArchitectureTest} 的
 * {@code UiportSignature} 字节码扫描覆盖）；防御性不可变拷贝与 {@link WorkspaceVm} 同口径。</p>
 */
public record ImportPlanVm(List<ImportBatchVm> batches,
                           int ignoredNonMht, int hiddenDirs, int emptyDirs,
                           int unreadable, int depthLimited,
                           boolean quotaHit, boolean hasMore) {

    public ImportPlanVm {
        batches = List.copyOf(Objects.requireNonNull(batches, "batches"));
    }

    /**
     * 一个导入批次（= 一次列表插入动作）的端口视图模型。
     *
     * @param dirPath    来源目录（写入 {@code PreviewItem.sourceFolder}）
     * @param folderName 建组批次（{@code grouped() == true}）的文件夹名；平铺批次为 {@code null}
     * @param grouped    true = 该批次应建组（成员只入组、不入顶层）；false = 平铺到顶层
     * @param filePaths  批次文件绝对路径（已按文件名升序、已去重），顺序不变
     */
    public record ImportBatchVm(String dirPath, String folderName, boolean grouped,
                                List<String> filePaths) {
        public ImportBatchVm {
            Objects.requireNonNull(dirPath, "dirPath");
            filePaths = List.copyOf(Objects.requireNonNull(filePaths, "filePaths"));
        }
    }
}
