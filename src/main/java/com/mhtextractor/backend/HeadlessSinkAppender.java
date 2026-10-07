package com.mhtextractor.backend;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

/**
 * GUI {@code logging.TextAreaAppender} 的无头空实现（《UI可切换重构方案》冲突 C8 的落点）。
 *
 * <p><b>为什么需要它</b>：GUI 的 {@code logback.xml} 无条件引用 {@code com.mhtextractor.logging.TextAreaAppender}
 * （其字节码依赖 UI 框架），无头进程装载它即违反"后端零 UI 框架"（native 构建即崩，见冲突 C8/C9）。
 * 本类是那个 appender 的无头替代：<b>吸收日志事件、什么都不做</b> —— 无头后端没有界面日志区，
 * 落盘交给同一配置里的文件 appender。</p>
 *
 * <p><b>零 UI 框架</b>：本包由 {@code ArchitectureTest.businessPackagesHaveNoUiFrameworkDependency}
 * 按字节码常量池强制（P4-1 起 {@code com/mhtextractor/backend} 纳入 BUSINESS_PACKAGES）。</p>
 */
public class HeadlessSinkAppender extends AppenderBase<ILoggingEvent> {

    @Override
    protected void append(ILoggingEvent eventObject) {
        // 空实现：事件已由 root 上的文件 appender 落盘，这里只负责"无头替代 GUI TextArea"
    }
}
