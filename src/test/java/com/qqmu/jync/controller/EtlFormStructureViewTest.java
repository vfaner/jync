package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

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
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.etl.EtlBatchService;
import com.qqmu.jync.service.etl.EtlPrecheckService;
import com.qqmu.jync.service.etl.EtlTaskService;

/**
 * The ETL form must stay one form element from its first field to its submit buttons.
 *
 * <p>An unbalanced {@code </div>} is not merely cosmetic here: a browser pops the open elements
 * until a {@code div} is closed, and an open {@code <form>} in between goes with it. The buttons
 * then have no form owner, and pressing Save or Precheck does nothing at all. These assertions
 * pin the rendered page, not the source template, because that is what the browser receives.
 */
@WebMvcTest(EtlController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class EtlFormStructureViewTest {

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

    private String newForm() throws Exception {
        when(etlTaskService.connections()).thenReturn(List.of());
        return mvc.perform(get("/etl/new").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /**
     * Spring's {@code CronExpression} takes six fields with the seconds first. A hint showing
     * the five-field Unix form teaches an expression the validator then rejects.
     */
    @Test
    void theCronHintShowsTheSixFieldFormTheValidatorAccepts() throws Exception {
        String page = newForm();

        assertThat(page).contains("placeholder=\"0 0 2 * * *\"");
        assertThat(page).doesNotContain("placeholder=\"0 2 * * *\"");
    }

    /**
     * A standalone form is the wrong place to discover that several tables from one source and
     * one target should have been a batch: the task is created first, then someone has to delete
     * it and rebuild the schedule inside a batch. The hint belongs on the form, before the save.
     */
    @Test
    void aStandaloneTaskFormSuggestsABatchForTablesSharingConnections() throws Exception {
        String page = newForm();

        assertThat(page).contains("data-role=\"batch-hint\"");
    }

    @Test
    void bothHintsShareTheLineUnderTheTitle() throws Exception {
        String page = newForm();

        // Two grey paragraphs under "New ETL task" read as two separate instructions. The
        // batch advice continues the first sentence, so it shares that line.
        assertThat(page).containsPattern("(?s)<p class=\"sub\">\\s*<span[^>]*>[^<]*</span>\\s*"
                + "<span[^>]*data-role=\"batch-hint\"[^>]*>[^<]*</span>\\s*</p>");
        assertThat(page).doesNotContain("class=\"help mb-12\" data-role=\"batch-hint\"");
    }

    @Test
    void everyDivIsClosedSoTheFormCannotEndEarly() throws Exception {
        String page = newForm();

        // Browsers recover from an extra </div> by closing the nearest open div — a <form> in
        // between is closed too. Balanced tags keep that recovery from moving </form>.
        assertThat(count(page, "<div")).isEqualTo(count(page, "</div>"));
        assertThat(count(page, "<form")).isEqualTo(count(page, "</form>"));
    }

    @Test
    void theScheduleFieldsAndTheSubmitButtonsAreInsideTheSaveForm() throws Exception {
        String page = newForm();

        int formStart = page.indexOf("<form method=\"post\" action=\"/etl/save\"");
        assertThat(formStart).as("save form rendered").isPositive();
        int formEnd = page.indexOf("</form>", formStart);
        assertThat(formEnd).isGreaterThan(formStart);

        assertThat(page.indexOf("id=\"cronExpression\""))
                .as("cron field inside the form").isBetween(formStart, formEnd);
        assertThat(page.indexOf("formaction=\"/etl/save\""))
                .as("save button inside the form").isBetween(formStart, formEnd);
        assertThat(page.indexOf("formaction=\"/etl/precheck\""))
                .as("precheck button inside the form").isBetween(formStart, formEnd);
    }

    /** Two selects with the same id and name make the first one win (or break binding). */
    @Test
    void theWriteModeSelectIsRenderedExactlyOnce() throws Exception {
        assertThat(count(newForm(), "name=\"writeMode\"")).isEqualTo(1);
    }

    private static int count(String text, String needle) {
        int found = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            found++;
            at = text.indexOf(needle, at + needle.length());
        }
        return found;
    }
}
