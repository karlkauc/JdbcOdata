package at.jdbcodata.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CacheKeyTest {

    @Test
    void sessionSettingsAreDistinctButOptional() {
        UUID id = UUID.randomUUID();
        Map<String, Object> params = Map.of("p", "1");
        CacheKey plain = CacheKey.of(id, 1, "u", params);
        assertThat(CacheKey.of(id, 1, "u", params, Map.of())).isEqualTo(plain);
        CacheKey german = CacheKey.of(id, 1, "u", params, Map.of("NLS_SORT", "GERMAN"));
        assertThat(german).isNotEqualTo(plain);
        assertThat(CacheKey.of(id, 1, "u", params, Map.of("NLS_SORT", "BINARY"))).isNotEqualTo(german);
        assertThat(CacheKey.of(id, 1, "u", params, Map.of("NLS_SORT", "GERMAN"))).isEqualTo(german);
    }
}
