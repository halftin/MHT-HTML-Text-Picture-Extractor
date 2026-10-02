package com.mhtextractor.gui.component;

import com.mhtextractor.util.ParamScope;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * 参数目标信息栏（R1.4）：参数面板标题栏下方的<b>固定一行</b>，显示"我在配置谁" ——
 * `全局` ／ `组（组名）` ／ `条目（文件名）`，组内参数不统一时顺带提示。
 *
 * <h2>为什么纯代码构建、不新增 FXML</h2>
 * <p>R1.4 之后这个条要嵌进 Q6a 的侧边栏，而 <b>FXML 里每多一个节点/类型都可能牵动 native 元数据</b>
 * （{@code ParamDialog.fxml} 的 {@code fx:root} 结构本身不宜再动）。本类因此只用代码建控件：
 * 一个 {@code Label} 放进 {@code HBox}，不需要任何 {@code @FXML} 字段、不需要在 FXML 里登记，
 * 也不需要对 native-image 反射元数据做任何增补（本类是 {@code new} 出来的，不走反射）。</p>
 *
 * <h2>它自己不做判断</h2>
 * <p>文案由 {@link ParamTargetText}（纯静态、可无头单测）拼；本类只负责"把字符串放进 Label"。
 * 这样"信息栏内容对不对"能在无 JavaFX 的 JUnit 里钉死，"长什么样"才需要看图。</p>
 *
 * <h2>为什么允许换行（wrapText）</h2>
 * <p>组名是用户可编辑的自由文本，en_US 下 {@code Group (...)} 还可能带上更长的混合提示；
 * 不换行就会被 {@code HBox} 压到 pref 之下并被省略号截断 —— 这正是本项目 {@code UiProbe}
 * 专门在抓的缺陷（见 CLAUDE.md「界面布局」）。故：{@code wrapText=true} + {@code hgrow=ALWAYS}，
 * 宽度不够时折成两行而不是被截断。</p>
 */
public final class ParamTargetBar extends HBox {

    private final Label label = new Label();

    /** 建一个信息栏，初值按"全局"渲染（保证它不是空行、不影响布局高度）。 */
    public ParamTargetBar() {
        super(8);
        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(8, 12, 6, 12));
        // 固定一行语义：不被父容器拉伸（BorderPane 会把 top 拉满宽度，但不该拉高）
        setMinHeight(Region.USE_PREF_SIZE);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(label, Priority.ALWAYS);
        getChildren().add(label);
        show(ParamScope.GLOBAL, null, false);
    }

    /**
     * 显示目标：`全局` / `组（X）` / `条目（Y）`；{@code groupMixed=true} 时追加"组内存在多种参数"。
     *
     * @param target          参数目标
     * @param itemDisplayName 条目显示名（{@code null} 时由 {@link ParamTargetText} 从条目解析）
     * @param groupMixed      组内生效参数是否混合（{@link ParamTargetText#isGroupMixed} 的判定结果）
     */
    public void show(ParamScope.Target target, String itemDisplayName, boolean groupMixed) {
        label.setText(ParamTargetText.forTarget(target, itemDisplayName, groupMixed));
    }

    /**
     * 当前呈现的纯文本（供测试 / 探针断言用）。
     *
     * <p>刻意只暴露"呈现出来的那一行字"而不是 {@code Label} 本身：断言应当针对用户看得见的内容，
     * 而不是控件内部结构（内部结构一变，断言就该失效的测试是负担）。</p>
     */
    public String currentText() {
        return label.getText();
    }
}
