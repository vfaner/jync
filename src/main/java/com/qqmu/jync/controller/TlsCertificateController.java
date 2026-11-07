package com.qqmu.jync.controller;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.audit.AuditDetail;
import com.qqmu.jync.service.tls.TlsCertificateService;
import com.qqmu.jync.service.tls.TlsConfig;
import com.qqmu.jync.service.tls.TlsKeystoreException;
import com.qqmu.jync.util.AuditOrigin;

/**
 * The certificate upload and removal on the 系统配置 page.
 *
 * <p>Saving only writes files: the HTTPS connector was built at startup and cannot be rebuilt
 * here, so the page says "restart to apply" rather than pretending the switch flipped. Every
 * failure is flashed as an i18n key — an exception message could quote the passphrase — and the
 * audit row identifies the certificate by port and digest prefix, never by its contents.
 */
@Controller
public class TlsCertificateController {

    private static final String PAGE = "redirect:/system-config";

    private final TlsCertificateService service;
    private final AdminAuditService audit;

    public TlsCertificateController(TlsCertificateService service, AdminAuditService audit) {
        this.service = service;
        this.audit = audit;
    }

    /**
     * Two forms share this endpoint: a keystore with its passphrase, or a PEM certificate chain
     * plus private key. The presence of either PEM part selects the second mode, so the browser
     * needs no JavaScript to tell the server which form it submitted.
     */
    @PostMapping("/system-config/tls")
    public String upload(@RequestParam(value = "file", required = false) MultipartFile file,
                         @RequestParam(value = "certFile", required = false) MultipartFile certFile,
                         @RequestParam(value = "keyFile", required = false) MultipartFile keyFile,
                         @RequestParam(required = false) String passphrase,
                         @RequestParam(value = "httpsPort", required = false) String httpsPort,
                         @RequestParam(defaultValue = "false") boolean redirectHttp,
                         HttpServletRequest request,
                         RedirectAttributes flash) {
        // The parts' presence, not their contents, selects the form: an empty file still means
        // the PEM tab, whose own message names the field the operator left blank.
        if (certFile != null || keyFile != null) {
            return uploadPem(certFile, keyFile, httpsPort, redirectHttp, request, flash);
        }

        if (file == null || file.isEmpty()) {
            flash.addFlashAttribute("error", "error.tls.fileRequired");
            return PAGE;
        }
        // Checked before the bytes are materialized; the service checks the array again.
        if (file.getSize() > TlsCertificateService.MAX_BYTES) {
            flash.addFlashAttribute("error", "error.tls.fileTooLarge");
            return PAGE;
        }
        Integer port = parsePort(httpsPort);
        if (port == null) {
            flash.addFlashAttribute("error", "error.tls.portInvalid");
            return PAGE;
        }

        TlsConfig saved;
        try {
            saved = service.save(file.getBytes(), file.getOriginalFilename(), passphrase, port,
                    redirectHttp);
        } catch (TlsKeystoreException e) {
            flash.addFlashAttribute("error", e.messageKey());
            return PAGE;
        } catch (IOException | RuntimeException e) {
            // The message may quote the keystore path or a provider diagnostic; only the key ships.
            flash.addFlashAttribute("error", "error.tls.saveFailed");
            return PAGE;
        }

        return recordSaved(saved, request, flash);
    }

    private String uploadPem(MultipartFile certFile, MultipartFile keyFile, String httpsPort,
                             boolean redirectHttp, HttpServletRequest request,
                             RedirectAttributes flash) {
        if (!present(certFile)) {
            flash.addFlashAttribute("error", "error.tls.pemCertRequired");
            return PAGE;
        }
        if (certFile.getSize() > TlsCertificateService.MAX_BYTES
                || (present(keyFile) && keyFile.getSize() > TlsCertificateService.MAX_BYTES)) {
            flash.addFlashAttribute("error", "error.tls.fileTooLarge");
            return PAGE;
        }
        Integer port = parsePort(httpsPort);
        if (port == null) {
            flash.addFlashAttribute("error", "error.tls.portInvalid");
            return PAGE;
        }

        TlsConfig saved;
        try {
            // The key file is optional: HAProxy-style bundles carry the key in the cert file.
            saved = service.savePem(certFile.getBytes(),
                    present(keyFile) ? keyFile.getBytes() : null, port, redirectHttp);
        } catch (TlsKeystoreException e) {
            flash.addFlashAttribute("error", e.messageKey());
            return PAGE;
        } catch (IOException | RuntimeException e) {
            flash.addFlashAttribute("error", "error.tls.saveFailed");
            return PAGE;
        }

        return recordSaved(saved, request, flash);
    }

    private String recordSaved(TlsConfig saved, HttpServletRequest request,
                               RedirectAttributes flash) {
        audit.record(AuditAction.TLS_CONFIG_SAVE,
                AuditDetail.of("audit.detail.tlsSaved", AuditOrigin.of(request),
                        String.valueOf(saved.httpsPort()),
                        String.valueOf(saved.redirectHttp()),
                        saved.sha256().substring(0, 12)));
        flash.addFlashAttribute("message", "msg.systemConfig.tls.saved");
        return PAGE;
    }

    private static boolean present(MultipartFile file) {
        return file != null && !file.isEmpty();
    }

    @PostMapping("/system-config/tls/delete")
    public String delete(HttpServletRequest request, RedirectAttributes flash) {
        boolean removed;
        try {
            removed = service.delete();
        } catch (TlsKeystoreException e) {
            // Removal is the repair action after a corrupt store; a read-only data directory
            // must not turn it into an HTML 500 page with no explanation.
            flash.addFlashAttribute("error", e.messageKey());
            return PAGE;
        } catch (RuntimeException e) {
            flash.addFlashAttribute("error", "error.tls.deleteFailed");
            return PAGE;
        }
        if (removed) {
            audit.record(AuditAction.TLS_CONFIG_DELETE,
                    AuditDetail.of("audit.detail.tlsDeleted", AuditOrigin.of(request)));
            flash.addFlashAttribute("message", "msg.systemConfig.tls.removed");
        }
        return PAGE;
    }

    private static Integer parsePort(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
