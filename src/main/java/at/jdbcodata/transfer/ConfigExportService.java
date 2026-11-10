package at.jdbcodata.transfer;

import at.jdbcodata.datasource.Datasource;
import at.jdbcodata.datasource.DatasourceService;
import at.jdbcodata.identity.FixedCredential;
import at.jdbcodata.identity.FixedCredentialService;
import at.jdbcodata.identity.ServiceGroup;
import at.jdbcodata.identity.ServiceUserService;
import at.jdbcodata.query.ComplexField;
import at.jdbcodata.query.PrincipalType;
import at.jdbcodata.query.QueryColumn;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryGrant;
import at.jdbcodata.query.QueryParameter;
import at.jdbcodata.query.QueryRelation;
import at.jdbcodata.query.QueryService;
import at.jdbcodata.transfer.ConfigDocument.ColumnEntry;
import at.jdbcodata.transfer.ConfigDocument.DatasourceEntry;
import at.jdbcodata.transfer.ConfigDocument.FieldEntry;
import at.jdbcodata.transfer.ConfigDocument.FixedCredentialEntry;
import at.jdbcodata.transfer.ConfigDocument.GrantEntry;
import at.jdbcodata.transfer.ConfigDocument.GroupEntry;
import at.jdbcodata.transfer.ConfigDocument.ParameterEntry;
import at.jdbcodata.transfer.ConfigDocument.QueryEntry;
import at.jdbcodata.transfer.ConfigDocument.RelationEntry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Current configuration as {@link ConfigDocument}. Entries are sorted by name and maps by key, so two exports of the
 * same state are identical apart from {@code exportedAt}. The import compares against this view as well.
 */
@Service
public class ConfigExportService {

    private final DatasourceService datasources;
    private final FixedCredentialService fixedCredentials;
    private final ServiceUserService serviceUsers;
    private final QueryService queries;

    public ConfigExportService(DatasourceService datasources, FixedCredentialService fixedCredentials,
                               ServiceUserService serviceUsers, QueryService queries) {
        this.datasources = datasources;
        this.fixedCredentials = fixedCredentials;
        this.serviceUsers = serviceUsers;
        this.queries = queries;
    }

    @Transactional(readOnly = true)
    public ConfigDocument export() {
        String version = ConfigExportService.class.getPackage().getImplementationVersion();
        Map<UUID, String> principals = new HashMap<>();
        serviceUsers.findAll().forEach(u -> principals.put(u.id(), u.username()));
        serviceUsers.findAllGroups().forEach(g -> principals.put(g.id(), g.name()));
        return new ConfigDocument(ConfigDocument.FORMAT, "JdbcOdata " + (version == null ? "dev" : version),
                Instant.now().truncatedTo(ChronoUnit.SECONDS),
                datasources.findAll().stream().map(ConfigExportService::entry).sorted(Comparator.comparing(DatasourceEntry::name)).toList(),
                fixedCredentials.findAll().stream().map(ConfigExportService::entry).sorted(Comparator.comparing(FixedCredentialEntry::name)).toList(),
                serviceUsers.findAllGroups().stream().map(ConfigExportService::entry).sorted(Comparator.comparing(GroupEntry::name)).toList(),
                queries.findAll().stream().map(q -> entry(q, queries.grants(q.id()), principals)).sorted(Comparator.comparing(QueryEntry::name)).toList());
    }

    static DatasourceEntry entry(Datasource d) {
        return new DatasourceEntry(d.name(), d.description(), d.jdbcUrl(), d.driverClass(), d.timezone(),
                d.defaultTimeoutSec(), d.maxRows(), d.maxConcurrent(), d.poolSize(), d.enabled(), d.passCaller(),
                sorted(d.sessionSettings()), sorted(d.driverProperties()));
    }

    static FixedCredentialEntry entry(FixedCredential c) {
        return new FixedCredentialEntry(c.name(), c.description(), c.datasourceName(), c.dbUsername(), null);
    }

    static GroupEntry entry(ServiceGroup g) {
        return new GroupEntry(g.name(), g.description());
    }

    /** Grants of deleted users or groups are left out; {@code principals} maps their IDs to names. */
    static QueryEntry entry(QueryDefinition q, List<QueryGrant> grants, Map<UUID, String> principals) {
        return new QueryEntry(q.name(), q.description(), q.datasourceName(), q.sqlText(), q.executionMode(),
                q.fixedCredentialName(), q.enabled(), q.keyMode(), q.pushDown(), q.passCaller(), q.cacheEnabled(),
                q.cacheTtlSec(), q.timeoutSec(), q.maxRows(), sorted(q.sessionSettings()),
                q.parameters().stream().map(ConfigExportService::entry).toList(),
                q.relations().stream().map(ConfigExportService::entry).toList(),
                q.columns().stream().sorted(Comparator.comparingInt(QueryColumn::ordinal)).map(ConfigExportService::entry).toList(),
                grants(grants.stream()
                        .filter(g -> g.principalType() == PrincipalType.ALL_AUTHENTICATED || principals.containsKey(g.principalId()))
                        .map(g -> new GrantEntry(g.principalType(),
                                g.principalType() == PrincipalType.ALL_AUTHENTICATED ? null : principals.get(g.principalId())))
                        .toList()));
    }

    static ParameterEntry entry(QueryParameter p) {
        return new ParameterEntry(p.name(), p.type(), p.required(), p.defaultValue(), p.description(), p.sensitive());
    }

    static RelationEntry entry(QueryRelation r) {
        Map<String, String> columns = new LinkedHashMap<>();
        for (int i = 0; i < r.localColumns().size(); i++) {
            columns.put(r.localColumns().get(i), r.targetColumns().get(i));
        }
        return new RelationEntry(r.name(), r.targetQuery(), columns, r.toMany(), r.partner(), r.description());
    }

    static ColumnEntry entry(QueryColumn c) {
        return new ColumnEntry(c.name(), c.propertyName(), c.jdbcType(), c.jdbcTypeName(), c.edmType(), c.precision(),
                c.scale(), c.nullable(), c.key(), c.visible(), c.elementType(),
                c.structure().stream().map(ConfigExportService::entry).toList(), c.collection(), c.singleObject(),
                c.textCopy());
    }

    static FieldEntry entry(ComplexField f) {
        return new FieldEntry(f.key(), f.name(), f.edmType(), f.collection());
    }

    /** Grants have no order; sorted, so that two lists with the same grants compare equal. */
    static List<GrantEntry> grants(List<GrantEntry> grants) {
        return grants.stream().sorted(Comparator.comparing((GrantEntry g) -> g.type().name())
                .thenComparing(g -> g.name() == null ? "" : g.name())).toList();
    }

    private static Map<String, String> sorted(Map<String, String> map) {
        return map == null ? Map.of() : new TreeMap<>(map);
    }
}
