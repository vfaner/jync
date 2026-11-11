package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.etl.EtlBatchService;
import com.qqmu.jync.service.etl.EtlPrecheckResult;
import com.qqmu.jync.service.etl.EtlPrecheckResult.Check;
import com.qqmu.jync.service.etl.EtlPrecheckResult.ColumnInfo;
import com.qqmu.jync.service.etl.EtlPrecheckResult.Level;
import com.qqmu.jync.service.etl.EtlPrecheckService;
import com.qqmu.jync.service.etl.EtlTaskService;

/**
 * The precheck card is the only place the operator learns why a run would fail, so it must
 * render localized sentences — not the raw {@code etl.precheck.*} keys the service carries.
 *
 * <p>Every message embeds its offending text through {@code {0}}; the detail is therefore
 * substituted into the sentence, and only text the sentence does not already carry is shown
 * next to it. The row badges keep the shared {@code badge} class (the level modifier only
 * supplies the colour).
 */
@WebMvcTest(EtlController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class EtlFormPrecheckViewTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private EtlTaskService etlTaskService;

    @MockitoBean
    private EtlPrecheckService precheckService;

    @MockitoBean
    private EtlBatchService etlBatchService;

    private static RequestPostProcessor asAdmin() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPasswordHash("not-checked-here");
        user.setRole(UserRole.ADMIN);
        SyncUserDetails principal = new SyncUserDetails(user, false);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    @Test
    void theCardRendersLocalizedMessagesWithTheirDetailAndKeepsTheBadgeClass()
            throws Exception {
        when(precheckService.check(any())).thenReturn(new EtlPrecheckResult(
                List.of(new Check(Level.OK, "etl.precheck.source.ok", "12"),
                        new Check(Level.ERROR, "etl.precheck.target.columns.error", "id")),
                List.of(new ColumnInfo("id", "INT"))));

        String page = mvc.perform(post("/etl/precheck").with(asAdmin()).with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page)
                .contains("Source query validated (found 12 columns)")
                .contains("Missing target columns: id");
        int from = page.indexOf(">Check results<");
        int to = page.indexOf(">Result columns<");
        assertThat(from).as("the checks column is rendered").isNotNegative();
        assertThat(to).as("the columns column follows it").isGreaterThan(from);
        assertThat(page.substring(from, to))
                .as("message keys are localized, not rendered verbatim")
                .doesNotContain("etl.precheck.")
                .doesNotContain("{0}");
        assertThat(page)
                .as("the column count fills the summary placeholder")
                .contains("Result columns: 1");
        assertThat(page)
                .as("the level modifier is appended to the shared badge class")
                .contains("class=\"badge badge-ok\"")
                .contains("class=\"badge badge-danger\"");
    }
}
