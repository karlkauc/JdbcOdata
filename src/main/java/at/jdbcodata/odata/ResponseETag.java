package at.jdbcodata.odata;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Weak ETags for read responses: a hash of the response body. Correct for every path (cache hit, fresh execution,
 * row-level security per caller) because equal bodies mean equal content; it saves the transfer, not the query.
 */
final class ResponseETag {

    private ResponseETag() {
    }

    static String of(byte[] body) {
        return "W/\"" + hash(body) + "\"";
    }

    static String hash(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** True if an {@code If-None-Match} header (list, {@code *}, weak or strong tags) matches {@code etag}. */
    static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) return false;
        String opaque = opaque(etag);
        for (String candidate : ifNoneMatch.split(",")) {
            String c = candidate.strip();
            if ("*".equals(c) || opaque.equals(opaque(c))) return true;
        }
        return false;
    }

    private static String opaque(String tag) {
        String t = tag.strip();
        if (t.startsWith("W/")) t = t.substring(2);
        return t;
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
