package at.jdbcodata.transfer;

import at.jdbcodata.audit.AuditService;
import at.jdbcodata.common.ConflictException;
import at.jdbcodata.common.InvalidRequestException;
import at.jdbcodata.common.NotFoundException;
import at.jdbcodata.datasource.Datasource;
import at.jdbcodata.datasource.DatasourceService;
import at.jdbcodata.datasource.DatasourceSpec;
import at.jdbcodata.identity.FixedCredential;
import at.jdbcodata.identity.FixedCredentialService;
import at.jdbcodata.identity.FixedCredentialService.FixedCredentialSpec;
import at.jdbcodata.identity.ServiceGroup;
import at.jdbcodata.identity.ServiceUser;
import at.jdbcodata.identity.ServiceUserService;
import at.jdbcodata.query.CollectionKind;
import at.jdbcodata.query.ComplexField;
import at.jdbcodata.query.ParameterType;
import at.jdbcodata.query.PrincipalType;
import at.jdbcodata.query.QueryColumn;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryParameter;
import at.jdbcodata.query.QueryRelation;
import at.jdbcodata.query.QueryService;
import at.jdbcodata.query.QueryService.GrantSpec;
import at.jdbcodata.query.QuerySpec;
import at.jdbcodata.transfer.ConfigDocument.ColumnEntry;
import at.jdbcodata.transfer.ConfigDocument.DatasourceEntry;
import at.jdbcodata.transfer.ConfigDocument.FieldEntry;
import at.jdbcodata.transfer.ConfigDocument.FixedCredentialEntry;
import at.jdbcodata.transfer.ConfigDocument.GrantEntry;
import at.jdbcodata.transfer.ConfigDocument.GroupEntry;
import at.jdbcodata.transfer.ConfigDocument.ParameterEntry;
import at.jdbcodata.transfer.ConfigDocument.QueryEntry;
import at.jdbcodata.transfer.ConfigDocument.RelationEntry;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/**
 * Applies a {@link ConfigDocument}: entries are matched by name, created or updated, and with {@code prune} the ones
 * missing from a section are deleted. Everything runs in one transaction through the regular services, so their
 * validation, versions and audit apply; a dry run or any error rolls it back. Unchanged entries are not touched.
 *
 * <p>Connection data of existing datasources and the login of existing technical accounts belong to the target and
 * are kept unless {@code overwriteConnections} is set.
 */
@Service
public class ConfigImportService {

    public record Options(boolean dryRun, boolean overwriteConnections, boolean prune) {
    }

    public enum Action { CREATE, UPDATE, UNCHANGED, DELETE, NOT_IN_FILE }

    /** {@code fields}: what an update changes. {@code note}: what the admin should know about this entry. */
    public record Change(String type, String name, Action action, List<String> fields, String note) {
    }

    public record Result(boolean applied, boolean dryRun, List<Change> changes, List<String> warnings,
                         List<String> errors) {
        public long count(Action action) {
            return changes.stream().filter(c -> c.action() == action).count();
        }
    }

    static final String DATASOURCE = "datasource";
    static final String FIXED_CREDENTIAL = "fixedCredential";
    static final String GROUP = "group";
    static final String QUERY = "query";

    private static final Set<String> CONNECTION_FIELDS = Set.of("jdbcUrl", "driverClass", "driverProperties");
    private static final Set<String> LOGIN_FIELDS = Set.of("dbUsername", "dbPassword");
    private static final Set<String> SNAPSHOT_DROPPING_FIELDS = Set.of("sql", "datasource");

    private final DatasourceService datasources;
    private final FixedCredentialService fixedCredentials;
    private final ServiceUserService serviceUsers;
    private final QueryService queries;
    private final AuditService audit;

    public ConfigImportService(DatasourceService datasources, FixedCredentialService fixedCredentials,
                               ServiceUserService serviceUsers, QueryService queries, AuditService audit) {
        this.datasources = datasources;
        this.fixedCredentials = fixedCredentials;
        this.serviceUsers = serviceUsers;
        this.queries = queries;
        this.audit = audit;
    }

