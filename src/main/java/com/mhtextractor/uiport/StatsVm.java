package com.mhtextractor.uiport;

/**
 * 提取统计视图模型（不可变 record，自持；最小可用切片，不引用任何业务类型）。
 *
 * @param unitsOk      成功处理单元数（对应处理统计的 success 语义）
 * @param unitsFailed  失败处理单元数（对应处理统计的 fail 语义）
 * @param unitsSkipped 跳过处理单元数（对应处理统计的 skip 语义）
 * @param filesWritten 实际写出的文件数（后端暂无独立计数时可为 0）
 */
public record StatsVm(int unitsOk, int unitsFailed, int unitsSkipped, int filesWritten) {
}
