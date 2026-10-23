package at.jdbcodata.common;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** JSON form of string maps (session settings, driver properties) in TEXT columns; empty maps are stored as null. */
public final class StringMapJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<LinkedHashMap<String, String>> MAP = new TypeReference<>() {
    };

    private StringMapJson() {
    }

    public static String write(Map<String, String> map) {
        return map == null || map.isEmpty() ? null : MAPPER.writeValueAsString(map);
    }

    public static Map<String, String> read(String json) {
        if (json == null || json.isBlank()) return Map.of();
        return java.util.Collections.unmodifiableMap(MAPPER.readValue(json, MAP));
    }
}
