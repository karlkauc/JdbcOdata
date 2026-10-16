package at.jdbcodata.query;

import java.util.List;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** JSON form of the parameter list, used by the version history. */
final class ParametersJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<List<QueryParameter>> LIST = new TypeReference<>() {
    };

    private ParametersJson() {
    }

    static String write(List<QueryParameter> parameters) {
        return MAPPER.writeValueAsString(parameters == null ? List.of() : parameters);
    }

    static List<QueryParameter> read(String json) {
        if (json == null || json.isBlank()) return List.of();
        return MAPPER.readValue(json, LIST);
    }
}
