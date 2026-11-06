package com.qqmu.jync.controller.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.model.AuditAction;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.settings.MetadataDbTester;

/**
 * The JSON endpoint behind 测试连接.
 *
 * <p>A failed connection is an answer, not an HTTP error; malformed input is the 400. The
 * environment block carries the password back to the page, but the audit row must not.
 */
@WebMvcTest(MetadataDbTestApiController.class)
@WithMockUser(roles = "ADMIN")
class MetadataDbTestApiControllerTest {

    private static final String BODY = """
            {"url":"%s","driverClass":"com.mysql.cj.jdbc.Driver",
             "username":"app","password":"pw"}""";

    @Autowired
    private MockMvc mvc;

    @MockBean
    private MetadataDbTester tester;
    @MockBean
    private AdminAuditService audit;

    @Test
    void aReachableDatabaseReportsProductVersionAndTheEnvironmentBlock() throws Exception {
        when(tester.test(any())).thenReturn(new MetadataDbTester.Result(
                true, "msg.systemConfig.dbTest.ok", "MySQL", "8.0.36",
                "SPRING_DATASOURCE_URL='jdbc:mysql://db:3306/jync'"));

        mvc.perform(post("/system-config/db-test").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("jdbc:mysql://db:3306/jync")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.product").value("MySQL"))
                .andExpect(jsonPath("$.version").value("8.0.36"))
                .andExpect(jsonPath("$.envBlock").value(
                        "SPRING_DATASOURCE_URL='jdbc:mysql://db:3306/jync'"));
    }

    @Test
    void aFailedConnectionStillAnswersOk() throws Exception {
        when(tester.test(any())).thenReturn(new MetadataDbTester.Result(
                false, "error.dbTest.timeout", "", "", ""));

        mvc.perform(post("/system-config/db-test").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("jdbc:mysql://db:3306/jync")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("error.dbTest.timeout"));
    }

    @Test
    void malformedInputIsABadRequestCarryingItsKey() throws Exception {
        when(tester.test(any())).thenThrow(new IllegalArgumentException("error.dbTest.urlInvalid"));

        mvc.perform(post("/system-config/db-test").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("jdbc:h2:mem:x")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("error.dbTest.urlInvalid"));
    }

    @Test
    void theAuditRowNeverCarriesCredentials() throws Exception {
        when(tester.test(any())).thenReturn(new MetadataDbTester.Result(
                true, "msg.systemConfig.dbTest.ok", "MySQL", "8.0.36", "block"));

        mvc.perform(post("/system-config/db-test").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("jdbc:mysql://app:secret@db:3306/jync?password=hidden")))
                .andExpect(status().isOk());

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(audit).record(any(AuditAction.class), detail.capture());
        assertThat(detail.getValue())
                .contains("jdbc:mysql://db:3306/jync")
                .contains("MySQL 8.0.36")
                .doesNotContain("secret")
                .doesNotContain("hidden");
    }

    private static String body(String url) {
        return String.format(BODY, url);
    }
}
