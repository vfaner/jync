package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.config.ConfigExportService;
import com.qqmu.jync.service.config.ConfigImportService;
import com.qqmu.jync.service.config.ConfigPackageException;
import com.qqmu.jync.service.settings.RequireHttpsResolver;
import com.qqmu.jync.service.settings.SystemSettingKey;
import com.qqmu.jync.service.settings.SystemSettingService;
import com.qqmu.jync.service.tls.TlsCertificateInfo;
import com.qqmu.jync.service.tls.TlsCertificateService;
import com.qqmu.jync.service.tls.TlsPageState;
import com.qqmu.jync.util.CryptoUtil;

/**
 * The configuration-package endpoints and the page's forced-HTTPS switch.
 *
 * <p>Three rules drive these tests. The passphrase never reaches the audit trail; a failure the
 * caller sees is a message key, never an exception message that might quote a value; and turning
 * forced HTTPS on is allowed only from a request that is already secure or from loopback, because
 * otherwise the switch locks the administrator out of the page they are standing on.
 */
@WebMvcTest({SystemConfigController.class, ConfigPackageController.class})
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
@WithMockUser(roles = "ADMIN")
class ConfigPackageControllerTest {

    private static final String PASSPHRASE = "correct horse battery";

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ConfigExportService exportService;
    @MockBean
    private ConfigImportService importService;
    @MockBean
    private RequireHttpsResolver requireHttps;
    @MockBean
    private SystemSettingService systemSettings;
    @MockBean
    private AdminAuditService audit;
    @MockBean
    private CryptoUtil cryptoUtil;
    // Only the POST endpoints are exercised here; the page state is covered by
    // SystemConfigViewTest. The mock keeps the controller's constructor satisfied.
    @MockBean
    private TlsCertificateService tlsCertificateService;

    // --- export --------------------------------------------------------------------------

    @Test
    void aShortPassphraseIsRefusedBeforeAnythingIsBuilt() throws Exception {
        mvc.perform(post("/system-config/export").with(csrf())
                        .param("passphrase", "short")
                        .param("confirmPassphrase", "short"))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("error", "error.config.passphrase.tooShort"));

