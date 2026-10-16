package at.jdbcodata.odata;

import static at.jdbcodata.testutil.TestData.column;
import static at.jdbcodata.testutil.TestData.query;
import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.query.KeyMode;
import at.jdbcodata.query.QueryDefinition;
import java.util.List;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.apache.olingo.commons.api.edm.provider.CsdlEntityType;
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
