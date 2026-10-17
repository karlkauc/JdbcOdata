package at.jdbcodata.odata;

import static at.jdbcodata.testutil.TestData.column;
import static at.jdbcodata.testutil.TestData.query;
import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.query.KeyMode;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryRelation;
import java.util.List;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.apache.olingo.commons.api.edm.provider.CsdlEntityType;
import org.apache.olingo.commons.api.edm.provider.CsdlNavigationProperty;
import org.apache.olingo.commons.api.edm.provider.CsdlNavigationPropertyBinding;
import org.apache.olingo.commons.api.edm.provider.CsdlProperty;
import org.apache.olingo.commons.api.edm.provider.CsdlPropertyRef;
import org.junit.jupiter.api.Test;

class DynamicEdmProviderTest {

    private final QueryDefinition rowIdQuery = query("Sales", "SELECT 1", List.of(), List.of(
            column(0, "customer", "Edm.String", null, null, false, true),
            column(1, "amount", "Edm.Decimal", 12, 2, false, true),
            column(2, "blob", "Edm.Binary", null, null, false, false)), KeyMode.ROWID);

    private final QueryDefinition columnKeyQuery = query("Customers", "SELECT 1", List.of(), List.of(
            column(0, "id", "Edm.Int32", null, null, true, true),
            column(1, "name", "Edm.String", null, null, false, true)), KeyMode.COLUMNS);

    private final QueryDefinition noKeyQuery = query("NoKey", "SELECT 1", List.of(), List.of(
            column(0, "name", "Edm.String", null, null, false, true)), KeyMode.COLUMNS);

    private final DynamicEdmProvider provider = new DynamicEdmProvider(List.of(rowIdQuery, columnKeyQuery, noKeyQuery));

    private static QueryDefinition withRelations(QueryDefinition q, List<QueryRelation> relations) {
        return new QueryDefinition(q.id(), q.name(), q.description(), q.datasourceId(), q.datasourceName(), q.sqlText(),
                q.executionMode(), q.fixedCredentialId(), q.fixedCredentialName(), q.cacheEnabled(), q.cacheTtlSec(),
                q.timeoutSec(), q.maxRows(), q.keyMode(), q.version(), q.enabled(), q.parameters(), q.columns(),
                q.createdAt(), q.updatedAt(), relations);
    }

    @Test
    void relationsBecomeNavigationPropertiesWithConstraintsAndBindings() throws Exception {
        QueryDefinition orders = withRelations(query("Orders", "SELECT 1", List.of(), List.of(
                column(0, "id", "Edm.Int32", null, null, true, true),
                column(1, "customerId", "Edm.Int32", null, null, false, true)), KeyMode.COLUMNS), List.of(
                new QueryRelation("Customer", "Customers", List.of("customerId"), List.of("id"), false, "Orders", null),
                new QueryRelation("Ghost", "Missing", List.of("customerId"), List.of("id"), false, null, null),
                new QueryRelation("id", "Customers", List.of(), List.of(), true, null, "clashes with a property")));
        QueryDefinition customers = withRelations(columnKeyQuery, List.of(
                new QueryRelation("Orders", "Orders", List.of(), List.of(), true, "Customer", null)));
        DynamicEdmProvider p = new DynamicEdmProvider(List.of(orders, customers));

        CsdlEntityType orderType = p.getEntityType(new FullQualifiedName("JdbcOdata", "Orders"));
        assertThat(orderType.getNavigationProperties()).extracting(CsdlNavigationProperty::getName).containsExactly("Customer");
        CsdlNavigationProperty customer = orderType.getNavigationProperty("Customer");
        assertThat(customer.getTypeFQN()).isEqualTo(new FullQualifiedName("JdbcOdata", "Customers"));
        assertThat(customer.isCollection()).isFalse();
        assertThat(customer.getPartner()).isEqualTo("Orders");
        assertThat(customer.getReferentialConstraints()).hasSize(1);
        assertThat(customer.getReferentialConstraints().get(0).getProperty()).isEqualTo("customerId");
        assertThat(customer.getReferentialConstraints().get(0).getReferencedProperty()).isEqualTo("id");

        CsdlNavigationProperty ordersNav = p.getEntityType(new FullQualifiedName("JdbcOdata", "Customers"))
                .getNavigationProperty("Orders");
        assertThat(ordersNav.isCollection()).isTrue();
        assertThat(ordersNav.getPartner()).isEqualTo("Customer");
        assertThat(ordersNav.getReferentialConstraints()).isEmpty();

        assertThat(p.getEntitySet(DynamicEdmProvider.CONTAINER, "Orders").getNavigationPropertyBindings())
                .extracting(CsdlNavigationPropertyBinding::getPath, CsdlNavigationPropertyBinding::getTarget)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Customer", "Customers"));
        // A partner that clashes with a property on the other side is dropped there, so it is no partner here either.
        QueryDefinition clashing = withRelations(columnKeyQuery, List.of(
                new QueryRelation("name", "Orders", List.of(), List.of(), true, "Customer", null)));
        assertThat(new DynamicEdmProvider(List.of(orders, clashing)).getEntityType(new FullQualifiedName("JdbcOdata", "Orders"))
                .getNavigationProperty("Customer").getPartner()).isNull();
        // A partner that does not point back is not advertised as partner.
        QueryDefinition lonely = withRelations(orders, List.of(
                new QueryRelation("Customer", "Customers", List.of("customerId"), List.of("id"), false, "Nope", null)));
        assertThat(new DynamicEdmProvider(List.of(lonely, columnKeyQuery)).getEntityType(new FullQualifiedName("JdbcOdata", "Orders"))
                .getNavigationProperty("Customer").getPartner()).isNull();
    }

