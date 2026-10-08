package com.mhtextractor.extractor;

/** Shared preview/extraction decision; does not change resource ordering. */
public record ImageRegion(Rule rule, String html) {
    public enum Rule { R1, R2, R3, R4 }

    public String warningKey() {
        return rule == Rule.R1 ? null : "extract.region." + rule.name().toLowerCase(java.util.Locale.ROOT);
    }

    public static ImageRegion resolve(String html, String start, String end) {
        // AppConfig defaults are null (forms also use ""). Both mean unset, as does whitespace.
        // Valid markers must occur in HTML. Use the first start and first end AFTER that
        // complete start marker. An end only before the start invalidates BOTH (R2).
        int s = start == null || start.isBlank() ? -1 : html.indexOf(start);
        int firstEnd = end == null || end.isBlank() ? -1 : html.indexOf(end);
        int e = s >= 0 && firstEnd >= 0 ? html.indexOf(end, s + start.length()) : firstEnd;
        if (s >= 0 && firstEnd >= 0 && e < 0) return new ImageRegion(Rule.R2, html);
        if (s >= 0 && e >= 0) return new ImageRegion(Rule.R1, html.substring(s + start.length(), e));
        if (s < 0 && e < 0) return new ImageRegion(Rule.R2, html);
        if (s >= 0) return new ImageRegion(Rule.R3, html.substring(s + start.length()));
        return new ImageRegion(Rule.R4, html.substring(0, e));
    }
}
