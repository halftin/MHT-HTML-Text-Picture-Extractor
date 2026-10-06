package com.mhtextractor.model;

import com.mhtextractor.config.AppConfig;
import com.mhtextractor.model.ChangeListener;

import java.util.ArrayList;
import java.util.List;

/**
 * 分组条目（F5 6.7）：二级列表中的组，泛型 M 为组内项目类型。
 *
 * <p>textParamsCache / imageParamsCache 为组级提取参数（6.7，阶段 5 落地），
 * null = 该模式未设置组参数（组内项目继承全局参数）。expanded 默认 true（展开）。</p>
 *
 * <p><b>会话 18 · E1.1：本类已去除 JavaFX 依赖</b>（原用 {@code StringProperty}／{@code BooleanProperty}／
 * {@code ObservableList}）。这是"业务/数据模型层可换 UI 框架复用"的验收项 V4 之一。</p>
 *
 * <p><b>行为等价性说明（重构红线：行为完全不变）</b>：</p>
 * <ul>
 *   <li>{@code name}：原为 {@code StringProperty}，可用方仅注册过一个监听器
 *       （{@code MainController.registerGroupNameRefresh}：改名后 {@code fileListView.refresh()}）。
 *       现以普通字段 + {@link ChangeListener} 复刻同一语义（同步回调）。</li>
 *   <li>{@code members}：原为 {@code ObservableList}，但全项目<b>从未</b>对其注册
 *       {@code ListChangeListener}／{@code addListener}，只当成普通 {@code List} 用
 *       （add/addAll/remove/clear/indexOf/setAll/stream）。故改为 {@code ArrayList} 是纯等价替换 ——
 *       {@code ArrayList} 对"越界不抛异常的 add(index, e)"等差异已逐点核对（见 DropResolver 的
 *       {@code Math.min} 夹取，两个实现都不抛）。</li>
 *   <li>{@code expanded}：原为 {@code BooleanProperty}，但<b>只有</b> {@code isExpanded()}／
 *       {@code setExpanded()} 两个调用形态，从未注册监听器或绑定。改为普通布尔字段。</li>
 * </ul>
 * <p>因此删除 {@code nameProperty()}／{@code expandedProperty()} 不影响任何既有行为。</p>
 */
public class GroupItem<M> {

    private String name;
    private final int groupNo;
    private final List<M> members = new ArrayList<>();
    private boolean expanded = true;
    private AppConfig textParamsCache;
    private AppConfig imageParamsCache;

    /**
     * 「从未被用户重命名过」的显式标记（步骤 3 · 功能一）：<b>手工建组</b>置 true；
     * 用户提交改名后<b>永久</b>置 false（改回默认名也不再提醒）；导入自动建组保持默认 false。
     */
    private boolean defaultNamed;

    /** 组名变更监听器（懒初始化：大多数组不需要） */
    private List<ChangeListener> nameChangeListeners;

    public GroupItem(int groupNo, String name) {
        this.groupNo = groupNo;
        this.name = name;
    }

    /**
     * 注册组名变更监听器（同步回调）。
     *
     * <p>替换原先的 {@code nameProperty().addListener(...)}：调用方仅需"改名后刷新列表"。</p>
     */
    public void addNameChangeListener(ChangeListener listener) {
        if (listener == null) return;
        if (nameChangeListeners == null) {
            nameChangeListeners = new ArrayList<>(1);
        }
        nameChangeListeners.add(listener);
    }

    public String getName() {
        return name;
    }

    /**
     * 设置组名。<b>仅在值确实变化时</b>通知监听器 —— 与原 JavaFX 属性的行为一致
     * （{@code StringProperty.set} 在旧值等于新值时不触发监听器）。
     */
    public void setName(String name) {
        boolean changed = this.name == null ? name != null : !this.name.equals(name);
        this.name = name;
        if (changed && nameChangeListeners != null) {
            // 复制后遍历：监听器若在回调中增删监听器不会触发 ConcurrentModificationException
            for (ChangeListener l : new ArrayList<>(nameChangeListeners)) {
                l.onValueChanged();
            }
        }
    }

    public int getGroupNo() {
        return groupNo;
    }

    /** 是否为「从未被用户重命名过」的默认名组（提取前未命名组拦截的判定依据，步骤 3 · 功能一）。 */
    public boolean isDefaultNamed() {
        return defaultNamed;
    }

    /** 见 {@link #isDefaultNamed()}。置 true 的唯一路径是手工建组；用户提交改名后永久 false。 */
    public void setDefaultNamed(boolean defaultNamed) {
        this.defaultNamed = defaultNamed;
    }

    /** 组内成员列表（可变更；顺序即组内顺序）。零 JavaFX 依赖。 */
    public List<M> getMembers() {
        return members;
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void setExpanded(boolean expanded) {
        this.expanded = expanded;
    }

    public AppConfig getTextParamsCache() {
        return textParamsCache;
    }

    public void setTextParamsCache(AppConfig textParamsCache) {
        this.textParamsCache = textParamsCache;
    }

    public AppConfig getImageParamsCache() {
        return imageParamsCache;
    }

    public void setImageParamsCache(AppConfig imageParamsCache) {
        this.imageParamsCache = imageParamsCache;
    }
}
