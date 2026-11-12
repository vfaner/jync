package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.settings.RequireHttpsResolver;
import com.qqmu.jync.service.settings.SystemSettingService;
import com.qqmu.jync.service.tls.TlsCertificateService;
import com.qqmu.jync.service.tls.TlsConfig;
import com.qqmu.jync.service.tls.TlsKeystoreException;
import com.qqmu.jync.service.tls.TlsKeystoreFormat;

/**
 * The two TLS endpoints on the 系统配置 page.
 *
 * <p>The upload carries a keystore and its passphrase, so every failure reaches the page as an
 * i18n key: an exception's message could quote the passphrase, and the audit row records only
 * the port, the redirect choice and the first twelve hex digits of the digest.
 */
@WebMvcTest({TlsCertificateController.class})
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
@WithMockUser(roles = "ADMIN")
class TlsCertificateControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private TlsCertificateService service;
    @MockitoBean
    private AdminAuditService audit;
    @MockitoBean
    private RequireHttpsResolver requireHttps;
    @MockitoBean
    private SystemSettingService systemSettings;

    private static MockMultipartFile keystoreFile() {
        return new MockMultipartFile("file", "certificate.p12", "application/octet-stream",
                new byte[] {1, 2, 3});
    }

    @Test
    void anEmptyFileIsRefused() throws Exception {
        mvc.perform(multipart("/system-config/tls")
                        .file(new MockMultipartFile("file", "certificate.p12", null, new byte[0]))
                        .param("passphrase", "changeit")
                        .param("httpsPort", "8443")
                        .with(csrf()))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("error", "error.tls.fileRequired"));

        verify(service, never()).save(any(), anyString(), anyString(), anyInt(), anyBoolean());
    }

    @Test
    void anOversizedFileIsRefusedBeforeItIsRead() throws Exception {
        byte[] tooBig = new byte[TlsCertificateService.MAX_BYTES + 1];

        mvc.perform(multipart("/system-config/tls")
                        .file(new MockMultipartFile("file", "certificate.p12", null, tooBig))
                        .param("passphrase", "changeit")
                        .param("httpsPort", "8443")
                        .with(csrf()))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("error", "error.tls.fileTooLarge"));

        verify(service, never()).save(any(), anyString(), anyString(), anyInt(), anyBoolean());
    }

    @Test
    void aNonNumericPortIsRefused() throws Exception {
        mvc.perform(multipart("/system-config/tls")
                        .file(keystoreFile())
                        .param("passphrase", "changeit")
                        .param("httpsPort", "eighty")
                        .with(csrf()))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("error", "error.tls.portInvalid"));

        verify(service, never()).save(any(), anyString(), anyString(), anyInt(), anyBoolean());
    }

    @Test
    void aRejectedUploadShowsItsKeyNotTheExceptionMessage() throws Exception {
        when(service.save(any(), anyString(), anyString(), anyInt(), anyBoolean()))
                .thenThrow(new TlsKeystoreException("error.tls.unreadable"));

        mvc.perform(multipart("/system-config/tls")
                        .file(keystoreFile())
                        .param("passphrase", "wrong")
                        .param("httpsPort", "8443")
                        .with(csrf()))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("error", "error.tls.unreadable"));
    }

    @Test
    void aSuccessfulUploadFlashesAndIsAuditedWithoutThePassphrase() throws Exception {
        when(service.save(any(), anyString(), anyString(), eq(8443), eq(true)))
                .thenReturn(config());

        mvc.perform(multipart("/system-config/tls")
                        .file(keystoreFile())
                        .param("passphrase", "changeit")
                        .param("httpsPort", "8443")
                        .param("redirectHttp", "true")
                        .with(csrf()))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("message", "msg.systemConfig.tls.saved"));

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq(AuditAction.TLS_CONFIG_SAVE), detail.capture());
        assertThat(detail.getValue())
                .doesNotContain("changeit")
                .contains("8443")
                .contains("abc123abc123");
    }

    @Test
    void aPemUploadWithoutACertificateIsRefused() throws Exception {
        mvc.perform(multipart("/system-config/tls")
                        .file(new MockMultipartFile("keyFile", "privkey.pem",
                                "application/x-pem-file", new byte[] {2}))
                        .param("httpsPort", "8443")
                        .with(csrf()))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("error", "error.tls.pemCertRequired"));

        verify(service, never()).savePem(any(), any(), anyInt(), anyBoolean());
    }

    @Test
    void anEmptyPemCertificateIsRefusedWithThePemMessage() throws Exception {
        // The browser's required attribute can be bypassed; the answer must still name the PEM
        // form's field, not the keystore's "choose a file".
        mvc.perform(multipart("/system-config/tls")
                        .file(new MockMultipartFile("certFile", "fullchain.pem",
                                "application/x-pem-file", new byte[0]))
                        .param("httpsPort", "8443")
                        .with(csrf()))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("error", "error.tls.pemCertRequired"));

        verify(service, never()).savePem(any(), any(), anyInt(), anyBoolean());
        verify(service, never()).save(any(), anyString(), anyString(), anyInt(), anyBoolean());
    }

    @Test
    void aCombinedPemUploadNeedsOnlyTheCertificateFile() throws Exception {
        // HAProxy-style bundle: leaf, chain and key in one file. The key part is optional.
        when(service.savePem(any(), isNull(), eq(8443), eq(true))).thenReturn(config());

        mvc.perform(multipart("/system-config/tls")
                        .file(new MockMultipartFile("certFile", "fullchain-with-key.pem",
                                "application/x-pem-file", new byte[] {1}))
                        .param("httpsPort", "8443")
                        .param("redirectHttp", "true")
                        .with(csrf()))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("message", "msg.systemConfig.tls.saved"));

        verify(service).savePem(any(), isNull(), eq(8443), eq(true));
        verify(audit).record(eq(AuditAction.TLS_CONFIG_SAVE), anyString());
    }

    @Test
    void aSuccessfulPemUploadFlashesAndIsAudited() throws Exception {
        when(service.savePem(any(), any(), eq(8443), eq(true))).thenReturn(config());

        mvc.perform(multipart("/system-config/tls")
                        .file(new MockMultipartFile("certFile", "fullchain.pem",
                                "application/x-pem-file", new byte[] {1}))
                        .file(new MockMultipartFile("keyFile", "privkey.pem",
                                "application/x-pem-file", new byte[] {2}))
                        .param("httpsPort", "8443")
                        .param("redirectHttp", "true")
                        .with(csrf()))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("message", "msg.systemConfig.tls.saved"));

        verify(service, never()).save(any(), anyString(), anyString(), anyInt(), anyBoolean());
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq(AuditAction.TLS_CONFIG_SAVE), detail.capture());
        assertThat(detail.getValue()).contains("8443").contains("abc123abc123");
    }

    @Test
    void aRejectedPemUploadShowsItsKey() throws Exception {
        when(service.savePem(any(), any(), anyInt(), anyBoolean()))
                .thenThrow(new TlsKeystoreException("error.tls.pemKeyMismatch"));

        mvc.perform(multipart("/system-config/tls")
                        .file(new MockMultipartFile("certFile", "fullchain.pem",
                                "application/x-pem-file", new byte[] {1}))
                        .file(new MockMultipartFile("keyFile", "privkey.pem",
                                "application/x-pem-file", new byte[] {2}))
                        .param("httpsPort", "8443")
                        .with(csrf()))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("error", "error.tls.pemKeyMismatch"));
    }

    @Test
    void anOversizedPemPartIsRefusedBeforeItIsRead() throws Exception {
        byte[] tooBig = new byte[TlsCertificateService.MAX_BYTES + 1];

        mvc.perform(multipart("/system-config/tls")
                        .file(new MockMultipartFile("certFile", "fullchain.pem", null, tooBig))
                        .file(new MockMultipartFile("keyFile", "privkey.pem", null, new byte[] {2}))
                        .param("httpsPort", "8443")
                        .with(csrf()))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("error", "error.tls.fileTooLarge"));

        verify(service, never()).savePem(any(), any(), anyInt(), anyBoolean());
    }

    // ── Server-side paths ─────────────────────────────────────────────────────

    @Test
    void aKeystorePathIsSaved() throws Exception {
        when(service.saveKeystorePath("/etc/jync/tls/server.p12", "changeit", 8443, true))
                .thenReturn(config());

        mvc.perform(post("/system-config/tls/path")
                        .param("mode", "keystore")
                        .param("keystorePath", "/etc/jync/tls/server.p12")
                        .param("passphrase", "changeit")
                        .param("httpsPort", "8443")
                        .param("redirectHttp", "true")
                        .with(csrf()))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("message", "msg.systemConfig.tls.saved"));

        verify(service).saveKeystorePath("/etc/jync/tls/server.p12", "changeit", 8443, true);
        verify(service, never()).save(any(), anyString(), anyString(), anyInt(), anyBoolean());
        verify(audit).record(eq(AuditAction.TLS_CONFIG_SAVE), anyString());
    }

    @Test
    void aPemPathIsSaved() throws Exception {
        when(service.savePemPath("/etc/jync/tls/fullchain.pem", "/etc/jync/tls/privkey.pem",
                8443, false)).thenReturn(config());

        mvc.perform(post("/system-config/tls/path")
                        .param("mode", "pem")
                        .param("pemCertPath", "/etc/jync/tls/fullchain.pem")
                        .param("pemKeyPath", "/etc/jync/tls/privkey.pem")
                        .param("httpsPort", "8443")
                        .with(csrf()))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("message", "msg.systemConfig.tls.saved"));

        verify(service).savePemPath("/etc/jync/tls/fullchain.pem", "/etc/jync/tls/privkey.pem",
                8443, false);
    }

    @Test
    void aPathSaveWithANonNumericPortIsRefusedBeforeTheService() throws Exception {
        mvc.perform(post("/system-config/tls/path")
                        .param("mode", "keystore")
                        .param("keystorePath", "/etc/jync/tls/server.p12")
                        .param("passphrase", "changeit")
                        .param("httpsPort", "eighty")
                        .with(csrf()))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("error", "error.tls.portInvalid"));

        verify(service, never()).saveKeystorePath(any(), any(), anyInt(), anyBoolean());
    }

    @Test
    void anUnknownPathModeIsRefused() throws Exception {
        mvc.perform(post("/system-config/tls/path")
                        .param("mode", "upload")
                        .param("httpsPort", "8443")
                        .with(csrf()))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("error", "error.tls.pathInvalid"));

        verify(service, never()).saveKeystorePath(any(), any(), anyInt(), anyBoolean());
        verify(service, never()).savePemPath(any(), any(), anyInt(), anyBoolean());
    }

    @Test
    void aRejectedPathShowsItsKeyNotTheExceptionMessage() throws Exception {
        when(service.saveKeystorePath(any(), any(), anyInt(), anyBoolean()))
                .thenThrow(new TlsKeystoreException("error.tls.pathMissing"));

        mvc.perform(post("/system-config/tls/path")
                        .param("mode", "keystore")
                        .param("keystorePath", "/etc/jync/tls/absent.p12")
                        .param("passphrase", "changeit")
                        .param("httpsPort", "8443")
                        .with(csrf()))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("error", "error.tls.pathMissing"));
    }

    @Test
    void pathSaveRequiresCsrf() throws Exception {
        mvc.perform(post("/system-config/tls/path")
                        .param("mode", "keystore")
                        .param("keystorePath", "/etc/jync/tls/server.p12")
                        .param("passphrase", "changeit")
                        .param("httpsPort", "8443"))
                .andExpect(status().isForbidden());

        verify(service, never()).saveKeystorePath(any(), any(), anyInt(), anyBoolean());
    }

    @Test
    void uploadRequiresCsrf() throws Exception {
        mvc.perform(multipart("/system-config/tls")
                        .file(keystoreFile())
                        .param("passphrase", "changeit")
                        .param("httpsPort", "8443"))
                .andExpect(status().isForbidden());

        verify(service, never()).save(any(), anyString(), anyString(), anyInt(), anyBoolean());
    }

    @Test
    void anAdminCanRemoveTheCertificate() throws Exception {
        when(service.delete()).thenReturn(true);

        mvc.perform(post("/system-config/tls/delete").with(csrf()))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("message", "msg.systemConfig.tls.removed"));

        verify(audit).record(eq(AuditAction.TLS_CONFIG_DELETE), anyString());
    }

    @Test
    void deleteWithNothingSavedDoesNotAudit() throws Exception {
        when(service.delete()).thenReturn(false);

        mvc.perform(post("/system-config/tls/delete").with(csrf()))
                .andExpect(redirectedUrl("/system-config"));

        verify(audit, never()).record(eq(AuditAction.TLS_CONFIG_DELETE), anyString());
    }

    @Test
    void aDeleteFailureIsFlashedAsAKeyInsteadOfA500() throws Exception {
        // The removal is the repair action after a corrupt store; a read-only data directory
        // must not turn it into an HTML 500 page with no explanation.
        when(service.delete()).thenThrow(new TlsKeystoreException("error.tls.deleteFailed"));

        mvc.perform(post("/system-config/tls/delete").with(csrf()))
                .andExpect(redirectedUrl("/system-config"))
                .andExpect(flash().attribute("error", "error.tls.deleteFailed"));

        verify(audit, never()).record(eq(AuditAction.TLS_CONFIG_DELETE), anyString());
    }

    private static TlsConfig config() {
        return new TlsConfig(TlsKeystoreFormat.PKCS12, "keystore-x.p12", "changeit", 8443, true,
                Instant.now(), "abc123abc123def4");
    }
}
