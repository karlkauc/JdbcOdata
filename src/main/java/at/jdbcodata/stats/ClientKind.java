package at.jdbcodata.stats;

/** Client classification from the User-Agent header. Power Query hides whether it runs in Excel or PowerBI. */
public enum ClientKind {
    EXCEL, POWERBI, POWER_QUERY, BROWSER, OTHER;

    public static ClientKind detect(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return OTHER;
        String ua = userAgent.toLowerCase();
        if (ua.contains("powerbi") || ua.contains("power bi") || ua.contains("pbi")) return POWERBI;
        if (ua.contains("excel")) return EXCEL;
        if (ua.contains("mashup") || ua.contains("power query")) return POWER_QUERY;
        if (ua.contains("mozilla") || ua.contains("chrome") || ua.contains("safari")) return BROWSER;
        return OTHER;
    }
}
