package com.mhtextractor.gui.component;

import com.mhtextractor.app.Texts;
import com.mhtextractor.uiport.PortEvent;
import javafx.scene.Node;
import javafx.scene.control.TextArea;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Single frontend consumer for cache toasts and remote diagnostics. Called on the UI thread. */
public final class BackendEventPresenter {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private BackendEventPresenter() { }

    public static void accept(PortEvent event, Node anchor, TextArea logs) {
        if (event instanceof PortEvent.CacheSizeAlert alert) {
            var params = new LinkedHashMap<>(alert.namedParams());
            params.put("dir", alert.root());
            params.put("step", Long.toString(alert.step() * alert.stepBytes() / (1024L * 1024 * 1024)));
            params.put("actual", String.format(Locale.ROOT, "%.1f", alert.actualBytes() / (1024.0 * 1024 * 1024)));
            GuiNotifications.cacheStepWarning(anchor, render(alert.key(), params) + Texts.get("cache.sizeAlertNote"));
        } else if (event instanceof PortEvent.BackendLog entry) {
            if (!entry.level().equals("WARN") && !entry.level().equals("ERROR")) return;
            logs.appendText(TIME.format(Instant.ofEpochMilli(entry.timestamp())) + " " + entry.level()
                    + " " + render(entry.key(), entry.namedParams()) + "\n");
            logs.setScrollTop(Double.MAX_VALUE);
        }
    }

    private static String render(String key, Map<String, String> params) {
        var resolved = new LinkedHashMap<String, String>();
        params.forEach((name,value) -> { if (!name.startsWith("$")) resolved.put(name,value); });
        params.forEach((name,value) -> {
            if (!name.startsWith("$key.")) return;
            String argument = name.substring(5), prefix = "$arg." + argument + ".";
            var nested = new LinkedHashMap<String, String>();
            params.forEach((n,v) -> { if (n.startsWith(prefix)) nested.put(n.substring(prefix.length()),v); });
            resolved.put(argument, render(value, nested));
        });
        Object[] pairs = new Object[resolved.size() * 2];
        int index = 0;
        for (var param : resolved.entrySet()) { pairs[index++] = param.getKey(); pairs[index++] = param.getValue(); }
        return Texts.get(key, pairs);
    }
}
