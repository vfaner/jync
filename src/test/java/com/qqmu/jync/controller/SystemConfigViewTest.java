package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
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
import com.qqmu.jync.service.settings.UpdateCheckResolver;
import com.qqmu.jync.service.tls.TlsCertificateInfo;
import com.qqmu.jync.service.tls.TlsCertificateService;
import com.qqmu.jync.service.tls.TlsConfiguredPaths;
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
    @MockitoBean
    private UpdateCheckResolver updateCheckResolver;

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

    @BeforeEach
    void defaultSwitches() {
        when(requireHttpsResolver.effective()).thenReturn(HTTPS_OFF);
        when(requireHttpsResolver.isConfigControlled()).thenReturn(false);
        when(updateCheckResolver.effective()).thenReturn(
                new UpdateCheckResolver.Effective(true, UpdateCheckResolver.Source.DEFAULT));
        when(updateCheckResolver.isConfigControlled()).thenReturn(false);
    }

    private String render(UserRole role, TlsPageState tls) throws Exception {
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
                redirectHttp, null, null, null);
    }

    private static TlsPageState configuredState(TlsConfiguredPaths paths) {
        return new TlsPageState(true, true, false, false, false, false, false, null,
                certificate(Instant.now().plusSeconds(86_400)), null, false, false, 8443, true,
                null, paths, null);
    }

    private static TlsPageState serverPathState() {
        return new TlsPageState(true, false, true, false, false, true, false, null,
                certificate(Instant.now().plusSeconds(86_400)), null, false, false, 8443, true,
                null, null, new TlsConfiguredPaths("/etc/jync/tls/server.p12", null, null));
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
        // comes first; the short action cards share the right one, with the transport switch at
        // its top. The read-only metadata database block moved to 系统信息.
        String page = render(UserRole.ADMIN);

        int tls = page.indexOf("<span>TLS 证书</span>");
        int https = page.indexOf("<span>强制 HTTPS</span>");
        int pkg = page.indexOf("<span>配置包</span>");
        int diagnostics = page.indexOf("<span>诊断包</span>");
        int updateCheck = page.indexOf("<span>更新检查</span>");
        assertThat(tls).isGreaterThan(-1);
        assertThat(https).isGreaterThan(tls);
        assertThat(pkg).isGreaterThan(https);
        assertThat(diagnostics).isGreaterThan(pkg);
        assertThat(updateCheck).isGreaterThan(diagnostics);
    }

    @Test
    void theUpdateCheckCardIsAnActionOnThisPage() throws Exception {
        String page = render(UserRole.ADMIN);

        assertThat(page).contains("<span>更新检查</span>")
                .contains("action=\"/system-config/update-check\"");
    }

    @Test
    void theUpdateCheckCardShowsTheOffStateAndWhereItWasSet() throws Exception {
        when(updateCheckResolver.effective()).thenReturn(
                new UpdateCheckResolver.Effective(false, UpdateCheckResolver.Source.DATABASE));
        String page = render(UserRole.ADMIN);

        assertThat(page).contains("未开启", "本页设置");
    }

    @Test
    void aPinnedUpdateCheckSwitchIsReadOnly() throws Exception {
        when(updateCheckResolver.effective()).thenReturn(
                new UpdateCheckResolver.Effective(false, UpdateCheckResolver.Source.CONFIG));
        when(updateCheckResolver.isConfigControlled()).thenReturn(true);
        String page = render(UserRole.ADMIN);

        assertThat(page).contains("该开关由配置文件固定");
        int form = page.indexOf("action=\"/system-config/update-check\"");
        assertThat(form).isGreaterThan(-1);
        assertThat(page.substring(form, page.indexOf("</form>", form))).contains("disabled");
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
                "import-file", "import-passphrase", "tls-keystore-path", "tls-passphrase",
                "tls-port", "tls-cert-path", "tls-key-path", "tls-pem-port")) {
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
    void aCertificateFromTheConfigurationFileMakesTheTlsCardReadOnly() throws Exception {
        // Container deployments mount the certificate read-only and name it in app.tls.*;
        // a page that still offered upload/delete would write into app.tls.directory, which the
        // next container recreation throws away.
        String page = render(UserRole.ADMIN,
                configuredState(new TlsConfiguredPaths("/etc/jync/tls/jync.p12", null, null)));

        assertThat(page).contains("/etc/jync/tls/jync.p12", "app.tls.keystore-path");
        assertThat(page).doesNotContain("id=\"tls-keystore-path\"", "id=\"tls-cert-path\"",
                "id=\"tls-key-path\"", "id=\"tls-mode-pem\"",
                "action=\"/system-config/tls/delete\"", "action=\"/system-config/tls/path",
                "data-upload-url=\"/system-config/tls/stage\"", "重启 Jync 后保存的证书才会生效");
    }

    @Test
    void aConfiguredPemPairShowsBothFiles() throws Exception {
        String page = render(UserRole.ADMIN, configuredState(new TlsConfiguredPaths(null,
                "/etc/jync/tls/fullchain.pem", "/etc/jync/tls/privkey.pem")));

        assertThat(page).contains("app.tls.pem-cert-path",
                "/etc/jync/tls/fullchain.pem", "/etc/jync/tls/privkey.pem");
        assertThat(page).doesNotContain("id=\"tls-keystore-path\"",
                "action=\"/system-config/tls/delete\"");
    }

    @Test
    void theCertificateFieldsArePathInputsWithAnUploadButton() throws Exception {
        // 文件字段是「路径输入框 + 上传按钮」：上传只把文件暂存到数据目录并回填绝对路径，
        // 保存仍按路径处理；也可以直接手填服务器上已有的文件。
        String page = render(UserRole.ADMIN);

        for (String id : List.of("tls-keystore-path", "tls-cert-path", "tls-key-path")) {
            int at = page.indexOf("id=\"" + id + "\"");
            assertThat(at).as("field %s", id).isGreaterThan(-1);
            String tag = page.substring(page.lastIndexOf('<', at), page.indexOf('>', at));
            assertThat(tag).as("field %s is a text input", id).contains("type=\"text\"");
            assertThat(page).as("field %s has an upload button", id)
                    .contains("data-tls-upload=\"" + id + "\"");
        }
        assertThat(page).containsPattern("action=\"/system-config/tls/path[^\"]*_csrf=")
                .contains("data-upload-url=\"/system-config/tls/stage\"")
                .containsPattern("accept=\"\\.p12,\\.pfx,\\.jks\"");
        // 证书表单只提交路径；页面上仅剩「配置包导入」仍是 multipart 表单。
        assertThat(page.split("enctype=\"multipart/form-data\"", -1)).hasSize(2);
    }

    @Test
    void thePemFormTakesACertificatePathAndAnOptionalKeyPathWithoutAPassphrase() throws Exception {
        String page = render(UserRole.ADMIN);

        int cert = page.indexOf("id=\"tls-cert-path\"");
        assertThat(cert).isGreaterThan(-1);
        String form = page.substring(page.lastIndexOf("<form", cert),
                page.indexOf("</form>", cert));
        assertThat(form).contains("name=\"pemCertPath\"", "name=\"pemKeyPath\"",
                "name=\"httpsPort\"", "action=\"/system-config/tls/path");
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
    void aSavedServerPathIsShownAndPrefilledInTheMatchingTab() throws Exception {
        String page = render(UserRole.ADMIN, serverPathState());

        assertThat(page).contains("来自服务器路径", "/etc/jync/tls/server.p12");
        int field = page.indexOf("id=\"tls-keystore-path\"");
        assertThat(field).isGreaterThan(-1);
        String tag = page.substring(page.lastIndexOf('<', field), page.indexOf('>', field));
        assertThat(tag).contains("value=\"/etc/jync/tls/server.p12\"");
    }

    @Test
    void thePemKeyPathIsOptionalForACombinedFile() throws Exception {
        // The certificate file may already carry the private key (HAProxy-style bundle).
        String page = render(UserRole.ADMIN);

        int field = page.indexOf("id=\"tls-key-path\"");
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
        assertThat(found).as("both certificate forms offer the redirect choice").isEqualTo(2);
    }

    @Test
    void passphraseFieldsAskTheBrowserNotToRememberThem() throws Exception {
        String page = render(UserRole.ADMIN);

        assertThat(page).containsPattern("type=\"password\"[^>]*autocomplete=\"new-password\"");
        // Export, its confirmation, and import each get a field.
        assertThat(page.split("autocomplete=\"new-password\"", -1)).hasSizeGreaterThanOrEqualTo(4);
    }
}
