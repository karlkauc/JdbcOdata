package at.jdbcodata.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Identifies a cached result: query, version, effective DB user and the parameter values. */
public record CacheKey(UUID queryId, int queryVersion, String dbUser, String paramsHash) {

    public static CacheKey of(UUID queryId, int version, String dbUser, Map<String, Object> params) {
        return new CacheKey(queryId, version, dbUser == null ? "" : dbUser.toLowerCase(), hash(unambiguous(params)));
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
