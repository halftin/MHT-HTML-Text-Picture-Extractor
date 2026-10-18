package com.mhtextractor.uicore;

import com.mhtextractor.model.AppConfig;

/**
 * 参数侧边栏<b>会话起点</b>基线（P1-2）—— 两份全局参数缓存的<b>纯引用持有</b>（不复制）。
 *
 * <p>原 {@code ParamSidePanel} 的两个私有 {@code AppConfig} 字段 {@code sessionTextCache} /
 * {@code sessionImageCache} 收敛为此 record；「取消」丢弃草稿时按会话起点的缓存重新载入同一目标
 * （复位语义见 {@code ParamSidePanel} 会话基线 javadoc）。</p>
 *
 * <p><b>零 JavaFX 依赖</b>：本类只依赖 {@code model} 数据模型，
 * 由 {@code ArchitectureTest} 强制其依赖闭包不含 {@code javafx}。</p>
 */
public record SessionParamBaseline(AppConfig text, AppConfig image) {
}
