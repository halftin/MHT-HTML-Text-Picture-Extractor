import com.mhtextractor.model.AppConfig;
import com.mhtextractor.gui.component.ParamTargetBar;
import com.mhtextractor.gui.controller.ParamFormPane;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.model.GroupItem;
import com.mhtextractor.uicore.ParamScope;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Q5b probe: R1.4 target info bar + R1.5 draft state machine, driven through the REAL pane.
 *
 * Why a probe and not a unit test: isDirty()/commitDraft() live on ParamFormPane (an HBox/BorderPane
 * subtree - a JavaFX object), so "only confirm commits" and "the bar is really installed at the top"
 * cannot be asserted headlessly. The unit tests cover the pure parts (app.ParamDraft,
 * ParamTargetText); this probe covers the WIRING in the real container.
 *
 * It also deliberately does NOT touch FeatureExercise / the 31 GUI assertions, so the GUI automation
 * baseline (zh 31/0, en 31/0) stays comparable.
 *
 * Expectations are hardcoded per locale on purpose: reading them back out of the bundle would just
 * re-run the production expression and prove nothing.
 *
 * Usage: powershell -NoProfile -ExecutionPolicy Bypass -File tools\probes\run-probe.ps1 ParamDraftProbe -Locale zh_CN
 * Exit code: 0 = all checks passed, 1 = at least one failed.
 */
