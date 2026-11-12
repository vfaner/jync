package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.settings.RequireHttpsResolver;
import com.qqmu.jync.service.settings.SystemSettingService;
import com.qqmu.jync.service.tls.TlsCertificateInfo;
import com.qqmu.jync.service.tls.TlsCertificateService;
import com.qqmu.jync.service.tls.TlsPageState;
import com.qqmu.jync.util.CryptoUtil;

/**
 * The administrator's 系统配置 page: the configuration package, the diagnostics bundle, the
 * HTTPS switch and the TLS certificate. Read-only facts about the instance — including the
 * metadata database — live on 系统信息.
 *
 * <p>It never offers a field for {@code app.security.trusted-proxies}: that list decides which
 * peers may assert HTTPS and which forwarding headers are believed, so editing it from a web
 * form would let a compromised admin account defeat both. It stays in the configuration file.
 */
@WebMvcTest(SystemConfigController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = {
        "app.github-url=https://github.com/vfaner/jync",
        "app.update-check.enabled=false"
})
class SystemConfigViewTest {

    private static final RequireHttpsResolver.Effective HTTPS_OFF =
            new RequireHttpsResolver.Effective(false, RequireHttpsResolver.Source.DEFAULT);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RequireHttpsResolver requireHttpsResolver;
    @MockitoBean
    private SystemSettingService systemSettingService;
    @MockitoBean
    private AdminAuditService adminAuditService;
    @MockitoBean
    private CryptoUtil cryptoUtil;
    @MockitoBean
    private TlsCertificateService tlsCertificateService;