        verify(exportService, never()).build(any(), anyBoolean());
    }

    @Test
    void mismatchedPassphrasesAreRefused() throws Exception {
        mvc.perform(post("/system-config/export").with(csrf())
                        .param("passphrase", PASSPHRASE)
                        .param("confirmPassphrase", PASSPHRASE + "!"))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("error", "error.config.passphrase.mismatch"));

        verify(exportService, never()).build(any(), anyBoolean());
    }

    @Test
    void aBuiltPackageIsWrittenAsAnAttachment() throws Exception {
        byte[] sealed = "JYNC-not-really-a-package".getBytes(StandardCharsets.US_ASCII);
        when(exportService.build(any(), eq(true))).thenReturn(packageOf(sealed));

        mvc.perform(post("/system-config/export").with(csrf())
                        .param("passphrase", PASSPHRASE)
                        .param("confirmPassphrase", PASSPHRASE)
                        .param("includeState", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("jync-config-1.jync")))
                .andExpect(content().bytes(sealed));

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq(AuditAction.CONFIG_EXPORT), detail.capture());
        // The audit trail records that a package was written, never what sealed it.
        assertThat(detail.getValue()).doesNotContain(PASSPHRASE).contains("14");
    }

    @Test
    void anExportFailureIsReportedByKey() throws Exception {
        when(exportService.build(any(), anyBoolean()))
                .thenThrow(new ConfigPackageException("error.config.export.tooLarge"));

        mvc.perform(post("/system-config/export").with(csrf())
                        .param("passphrase", PASSPHRASE)
                        .param("confirmPassphrase", PASSPHRASE))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("error", "error.config.export.tooLarge"));
    }

    // --- import --------------------------------------------------------------------------

    @Test
    void anEmptyFileIsRefused() throws Exception {
        mvc.perform(multipart("/system-config/import")
                        .file(new MockMultipartFile("file", "pkg.jync",
                                "application/octet-stream", new byte[0]))
                        .param("passphrase", PASSPHRASE)
                        .with(csrf()))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("error", "error.config.import.fileRequired"));

        verify(importService, never()).importPackage(any(), any(), anyBoolean());
    }

    @Test
    void aMissingFileIsRefused() throws Exception {
        mvc.perform(multipart("/system-config/import")
                        .param("passphrase", PASSPHRASE)
                        .with(csrf()))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("error", "error.config.import.fileRequired"));

        verify(importService, never()).importPackage(any(), any(), anyBoolean());
    }

    @Test
    void anOversizedFileIsRefusedBeforeItIsRead() throws Exception {
        byte[] tooBig = new byte[(int) SystemConfigController.PACKAGE_MAX_BYTES + 1];

        mvc.perform(multipart("/system-config/import")
                        .file(new MockMultipartFile("file", "pkg.jync",
                                "application/octet-stream", tooBig))
                        .param("passphrase", PASSPHRASE)
                        .with(csrf()))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("error", "error.config.import.fileTooLarge"));

        verify(importService, never()).importPackage(any(), any(), anyBoolean());
    }

    @Test
    void importSuccessFlashesTheCountsAndWarnings() throws Exception {
        when(importService.importPackage(any(), any(), eq(false))).thenReturn(new ConfigImportService
                .ImportResult(2, 1, 1, 3, 0, 2, 1, 1, 1, 1, 4, true, true,
                        List.of("warn.config.import.httpsReset")));

        mvc.perform(multipart("/system-config/import")
                        .file(new MockMultipartFile("file", "pkg.jync",
                                "application/octet-stream", new byte[] {1, 2, 3}))
                        .param("passphrase", PASSPHRASE)
                        .with(csrf()))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("message", "msg.systemConfig.import.done"))
                .andExpect(flash().attribute("importDatabases", 2))
                .andExpect(flash().attribute("importProjects", 3))
                .andExpect(flash().attribute("importWarnings",
                        List.of("warn.config.import.httpsReset")));

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq(AuditAction.CONFIG_IMPORT), detail.capture());
        assertThat(detail.getValue()).doesNotContain(PASSPHRASE);
    }

    @Test
    void aRefusedPackageShowsOnlyItsKey() throws Exception {
        when(importService.importPackage(any(), any(), anyBoolean()))
                .thenThrow(new ConfigPackageException("error.config.import.notEmpty"));

        mvc.perform(multipart("/system-config/import")
                        .file(new MockMultipartFile("file", "pkg.jync",
                                "application/octet-stream", new byte[] {1, 2, 3}))
                        .param("passphrase", PASSPHRASE)
                        .with(csrf()))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("error", "error.config.import.notEmpty"));
    }

    // --- forced HTTPS --------------------------------------------------------------------

    @Test
    void enablingWithoutAServingCertificateIsRefused() throws Exception {
        when(requireHttps.isConfigControlled()).thenReturn(false);
        when(tlsCertificateService.pageState()).thenReturn(tls(false, false, false));

        mvc.perform(post("/system-config/require-https").with(csrf())
                        .param("enabled", "true")
                        .with(remoteAddress("127.0.0.1")))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("error",
                        "error.systemConfig.https.requiresCertificate"));

        verify(systemSettings, never()).putBoolean(any(), anyBoolean());
    }

    @Test
    void enablingWaitsForTheSavedCertificateToBeServing() throws Exception {
        when(requireHttps.isConfigControlled()).thenReturn(false);
        when(tlsCertificateService.pageState()).thenReturn(tls(true, false, false));

        mvc.perform(post("/system-config/require-https").with(csrf())
                        .param("enabled", "true")
                        .with(remoteAddress("127.0.0.1")))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("error",
                        "error.systemConfig.https.certificatePendingRestart"));

        verify(systemSettings, never()).putBoolean(any(), anyBoolean());
    }

    @Test
    void enablingWhileTlsIsDisabledSaysTheRestartWouldNotHelp() throws Exception {
        when(requireHttps.isConfigControlled()).thenReturn(false);
        // Saved and valid, but app.tls.enabled=false keeps the connector from ever starting.
        when(tlsCertificateService.pageState()).thenReturn(new TlsPageState(
                true, false, true, false, true, true, false, null, null, null,
                false, false, 8443, true, null));

        mvc.perform(post("/system-config/require-https").with(csrf())
                        .param("enabled", "true")
                        .with(remoteAddress("127.0.0.1")))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("error",
                        "error.systemConfig.https.tlsDisabled"));

        verify(systemSettings, never()).putBoolean(any(), anyBoolean());
    }

    @Test
    void enablingWhileTlsIsDisabledAndNothingIsSavedNamesTheConfigurationToo() throws Exception {
        when(requireHttps.isConfigControlled()).thenReturn(false);
        // Uploading cannot unblock the switch while the connector is disabled at startup, so
        // "upload a certificate" is the wrong instruction for this state.
        when(tlsCertificateService.pageState()).thenReturn(new TlsPageState(
                false, false, false, false, true, false, false, null, null, null,
                false, false, null, false, null));

        mvc.perform(post("/system-config/require-https").with(csrf())
                        .param("enabled", "true")
                        .with(remoteAddress("127.0.0.1")))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("error", "error.systemConfig.https.tlsDisabled"));

        verify(systemSettings, never()).putBoolean(any(), anyBoolean());
    }

    @Test
    void anUnreadableSavedCertificateAsksForANewUpload() throws Exception {
        when(requireHttps.isConfigControlled()).thenReturn(false);
        // saved=true but the key cannot be opened, so a restart is not the fix.
        when(tlsCertificateService.pageState()).thenReturn(new TlsPageState(
                true, false, true, false, false, true, false, "error.tls.unreadable",
                null, null, false, false, 8443, true, null));

        mvc.perform(post("/system-config/require-https").with(csrf())
                        .param("enabled", "true")
                        .with(remoteAddress("127.0.0.1")))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("error",
                        "error.systemConfig.https.requiresCertificate"));

        verify(systemSettings, never()).putBoolean(any(), anyBoolean());
    }

    @Test
    void anExpiredServingCertificateDoesNotUnlockTheSwitch() throws Exception {
        when(requireHttps.isConfigControlled()).thenReturn(false);
        when(tlsCertificateService.pageState()).thenReturn(tls(true, true, true));

        mvc.perform(post("/system-config/require-https").with(csrf())
                        .param("enabled", "true")
                        .with(remoteAddress("127.0.0.1")))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("error",
                        "error.systemConfig.https.certificateExpired"));

        verify(systemSettings, never()).putBoolean(any(), anyBoolean());
    }

    @Test
    void enablingFromAPlaintextRemoteRequestIsRefusedEvenWithAServingCertificate()
            throws Exception {
        when(requireHttps.isConfigControlled()).thenReturn(false);
        when(tlsCertificateService.pageState()).thenReturn(tls(true, true, false));

        mvc.perform(post("/system-config/require-https").with(csrf())
                        .param("enabled", "true")
                        .with(remoteAddress("10.1.2.3")))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("error", "error.systemConfig.https.requiresSecure"));

        verify(systemSettings, never()).putBoolean(any(), anyBoolean());
    }

    @Test
    void enablingFromLoopbackIsAllowedOnceTheCertificateIsServing() throws Exception {
        when(requireHttps.isConfigControlled()).thenReturn(false);
        when(tlsCertificateService.pageState()).thenReturn(tls(true, true, false));
        when(systemSettings.putBoolean(SystemSettingKey.SECURITY_REQUIRE_HTTPS, true))
                .thenReturn(Optional.empty());

        mvc.perform(post("/system-config/require-https").with(csrf())
                        .param("enabled", "true")
                        .with(remoteAddress("127.0.0.1")))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("message", "msg.systemConfig.https.enabled"));

        verify(systemSettings).putBoolean(SystemSettingKey.SECURITY_REQUIRE_HTTPS, true);
    }

    @Test
    void enablingBehindAProxyIsAllowedWithoutAnApplicationCertificate() throws Exception {
        // The proxy's X-Forwarded-Proto makes the request secure; Jync needs no certificate of
        // its own, and refusing here would break every proxy deployment.
        when(requireHttps.isConfigControlled()).thenReturn(false);
        when(tlsCertificateService.pageState()).thenReturn(tls(false, false, false));
        when(systemSettings.putBoolean(SystemSettingKey.SECURITY_REQUIRE_HTTPS, true))
                .thenReturn(Optional.empty());

        mvc.perform(post("/system-config/require-https").with(csrf())
                        .param("enabled", "true")
                        .with(secureRequest()))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("message", "msg.systemConfig.https.enabled"));

        verify(systemSettings).putBoolean(SystemSettingKey.SECURITY_REQUIRE_HTTPS, true);
    }

    @Test
    void aConfigurationPinnedSwitchCannotBeChanged() throws Exception {
        when(requireHttps.isConfigControlled()).thenReturn(true);

        mvc.perform(post("/system-config/require-https").with(csrf())
                        .param("enabled", "false")
                        .with(remoteAddress("127.0.0.1")))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("error", "error.systemConfig.https.envControlled"));

        verify(systemSettings, never()).putBoolean(any(), anyBoolean());
    }

    @Test
    void disablingIsAlwaysAllowed() throws Exception {
        when(requireHttps.isConfigControlled()).thenReturn(false);
        when(systemSettings.putBoolean(SystemSettingKey.SECURITY_REQUIRE_HTTPS, false))
                .thenReturn(Optional.of(true));

        mvc.perform(post("/system-config/require-https").with(csrf())
                        .param("enabled", "false")
                        .with(remoteAddress("10.1.2.3")))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("message", "msg.systemConfig.https.disabled"));

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq(AuditAction.SYSTEM_SETTING_SAVE), detail.capture());
        assertThat(detail.getValue()).contains("security.require-https").contains("true");
    }

    // --- fixtures ------------------------------------------------------------------------

    private static ConfigExportService.ConfigPackage packageOf(byte[] bytes) {
        return new ConfigExportService.ConfigPackage(bytes, "jync-config-1.jync", 14,
                2, 1, 1, 3, 0, 2, 1, 1, 2, 4, true);
    }

    private static RequestPostProcessor remoteAddress(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private static RequestPostProcessor secureRequest() {
        return request -> {
            request.setSecure(true);
            return request;
        };
    }

    private static TlsPageState tls(boolean saved, boolean active, boolean expired) {
        TlsCertificateInfo certificate = saved
                ? new TlsCertificateInfo("CN=misconfigured.example", "CN=misconfigured.example",
                        "jync", "RSA", "abc", Instant.EPOCH,
                        Instant.parse("2100-01-01T00:00:00Z"))
                : null;
        return new TlsPageState(saved, active, saved && !active, false, false, saved, false,
                null, certificate, active ? certificate : null, expired, false,
                saved ? 8443 : null, true, null);
    }
}
