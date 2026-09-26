package com.mhtextractor.util;

import com.mhtextractor.i18n.I18n;

import java.util.List;

/**
 * HTML 内容提取工具类
 */
public class HtmlUtils {

    /**
     * 基于起止标记的 indexOf 方式截取 HTML 区域
     *
     * @param html         原始 HTML
     * @param startMarker  起始标记
     * @param endMarker    结束标记
     * @param warnings     警告收集列表（可为 null）
     * @return 截取后的内容；起始标记未找到返回 null，结束标记未找到返回起始标记后的全部内容
     */
    public static String extractRegion(String html, String startMarker, String endMarker,
                                       List<String> warnings) {
        if (html == null || startMarker == null || endMarker == null) return html;

        int startIdx = html.indexOf(startMarker);
        if (startIdx == -1) {
            if (warnings != null) {
                warnings.add(I18n.get("core.startMarkerNotFound", "marker", startMarker));
            }
            return null;
        }
        int contentStart = startIdx + startMarker.length();
        int endIdx = html.indexOf(endMarker, contentStart);
        if (endIdx == -1) {
            if (warnings != null) {
                warnings.add(I18n.get("core.endMarkerNotFound", "marker", endMarker));
            }
            return html.substring(contentStart);
        }
        return html.substring(contentStart, endIdx);
    }

}
