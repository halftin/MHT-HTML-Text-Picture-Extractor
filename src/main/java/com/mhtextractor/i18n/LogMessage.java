package com.mhtextractor.i18n;

import java.util.LinkedHashMap;
import java.util.Map;

/** One synchronous log call's original key/arguments. No rendered text crosses the IPC boundary. */
public record LogMessage(String key, Map<String, String> namedParams, String rendered) {
    private static final ThreadLocal<LogMessage> CURRENT = new ThreadLocal<>();

    static String remember(String key, Object[] args, String rendered) {
        var nested = CURRENT.get();
        var params = new LinkedHashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            String name = Integer.toString(i);
            if (nested != null && args[i] == nested.rendered) {
                params.put(name, "");
                params.put("$key." + name, nested.key);
                nested.namedParams.forEach((k,v) -> params.put("$arg." + name + "." + k, v));
            } else params.put(name, String.valueOf(args[i]));
        }
        CURRENT.set(new LogMessage(key, Map.copyOf(params), rendered));
        return rendered;
    }

    static String rememberNamed(String key, Map<String, String> params, String rendered) {
        CURRENT.set(new LogMessage(key, Map.copyOf(params), rendered));
        return rendered;
    }

    /** Identity prevents an unrelated equal string from reusing a stale key. Consume on all levels. */
    public static LogMessage take(String message) {
        var current = CURRENT.get();
        CURRENT.remove();
        return current != null && current.rendered == message ? current : null;
    }
}
