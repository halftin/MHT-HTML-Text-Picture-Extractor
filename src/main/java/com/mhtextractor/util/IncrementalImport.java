package com.mhtextractor.util;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 增量导入（F3）：输入目录作为导入源，以绝对路径为唯一键去重，
 * 新文件按扫描顺序追加到列表末尾，已有项目保持不变。
 */
public final class IncrementalImport {

    private IncrementalImport() {
    }

    /**
     * 从目录扫描结果中筛选出列表中尚不存在的新文件（唯一键 = 绝对路径）。
     *
     * <p><b>⚠️ {@code existingPaths} 必须只含"可见"项目（{@code removed == false}）</b>：工作区的移除是
     * <b>隐藏式</b>的 —— 只置 {@code PreviewItem.removed} 标记，对象仍留在数据序里（这是撤销能恢复的前提）。
     * 若调用方把"含已移除项的全量集合"传进来，用户移除过的文件会被判为"已导入"，
     * 于是重复导入该目录时整个批次被跳过（界面上"新增 0 个项目"，只有一条 debug 日志说明原因），
     * 表现就是<b>"移除后再也加不回来"</b>。因此 GUI 的调用点固定传 {@code allVisibleItems()}，
     * 由自动化断言 {@code [PASS] Re-import after remove restores items} 守护该口径。</p>
     *
     * @param scanned       目录扫描结果（如 {@link FileUtils#findMhtFiles} 的输出）
     * @param existingPaths 列表中<b>可见</b>项目的绝对路径（<b>不得</b>含已移除项）
     * @return 需要追加到列表末尾的新文件（保持扫描顺序）；无新文件时返回空列表
     */
    public static List<File> findNewFiles(List<File> scanned, Collection<String> existingPaths) {
        Set<String> existing = new HashSet<>(existingPaths);
        List<File> newFiles = new ArrayList<>();
        for (File f : scanned) {
            if (!existing.contains(f.getAbsolutePath())) {
                newFiles.add(f);
            }
        }
        return newFiles;
    }
}
