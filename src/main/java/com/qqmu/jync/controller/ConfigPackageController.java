package com.qqmu.jync.controller;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.audit.AuditDetail;
import com.qqmu.jync.service.config.ConfigExportService;
import com.qqmu.jync.service.config.ConfigImportService;
import com.qqmu.jync.service.config.ConfigPackageException;
import com.qqmu.jync.service.config.ConfigPackageFormat;
import com.qqmu.jync.util.AuditOrigin;
import com.qqmu.jync.util.HttpsSupport;

/**
 * Export and import of a configuration package.
 *
 * <p>Export answers with the sealed file itself; import redirects back to the page with counts and
 * warnings. Neither ever puts the passphrase, a ciphertext or a value from the package into a
 * flash message, an audit row or a log — a failure reaches the page as an i18n key.
 *
 * <p>The import is told whether its own request arrived over HTTPS: restoring "require HTTPS" from
 * a plaintext request would lock the administrator out of the page they are standing on, so the
 * importer resets that one setting instead (and says so in its warnings).
 */
@Controller
public class ConfigPackageController {

    private static final Logger log = LoggerFactory.getLogger(ConfigPackageController.class);

    private static final String PAGE = "redirect:/system-config";

    private final ConfigExportService exportService;
    private final ConfigImportService importService;
    private final AdminAuditService audit;

    public ConfigPackageController(ConfigExportService exportService,
                                   ConfigImportService importService,
                                   AdminAuditService audit) {
        this.exportService = exportService;
        this.importService = importService;
        this.audit = audit;
    }

    /**
     * Returns either the sealed package ({@link ResponseEntity}) or a redirect back to the page
     * ({@code String}). The declared type is {@code Object} because a handler's return value is
     * dispatched on its runtime type, and a download and a redirect cannot share one.
     */
    @PostMapping("/system-config/export")
    public Object export(@RequestParam(required = false) String passphrase,
                         @RequestParam(required = false) String confirmPassphrase,
                         @RequestParam(defaultValue = "false") boolean includeState,
                         HttpServletRequest request,
                         RedirectAttributes flash) {
        String error = passphraseProblem(passphrase, confirmPassphrase);
        if (error != null) {
            flash.addFlashAttribute("error", error);
            return PAGE;
        }

        ConfigExportService.ConfigPackage pkg;
        try {
            pkg = exportService.build(passphrase.toCharArray(), includeState);
        } catch (ConfigPackageException e) {
            flash.addFlashAttribute("error", e.messageKey());
            return PAGE;
        }

        audit.record(AuditAction.CONFIG_EXPORT,
                AuditDetail.of("audit.detail.configExport", AuditOrigin.of(request),
                        String.valueOf(pkg.entryCount())));

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(pkg.filename()).build().toString())
                .contentLength(pkg.bytes().length)
                .body(pkg.bytes());
    }

    @PostMapping("/system-config/import")
    public String importPackage(@RequestParam(value = "file", required = false) MultipartFile file,
                                @RequestParam(required = false) String passphrase,
                                HttpServletRequest request,
                                RedirectAttributes flash) {
        if (file == null || file.isEmpty()) {
            flash.addFlashAttribute("error", "error.config.import.fileRequired");
            return PAGE;
        }
        // Checked before the bytes are materialized; the service checks again after decryption.
        if (file.getSize() > SystemConfigController.PACKAGE_MAX_BYTES) {
            flash.addFlashAttribute("error", "error.config.import.fileTooLarge");
            return PAGE;
        }
        if (passphrase == null || passphrase.isEmpty()) {
            flash.addFlashAttribute("error", "error.config.passphrase.tooShort");
            return PAGE;
        }

        ConfigImportService.ImportResult result;
        try {
            result = importService.importPackage(file.getBytes(), passphrase.toCharArray(),
                    HttpsSupport.isSecure(request));
        } catch (ConfigPackageException e) {
            flash.addFlashAttribute("error", e.messageKey());
            return PAGE;
        } catch (IOException | RuntimeException e) {
            // The exception message may quote package content or a JDBC URL; only the key ships.
            log.warn("Configuration import failed: {}", e.toString());
            flash.addFlashAttribute("error", "error.config.import.failed");
            return PAGE;
        }

        audit.record(AuditAction.CONFIG_IMPORT,
                AuditDetail.of("audit.detail.configImport", AuditOrigin.of(request),
                        String.valueOf(result.databases() + result.migrationConnections()),
                        String.valueOf(result.redisTargets()),
                        String.valueOf(result.projects()),
                        String.valueOf(result.etlTasks() + result.migrationTasks()),
                        String.valueOf(result.usersInserted())));

        flash.addFlashAttribute("message", "msg.systemConfig.import.done");
        flash.addFlashAttribute("importDatabases", result.databases());
        flash.addFlashAttribute("importProjects", result.projects());
        flash.addFlashAttribute("importWarnings", result.warningKeys());
        return PAGE;
    }

    /** Length first (the cheaper, more specific complaint), then the confirmation match. */
    private static String passphraseProblem(String passphrase, String confirmPassphrase) {
        int length = passphrase == null ? 0 : passphrase.length();
        if (length < ConfigPackageFormat.MIN_PASSPHRASE_CHARS) {
            return "error.config.passphrase.tooShort";
        }
        if (length > ConfigPackageFormat.MAX_PASSPHRASE_CHARS) {
            return "error.config.passphrase.tooLong";
        }
        if (!passphrase.equals(confirmPassphrase)) {
            return "error.config.passphrase.mismatch";
        }
        return null;
    }
}
