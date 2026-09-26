package com.mhtextractor.util;

/**
 * 极简变更监听器（会话 18 · E1.1，用于替换 JavaFX 属性）。
 *
 * <p><b>为什么自研而不是用 {@code java.beans.PropertyChangeSupport}</b>：</p>
 * <ul>
 *   <li>{@code PropertyChangeSupport} 依赖 {@code java.desktop} 模块（AWT）。本项目要出 GraalVM native EXE，
 *       为一个"改名就刷新列表"的需求把 AWT 拖进 native 镜像不划算；</li>
 *   <li>语义更窄更明确：本监听器只回答"值变了"，不带属性名/旧值反射语义（我们不需要）。</li>
 * </ul>
 *
 * <p><b>契约</b>：{@link #onValueChanged()} 在发起变更的线程上<b>同步</b>调用。
 * 与替换前的 JavaFX 属性监听行为一致（{@code StringProperty.set} 也是同步触发监听器）。</p>
 *
 * <p>本接口**零 JavaFX 依赖**，是"业务层可脱离 UI 框架复用"的一部分（验收 V4）。</p>
 *
 * @see GroupItem#addNameChangeListener(ChangeListener)
 */
@FunctionalInterface
public interface ChangeListener {

    /** 值已变更（同步回调） */
    void onValueChanged();
}
