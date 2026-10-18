package at.jdbcodata.odata;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.Property;
import org.springframework.stereotype.Component;

/**
 * Change tracking for {@code Prefer: odata.track-changes} without support from the database: the response carries a
 * delta link whose token names a snapshot of the delivered result (entity id → fingerprint of its property values).
 * Following the link evaluates the same request again and compares: new and changed entities are returned, missing
 * ones as deleted. Snapshots live in memory (lost on restart, then the link answers 410 Gone), at most
 * {@link #MAX_TOKENS} of them for {@link #LIFETIME}, and only for the caller who created them.
 */
@Component
public class DeltaTracker {

    static final int MAX_TOKENS = 1000;
    static final Duration LIFETIME = Duration.ofHours(24);

    /** A stored result: who may use it, for which request, and the fingerprints by entity id. */
    record Snapshot(String owner, String scope, Map<String, Long> rows, Instant created) {
    }

    private final Clock clock;
    private final Map<String, Snapshot> snapshots = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Snapshot> eldest) {
            return size() > MAX_TOKENS;
        }
    };

    public DeltaTracker() {
        this(Clock.systemUTC());
    }

    DeltaTracker(Clock clock) {
        this.clock = clock;
    }

    /** Stores the fingerprints of {@code rows} and returns the token for the delta link. */
    String register(String owner, String scope, List<Entity> rows) {
        Map<String, Long> fingerprints = fingerprints(rows);
        String token = UUID.randomUUID().toString().replace("-", "");
        synchronized (snapshots) {
            snapshots.put(token, new Snapshot(owner, scope, fingerprints, clock.instant()));
        }
        return token;
    }

    /** The snapshot of {@code token}, or null when it is unknown, expired, or belongs to another caller or request. */
    Snapshot find(String token, String owner, String scope) {
        synchronized (snapshots) {
            Snapshot s = snapshots.get(token);
            if (s == null) return null;
            if (s.created().plus(LIFETIME).isBefore(clock.instant())) {
                snapshots.remove(token);
                return null;
            }
            return s.owner().equals(owner) && s.scope().equals(scope) ? s : null;
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
            Object v = FilterValues.canonical(p.getValue());
            String text = p.getName() + "=" + (v instanceof java.math.BigDecimal bd ? bd.stripTrailingZeros().toPlainString()
                    : v instanceof byte[] b ? java.util.Base64.getEncoder().encodeToString(b) : String.valueOf(v)) + "\u0000";
            for (int i = 0; i < text.length(); i++) {
                h ^= text.charAt(i);
                h *= 0x100000001b3L;
            }
        }
        return h;
    }
}
