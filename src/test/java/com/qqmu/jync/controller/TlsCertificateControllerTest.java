package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

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

    @MockBean
    private TlsCertificateService service;
    @MockBean
    private AdminAuditService audit;
    @MockBean
    private RequireHttpsResolver requireHttps;
    @MockBean
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
    void aContainerRejectionIsReportedAsTooLarge() {
        RedirectAttributes flash = Mockito.mock(RedirectAttributes.class);

        String view = new TlsCertificateController(service, audit).tooLarge(flash);

        assertThat(view).isEqualTo("redirect:/system-config");
        verify(flash).addFlashAttribute("error", "error.tls.fileTooLarge");
    }

    private static TlsConfig config() {
        return new TlsConfig(TlsKeystoreFormat.PKCS12, "keystore-x.p12", "changeit", 8443, true,
                Instant.now(), "abc123abc123def4");
    }
}
