package com.mhtextractor.uicore;

import com.mhtextractor.uiport.ParamSchemaVm;
import com.mhtextractor.uiport.ParamSchemaVm.Field;
import java.util.LinkedHashMap;
import java.util.Map;

/** 仅依赖 schema 的控件联动；参数收集规则由后端持有。 */
public final class ParamSchemaRules {
    private ParamSchemaRules() { }

    /** 一个开关的联动：返回需要禁用并复位的字段值；启用时保留原输入。 */
    public static Map<String, Object> disabledValues(ParamSchemaVm schema, Map<String, Object> values,
                                                      String controller) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Field f : schema.fields()) {
            if (controller.equals(f.enabledBy()) && !Boolean.TRUE.equals(values.get(controller)))
                result.put(f.id(), f.disabledValue());
        }
        return result;
    }

}
