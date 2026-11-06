package at.jdbcodata.admin.ui;

import at.jdbcodata.common.InvalidRequestException;
import at.jdbcodata.transfer.ConfigExportService;
import at.jdbcodata.transfer.ConfigImportService;
import at.jdbcodata.transfer.ConfigImportService.Action;
import at.jdbcodata.transfer.ConfigImportService.Options;
import at.jdbcodata.transfer.ConfigImportService.Result;
import at.jdbcodata.transfer.ConfigYaml;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

/**
 * Export download and import in two steps: the upload runs as dry run and shows what would change; the result page
 * sends the same file again to apply it. The file travels with the form, nothing is kept on the server in between.
 */
@Controller
@RequestMapping("/admin/transfer")
public class TransferUiController {

    private final ConfigExportService exporter;
    private final ConfigImportService importer;

    public TransferUiController(ConfigExportService exporter, ConfigImportService importer) {
        this.exporter = exporter;
        this.importer = importer;
    }

    @GetMapping
    public String page() {
        return "admin/transfer";
    }

    @GetMapping("/export")
    public ResponseEntity<String> export() {
        return ResponseEntity.ok()
                .contentType(new MediaType("application", "yaml", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"jdbcodata-config-" + LocalDate.now() + ".yaml\"")
                .body(ConfigYaml.write(exporter.export()));
    }

    /** htmx: dry run of the uploaded file. */
    @PostMapping("/preview")
    public String preview(@RequestParam MultipartFile file, @RequestParam(defaultValue = "false") boolean overwriteConnections,
                          @RequestParam(defaultValue = "false") boolean prune, Model model) throws IOException {
        return run(new String(file.getBytes(), StandardCharsets.UTF_8), true, overwriteConnections, prune, model);
    }

    /** htmx: applies the file the preview showed. */
    @PostMapping("/apply")
    public String apply(@RequestParam String content, @RequestParam(defaultValue = "false") boolean overwriteConnections,
                        @RequestParam(defaultValue = "false") boolean prune, Model model) {
        return run(content, false, overwriteConnections, prune, model);
    }

    private String run(String content, boolean dryRun, boolean overwriteConnections, boolean prune, Model model) {
        model.addAttribute("overwriteConnections", overwriteConnections);
        model.addAttribute("prune", prune);
        try {
            Result result = importer.run(ConfigYaml.read(content), new Options(dryRun, overwriteConnections, prune));
            model.addAttribute("result", result);
            model.addAttribute("content", content);
            model.addAttribute("creates", result.count(Action.CREATE));
            model.addAttribute("updates", result.count(Action.UPDATE));
            model.addAttribute("deletes", result.count(Action.DELETE));
            model.addAttribute("unchanged", result.count(Action.UNCHANGED));
        } catch (InvalidRequestException e) {
            model.addAttribute("error", e.getMessage());
        }
        return "admin/transfer-result";
    }
}
