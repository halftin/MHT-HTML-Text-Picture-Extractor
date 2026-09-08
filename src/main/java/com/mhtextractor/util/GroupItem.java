package com.mhtextractor.util;

import com.mhtextractor.config.AppConfig;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * 分组条目（F5 6.7）：二级列表中的组，泛型 M 为组内项目类型。
 *
 * <p>textParamsCache / imageParamsCache 为组级提取参数（6.7，阶段 5 落地），
 * null = 该模式未设置组参数（组内项目继承全局参数）。expanded 默认 true（展开）。</p>
 */
public class GroupItem<M> {

    private final StringProperty name = new SimpleStringProperty();
    private final int groupNo;
    private final ObservableList<M> members = FXCollections.observableArrayList();
    private final BooleanProperty expanded = new SimpleBooleanProperty(true);
    private AppConfig textParamsCache;
    private AppConfig imageParamsCache;

    public GroupItem(int groupNo, String name) {
        this.groupNo = groupNo;
        this.name.set(name);
    }

    public StringProperty nameProperty() {
        return name;
    }

    public String getName() {
        return name.get();
    }

    public void setName(String name) {
        this.name.set(name);
    }

    public int getGroupNo() {
        return groupNo;
    }

    public ObservableList<M> getMembers() {
        return members;
    }

    public BooleanProperty expandedProperty() {
        return expanded;
    }

    public boolean isExpanded() {
        return expanded.get();
    }

    public void setExpanded(boolean expanded) {
        this.expanded.set(expanded);
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
