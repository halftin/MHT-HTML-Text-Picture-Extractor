package com.mhtextractor.gui;
import com.mhtextractor.app.*;
import com.mhtextractor.model.*;
import com.mhtextractor.uicore.*;
import javafx.application.Platform;
import javafx.scene.control.ListView;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independently compares backend order with stable frontend VM identities. */
class WorkspaceFxAdapterTest {
    private static PreviewItem item(String n){return new PreviewItem("H:/TEST/mht/adapter/"+n,n,LocalDateTime.now(),"H:/TEST/mht/adapter");}
    private static final class StubHost implements WorkspaceFxAdapter.SelectionHost {
        Object selected;public Object selectedItem(){return selected;}public void select(Object i){selected=i;}
    }
    private static final class Fixture implements AutoCloseable {
        final WorkspaceModel model=new WorkspaceModel();
        final InProcessUiport backend=new InProcessUiport(model,request->null);
        final FrontendPort port=new FrontendPort(backend,Runnable::run,ignored->{});
        final WorkspaceView view;final WorkspaceFxAdapter adapter;
        Fixture(){this(null);}
        Fixture(StubHost host){var r=new AppConfig();r.setInputFolder("H:/TEST/mht/adapter");r.setOutputFolder("H:/TEST/mht/adapter-output");
            port.bootstrap(r);view=new WorkspaceView(port, no -> "Group " + no);adapter=new WorkspaceFxAdapter(view,host);}
        void sync(){var done=new CompletableFuture<Void>();port.execute(p->null,ignored->done.complete(null));done.orTimeout(10,TimeUnit.SECONDS).join();}
        PreviewRow row(PreviewItem i){return view.allItems().stream().filter(v->v.path.equals(i.path)).findFirst().orElseThrow();}
        void refresh(){sync();adapter.refresh();}
        public void close(){adapter.dispose();port.close();}
    }
    private static String id(Object v){return switch(v){case PreviewItem i->i.path;case GroupItem<?> g->"g:"+g.getGroupNo();
        case PreviewRow i->i.path;case GroupRow<?> g->"g:"+g.getGroupNo();default->throw new AssertionError(v);};}
    private static void same(Fixture f){assertEquals(f.model.displayOrder().stream().map(WorkspaceFxAdapterTest::id).toList(),
        f.adapter.displayItems().stream().map(WorkspaceFxAdapterTest::id).toList());}
    @Test void projectionFollowsModelAfterAddRemoveGroupUngroupReorderAndUndo(){try(var f=new Fixture()){
        var a=item("a");var b=item("b");var c=item("c");f.model.addItems(List.of(a,b,c));f.sync();
        assertTrue(f.adapter.isStale());f.adapter.refresh();assertFalse(f.adapter.isStale());same(f);var stable=f.row(a);
        var g=f.model.group(List.of(a,b));f.refresh();same(f);assertTrue(f.adapter.displayItems().stream().anyMatch(o->id(o).equals("g:"+g.getGroupNo())));
        g.setExpanded(false);f.refresh();same(f);assertFalse(f.adapter.displayItems().contains(stable));
        g.setExpanded(true);f.model.ungroup(g);f.refresh();same(f);
        f.model.applyResolvedDrop(DropResolver.resolve(f.model.topLevel(),c,a,DropResolver.Zone.TOP));f.refresh();same(f);
        f.model.removeItems(List.of(b));f.refresh();same(f);assertFalse(f.adapter.displayItems().contains(f.row(b)));
        f.model.undo();f.refresh();same(f);assertTrue(f.adapter.displayItems().contains(f.row(b)));assertSame(stable,f.row(a));
    }}
    @Test void dataOrderIsLiveReadOnlyProjectionAndClearForwardsToModel(){try(var f=new Fixture()){
        var a=item("a");var b=item("b");f.model.addItems(List.of(a,b));f.refresh();var order=f.adapter.dataOrder();assertEquals(2,order.size());
        assertThrows(UnsupportedOperationException.class,()->order.add(f.row(a)));assertThrows(UnsupportedOperationException.class,()->order.remove(0));
        f.model.group(List.of(a,b));f.refresh();assertEquals(1,order.size());int depth=f.model.undoDepth();order.clear();f.sync();
        assertTrue(f.model.topLevel().isEmpty());assertTrue(order.isEmpty());assertEquals(depth,f.model.undoDepth());
    }}
    @Test void refreshRestoresSelectionOnlyWhenStillVisible(){var h=new StubHost();try(var f=new Fixture(h)){
        var a=item("a");var b=item("b");f.model.addItems(List.of(a,b));f.refresh();h.selected=f.row(a);
        f.model.group(List.of(a,b));f.refresh();assertSame(f.row(a),h.selected);f.model.removeItems(List.of(a));f.sync();h.selected=f.row(a);
        f.adapter.refresh();assertFalse(f.adapter.displayItems().contains(h.selected));
    }}
    @Test void projectionWorksWithoutSelectionHost(){try(var f=new Fixture()){
        f.model.addItems(List.of(item("a"),item("b")));f.refresh();same(f);assertEquals(2,f.adapter.displayItems().size());
    }}
    @Test void hideRowOnlyAffectsProjectionNotModel(){try(var f=new Fixture()){
        var a=item("a");f.model.addItems(List.of(a));f.refresh();f.adapter.hideRow(f.row(a));assertTrue(f.adapter.displayItems().isEmpty());
        assertEquals(1,f.model.visibleCount());f.adapter.project();same(f);
    }}
    @Test void disposeStopsSubscribing(){try(var f=new Fixture()){
        f.model.addItems(List.of(item("a")));f.refresh();f.adapter.dispose();f.model.addItems(List.of(item("b")));f.sync();
        assertFalse(f.adapter.isStale());assertEquals(1,f.adapter.dataOrder().size());
    }}
    @Test void listViewShowsExactlyTheModelProjection() throws Exception {
        try{Platform.startup(()->{});}catch(IllegalStateException alreadyStarted){}
        var done=new CompletableFuture<Void>();Platform.runLater(()->{try(var f=new Fixture()){
            var a=item("a");var b=item("b");f.model.addItems(List.of(a,b));f.sync();var list=new ListView<Object>();
            f.adapter.bindListView(list);f.adapter.refresh();same(f);var row=f.row(a);list.getSelectionModel().select(row);
            f.model.group(List.of(a,b));f.sync();f.adapter.refresh();same(f);assertSame(row,list.getSelectionModel().getSelectedItem());
            assertSame(f.adapter.displayItems(),list.getItems());assertThrows(UnsupportedOperationException.class,()->f.adapter.dataOrder().add(row));done.complete(null);
        }catch(Throwable error){done.completeExceptionally(error);}});done.get(15,TimeUnit.SECONDS);
    }
}
