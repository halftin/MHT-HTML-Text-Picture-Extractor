package com.mhtextractor.uicore;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.uiport.TargetId;
/** Display projection access used by parameter draft targets. */
public interface ParamTargetView {
    TargetId id();
    String getName();
    default int getGroupNo(){return Math.toIntExact(id().groupNo());}
    AppConfig getTextParamsCache();
    AppConfig getImageParamsCache();
}
