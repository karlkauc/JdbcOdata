package at.jdbcodata.odata;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.Property;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Change tracking for {@code Prefer: odata.track-changes} without support from the database: the response carries a
 * delta link whose token names a snapshot of the delivered result (entity id → fingerprint of its property values).
 * Following the link evaluates the same request again and compares: new and changed entities are returned, missing
 * ones as deleted. Snapshots are stored compressed in the metadata database ({@code delta_snapshot}), so delta links
 * survive restarts and work across instances; they expire after {@link #LIFETIME}, at most {@link #MAX_TOKENS} are
 * kept, and only the caller who created one can use it. Without a database (unit tests) they live in memory.
 */
@Component
public class DeltaTracker {

    static final int MAX_TOKENS = 10_000;
    static final Duration LIFETIME = Duration.ofDays(7);

    /** A stored result: the fingerprints by entity id. */
    record Snapshot(Map<String, Long> rows, Instant created) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AtomicInteger registrations = new AtomicInteger();
    /** Fallback without database. */
    private final Map<String, Object[]> memory = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Object[]> eldest) {
            return size() > MAX_TOKENS;
        }
    };

    @Autowired
    public DeltaTracker(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    DeltaTracker(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Stores the fingerprints of {@code rows} and returns the token for the delta link. */
    String register(String owner, String scope, List<Entity> rows) {
        Map<String, Long> fingerprints = fingerprints(rows);
        String token = UUID.randomUUID().toString().replace("-", "");
        Instant now = clock.instant();
        if (jdbc == null) {
            synchronized (memory) {
                memory.put(token, new Object[] {owner, hash(scope), fingerprints, now});
            }
            return token;
        }
        jdbc.update("INSERT INTO delta_snapshot (token, owner, scope_hash, payload, row_count, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                token, owner, hash(scope), encode(fingerprints), fingerprints.size(), Timestamp.from(now));
        if (registrations.incrementAndGet() % 100 == 1) cleanUp(now);
        return token;
    }

    /** The snapshot of {@code token}, or null when it is unknown, expired, or belongs to another caller or request. */
    Snapshot find(String token, String owner, String scope) {
        if (token == null || !token.matches("[0-9a-f]{32}")) return null;
        Instant oldest = clock.instant().minus(LIFETIME);
        if (jdbc == null) {
            synchronized (memory) {
                Object[] s = memory.get(token);
                if (s == null || ((Instant) s[3]).isBefore(oldest) || !s[0].equals(owner) || !s[1].equals(hash(scope))) return null;
                @SuppressWarnings("unchecked")
                Map<String, Long> rows = (Map<String, Long>) s[2];
                return new Snapshot(rows, (Instant) s[3]);
            }
        }
        List<Snapshot> found = jdbc.query(
                "SELECT payload, created_at FROM delta_snapshot WHERE token = ? AND owner = ? AND scope_hash = ? AND created_at >= ?",
                (rs, i) -> new Snapshot(decode(rs.getBytes(1)), rs.getTimestamp(2).toInstant()),
                token, owner, hash(scope), Timestamp.from(oldest));
        return found.isEmpty() ? null : found.get(0);
    }

    /** Drops expired snapshots and, beyond {@link #MAX_TOKENS}, the oldest ones. */
    void cleanUp(Instant now) {
        jdbc.update("DELETE FROM delta_snapshot WHERE created_at < ?", Timestamp.from(now.minus(LIFETIME)));
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM delta_snapshot", Integer.class);
        if (count != null && count > MAX_TOKENS) {
            List<Timestamp> cut = jdbc.queryForList("SELECT created_at FROM delta_snapshot ORDER BY created_at DESC",
                    Timestamp.class).subList(MAX_TOKENS - 1, MAX_TOKENS);
            jdbc.update("DELETE FROM delta_snapshot WHERE created_at < ?", cut.get(0));
        }
    }

    static Map<String, Long> fingerprints(List<Entity> rows) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Entity e : rows) {
            if (e.getId() != null) out.put(e.getId().toString(), fingerprint(e));
        }
        return out;
    }

    /** 64-bit FNV-1a over the property names and canonical values. */
    static long fingerprint(Entity e) {
        long h = 0xcbf29ce484222325L;
        for (Property p : e.getProperties()) {
            Object v = p.getValue() instanceof List || p.getValue() instanceof org.apache.olingo.commons.api.data.ComplexValue
                    ? CollectionValues.text(p.getValue()) : FilterValues.canonical(p.getValue());
            String text = p.getName() + "=" + (v instanceof java.math.BigDecimal bd ? bd.stripTrailingZeros().toPlainString()
                    : v instanceof byte[] b ? java.util.Base64.getEncoder().encodeToString(b) : String.valueOf(v)) + "\u0000";
            for (int i = 0; i < text.length(); i++) {
                h ^= text.charAt(i);
                h *= 0x100000001b3L;
            }
        }
        return h;
    }

    private static String hash(String scope) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(scope.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] encode(Map<String, Long> rows) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeInt(rows.size());
            for (Map.Entry<String, Long> r : rows.entrySet()) {
                out.writeUTF(r.getKey());
                out.writeLong(r.getValue());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    static Map<String, Long> decode(byte[] payload) {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(payload)))) {
            int n = in.readInt();
            Map<String, Long> rows = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) rows.put(in.readUTF(), in.readLong());
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
