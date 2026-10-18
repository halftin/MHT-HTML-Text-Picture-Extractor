package com.mhtextractor.uicore;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.ChangeListener;
import com.mhtextractor.uicore.ParamTargetView;
import com.mhtextractor.uiport.*;
import java.util.*;
/** Group identity, order and confirmation are read-only backend projections. */
public final class GroupRow<T> implements ParamTargetView {
    private final int no;
    private String name;
    private final java.util.function.LongFunction<String> defaultName;
    private boolean expanded,defaultNamed;
    private List<T> members=List.of();
    private ParameterStateVm.TargetState text,image;
    private String editingName;
    private final List<ChangeListener> names=new ArrayList<>();
    public GroupRow(GroupVm vm){this(vm, no -> null);}
    public GroupRow(GroupVm vm, java.util.function.LongFunction<String> defaultName){this.defaultName=defaultName;no=Math.toIntExact(vm.no());update(vm,List.of());}
    void update(GroupVm vm,List<T> members){
        boolean changed=!Objects.equals(name,vm.name());name=vm.name();editingName=null;expanded=vm.expanded();defaultNamed=vm.defaultNamed();this.members=List.copyOf(members);
        if(changed)for(var listener:List.copyOf(names))listener.onValueChanged();
    }
    void parameters(boolean mode,ParameterStateVm.TargetState state){if(mode)image=state;else text=state;}
    public TargetId id(){return TargetId.ofGroup(no);}
    public String getName(){return editingName!=null?editingName:defaultNamed&&name==null?defaultName.apply(no):name;}
    public void editingName(String value){editingName=value;}
    public int getGroupNo(){return no;}
    public boolean isDefaultNamed(){return defaultNamed;}
    public boolean isExpanded(){return expanded;}
    public List<T> getMembers(){return members;}
    public AppConfig getTextParamsCache(){return text!=null&&text.independent()?text.config():null;}
    public AppConfig getImageParamsCache(){return image!=null&&image.independent()?image.config():null;}
    public void addNameChangeListener(ChangeListener listener){names.add(listener);}
}
