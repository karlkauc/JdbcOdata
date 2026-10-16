package at.jdbcodata.datasource;

public record ConnectionTestResult(boolean ok, String productName, String productVersion, long durationMs, String error) {
    public static ConnectionTestResult success(String product, String version, long durationMs) {
        return new ConnectionTestResult(true, product, version, durationMs, null);
    }

    public static ConnectionTestResult failure(String error, long durationMs) {
        return new ConnectionTestResult(false, null, null, durationMs, error);
    }
}
