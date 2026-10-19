package at.jdbcodata.health;

import at.jdbcodata.datasource.Datasource;
import at.jdbcodata.datasource.DatasourceService;
import at.jdbcodata.datasource.DbErrorClassifier;
import at.jdbcodata.datasource.IdentityPoolManager;
import at.jdbcodata.identity.FixedCredential;
import at.jdbcodata.identity.FixedCredentialService;
import at.jdbcodata.stats.StatsService;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Reachability of the datasources for the admin UI.
 * <p>
 * A datasource with a technical account is checked actively: a connection of that account's pool is validated
 * periodically. Without a technical account there are no credentials to check with, so the state is derived from
 * the latest execution that reached the database (cache hits excluded).
 */
@Service
public class DatasourceHealthService {

    private static final Logger log = LoggerFactory.getLogger(DatasourceHealthService.class);
    private static final Duration PASSIVE_WINDOW = Duration.ofHours(24);
    /** Status values of an execution that prove the database answered. */
    private static final Set<String> REACHED = Set.of("OK", "AUTH_FAILED", "VALIDATION_ERROR", "TOO_LARGE", "NOT_FOUND");

    public enum State { OK, WARN, DOWN, UNKNOWN, DISABLED }

    /**
     * {@code source}: how the state was determined, shown as a hint. {@code active}: a technical account exists, so
     * the check can be repeated on demand.
     */
    public record Health(UUID datasourceId, String name, String jdbcUrl, State state, Long latencyMs, String detail,
                         Instant checkedAt, String source, boolean active) {
        public boolean problem() {
            return state == State.DOWN || state == State.WARN;
        }
    }

    private record Check(boolean ok, long latencyMs, String detail, Instant at, String account) {
    }

    private final DatasourceService datasources;
    private final FixedCredentialService fixedCredentials;
    private final IdentityPoolManager pools;
    private final StatsService stats;
    private final boolean enabled;
    private final Map<UUID, Check> checks = new ConcurrentHashMap<>();

    public DatasourceHealthService(DatasourceService datasources, FixedCredentialService fixedCredentials,
                                   IdentityPoolManager pools, StatsService stats,
                                   @Value("${jdbcodata.health.interval-ms:60000}") long intervalMs) {
        this.datasources = datasources;
        this.fixedCredentials = fixedCredentials;
        this.pools = pools;
        this.stats = stats;
        this.enabled = intervalMs > 0;
    }

    /** Active check of every enabled datasource that has a technical account. */
    @Scheduled(initialDelayString = "${jdbcodata.health.initial-delay-ms:15000}", fixedDelayString = "${jdbcodata.health.interval-ms:60000}")
    public void checkAll() {
        if (!enabled) return;
        for (Datasource ds : datasources.findAll()) {
            if (ds.enabled()) {
                try {
                    check(ds.id());
                } catch (RuntimeException e) {
                    log.debug("Health check of {} failed: {}", ds.name(), e.getMessage());
                }
            }
        }
    }

    /** Runs the active check now; empty if the datasource has no technical account. */
    public Optional<Health> check(UUID datasourceId) {
        Datasource ds = datasources.require(datasourceId);
        Optional<FixedCredential> account = accountOf(ds.id());
        if (account.isEmpty()) {
            checks.remove(ds.id());
            return Optional.empty();
        }
        FixedCredential acc = account.get();
        long start = System.nanoTime();
        Check result;
        try {
            var login = fixedCredentials.resolveLogin(acc.id());
            try (Connection c = pools.connect(ds, login.username(), login.password())) {
                boolean valid = c.isValid(10);
                long ms = (System.nanoTime() - start) / 1_000_000;
                result = new Check(valid, ms, valid ? null : "Connection not valid", Instant.now(), acc.name());
            }
        } catch (SQLException e) {
            result = new Check(false, (System.nanoTime() - start) / 1_000_000, DbErrorClassifier.describe(e), Instant.now(), acc.name());
        } catch (RuntimeException e) {
            result = new Check(false, (System.nanoTime() - start) / 1_000_000, e.getMessage(), Instant.now(), acc.name());
        }
        checks.put(ds.id(), result);
        return Optional.of(toHealth(ds, result, null, true));
    }

    /** State of all datasources, problems first. */
    public List<Health> all() {
        Map<UUID, StatsService.LastDbExecution> last = stats.lastDbExecutions(Instant.now().minus(PASSIVE_WINDOW));
        Set<UUID> withAccount = fixedCredentials.findAll().stream().map(FixedCredential::datasourceId).collect(Collectors.toSet());
        return datasources.findAll().stream()
                .map(ds -> toHealth(ds, checks.get(ds.id()), last.get(ds.id()), withAccount.contains(ds.id())))
                .sorted(Comparator.comparing((Health h) -> !h.problem()).thenComparing(Health::name))
                .toList();
    }

    private Optional<FixedCredential> accountOf(UUID datasourceId) {
        return fixedCredentials.findAll().stream().filter(c -> c.datasourceId().equals(datasourceId))
                .min(Comparator.comparing(FixedCredential::name));
    }

    private Health toHealth(Datasource ds, Check check, StatsService.LastDbExecution last, boolean hasAccount) {
        boolean active = check != null || hasAccount;
        if (!ds.enabled()) {
            return new Health(ds.id(), ds.name(), ds.jdbcUrl(), State.DISABLED, null, "Datasource disabled", null, null, active);
        }
        if (check != null) {
            return new Health(ds.id(), ds.name(), ds.jdbcUrl(), check.ok() ? State.OK : State.DOWN, check.ok() ? check.latencyMs() : null,
                    check.detail(), check.at(), "Connection check with technical account " + check.account(), true);
        }
        if (last != null) {
            boolean reached = REACHED.contains(last.status());
            return new Health(ds.id(), ds.name(), ds.jdbcUrl(), reached ? State.OK : State.WARN, reached ? last.dbDurationMs() : null,
                    reached ? null : "Last execution failed: " + last.errorMessage(), last.startedAt(),
                    "Last execution", active);
        }
        return new Health(ds.id(), ds.name(), ds.jdbcUrl(), State.UNKNOWN, null,
                active ? "Not checked yet" : "No execution in the last 24 h and no technical account to check with",
                null, null, active);
    }
}
