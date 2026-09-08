package com.mhtextractor.util;

import java.util.List;

/**
 * 跨输出单元的进度聚合（6.8，阶段 8）：总文件数 = 各单元文件数之和；
 * 单元内 current 映射为全局累计 current，整体进度按 (全局current - 1 + 子进度) / 总文件数 计算。
 */
public final class UnitProgressAggregator {

    private final int totalFiles;
    private final int[] cumulativeBefore;

    /**
     * @param unitSizes 各输出单元的文件数（按执行顺序）
     */
    public UnitProgressAggregator(List<Integer> unitSizes) {
        cumulativeBefore = new int[unitSizes.size()];
        int sum = 0;
        for (int i = 0; i < unitSizes.size(); i++) {
            cumulativeBefore[i] = sum;
            sum += unitSizes.get(i);
        }
        totalFiles = sum;
    }

    public int totalFiles() {
        return totalFiles;
    }

    /** 单元内 current（从 1 起）映射为全局累计 current */
    public int globalCurrent(int unitIndex, int currentInUnit) {
        return cumulativeBefore[unitIndex] + currentInUnit;
    }

    /** 整体进度 0.0~1.0 */
    public double overall(int unitIndex, int currentInUnit, double subProgress) {
        if (totalFiles == 0) {
            return 0;
        }
        return (globalCurrent(unitIndex, currentInUnit) - 1 + subProgress) / totalFiles;
    }
}
