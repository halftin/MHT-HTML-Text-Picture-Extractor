package com.mhtextractor.backend;

import com.mhtextractor.app.InProcessUiport;
import com.mhtextractor.app.WorkspaceModel;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.uiport.Uiport;

import java.time.LocalDateTime;
import java.util.List;

/** Test-only assembly: populated workspace through public model APIs, same production wire server. */
public final class BackendIpcFixture {
    static final String ROOT = "H:/TEST/mht/p4-2a-fixture/";
    static final List<String> NAMES = List.of("a.mht", "中文.mht", "quote\"line\n.mht", "removed.mht", "tail.mht");

    static Uiport port() {
        WorkspaceModel model = new WorkspaceModel();
        var items = NAMES.stream().map(name -> new PreviewItem(ROOT + name, name,
                LocalDateTime.of(2026, 10, 7, 0, 0), ROOT)).toList();
        model.addItems(items);
        var group = model.group(items.subList(0, 2), "fixture");
        model.renameGroup(group.getGroupNo(), "group \"中文\"\n");
        model.removeItems(List.of(items.get(3)));
        return new InProcessUiport(model, request -> {
            throw new AssertionError("setChecked must not request confirmation");
        });
    }

    public static void main(String[] arguments) throws Exception {
        new IpcServer(port()).run(System.in, System.out);
    }
}
