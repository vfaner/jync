package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * An oversized multipart body is refused by the container before Spring selects a handler, so
 * the per-controller handlers never ran. One advice routes by path instead.
 */
class UploadSizeExceptionAdviceTest {

    private final UploadSizeExceptionAdvice advice = new UploadSizeExceptionAdvice();

    @Test
    void aTlsUploadNamesTheCertificateFile() {
        RedirectAttributes flash = mock(RedirectAttributes.class);

        String view = advice.tooLarge(request("/system-config/tls"), flash);

        assertThat(view).isEqualTo("redirect:/system-config");
        verify(flash).addFlashAttribute("error", "error.tls.fileTooLarge");
    }

    @Test
    void aConfigPackageImportNamesThePackageFile() {
        RedirectAttributes flash = mock(RedirectAttributes.class);

        String view = advice.tooLarge(request("/system-config/import"), flash);

        assertThat(view).isEqualTo("redirect:/system-config");
        verify(flash).addFlashAttribute("error", "error.config.import.fileTooLarge");
    }

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(uri);
        return request;
    }
}
