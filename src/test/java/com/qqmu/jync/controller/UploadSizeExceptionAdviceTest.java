package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * An oversized multipart body is refused by the container before Spring selects a handler, so
 * the per-controller handlers never ran. One advice routes by path instead.
 */
class UploadSizeExceptionAdviceTest {

    private final UploadSizeExceptionAdvice advice = new UploadSizeExceptionAdvice();

    @Test
    void aTlsUploadNamesTheCertificateFile() throws Exception {
        RedirectAttributes flash = mock(RedirectAttributes.class);

        String view = advice.tooLarge(request("/system-config/tls"), new MockHttpServletResponse(),
                flash);

        assertThat(view).isEqualTo("redirect:/system-config");
        verify(flash).addFlashAttribute("error", "error.tls.fileTooLarge");
    }

    @Test
    void aConfigPackageImportNamesThePackageFile() throws Exception {
        RedirectAttributes flash = mock(RedirectAttributes.class);

        String view = advice.tooLarge(request("/system-config/import"),
                new MockHttpServletResponse(), flash);

        assertThat(view).isEqualTo("redirect:/system-config");
        verify(flash).addFlashAttribute("error", "error.config.import.fileTooLarge");
    }

    @Test
    void anOversizedStagedUploadAnswersJsonInsteadOfARedirect() throws Exception {
        // The stage endpoint's caller is fetch(). A redirect is followed into the HTML page, the
        // JSON parse fails, and the toast says "HTTP 200" instead of naming the oversized file.
        MockHttpServletResponse response = new MockHttpServletResponse();
        RedirectAttributes flash = mock(RedirectAttributes.class);

        String view = advice.tooLarge(request("/system-config/tls/stage"), response, flash);

        assertThat(view).isNull();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString())
                .contains("\"success\":false")
                .contains("error.tls.fileTooLarge");
        verifyNoInteractions(flash);
    }

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(uri);
        return request;
    }
}
