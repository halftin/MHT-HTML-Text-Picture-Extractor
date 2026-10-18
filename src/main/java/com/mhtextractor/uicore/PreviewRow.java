package com.mhtextractor.uicore;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.ChangeListener;
import com.mhtextractor.model.ExtractOutcome;
import com.mhtextractor.model.ThumbnailData;
import com.mhtextractor.uicore.ParamTargetView;
import com.mhtextractor.uiport.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;

/** Stable-ID display row. These values are replaced from backend VMs, never used as a business model. */
public final class PreviewRow implements ParamTargetView {
    public enum Status{WAITING,READY,EMPTY,FAILED}
    public final String path,name,sourceFolder;
    public final LocalDateTime modifiedTime;
    public Status status=Status.WAITING;
    public String errorKey,errorDetail,badge,cachedText,cachedTitle,titleOverride;
    public String extractMessageKey, extractMessageDetail;
    public java.util.Map<String, String> extractMessageParams;
    public java.util.List<com.mhtextractor.model.ExtractWarning> extractWarnings = java.util.List.of();

    public boolean chapterEnabled,selected,removed;
    public ExtractOutcome extractOutcome=ExtractOutcome.NONE;
    public GroupRow<PreviewRow> parentGroup;
    private ParameterStateVm.TargetState textState,imageState;
    public List<ThumbnailData> cachedThumbs;
    public List<PortEvent.ImageFile> cachedImages;
    public PresentationVm cachedMeta;
    public boolean previewCached;
    public String previewHead="",previewTail="",displayName,finalFileName;
    private final Consumer<Boolean> check;
    private final PreviewRetention retention;
    private final List<ChangeListener> checkedListeners=new ArrayList<>();
    public PreviewRow(ItemVm vm,Consumer<Boolean> check){
        this(vm,check,new PreviewRetention());
    }
    PreviewRow(ItemVm vm,Consumer<Boolean> check,PreviewRetention retention){
        this.retention=retention;
        this.path=vm.id();name=vm.name();sourceFolder=vm.sourceFolder();
        modifiedTime=vm.modifiedTime()==null?null:LocalDateTime.parse(vm.modifiedTime());this.check=check;update(vm);
    }
    void update(ItemVm vm){
        boolean changed=selected!=vm.checked();selected=vm.checked();removed=vm.removed();
        status=vm.status()==null?Status.WAITING:Status.valueOf(vm.status());errorKey=vm.errorKey();errorDetail=vm.errorDetail();
        chapterEnabled=Boolean.TRUE.equals(vm.chapterEnabled());badge=vm.badge();cachedTitle=vm.cachedTitle();titleOverride=vm.titleOverride();
        boolean hadPreview=previewCached;previewCached=vm.previewManifest()!=null;
        if(hadPreview&&!previewCached){releaseImageData();cachedMeta=null;}
        extractOutcome=vm.extractOutcome()==null?ExtractOutcome.NONE:ExtractOutcome.valueOf(vm.extractOutcome());extractMessageKey=vm.extractMessageKey();extractMessageParams=vm.extractMessageParams();
        extractMessageDetail=vm.extractMessageDetail();extractWarnings=vm.extractWarnings();
        if(changed)for(var listener:List.copyOf(checkedListeners))listener.onValueChanged();
    }
    public TargetId id(){return TargetId.ofItem(path);}
    public String getName(){return name;}
    public AppConfig getTextParamsCache(){return parentGroup==null&&textState!=null&&textState.independent()?textState.config():null;}
    public AppConfig getImageParamsCache(){return parentGroup==null&&imageState!=null&&imageState.independent()?imageState.config():null;}
    void parameters(boolean image,ParameterStateVm.TargetState state){if(image)imageState=state;else textState=state;}
    public void addSelectedChangeListener(ChangeListener l){checkedListeners.add(l);}
    public void removeSelectedChangeListener(ChangeListener l){checkedListeners.remove(l);}
    public void setSelected(boolean value){check.accept(value);}
    public void retainImageData(){retention.retain(this);}
    public void releaseImageData(){retention.forget(this);cachedThumbs=null;cachedImages=null;}
    public void resetPreviewState(){releaseImageData();cachedMeta=null;cachedText=null;}
}
