package com.mhtextractor.uiport;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3-2 端口最小切片的纯 DTO 无头单测：防御性不可变拷贝、sealed 层级、枚举常量、门面形态。
 *
 * <p>本测试只针对 {@code com.mhtextractor.uiport} 自身的 view model 纪律，不触任何生产实现。</p>
 */
class UiportPortSliceTest {

    @Test
    void groupVmDefensivelyCopiesMembers() {
        List<ItemVm> members = new ArrayList<>();
        members.add(new ItemVm("a", "a.mht", false, false, 1, UpdateKind.NONE));
        GroupVm group = new GroupVm(1, "组1", true, false, members);

        // 构造后再改原 list 不影响快照
        members.add(new ItemVm("b", "b.mht", false, false, 2, UpdateKind.APPEARED));
        assertEquals(1, group.members().size());
        assertEquals("a.mht", group.members().get(0).name());

        // 拷贝不可变
        assertThrows(UnsupportedOperationException.class, () -> group.members().add(null));
    }

    @Test
    void workspaceVmDefensivelyCopiesCollections() {
        List<GroupVm> groups = new ArrayList<>();
        groups.add(new GroupVm(1, "组1", true, false, List.of()));
        List<ItemVm> top = new ArrayList<>();
        top.add(new ItemVm("a", "a.mht", true, false, null, UpdateKind.NONE));
        WorkspaceVm vm = new WorkspaceVm(groups, top, true, 3, TriState.SOME);

        groups.clear();
        top.clear();
        assertEquals(1, vm.groups().size());
        assertEquals(1, vm.topLevelItems().size());
        assertThrows(UnsupportedOperationException.class, () -> vm.groups().clear());
        assertThrows(UnsupportedOperationException.class, () -> vm.topLevelItems().clear());
        assertTrue(vm.canUndo());
        assertEquals(3, vm.undoDepth());
        assertEquals(TriState.SOME, vm.selection());
    }

    @Test
    void confirmRequestAndNoticeDefensivelyCopyMapsAndLists() {
        Map<String, String> params = new HashMap<>();
        params.put("count", "5");
        List<String> actions = new ArrayList<>(List.of("ok", "cancel"));
        ConfirmRequest request = new ConfirmRequest("confirm.quota", params, actions, 30_000L);

        params.put("extra", "x");
        actions.add("x");
        assertEquals(1, request.namedParams().size());
        assertEquals(2, request.actionKeys().size());
        assertThrows(UnsupportedOperationException.class, () -> request.namedParams().put("k", "v"));
        assertThrows(UnsupportedOperationException.class, () -> request.actionKeys().add("x"));

        PortEvent.Notice notice = new PortEvent.Notice("log.x", Map.of("p", "v"), NoticeLevel.WARNING);
        assertThrows(UnsupportedOperationException.class, () -> notice.namedParams().put("k", "v"));
    }

    @Test
    void sealedHierarchyContainsWorkspaceAndActivityEventRecords() {
        Class<?>[] permitted = PortEvent.class.getPermittedSubclasses();
        assertEquals(12, permitted.length);
        assertTrue(PortEvent.WorkspaceChanged.class.isRecord());
        assertTrue(PortEvent.RunningChanged.class.isRecord());
        assertTrue(PortEvent.Notice.class.isRecord());
        for (Class<?> c : permitted) {
            assertTrue(c.isRecord());
            assertTrue(PortEvent.class.isAssignableFrom(c));
        }
    }

    @Test
    void enumsHaveTheRequiredConstants() {
        assertArrayEquals(
                new NoticeLevel[]{NoticeLevel.ERROR, NoticeLevel.WARNING, NoticeLevel.INFO},
                NoticeLevel.values());
        assertArrayEquals(
                new UpdateKind[]{UpdateKind.NONE, UpdateKind.APPEARED, UpdateKind.DISAPPEARED},
                UpdateKind.values());
        assertArrayEquals(
                new TriState[]{TriState.NONE, TriState.SOME, TriState.ALL},
                TriState.values());
    }

    @Test
    void facadeSurfaceHasTheFourChannels() {
        Uiport uiport = new Uiport() {
            @Override
            public WorkspaceCommands commands() {
                return new WorkspaceCommands() {
                    public void cancelWorkspaceActivity() {}
                    public void cancelPreviewLoad() {}
                    public void startPreparse() {}
                    public void startPreparseFor(List<String> paths) {}
                    public void refreshPreparseProgress() {}
                    public void loadPreview(String path, boolean confirmed) {}
                    public void awaitPreviewInFlight(long millis) {}
                    @Override
                    public void undo() {
                    }

                    @Override
                    public void clearItems() {
                    }
                };
            }

            @Override
            public WorkspaceQueries queries() {
                return new WorkspaceQueries() {
                    public long activityGeneration() { return 0; }
                    public long previewGeneration() { return 0; }
                    public String previewLoadingPath() { return null; }
                    public WorkspaceVm workspace() { return new WorkspaceVm(List.of(), List.of(), false, 0, TriState.NONE); }
                    public com.mhtextractor.model.AppConfig runtimeConfig() { throw new UnsupportedOperationException(); }
                    public List<OutputUnitVm> outputUnitPlan(boolean checkedOnly) { throw new UnsupportedOperationException(); }
                    public ParamSchemaVm paramSchema(boolean imageMode) { throw new UnsupportedOperationException(); }
                };
            }

            @Override
            public PortEvents events() {
                return listener -> () -> {
                };
            }

            @Override
            public Confirmer confirmer() {
                return request -> new ConfirmToken() {
                    @Override
                    public void answer(String actionKey) {
                    }

                    @Override
                    public void cancel() {
                    }
                };
            }
        };

        // 门面四通道可装配、可调用（P3-3a 收敛后命令切片只剩撤销 / 清场）
        assertEquals(TriState.NONE, uiport.queries().workspace().selection());
        assertEquals(0, uiport.queries().workspace().groups().size());
        uiport.commands().undo();
        uiport.commands().clearItems();
    }
}
