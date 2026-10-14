package com.mhtextractor.uicore;
import com.mhtextractor.model.AppConfig;
/** Parameter editor identity and initial values; all confirmed values are query snapshots. */
public final class ParamScope {
    private ParamScope(){}
    public enum Status {NONE,GLOBAL,GROUP}
    public sealed interface Target permits GlobalTarget,GroupTarget,ItemTarget {}
    public record GlobalTarget() implements Target {}
    public record GroupTarget(ParamTargetView group) implements Target {}
    public record ItemTarget<T>(T item,AppConfig textParams,AppConfig imageParams) implements Target {}
    public static final GlobalTarget GLOBAL=new GlobalTarget();
    public static AppConfig initialParams(Target target,boolean image,AppConfig textGlobal,AppConfig imageGlobal){
        AppConfig own=switch(target){
            case GroupTarget g->image?g.group().getImageParamsCache():g.group().getTextParamsCache();
            case ItemTarget<?> i->image?i.imageParams():i.textParams();
            case GlobalTarget ignored->image?imageGlobal:textGlobal;
        };
        return own!=null?own:image?imageGlobal:textGlobal;
    }
}
