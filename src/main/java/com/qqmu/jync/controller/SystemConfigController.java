package com.qqmu.jync.controller;

import java.util.Optional;

import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.audit.AuditDetail;
import com.qqmu.jync.service.settings.RequireHttpsResolver;
import com.qqmu.jync.service.settings.SystemSettingKey;
import com.qqmu.jync.service.settings.SystemSettingService;
import com.qqmu.jync.service.tls.TlsCertificateService;
import com.qqmu.jync.service.tls.TlsPageState;
import com.qqmu.jync.util.AuditOrigin;
import com.qqmu.jync.util.ClientIpResolver;
import com.qqmu.jync.util.CryptoUtil;
import com.qqmu.jync.util.HttpsSupport;
import com.qqmu.jync.util.JdbcUrlSanitizer;

/**
 * The administrator's 系统配置 page: metadata database, transport security, configuration
 * package.
 *
 * <p>The metadata database block is informational — which database is in use is decided by
 * {@code SPRING_DATASOURCE_*} and only changes on restart. The page can test a candidate
 * connection and print the environment block to paste, but it never switches anything at
 * runtime.
 */
@Controller
public class SystemConfigController {

    /** Matches the multipart limit; the service enforces it again after decryption. */
    static final long PACKAGE_MAX_BYTES = 20L * 1024 * 1024;

    private final RequireHttpsResolver requireHttps;
    private final SystemSettingService settings;
    private final AdminAuditService audit;
    private final CryptoUtil crypto;
    private final TlsCertificateService tls;
    private final String datasourceUrl;
    private final String datasourceUsername;

    public SystemConfigController(RequireHttpsResolver requireHttps,
                                  SystemSettingService settings,
                                  AdminAuditService audit,
                                  CryptoUtil crypto,
                                  TlsCertificateService tls,
                                  @Value("${spring.datasource.url:}") String datasourceUrl,
                                  @Value("${spring.datasource.username:}") String datasourceUsername) {
        this.requireHttps = requireHttps;
        this.settings = settings;
        this.audit = audit;
        this.crypto = crypto;
        this.tls = tls;
        this.datasourceUrl = datasourceUrl;
        this.datasourceUsername = datasourceUsername;
    }

    @GetMapping("/system-config")
    public String form(Model model, HttpServletRequest request) {
        RequireHttpsResolver.Effective https = requireHttps.effective();
        model.addAttribute("httpsRequired", https.required());
        model.addAttribute("httpsSource", https.source().name());
        model.addAttribute("httpsConfigControlled", requireHttps.isConfigControlled());
        model.addAttribute("requestSecure", HttpsSupport.isSecure(request));
        model.addAttribute("requestLoopback", ClientIpResolver.isLoopback(request.getRemoteAddr()));
        model.addAttribute("datasourceUrl", JdbcUrlSanitizer.sanitize(datasourceUrl));
        model.addAttribute("datasourceUsername",
                datasourceUsername == null ? "" : datasourceUsername);
        model.addAttribute("defaultCryptoKey", crypto.isUsingDefaultKey());
        model.addAttribute("packageMaxBytes", PACKAGE_MAX_BYTES);
        TlsPageState tlsState = tls.pageState();
        model.addAttribute("tls", tlsState);
        model.addAttribute("tlsMaxBytes", TlsCertificateService.MAX_BYTES);
        // 强制 HTTPS 只有在确实存在一条 HTTPS 通路时才开得了：应用内证书已在服务，
        // 或当前请求本身就是 HTTPS（反代终止 TLS）。否则开启后除回环外全部 403。
        model.addAttribute("httpsEnableBlocked", !httpsAvailable(tlsState, request));
        model.addAttribute("httpsBlockedKey", httpsBlockedKey(tlsState));
        return "system-config";
    }

    /**
     * Turns forced HTTPS on or off on the page.
     *
     * <p>On is allowed only from a request that is already secure or from loopback: every other
     * plaintext request would immediately be refused, including the next one from the
     * administrator who just clicked. Off is always allowed — refusing it would make the switch a
     * one-way door. A value pinned by the configuration file cannot be changed at all.
     */
    @PostMapping("/system-config/require-https")
    public String saveRequireHttps(@RequestParam(required = false) Boolean enabled,
                                   HttpServletRequest request,
                                   RedirectAttributes flash) {
        if (enabled == null) {
            flash.addFlashAttribute("error", "error.systemConfig.setting.invalid");
            return "redirect:/system-config";
        }
        if (requireHttps.isConfigControlled()) {
            flash.addFlashAttribute("error", "error.systemConfig.https.envControlled");
            return "redirect:/system-config";
        }
        TlsPageState tlsState = tls.pageState();
        if (enabled && !httpsAvailable(tlsState, request)) {
            flash.addFlashAttribute("error", httpsBlockedKey(tlsState));
            return "redirect:/system-config";
        }
        if (enabled && !HttpsSupport.isSecure(request)
                && !ClientIpResolver.isLoopback(request.getRemoteAddr())) {
            flash.addFlashAttribute("error", "error.systemConfig.https.requiresSecure");
            return "redirect:/system-config";
        }

        Optional<Boolean> previous =
                settings.putBoolean(SystemSettingKey.SECURITY_REQUIRE_HTTPS, enabled);
        audit.record(AuditAction.SYSTEM_SETTING_SAVE,
                AuditDetail.of("audit.detail.systemSettingSave", AuditOrigin.of(request),
                        SystemSettingKey.SECURITY_REQUIRE_HTTPS.key(),
                        previous.map(String::valueOf).orElse(""),
                        String.valueOf(enabled)));

        flash.addFlashAttribute("message", enabled
                ? "msg.systemConfig.https.enabled"
                : "msg.systemConfig.https.disabled");
        return "redirect:/system-config";
    }

    /** The app's own certificate is loaded and not expired, or this request already came in
     *  over HTTPS (a reverse proxy terminated TLS). Either way an HTTPS path exists. */
    private static boolean httpsAvailable(TlsPageState state, HttpServletRequest request) {
        if (state != null && state.active() && !state.expired()) {
            return true;
        }
        return HttpsSupport.isSecure(request);
    }

    /**
     * Why the switch is blocked. Deliberately distinguishes the three fixes: upload a usable
     * certificate, re-enable application TLS, or restart. "Saved but unusable" must not be
     * reported as a pending restart — restarting a disabled or unreadable connector changes
     * nothing.
     */
    private static String httpsBlockedKey(TlsPageState state) {
        if (state == null || !state.saved()) {
            return "error.systemConfig.https.requiresCertificate";
        }
        if (state.disabled()) {
            return "error.systemConfig.https.tlsDisabled";
        }
        if (state.expired()) {
            return "error.systemConfig.https.certificateExpired";
        }
        return state.certificate() == null
                ? "error.systemConfig.https.requiresCertificate"
                : "error.systemConfig.https.certificatePendingRestart";
    }

}
