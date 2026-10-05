package com.mhtextractor.app;

import java.util.function.Consumer;
import java.util.function.Function;

import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.util.ParamScope;
import com.mhtextractor.util.UndoManager;

/**
 * 参数剪贴板构造工厂（P1-3）：把 {@link ParamClipboard} 的构造点从 gui 层下沉到 app 层，
 * 使 gui 不再出现 {@code new ParamClipboard(}（闸门 2 的 (c) 类构造点）。
 *
 * <p>三个入参即剪贴板所需的三份宿主注入：撤销压栈出口（宿主的 {@code WorkspaceModel::push}）、
 * 作用域路由（选择对象 → {@link ParamScope.Target}，与"设置参数"按钮同一份实现）与应用本体
 * （复用确认路径的 {@link ParamClipboard.ApplyFn}）。</p>
 */
public final class ParamClipboardFactory {

    private ParamClipboardFactory() {
    }

    public static ParamClipboard create(Consumer<UndoManager.Record<PreviewItem>> undoPush,
                                        Function<Object, ParamScope.Target> scopeOf,
                                        ParamClipboard.ApplyFn apply) {
        return new ParamClipboard(undoPush, scopeOf, apply);
    }
}
