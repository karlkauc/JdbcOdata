package at.jdbcodata.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Identifies a cached result: query, version, effective DB user, the parameter values, the session settings and,
 * when the query passes it to the database, the caller.
 */
public record CacheKey(UUID queryId, int queryVersion, String dbUser, String paramsHash) {

    public static CacheKey of(UUID queryId, int version, String dbUser, Map<String, Object> params) {
        return of(queryId, version, dbUser, params, Map.of());
    }

    /**
     * With the effective session settings: NLS formats and sort orders change the result without a new query
     * version. Without settings the key is the same as before they existed.
     */
    public static CacheKey of(UUID queryId, int version, String dbUser, Map<String, Object> params,
                              Map<String, String> sessionSettings) {
        return of(queryId, version, dbUser, params, sessionSettings, null);
    }

    /**
     * With the caller the database learns: row-level security makes the result depend on it, although the DB user
     * is the same technical account for everybody.
     *
     * @param caller {@link at.jdbcodata.datasource.CallerContext#cacheInput()}, or {@code null} when not passed
     */
    public static CacheKey of(UUID queryId, int version, String dbUser, Map<String, Object> params,
                              Map<String, String> sessionSettings, String caller) {
        String input = unambiguous(params);
        if (sessionSettings != null && !sessionSettings.isEmpty()) {
            input += "S" + unambiguous(new TreeMap<String, Object>(sessionSettings));
        }
        if (caller != null) {
            input += "C" + caller.length() + ":" + caller;
        }
        return new CacheKey(queryId, version, dbUser == null ? "" : dbUser.toLowerCase(), hash(input));
    }

    /**
     * Input of the parameter hash: each name and value length-prefixed and {@code null} distinct from the text
     * "null", so values containing {@code ,} or {@code =} cannot make two parameter sets collide.
     */
    static String unambiguous(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(params).forEach((k, v) -> {
            sb.append(k.length()).append(':').append(k);
            if (v == null) {
                sb.append('N');
            } else {
                String text = v.toString();
                sb.append('V').append(text.length()).append(':').append(text);
            }
        });
        return sb.toString();
    }

    /** Stable text form of the parameters (sorted by name), for display. */
    public static String canonical(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        new TreeMap<>(params).forEach((k, v) -> {
            if (sb.length() > 1) sb.append(',');
            sb.append(k).append('=').append(v == null ? "null" : v.toString());
        });
        return sb.append('}').toString();
    }

    static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