    private static RequestPostProcessor as(UserRole role) {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername(role == UserRole.ADMIN ? "admin" : "view");
        user.setPasswordHash("not-checked-here");
        user.setRole(role);
        SyncUserDetails principal = new SyncUserDetails(user, false);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private String render(UserRole role) throws Exception {
        return render(role, noTls());
    }

    private String render(UserRole role, TlsPageState tls) throws Exception {
        when(requireHttpsResolver.effective()).thenReturn(HTTPS_OFF);
        when(requireHttpsResolver.isConfigControlled()).thenReturn(false);
        when(tlsCertificateService.pageState()).thenReturn(tls);
        return mvc.perform(get("/system-config").locale(Locale.SIMPLIFIED_CHINESE).with(as(role)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static TlsPageState noTls() {
        return state(false, false, false, false, false, false, false, null, null, false, false,
                null, false);
    }

    private static TlsPageState state(boolean saved, boolean active, boolean pendingRestart,
                                      boolean removalPending, boolean disabled, boolean canRemove,
                                      boolean defaultKey, String problemKey,
                                      TlsCertificateInfo certificate, boolean expired,
                                      boolean expiresSoon, Integer httpsPort,
                                      boolean redirectHttp) {
        return new TlsPageState(saved, active, pendingRestart, removalPending, disabled, canRemove,
                defaultKey, problemKey, certificate, null, expired, expiresSoon, httpsPort,
                redirectHttp, null);
    }

    private static TlsCertificateInfo certificate(Instant notAfter) {
        return new TlsCertificateInfo("CN=localhost", "CN=localhost", "tomcat", "RSA",
                "00", Instant.now().minusSeconds(60), notAfter);
    }

    @Test
    void thePageCarriesTheAdministrativeCards() throws Exception {
        String page = render(UserRole.ADMIN);

        assertThat(page).contains("配置包", "强制 HTTPS", "TLS 证书", "诊断包");
    }

    @Test
    void theTlsFormOwnsTheWiderLeftColumnAndTheActionsStackOnTheRight() throws Exception {
        // .split gives the first column 1.35fr and the second 1fr, and cards render in document
        // order. The tall certificate card with its form fields needs the wider column, so it
        // comes first; the short action cards (package, diagnostics, HTTPS) share the right one.
        // The read-only metadata database block moved to 系统信息.
        String page = render(UserRole.ADMIN);

        int tls = page.indexOf("<span>TLS 证书</span>");
        int pkg = page.indexOf("<span>配置包</span>");
        int diagnostics = page.indexOf("<span>诊断包</span>");
        int https = page.indexOf("<span>强制 HTTPS</span>");
        assertThat(tls).isGreaterThan(-1);
        assertThat(pkg).isGreaterThan(tls);
        assertThat(diagnostics).isGreaterThan(pkg);
        assertThat(https).isGreaterThan(diagnostics);
    }

    @Test
    void theMetadataDatabaseBlockIsNoLongerOnThisPage() throws Exception {
        // It is read-only, so it belongs with the other instance facts on 系统信息; this page is
        // the administrator's action list.
        String page = render(UserRole.ADMIN);

        assertThat(page).doesNotContain("连接密钥", "<span>元数据库</span>");
    }

    @Test
    void theDiagnosticsBundleIsOfferedHere() throws Exception {
        String page = render(UserRole.ADMIN);

        assertThat(page).contains("下载诊断包")
                .contains("action=\"/api/diagnostics/bundle\"");
    }

    @Test
    void exportAndImportShareOneCardAsTabsWithExportFirst() throws Exception {
        String page = render(UserRole.ADMIN);

        int exportTab = page.indexOf("id=\"pkg-tab-export\"");
        int importTab = page.indexOf("id=\"pkg-tab-import\"");
        assertThat(exportTab).isGreaterThan(-1);
        assertThat(importTab).isGreaterThan(exportTab);
        assertThat(page.substring(exportTab, page.indexOf('>', exportTab))).contains("checked");
        assertThat(page.substring(importTab, page.indexOf('>', importTab)))
                .doesNotContain("checked");
        // Panels switch with the radios alone: no JavaScript, so the forms still work with
        // scripting disabled.
        assertThat(page.indexOf("data-mode=\"export\""))
                .isLessThan(page.indexOf("data-mode=\"import\""));
    }

    @Test
    void theSystemGroupIsTheActiveOneHere() throws Exception {
        assertThat(render(UserRole.ADMIN)).contains("nav-system is-active");
    }

    @Test
    void trustedProxiesAreNotEditableFromThePage() throws Exception {
        String page = render(UserRole.ADMIN);

        assertThat(page).doesNotContain("name=\"trustedProxies\"")
                .doesNotContain("name=\"trusted-proxies\"");
    }

    @Test
    void thePageNoLongerOffersAMetadataDatabaseConnectionTester() throws Exception {
        // The metadata database is chosen by SPRING_DATASOURCE_* at startup; a connection probe
        // on this page added a form, a script and an endpoint without changing anything it
        // showed. Validation stays with whatever client the operator already has.
        String page = render(UserRole.ADMIN);

        assertThat(page).doesNotContain("metadata-db-test-form")
                .doesNotContain("/system-config/db-test")
                .doesNotContain("id=\"db-test-url\"")
                .doesNotContain("system-config.js");
    }

    @Test
    void everyFormControlCarriesTheSharedInputStyling() throws Exception {
        String page = render(UserRole.ADMIN);

        // app.css has no .form-grid/.form-actions/.check — a page that reaches for them renders
        // as bare stacked labels and default browser controls.
        assertThat(page).doesNotContain("form-grid", "form-actions", "class=\"check\"");

        for (String id : List.of("export-passphrase", "export-passphrase-confirm",
                "import-file", "import-passphrase", "tls-file", "tls-passphrase", "tls-port",
                "tls-cert-file", "tls-key-file", "tls-pem-port")) {
            int at = page.indexOf("id=\"" + id + "\"");
            assertThat(at).as("field %s", id).isGreaterThan(-1);
            String tag = page.substring(page.lastIndexOf('<', at), page.indexOf('>', at));
            assertThat(tag).as("field %s uses .input", id).contains("class=\"input");
        }
    }

    @Test
    void tlsCardShowsSavedActiveAndPendingRestart() throws Exception {
        String page = render(UserRole.ADMIN, state(true, false, true, false, false, true, false,
                null, certificate(Instant.now().plusSeconds(86_400)), false, false, 8443, true));

        assertThat(page).contains("已保存", "仅 HTTP", "等待重启生效", "8443");
    }

    @Test
    void tlsUploadFormIsMultipartAndCarriesCsrfInTheAction() throws Exception {
        String page = render(UserRole.ADMIN);

        assertThat(page).contains("enctype=\"multipart/form-data\"")
                .containsPattern("action=\"/system-config/tls[^\"]*_csrf=");
        assertThat(page).containsPattern("accept=\"\\.p12,\\.pfx,\\.jks\"");
    }

    @Test
    void thePemFormTakesACertificateAndAKeyWithoutAPassphrase() throws Exception {
        String page = render(UserRole.ADMIN);

        int cert = page.indexOf("id=\"tls-cert-file\"");
        assertThat(cert).isGreaterThan(-1);
        String form = page.substring(page.lastIndexOf("<form", cert),
                page.indexOf("</form>", cert));
        assertThat(form).contains("enctype=\"multipart/form-data\"", "name=\"certFile\"",
                "name=\"keyFile\"", "name=\"httpsPort\"", "action=\"/system-config/tls");
        // nginx-style PEM has no keystore password; an encrypted key is rejected with its own key.
        assertThat(form).doesNotContain("type=\"password\"");
    }

    @Test
    void theTwoUploadModesAreTabsWithPemFirst() throws Exception {
        String page = render(UserRole.ADMIN);

        int pem = page.indexOf("id=\"tls-mode-pem\"");
        int keystore = page.indexOf("id=\"tls-mode-keystore\"");
        assertThat(pem).isGreaterThan(-1);
        assertThat(keystore).isGreaterThan(pem);
        assertThat(page.substring(pem, page.indexOf('>', pem))).contains("checked");
        assertThat(page.indexOf("data-mode=\"pem\""))
                .isLessThan(page.indexOf("data-mode=\"keystore\""));
    }

    @Test
    void thePemKeyFileIsOptionalForACombinedFile() throws Exception {
        // The certificate file may already carry the private key (HAProxy-style bundle).
        String page = render(UserRole.ADMIN);

        int field = page.indexOf("id=\"tls-key-file\"");
        assertThat(field).isGreaterThan(-1);
        String tag = page.substring(page.lastIndexOf('<', field), page.indexOf('>', field));
        assertThat(tag).doesNotContain("required");
    }

    @Test
    void tlsPassphraseIsNeverRenderedBack() throws Exception {
        String page = render(UserRole.ADMIN);

        int field = page.indexOf("id=\"tls-passphrase\"");
        assertThat(field).isGreaterThan(-1);
        String tag = page.substring(page.lastIndexOf('<', field), page.indexOf('>', field));
        assertThat(tag).doesNotContain("value=");
    }

    @Test
    void theRemoveFormIsShownOnlyWhenSomethingIsSavedOrCorrupt() throws Exception {
        assertThat(render(UserRole.ADMIN)).doesNotContain("action=\"/system-config/tls/delete\"");

        String corrupted = render(UserRole.ADMIN, state(false, false, false, false, false, true,
                false, "error.tls.storeCorrupt", null, false, false, null, false));
        assertThat(corrupted).contains("action=\"/system-config/tls/delete\"");
    }

    @Test
    void theExpiredChipIsShownForAnExpiredCertificate() throws Exception {
        String page = render(UserRole.ADMIN, state(true, false, true, false, false, true, false,
                null, certificate(Instant.now().minusSeconds(60)), true, false, 8443, false));

        assertThat(page).contains("已过期");
    }

    @Test
    void theDisabledNoticeIsShownWhenTlsIsDisabled() throws Exception {
        String page = render(UserRole.ADMIN, state(true, false, true, false, true, true, false,
                null, certificate(Instant.now().plusSeconds(86_400)), false, false, 8443, false));

        assertThat(page).contains("app.tls.enabled=false");
    }

    @Test
    void theCardExplainsTheRestartRequirement() throws Exception {
        String page = render(UserRole.ADMIN, state(true, false, true, false, false, true, false,
                null, certificate(Instant.now().plusSeconds(86_400)), false, false, 8443, false));

        assertThat(page).contains("重启 Jync 后保存的证书才会生效");
    }

    @Test
    void theHttpsSwitchIsBlockedUntilACertificateIsServing() throws Exception {
        String page = render(UserRole.ADMIN);

        assertThat(page).contains("开启强制 HTTPS 前，需要先上传有效证书并重启");
        int form = page.indexOf("action=\"/system-config/require-https\"");
        assertThat(form).isGreaterThan(-1);
        assertThat(page.substring(form, page.indexOf("</form>", form))).contains("disabled");
    }

    @Test
    void theHttpsSwitchIsAvailableWhenTheCertificateIsServing() throws Exception {
        String page = render(UserRole.ADMIN, state(true, true, false, false, false, true, false,
                null, certificate(Instant.now().plusSeconds(86_400)), false, false, 8443, true));

        assertThat(page).doesNotContain("开启强制 HTTPS 前，需要先上传有效证书并重启");
        int form = page.indexOf("action=\"/system-config/require-https\"");
        assertThat(page.substring(form, page.indexOf("</form>", form))).doesNotContain("disabled");
    }

    @Test
    void theBlockedSwitchExplainsThatAnExpiredCertificateDoesNotCount() throws Exception {
        String page = render(UserRole.ADMIN, state(true, true, false, false, false, true, false,
                null, certificate(Instant.now().minusSeconds(60)), true, false, 8443, true));

        assertThat(page).contains("当前证书已过期");
    }

    @Test
    void theBlockedSwitchNamesDisabledTlsEvenBeforeACertificateIsSaved() throws Exception {
        // Uploading a certificate cannot help while app.tls.enabled is false; "upload a
        // certificate" would send the operator down the wrong path.
        String page = render(UserRole.ADMIN, state(false, false, false, false, true, false, false,
                null, null, false, false, null, false));

        assertThat(page).contains("应用内 TLS 已被配置禁用");
    }

    @Test
    void aReuploadKeepsTheSavedPortAndRedirectChoiceAsDefaults() throws Exception {
        // The form used to hard-code 8443 and a checked redirect box, so uploading a renewed
        // certificate silently moved the port back and re-enabled redirection.
        String page = render(UserRole.ADMIN, state(true, true, false, false, false, true, false,
                null, certificate(Instant.now().plusSeconds(86_400)), false, false, 9443, false));

        for (String id : List.of("tls-port", "tls-pem-port")) {
            int at = page.indexOf("id=\"" + id + "\"");
            assertThat(at).as("field %s", id).isGreaterThan(-1);
            String tag = page.substring(page.lastIndexOf('<', at), page.indexOf('>', at));
            assertThat(tag).as("field %s", id).contains("value=\"9443\"");
        }
        int found = 0;
        for (int at = page.indexOf("name=\"redirectHttp\""); at >= 0;
             at = page.indexOf("name=\"redirectHttp\"", at + 1)) {
            found++;
            String tag = page.substring(page.lastIndexOf('<', at), page.indexOf('>', at));
            assertThat(tag).doesNotContain("checked");
        }
        assertThat(found).as("both upload forms offer the redirect choice").isEqualTo(2);
    }

    @Test
    void passphraseFieldsAskTheBrowserNotToRememberThem() throws Exception {
        String page = render(UserRole.ADMIN);

        assertThat(page).containsPattern("type=\"password\"[^>]*autocomplete=\"new-password\"");
        // Export, its confirmation, and import each get a field.
        assertThat(page.split("autocomplete=\"new-password\"", -1)).hasSizeGreaterThanOrEqualTo(4);
    }
}