public class ParamDraftProbe extends Application {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("[PASS] " + name);
        } else {
            failures.add(name);
            System.out.println("[FAIL] " + name + "  " + detail);
        }
    }

    @Override
    public void start(Stage stage) {
        Locale loc = I18n.parseLocale(System.getProperty("probe.locale", "zh_CN"));
        I18n.setLocale(loc);
        boolean zh = "zh".equals(I18n.currentLocale().getLanguage());
        System.out.println("=== ParamDraftProbe locale=" + I18n.currentLocale() + " ===");

        var frontend = ProbeRuntime.port();
        ParamFormPane pane = new ParamFormPane(frontend);
        Scene scene = new Scene(pane, 640, 620);
        stage.setScene(scene);
        stage.setTitle("ParamDraftProbe");
        stage.show();
        pane.applyCss();
        pane.layout();

        // ---------- 0) the bar is installed as BorderPane.top, above the parameter area ----------
        Node top = pane.getTop();
        check("top-is-target-bar", top instanceof ParamTargetBar,
                "pane.getTop() = " + (top == null ? "null" : top.getClass().getName()));
        if (!(top instanceof ParamTargetBar bar)) {
            report();
            Platform.exit();
            return;
        }
        check("bar-has-height", top.getBoundsInLocal().getHeight() > 10,
                "bar height = " + top.getBoundsInLocal().getHeight());
        Node center = ((BorderPane) pane).getCenter();
        double barBottom = top.localToScene(top.getBoundsInLocal()).getMaxY();
        double areaTop = center.localToScene(center.getBoundsInLocal()).getMinY();
        check("bar-is-above-parameter-area", barBottom <= areaTop + 0.5,
                "barBottom=" + barBottom + " areaTop=" + areaTop);

        // ---------- 1) R1.4: global target ----------
        String expectedGlobal = zh ? "全局" : "Global";
        String expectedGroup = zh ? "组（组1）" : "Group (组1)";
        String expectedItem = zh ? "条目（b.mht）" : "Item (b.mht)";
        String expectedMixed = expectedGroup
                + (zh ? " · 组内存在多种参数" : " · multiple parameter sets in this group");

        pane.load(null, null, false, ParamScope.GLOBAL);
        check("bar-global", expectedGlobal.equals(bar.currentText()),
                "bar=\"" + bar.currentText() + "\" expected=\"" + expectedGlobal + "\"");

        // ---------- 2) R1.5: clean right after load, dirty after an edit, query does not consume ----------
        check("clean-after-load", !pane.isDirty(), "isDirty() should be false right after load()");
        toggle(pane, "separateFilesCheck", true);
        check("dirty-after-edit", pane.isDirty(), "isDirty() should be true after flipping separateFiles");
        check("dirty-query-is-idempotent", pane.isDirty() && pane.isDirty(),
                "asking twice must not clear the draft (collapse = keep draft)");

        // ---------- 3) R1.9: only confirm commits ----------
        fire(pane, "cancelButton");
        check("cancel-keeps-draft", pane.isDirty(), "cancel must NOT commit (draft stays dirty)");
        fire(pane, "confirmButton");
        check("confirm-commits", !pane.isDirty(), "confirm must commit (draft no longer dirty)");

        // ---------- 4) R1.4: group target, mixed members -> hint appended ----------
        var backend = frontend.implementation();
        var paths = List.of("H:/TEST/mht/probe/a.mht", "H:/TEST/mht/probe/b.mht").stream()
                .map(path -> java.nio.file.Path.of(path).toAbsolutePath().toString()).toList();
        backend.commands().applyImportPlan(new com.mhtextractor.uiport.ImportPlanVm(
                List.of(new com.mhtextractor.uiport.ImportPlanVm.ImportBatchVm("H:/TEST/mht/probe", null,
                        false, paths)), 0, 0, 0, 0, 0, false, false));
        backend.commands().group(paths, "组"); // Port accepts a prefix and appends the group number.
        AppConfig itemParams = new AppConfig(); itemParams.setStartMarker("ITEM_ONLY");
        backend.commands().setItemParams(paths.getFirst(), false, itemParams);
        ProbeFx.awaitCommands(frontend);
        var workspace = new com.mhtextractor.uicore.WorkspaceView(frontend,
                no -> com.mhtextractor.i18n.I18n.get("core.groupDefault") + no);
        var group = workspace.groups().getFirst();
        var b = workspace.allItems().get(1);

        pane.load(null, null, false, new ParamScope.GroupTarget(group));
        check("bar-group-mixed", expectedMixed.equals(bar.currentText()),
                "bar=\"" + bar.currentText() + "\" expected=\"" + expectedMixed + "\"");
        check("clean-after-group-load", !pane.isDirty(), "load() must re-baseline (clean)");

        // ---------- 5) R1.4: once the group has its own params, members are uniform -> no hint ----------
        backend.commands().setGroupParams(group.getGroupNo(), false, new AppConfig());
        ProbeFx.awaitCommands(frontend); workspace.update(frontend.snapshot());
        pane.load(null, null, false, new ParamScope.GroupTarget(group));
        check("bar-group-uniform", expectedGroup.equals(bar.currentText()),
                "bar=\"" + bar.currentText() + "\" expected=\"" + expectedGroup + "\"");

        // ---------- 6) R1.4: item target uses the item's display name ----------
        pane.load(null, null, false, new ParamScope.ItemTarget<>(b, null, null));
        check("bar-item", expectedItem.equals(bar.currentText()),
                "bar=\"" + bar.currentText() + "\" expected=\"" + expectedItem + "\"");

        // ---------- 7) load() always re-baselines (no false "dirty" right after opening) ----------
        check("clean-after-item-load", !pane.isDirty(), "load() must re-baseline (clean) even for item target");

        report();
        int code = failures.isEmpty() ? 0 : 1;
        Platform.exit();
        System.exit(code);
    }

    private static void toggle(ParamFormPane pane, String id, boolean selected) {
        Node n = pane.lookup("#" + id);
        if (n instanceof atlantafx.base.controls.ToggleSwitch ts) {
            ts.setSelected(selected);
        } else {
            failures.add("lookup:" + id);
            System.out.println("[FAIL] lookup #" + id + " -> " + (n == null ? "null" : n.getClass().getName()));
        }
    }

    private static void fire(ParamFormPane pane, String id) {
        Node n = pane.lookup("#" + id);
        if (n instanceof Button btn) {
            btn.fire();
        } else {
            failures.add("lookup:" + id);
            System.out.println("[FAIL] lookup #" + id + " -> " + (n == null ? "null" : n.getClass().getName()));
        }
    }

    private static void report() {
        System.out.println("RESULTS: " + passed + " passed, " + failures.size() + " failed");
        if (!failures.isEmpty()) {
            System.out.println("FAILED CHECKS: " + failures);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
