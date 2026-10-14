package com.mhtextractor.uicore;
import com.mhtextractor.model.AppConfig;
/** Local user clipboard only. Batch writes and undo ownership belong to C29. */
public final class ParamClipboard {
    private AppConfig params;
    public boolean isEmpty(){return params==null;}
    public void copy(AppConfig value){params=value==null?null:value.copyExtractionParams();}
    public AppConfig snapshot(){return params==null?null:params.copyExtractionParams();}
    public void clear(){params=null;}
}
