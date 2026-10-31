package at.jdbcodata.usage;

/**
 * One row of {@code usage_event}. Holds no SQL, no query option or parameter values, no result data, no user name
 * and no raw IP; {@code targetNamespace} is a published query name or {@code other}, never text from the URL.
 */
public record UsageEvent(String eventType, String visitorHash, String userAgent, String device, int statusCode,
                         String appVersion, String source, String targetNamespace, int durationMs, String status,
                         String queryOptions, boolean authUsed) {
}