    @Transactional
    public Result run(ConfigDocument document, Options options) {
        Run run = new Run(options);
        try {
            run.groups(document.groups());
            run.datasources(document.datasources());
            run.fixedCredentials(document.fixedCredentials());
            run.queries(document.queries());
            run.prune(document);
        } catch (Stop stop) {
            // recorded as error; the session may be unusable after a failed statement
        }
        boolean apply = !options.dryRun() && run.errors.isEmpty();
        Result result = new Result(apply, options.dryRun(), run.changes, run.warnings, run.errors);
        if (apply) {
            Map<String, Object> details = new LinkedHashMap<>();
            for (Action a : List.of(Action.CREATE, Action.UPDATE, Action.DELETE)) {
                details.put(a.name().toLowerCase(), result.count(a));
            }
            details.put("overwriteConnections", options.overwriteConnections());
            details.put("prune", options.prune());
            audit.record("IMPORT", "CONFIG", (String) null, "configuration", details);
        } else {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
        return result;
    }

    /** A failed statement: further work in this transaction would only produce follow-up errors. */
    private static final class Stop extends RuntimeException {
        Stop() {
            super(null, null, false, false);
        }
    }

    private final class Run {

        final Options options;
        final List<Change> changes = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        Run(Options options) {
            this.options = options;
        }

        // --- groups ----------------------------------------------------------------------------------------------

        void groups(List<GroupEntry> entries) {
            if (entries == null) return;
            Map<String, ServiceGroup> existing = byName(serviceUsers.findAllGroups(), ServiceGroup::name);
            for (GroupEntry file : unique(GROUP, entries, GroupEntry::name)) {
                ServiceGroup current = existing.get(key(file.name()));
                if (current == null) {
                    attempt(GROUP, file.name(), () -> serviceUsers.createGroup(file.name(), file.description()));
                    changes.add(new Change(GROUP, file.name(), Action.CREATE, List.of(), null));
                    continue;
                }
                GroupEntry was = ConfigExportService.entry(current);
                GroupEntry effective = merge(file, was);
                List<String> fields = changed(was, effective);
                if (!fields.isEmpty()) {
                    attempt(GROUP, file.name(), () -> serviceUsers.updateGroupDescription(current.id(), effective.description()));
                }
                changes.add(change(GROUP, current.name(), fields, null));
            }
        }

        // --- datasources -----------------------------------------------------------------------------------------

        void datasources(List<DatasourceEntry> entries) {
            if (entries == null) return;
            Map<String, Datasource> existing = byName(datasources.findAll(), Datasource::name);
            for (DatasourceEntry file : unique(DATASOURCE, entries, DatasourceEntry::name)) {
                Datasource current = existing.get(key(file.name()));
                if (current == null) {
                    attempt(DATASOURCE, file.name(), () -> datasources.create(datasourceSpec(file)));
                    changes.add(new Change(DATASOURCE, file.name(), Action.CREATE, List.of(), null));
                    continue;
                }
                DatasourceEntry was = ConfigExportService.entry(current);
                DatasourceEntry effective = merge(file, was);
                String note = null;
                if (!options.overwriteConnections()) {
                    DatasourceEntry kept = new DatasourceEntry(effective.name(), effective.description(), was.jdbcUrl(),
                            was.driverClass(), effective.timezone(), effective.defaultTimeoutSec(), effective.maxRows(),
                            effective.maxConcurrent(), effective.poolSize(), effective.enabled(),
                            effective.passCaller(), effective.sessionSettings(), was.driverProperties());
                    if (changed(was, effective).stream().anyMatch(CONNECTION_FIELDS::contains)) {
                        note = "Connection kept; the file has other connection data";
                    }
                    effective = kept;
                }
                List<String> fields = changed(was, effective);
                DatasourceEntry target = effective;
                if (!fields.isEmpty()) {
                    attempt(DATASOURCE, file.name(), () -> datasources.update(current.id(), datasourceSpec(target)));
                }
                changes.add(change(DATASOURCE, current.name(), fields, note));
            }
        }

        // --- technical accounts ----------------------------------------------------------------------------------

        void fixedCredentials(List<FixedCredentialEntry> entries) {
            if (entries == null) return;
            Map<String, FixedCredential> existing = byName(fixedCredentials.findAll(), FixedCredential::name);
            for (FixedCredentialEntry file : unique(FIXED_CREDENTIAL, entries, FixedCredentialEntry::name)) {
                FixedCredential current = existing.get(key(file.name()));
                if (current == null) {
                    if (file.dbPassword() == null || file.dbPassword().isEmpty()) {
                        errors.add("Technical account '" + file.name() + "' is new and needs dbPassword in the file "
                                + "(exports never contain it), or create it in the target first");
                        changes.add(new Change(FIXED_CREDENTIAL, file.name(), Action.CREATE, List.of(), "Password missing"));
                        continue;
                    }
                    attempt(FIXED_CREDENTIAL, file.name(), () -> fixedCredentials.create(new FixedCredentialSpec(file.name(),
                            file.description(), datasourceId(file.datasource()), file.dbUsername(), file.dbPassword())));
                    changes.add(new Change(FIXED_CREDENTIAL, file.name(), Action.CREATE, List.of(), null));
                    continue;
                }
                FixedCredentialEntry was = ConfigExportService.entry(current);
                FixedCredentialEntry effective = merge(file, was);
                String note = null;
                if (!options.overwriteConnections()) {
                    if (changed(was, effective).stream().anyMatch(LOGIN_FIELDS::contains)) {
                        note = "Login kept; the file has another one";
                    }
                    effective = new FixedCredentialEntry(effective.name(), effective.description(), effective.datasource(),
                            was.dbUsername(), null);
                }
                List<String> fields = changed(was, effective);
                FixedCredentialEntry target = effective;
                if (!fields.isEmpty()) {
                    attempt(FIXED_CREDENTIAL, file.name(), () -> fixedCredentials.update(current.id(), new FixedCredentialSpec(
                            target.name(), target.description(), datasourceId(target.datasource()), target.dbUsername(),
                            target.dbPassword())));
                }
                changes.add(change(FIXED_CREDENTIAL, current.name(), fields, note));
            }
        }

        // --- queries ---------------------------------------------------------------------------------------------

        void queries(List<QueryEntry> entries) {
            if (entries == null) return;
            Map<String, QueryDefinition> existing = byName(queries.findAll(), QueryDefinition::name);
            Map<UUID, String> principals = principals();
            for (QueryEntry raw : unique(QUERY, entries, QueryEntry::name)) {
                QueryEntry file = normalize(raw);
                QueryDefinition current = existing.get(key(file.name()));
                if (current == null) {
                    attempt(QUERY, file.name(), () -> {
                        QueryDefinition created = queries.create(spec(file));
                        columns(created, file.columns());
                        grants(created, file.grants());
                    });
                    String note = file.columns() == null
                            ? "No schema snapshot in the file; take one in the target before the query is published" : null;
                    changes.add(new Change(QUERY, file.name(), Action.CREATE, List.of(), note));
                    continue;
                }
                QueryEntry was = ConfigExportService.entry(current, queries.grants(current.id()), principals);
                QueryEntry effective = merge(file, was);
                List<String> fields = changed(was, effective);
                boolean snapshotDropped = fields.stream().anyMatch(SNAPSHOT_DROPPING_FIELDS::contains);
                String note = snapshotDropped && file.columns() == null
                        ? "The changed SQL drops the schema snapshot; take a new one in the target" : null;
                if (!fields.isEmpty()) {
                    attempt(QUERY, file.name(), () -> {
                        QueryDefinition updated = current;
                        if (fields.stream().anyMatch(f -> !f.equals("columns") && !f.equals("grants"))) {
                            updated = queries.update(current.id(), spec(effective));
                        }
                        if (fields.contains("columns") || snapshotDropped) {
                            columns(updated, file.columns());
                        }
                        if (fields.contains("grants")) {
                            grants(updated, effective.grants());
                        }
                    });
                }
                changes.add(change(QUERY, current.name(), fields, note));
            }
        }

        private void columns(QueryDefinition query, List<ColumnEntry> columns) {
            if (columns == null) return;
            List<QueryColumn> snapshot = new ArrayList<>();
            for (int i = 0; i < columns.size(); i++) {
                ColumnEntry c = columns.get(i);
                snapshot.add(new QueryColumn(i, c.name(), c.propertyName(), orZero(c.jdbcType()), c.jdbcTypeName(),
                        c.edmType(), c.precision(), c.scale(), isTrue(c.nullable()), isTrue(c.key()), isTrue(c.visible()),
                        c.elementType(), c.structure().stream().map(f -> new ComplexField(f.key(), f.name(), f.edmType(),
                                isTrue(f.collection()))).toList(),
                        c.collection(), isTrue(c.singleObject()), isTrue(c.textCopy())));
            }
            queries.importColumns(query.id(), snapshot);
        }

        private void grants(QueryDefinition query, List<GrantEntry> grants) {
            if (grants == null) return;
            Map<String, ServiceGroup> groups = byName(serviceUsers.findAllGroups(), ServiceGroup::name);
            List<GrantSpec> specs = new ArrayList<>();
            for (GrantEntry g : grants) {
                if (g.type() == null) throw new InvalidRequestException("A grant has no type");
                switch (g.type()) {
                    case ALL_AUTHENTICATED -> specs.add(new GrantSpec(PrincipalType.ALL_AUTHENTICATED, null));
                    case GROUP -> {
                        ServiceGroup group = groups.get(key(g.name()));
                        if (group == null) throw new NotFoundException("Grant to unknown group '" + g.name() + "'");
                        specs.add(new GrantSpec(PrincipalType.GROUP, group.id()));
                    }
                    case USER -> {
                        Optional<ServiceUser> user = g.name() == null ? Optional.empty() : serviceUsers.findByUsername(g.name());
                        if (user.isEmpty()) {
                            warnings.add("Query '" + query.name() + "': service user '" + g.name()
                                    + "' does not exist here, its grant is left out");
                        } else {
                            specs.add(new GrantSpec(PrincipalType.USER, user.get().id()));
                        }
                    }
                }
            }
            queries.setGrants(query.id(), specs);
        }

        // --- prune -----------------------------------------------------------------------------------------------

        /** Only sections present in the file; queries first, they reference the rest. */
        void prune(ConfigDocument document) {
            if (document.queries() != null) {
                Set<String> keep = names(document.queries(), QueryEntry::name);
                for (QueryDefinition q : queries.findAll()) {
                    remove(QUERY, q.name(), keep, () -> queries.delete(q.id()));
                }
            }
            if (document.fixedCredentials() != null) {
                Set<String> keep = names(document.fixedCredentials(), FixedCredentialEntry::name);
                for (FixedCredential c : fixedCredentials.findAll()) {
                    remove(FIXED_CREDENTIAL, c.name(), keep, () -> fixedCredentials.delete(c.id()));
                }
            }
            if (document.datasources() != null) {
                Set<String> keep = names(document.datasources(), DatasourceEntry::name);
                for (Datasource d : datasources.findAll()) {
                    remove(DATASOURCE, d.name(), keep, () -> datasources.delete(d.id()));
                }
            }
            if (document.groups() != null) {
                Set<String> keep = names(document.groups(), GroupEntry::name);
                for (ServiceGroup g : serviceUsers.findAllGroups()) {
                    remove(GROUP, g.name(), keep, () -> serviceUsers.deleteGroup(g.id()));
                }
            }
        }

        private void remove(String type, String name, Set<String> keep, Runnable delete) {
            if (keep.contains(key(name))) return;
            if (options.prune()) {
                attempt(type, name, delete);
                changes.add(new Change(type, name, Action.DELETE, List.of(), null));
            } else {
                changes.add(new Change(type, name, Action.NOT_IN_FILE, List.of(), null));
            }
        }

        // --- helpers ---------------------------------------------------------------------------------------------

        private void attempt(String type, String name, Runnable action) {
            try {
                action.run();
            } catch (InvalidRequestException | ConflictException | NotFoundException e) {
                errors.add(label(type) + " '" + name + "': " + e.getMessage());
            } catch (RuntimeException e) {
                errors.add(label(type) + " '" + name + "': " + e.getClass().getSimpleName() + ": " + e.getMessage());
                throw new Stop();
            }
        }

        /** Entries without a name or with a name used twice are reported and skipped. */
        private <T> List<T> unique(String type, List<T> entries, Function<T, String> name) {
            Set<String> seen = new HashSet<>();
            List<T> out = new ArrayList<>();
            for (T e : entries) {
                String n = e == null ? null : name.apply(e);
                if (n == null || n.isBlank()) {
                    errors.add("A " + label(type).toLowerCase() + " in the file has no name");
                } else if (!seen.add(key(n))) {
                    errors.add(label(type) + " '" + n + "' appears twice in the file");
                } else {
                    out.add(e);
                }
            }
            return out;
        }

        private UUID datasourceId(String name) {
            if (name == null) throw new InvalidRequestException("datasource is required");
            return datasources.findByName(name).map(Datasource::id)
                    .orElseThrow(() -> new NotFoundException("Unknown datasource '" + name + "'"));
        }

        private UUID fixedCredentialId(String name) {
            if (name == null) return null;
            return fixedCredentials.findAll().stream().filter(c -> c.name().equalsIgnoreCase(name)).findFirst()
                    .map(FixedCredential::id)
                    .orElseThrow(() -> new NotFoundException("Unknown technical account '" + name + "'"));
        }

        private Map<UUID, String> principals() {
            Map<UUID, String> principals = new HashMap<>();
            serviceUsers.findAll().forEach(u -> principals.put(u.id(), u.username()));
            serviceUsers.findAllGroups().forEach(g -> principals.put(g.id(), g.name()));
            return principals;
        }

        private QuerySpec spec(QueryEntry q) {
            List<QueryParameter> parameters = q.parameters() == null ? List.of() : q.parameters().stream()
                    .map(p -> new QueryParameter(p.name(), p.type(), isTrue(p.required()), p.defaultValue(),
                            p.description(), isTrue(p.sensitive()))).toList();
            List<QueryRelation> relations = q.relations() == null ? null : q.relations().stream()
                    .map(r -> {
                        Map<String, String> columns = r.columns() == null ? Map.of() : r.columns();
                        return new QueryRelation(r.name(), r.target(), new ArrayList<>(columns.keySet()),
                                new ArrayList<>(columns.values()), isTrue(r.toMany()), r.partner(), r.description());
                    }).toList();
            return new QuerySpec(q.name(), q.description(), datasourceId(q.datasource()), q.sql(), q.executionMode(),
                    fixedCredentialId(q.fixedCredential()), q.cacheEnabled(), q.cacheTtlSec(), q.timeoutSec(),
                    q.maxRows(), q.keyMode(), q.enabled(), parameters, relations, q.pushDown(), q.sessionSettings(),
                    q.passCaller());
        }
    }

    // --- pure functions ------------------------------------------------------------------------------------------

    private static DatasourceSpec datasourceSpec(DatasourceEntry d) {
        return new DatasourceSpec(d.name(), d.description(), d.jdbcUrl(), d.driverClass(), d.timezone(),
                d.defaultTimeoutSec(), d.maxRows(), d.maxConcurrent(), d.enabled(), d.sessionSettings(),
                d.driverProperties(), d.poolSize(), d.passCaller());
    }

    /**
     * Fills the defaults of nested entries the way an export writes them, so a hand-written file without
     * {@code required: false} does not count as a change. An empty snapshot keeps the target's.
     */
    static QueryEntry normalize(QueryEntry q) {
        List<ParameterEntry> parameters = q.parameters() == null ? null : q.parameters().stream()
                .map(p -> new ParameterEntry(p.name(), p.type() == null ? ParameterType.STRING : p.type(),
                        isTrue(p.required()), p.defaultValue(), p.description(), isTrue(p.sensitive()))).toList();
        List<RelationEntry> relations = q.relations() == null ? null : q.relations().stream()
                .map(r -> new RelationEntry(r.name(), r.target(), r.columns() == null ? Map.of() : r.columns(),
                        isTrue(r.toMany()), r.partner(), r.description())).toList();
        List<ColumnEntry> columns = q.columns() == null || q.columns().isEmpty() ? null : q.columns().stream()
                .map(c -> new ColumnEntry(c.name(), c.propertyName(), orZero(c.jdbcType()), c.jdbcTypeName(),
                        c.edmType(), c.precision(), c.scale(), isTrue(c.nullable()), isTrue(c.key()),
                        isTrue(c.visible()) || isTrue(c.key()), c.elementType(),
                        c.structure() == null ? List.of() : c.structure().stream()
                                .map(f -> new FieldEntry(f.key(), f.name(), f.edmType(), isTrue(f.collection()))).toList(),
                        c.collection() == null ? CollectionKind.NONE : c.collection(), isTrue(c.singleObject()),
                        isTrue(c.textCopy()))).toList();
        List<GrantEntry> grants = q.grants() == null ? null : ConfigExportService.grants(q.grants().stream()
                .map(g -> g.type() == PrincipalType.ALL_AUTHENTICATED ? new GrantEntry(g.type(), null) : g).toList());
        return new QueryEntry(q.name(), q.description(), q.datasource(), q.sql(), q.executionMode(),
                q.fixedCredential(), q.enabled(), q.keyMode(), q.pushDown(), q.passCaller(), q.cacheEnabled(),
                q.cacheTtlSec(), q.timeoutSec(), q.maxRows(), q.sessionSettings(), parameters, relations, columns, grants);
    }

    /** Every component the file leaves out ({@code null}) is taken from {@code current}. */
    static <R extends Record> R merge(R file, R current) {
        RecordComponent[] components = file.getClass().getRecordComponents();
        Object[] values = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            Object v = value(components[i], file);
            values[i] = v != null ? v : value(components[i], current);
            types[i] = components[i].getType();
        }
        try {
            @SuppressWarnings("unchecked")
            Constructor<R> constructor = (Constructor<R>) file.getClass().getDeclaredConstructor(types);
            return constructor.newInstance(values);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Names of the components that differ, without {@code name}: entries match by name ignoring case. */
    static <R extends Record> List<String> changed(R before, R after) {
        List<String> fields = new ArrayList<>();
        for (RecordComponent c : before.getClass().getRecordComponents()) {
            if (c.getName().equals("name")) continue;
            if (!Objects.equals(value(c, before), value(c, after))) fields.add(c.getName());
        }
        return fields;
    }

    private static Object value(RecordComponent c, Record r) {
        try {
            return c.getAccessor().invoke(r);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Change change(String type, String name, List<String> fields, String note) {
        return new Change(type, name, fields.isEmpty() ? Action.UNCHANGED : Action.UPDATE, fields, note);
    }

    private static <T> Map<String, T> byName(Collection<T> items, Function<T, String> name) {
        Map<String, T> map = new HashMap<>();
        for (T t : items) map.put(key(name.apply(t)), t);
        return map;
    }

    private static <T> Set<String> names(List<T> entries, Function<T, String> name) {
        Set<String> names = new HashSet<>();
        for (T e : entries) {
            if (e != null && name.apply(e) != null) names.add(key(name.apply(e)));
        }
        return names;
    }

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
    }

    private static String label(String type) {
        return switch (type) {
            case DATASOURCE -> "Datasource";
            case FIXED_CREDENTIAL -> "Technical account";
            case GROUP -> "Group";
            default -> "Query";
        };
    }

    private static boolean isTrue(Boolean b) {
        return Boolean.TRUE.equals(b);
    }

    private static int orZero(Integer i) {
        return i == null ? 0 : i;
    }
}
