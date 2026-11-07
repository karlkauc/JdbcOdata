package at.jdbcodata.transfer;

import at.jdbcodata.common.InvalidRequestException;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.type.LogicalType;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLWriteFeature;

/** Reads and writes {@link ConfigDocument}s. JSON is read as well, being YAML too. */
public final class ConfigYaml {

    /** Larger than any real configuration, small enough that a mistaken upload cannot fill the heap. */
    static final int MAX_CHARS = 10 * 1024 * 1024;

    private static final YAMLMapper MAPPER = YAMLMapper.builder(YAMLFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(MAX_CHARS).build())
                    .build())
            .enable(YAMLWriteFeature.MINIMIZE_QUOTES, YAMLWriteFeature.LITERAL_BLOCK_STYLE)
            .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
            // a misspelt key would otherwise be dropped silently and keep the target's value
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // "groups:" without entries leaves the section alone; only "groups: []" means none (matters for prune)
            .withCoercionConfig(LogicalType.Collection, c -> c.setCoercion(CoercionInputShape.EmptyString, CoercionAction.AsNull))
            .withCoercionConfig(LogicalType.Map, c -> c.setCoercion(CoercionInputShape.EmptyString, CoercionAction.AsNull))
            .build();

    private ConfigYaml() {
    }

    public static String write(ConfigDocument document) {
        return MAPPER.writeValueAsString(document);
    }

    public static ConfigDocument read(String text) {
        if (text == null || text.isBlank()) {
            throw new InvalidRequestException("The configuration file is empty");
        }
        if (text.length() > MAX_CHARS) {
            throw new InvalidRequestException("The configuration file is larger than " + MAX_CHARS / 1024 / 1024 + " MB");
        }
        ConfigDocument document;
        try {
            document = MAPPER.readValue(text, ConfigDocument.class);
        } catch (JacksonException e) {
            TokenStreamLocation at = e.getLocation();
            int line = at == null ? -1 : at.getLineNr();
            if (e instanceof UnrecognizedPropertyException u && line > 0) {
                line = lineOf(text, u.getPropertyName(), line);
            }
            throw new InvalidRequestException((line > 0 ? "Line " + line + ": " : "") + firstLine(e.getOriginalMessage()));
        }
        if (document == null || document.format() == null) {
            throw new InvalidRequestException("Not a JdbcOdata configuration export: 'format: " + ConfigDocument.FORMAT + "' is missing");
        }
        if (document.format() != ConfigDocument.FORMAT) {
            throw new InvalidRequestException("Format " + document.format() + " is not supported, only " + ConfigDocument.FORMAT);
        }
        return document;
    }

    /** The YAML parser reads one event ahead: the line of the key itself, searched backwards from the reported one. */
    static int lineOf(String text, String key, int reported) {
        List<String> lines = text.lines().toList();
        for (int i = Math.min(reported, lines.size()) - 1; i >= 0; i--) {
            String l = lines.get(i).strip();
            if (l.startsWith("- ")) l = l.substring(2).strip();
            if (l.startsWith(key + ":") || l.startsWith("\"" + key + "\"")) return i + 1;
        }
        return reported;
    }

    /** Jackson appends the class and a source excerpt, neither helps the admin. */
    private static String firstLine(String message) {
        if (message == null) return "invalid YAML";
        String line = message.lines().findFirst().orElse(message);
        int cls = line.indexOf(" (class ");
        return cls > 0 ? line.substring(0, cls) : line;
    }
}
