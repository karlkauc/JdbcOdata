package at.jdbcodata.usage;

import java.util.List;
import java.util.Locale;

/**
 * Sorts clients by their User-Agent. Excel and Power BI both send {@code Microsoft.Data.Mashup}, so they cannot be
 * told apart, and neither sends {@code Mozilla/}: the viewers' rule "no Mozilla means bot" would hide all real use.
 */
final class UsageClassifier {

    static final String POWER_QUERY = "powerquery";
    static final String ODATA_VIEWER = "odata_viewer";
    static final String BROWSER = "browser";
    static final String SCRIPT = "script";
    static final String OTHER = "other";

    private static final List<String> BOTS = List.of("bot", "crawl", "spider", "scan", "headlesschrome", "jdbcodata-e2e",
            "audit", "leakix", "zgrab", "censys", "nmap", "nuclei", "masscan", "expanse", "slurp");
    private static final List<String> SCRIPTS = List.of("curl", "wget", "python-requests", "postman", "insomnia",
            "java/", "okhttp", "powershell", "go-http-client", "node", "axios");
    private static final List<String> MOBILE = List.of("mobile", "android", "iphone", "ipad");

    private UsageClassifier() {
    }

    static boolean isBot(String userAgent) {
        String ua = normalize(userAgent);
        return BOTS.stream().anyMatch(ua::contains);
    }

    static String client(String userAgent) {
        String ua = normalize(userAgent);
        if (ua.isEmpty() || isBot(ua)) return OTHER;
        if (ua.contains("microsoft.data.mashup")) return POWER_QUERY;
        if (ua.startsWith("python-httpx")) return ODATA_VIEWER; // the viewer's backend calls the feed with httpx
        if (ua.startsWith("mozilla/")) return BROWSER;
        return SCRIPTS.stream().anyMatch(ua::contains) ? SCRIPT : OTHER;
    }

    static String device(String userAgent) {
        String ua = normalize(userAgent);
        if (ua.isEmpty()) return "unknown";
        if (isBot(ua)) return "bot";
        if (MOBILE.stream().anyMatch(ua::contains)) return "mobile";
        return switch (client(ua)) {
            case POWER_QUERY, BROWSER -> "desktop";
            default -> "unknown";
        };
    }

    private static String normalize(String userAgent) {
        return userAgent == null ? "" : userAgent.strip().toLowerCase(Locale.ROOT);
    }
}
