package at.jdbcodata.odata;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.query.KeyMode;
import at.jdbcodata.query.ParameterType;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryParameter;
import at.jdbcodata.testutil.TestData;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Sensitive parameter values never reach the execution log or the cache list in clear text. */
class SensitiveParameterMaskingTest {

    private final QueryDefinition query = TestData.query("Q", "SELECT 1", List.of(
            new QueryParameter("token", ParameterType.STRING, false, null, null, true),
            TestData.param("year", ParameterType.INT, false, null)), List.of(), KeyMode.ROWID);

    @Test
    void masksSensitiveValuesInRawQueryString() {
        assertThat(QueryEntityCollectionProcessor.maskedOptions(query, "$top=5&token=s3cr%3Dt&year=2024&%74oken=x"))
                .isEqualTo("$top=5&token=***&year=2024&%74oken=***");
        assertThat(QueryEntityCollectionProcessor.maskedOptions(query, "token")).isEqualTo("token");
        assertThat(QueryEntityCollectionProcessor.maskedOptions(query, null)).isNull();
    }

    @Test
    void masksSensitiveValuesInParameterMap() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("token", "s3cret");
        params.put("year", 2024);
        assertThat(QueryEntityCollectionProcessor.maskedParameterMap(query, params))
                .containsEntry("token", "***").containsEntry("year", 2024);
    }
}