    @Test
    void buildsRowIdKeyedTypeAndSkipsHiddenColumns() throws Exception {
        CsdlEntityType type = provider.getEntityType(new FullQualifiedName("JdbcOdata", "Sales"));
        assertThat(type.getProperties()).extracting(CsdlProperty::getName).containsExactly("RowId", "customer", "amount");
        assertThat(type.getKey()).extracting(CsdlPropertyRef::getName).containsExactly("RowId");
        CsdlProperty amount = type.getProperty("amount");
        assertThat(amount.getPrecision()).isEqualTo(12);
        assertThat(amount.getScale()).isEqualTo(2);
        assertThat(amount.isNullable()).isTrue();
        assertThat(type.getProperty("RowId").isNullable()).isFalse();
    }

    @Test
    void buildsColumnKeyedType() throws Exception {
        CsdlEntityType type = provider.getEntityType(DynamicEdmProvider.typeName("Customers"));
        assertThat(type.getProperties()).extracting(CsdlProperty::getName).containsExactly("id", "name");
        assertThat(type.getKey()).extracting(CsdlPropertyRef::getName).containsExactly("id");
        assertThat(type.getProperty("id").isNullable()).isFalse();
    }

    @Test
    void fallsBackToRowIdWhenNoKeyColumnMarked() throws Exception {
        CsdlEntityType type = provider.getEntityType(DynamicEdmProvider.typeName("NoKey"));
        assertThat(type.getProperties()).extracting(CsdlProperty::getName).containsExactly("RowId", "name");
        assertThat(type.getKey()).extracting(CsdlPropertyRef::getName).containsExactly("RowId");
    }

    @Test
    void exposesContainerAndSchema() throws Exception {
        assertThat(provider.getEntityType(new FullQualifiedName("Other", "Sales"))).isNull();
        assertThat(provider.getEntityType(DynamicEdmProvider.typeName("Unknown"))).isNull();
        assertThat(provider.getEntitySet(DynamicEdmProvider.CONTAINER, "Sales").getTypeFQN())
                .isEqualTo(DynamicEdmProvider.typeName("Sales"));
        assertThat(provider.getEntitySet(DynamicEdmProvider.CONTAINER, "Unknown")).isNull();
        assertThat(provider.getEntitySet(new FullQualifiedName("x", "y"), "Sales")).isNull();
        assertThat(provider.getEntityContainer().getEntitySets()).hasSize(3);
        assertThat(provider.getEntityContainerInfo(null).getContainerName()).isEqualTo(DynamicEdmProvider.CONTAINER);
        assertThat(provider.getEntityContainerInfo(DynamicEdmProvider.CONTAINER)).isNotNull();
        assertThat(provider.getEntityContainerInfo(new FullQualifiedName("x", "y"))).isNull();
        var schemas = provider.getSchemas();
        assertThat(schemas).hasSize(1);
        assertThat(schemas.get(0).getNamespace()).isEqualTo("JdbcOdata");
        assertThat(schemas.get(0).getEntityTypes()).hasSize(3);
        assertThat(schemas.get(0).getEntityContainer().getEntitySets()).hasSize(3);
    }
}
