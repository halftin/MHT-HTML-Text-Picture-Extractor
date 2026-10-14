package com.mhtextractor.uicore;
import com.mhtextractor.uicore.FrontendPort;
import com.mhtextractor.uicore.PortSnapshot;
import com.mhtextractor.uiport.*;
import java.util.*;
/** Materializes display identities from a complete VM snapshot. No domain objects or undo stack. */
public final class WorkspaceView {
    private final FrontendPort port;
    private final Map<String,PreviewRow> items=new LinkedHashMap<>();
    private final Map<Long,GroupRow<PreviewRow>> groups=new LinkedHashMap<>();
    private List<Object> order=List.of();
    private long version;
    private final AutoCloseable subscription;
    public WorkspaceView(FrontendPort port){this.port=port;update(port.snapshot());subscription=port.events().subscribe(ignored->update(port.snapshot()));}
    public void update(PortSnapshot state){
        var vm=state.workspace();
        var active=new HashSet<String>();var topActive=new HashSet<String>();
        var current=new LinkedHashMap<String,ItemVm>();
        for(var g:vm.groups())for(var i:g.members())current.merge(i.id(),i,(old,next)->old.removed()&&!next.removed()?next:old);
        for(var i:vm.topLevelItems()){current.merge(i.id(),i,(old,next)->old.removed()&&!next.removed()?next:old);if(!i.removed())topActive.add(i.id());}
        current.values().forEach(i->{item(i);if(!i.removed())active.add(i.id());});
        for(var row:items.values())row.parentGroup=null;
        for(var g:vm.groups()){
            var row=groups.computeIfAbsent(g.no(),ignored->new GroupRow<>(g));
            var members=g.members().stream().filter(i->!i.removed()||!active.contains(i.id())).map(i->items.get(i.id())).toList();row.update(g,members);
            for(var member:members)member.parentGroup=row;
        }
        for(var mode:List.of(false,true))for(var target:state.parameters(mode).states()){
            if(target.target().groupNo()!=null)groups.get(target.target().groupNo()).parameters(mode,target);
            else items.get(target.target().path()).parameters(mode,target);
        }
        for(var entry:state.presentation()){
            var row=items.get(entry.path());row.cachedText=state.textBodies().get(entry.path());
            row.previewHead=entry.head();row.previewTail=entry.tail();row.displayName=entry.displayName();
            row.cachedMeta=row.previewCached?entry:null;row.cachedImages=entry.images();
        }
        var last=new HashMap<TargetId,Integer>();
        for(int index=0;index<vm.topLevelOrder().size();index++)last.put(vm.topLevelOrder().get(index),index);
        order=java.util.stream.IntStream.range(0,vm.topLevelOrder().size()).filter(index->{
            var id=vm.topLevelOrder().get(index);return last.get(id)==index
                    &&(id.groupNo()!=null||!active.contains(id.path())||topActive.contains(id.path()));
        }).mapToObj(index->{var id=vm.topLevelOrder().get(index);return id.groupNo()==null?(Object)items.get(id.path()):groups.get(id.groupNo());}).toList();
        version++;
    }
    private PreviewRow item(ItemVm vm){var row=items.computeIfAbsent(vm.id(),id->new PreviewRow(vm,value->port.commands().setChecked(List.of(id),value)));row.update(vm);return row;}
    public List<Object> topLevel(){return order;}
    public List<Object> topLevelView(){return order;}
    public List<PreviewRow> allItems(){return order.stream().flatMap(o->o instanceof GroupRow<?> g?g.getMembers().stream().map(PreviewRow.class::cast):java.util.stream.Stream.of((PreviewRow)o)).toList();}
    public List<GroupRow<PreviewRow>> groups(){return order.stream().filter(GroupRow.class::isInstance).map(o->asPreviewGroup((GroupRow<?>)o)).toList();}
    public List<Object> displayOrder(){var list=new ArrayList<Object>();for(var o:order){if(o instanceof GroupRow<?> g){list.add(g);if(g.isExpanded())for(var member:g.getMembers())if(!((PreviewRow)member).removed)list.add(member);}else if(!((PreviewRow)o).removed)list.add(o);}return List.copyOf(list);}
    public long visibleCount(){return allItems().stream().filter(i->!i.removed).count();}
    public boolean canUndo(){return port.queries().workspace().canUndo();}
    public long version(){return version;}
    public PortSnapshot snapshot(){return port.snapshot();}
    public void dispose(){try{subscription.close();}catch(Exception error){throw new IllegalStateException(error);}}
    public void clearItems(){port.commands().clearItems();}
    @SuppressWarnings("unchecked") public static GroupRow<PreviewRow> asPreviewGroup(GroupRow<?> group){return (GroupRow<PreviewRow>)group;}
}
