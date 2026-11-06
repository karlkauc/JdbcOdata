package at.jdbcodata.admin.api;

import at.jdbcodata.transfer.ConfigExportService;
import at.jdbcodata.transfer.ConfigImportService;
import at.jdbcodata.transfer.ConfigImportService.Options;
import at.jdbcodata.transfer.ConfigImportService.Result;
import at.jdbcodata.transfer.ConfigYaml;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Configuration", description = "Export and import of datasources, technical accounts, groups and queries, "
        + "e.g. from test to production. No passwords, no service users.")
@RestController
@RequestMapping("/api/v1/config")
public class ConfigController {

    static final String YAML = "application/yaml";

    private final ConfigExportService exporter;
    private final ConfigImportService importer;

    public ConfigController(ConfigExportService exporter, ConfigImportService importer) {
        this.exporter = exporter;
        this.importer = importer;
    }

    @Operation(summary = "Current configuration as YAML")
    @GetMapping(produces = YAML)
    public ResponseEntity<String> export() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"jdbcodata-config-" + LocalDate.now() + ".yaml\"")
                .body(ConfigYaml.write(exporter.export()));
    }

    /** 200 when the import (or dry run) went through, 400 with the same body when it found errors. */
    @Operation(summary = "Import a configuration export (dry run unless dryRun=false)",
            description = "Entries are matched by name. Existing datasources keep their connection data and technical "
                    + "accounts their login unless overwriteConnections=true. With prune=true, entries missing from a "
                    + "section of the file are deleted. Nothing is changed when an error occurs.")
    @PostMapping(value = "/import", consumes = {YAML, "application/x-yaml", "text/yaml", "application/json", "text/plain"})
    public ResponseEntity<Result> importConfig(
            @RequestBody String body,
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(defaultValue = "false") boolean overwriteConnections,
            @RequestParam(defaultValue = "false") boolean prune) {
        Result result = importer.run(ConfigYaml.read(body), new Options(dryRun, overwriteConnections, prune));
        return ResponseEntity.status(result.errors().isEmpty() ? HttpStatus.OK : HttpStatus.BAD_REQUEST).body(result);
    }
}
