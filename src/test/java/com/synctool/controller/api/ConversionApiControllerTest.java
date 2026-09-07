package com.synctool.controller.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.synctool.service.ai.AiSqlAssistant;
import com.synctool.service.ai.CandidateValidator;
import com.synctool.service.ai.ConversionAssistService;

/**
 * The JSON contract the review page's fetch() calls depend on.
 *
 * <p>Every response here is a 200 with a {@code success} flag, including the failures. The page
 * reads {@code message} out of the body and resolves it as a bundle key; a 4xx or 5xx carrying
 * Spring's HTML error page would reach the user as a blank toast, so "the request was refused"
 * and "the server broke" must not share a status code.
 */
@WebMvcTest(ConversionApiController.class)
/**
 * Signed in as ADMIN for the whole class. These slices exercise rendering and handler behaviour,
 * not authorization -- the role and CSRF rules have their own tests in
 * {@code com.synctool.config.SecurityConfigTest}. Without this the security filter chain answers
 * every request with a redirect to the login page and none of the assertions below get a chance
 * to run.
 */
@WithMockUser(roles = "ADMIN")
class ConversionApiControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ConversionAssistService assistService;

    /**
     * Builds candidates and validation results through their real factory methods.
     *
     * <p>Reflection because both are package-private: they are constructed only by the service
     * that owns them, and widening their access for a test in another package would weaken a
     * boundary that is doing useful work.
     */
    private AiSqlAssistant.Candidate drafted(String sql, List<String> uncertainties) {
        return ReflectionTestUtils.invokeMethod(AiSqlAssistant.Candidate.class, "success",
                sql, uncertainties, "test-model", 1234L);
    }

    private AiSqlAssistant.Candidate declined(List<String> uncertainties) {
        return ReflectionTestUtils.invokeMethod(AiSqlAssistant.Candidate.class, "declined",
                uncertainties, "test-model", 1234L);
    }

    private CandidateValidator.ValidationResult passed(String tempName, List<String> caveats) {
        return ReflectionTestUtils.invokeMethod(CandidateValidator.ValidationResult.class,
                "success", tempName, caveats);
    }

    private CandidateValidator.ValidationResult rejected(String message, String tempName) {
        return ReflectionTestUtils.invokeMethod(CandidateValidator.ValidationResult.class,
                "failure", message, tempName, List.of());
    }

    @Test
    @DisplayName("a drafted candidate comes back with its SQL and uncertainty list")
    void aDraftedCandidateIsReturned() throws Exception {
        when(assistService.draft(1L, "PROCEDURE", "GET_TOTAL")).thenReturn(
                drafted("CREATE PROCEDURE x() BEGIN END", List.of("Cursor semantics differ")));

        mvc.perform(post("/api/projects/1/conversions/PROCEDURE/GET_TOTAL/draft").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.sql").value("CREATE PROCEDURE x() BEGIN END"))
                .andExpect(jsonPath("$.uncertainties[0]").value("Cursor semantics differ"))
                .andExpect(jsonPath("$.model").value("test-model"))
                .andExpect(jsonPath("$.elapsedMs").value(1234));
    }

    @Test
    @DisplayName("a declined conversion still returns the model's reasons")
    void aDeclinedConversionKeepsItsReasons() throws Exception {
        when(assistService.draft(anyLong(), anyString(), anyString()))
                .thenReturn(declined(List.of("Package state has no equivalent")));

        // The refusal reason is the entire value of the answer. Dropping it on the failure path
        // would make a considered "no" look identical to a timeout.
        mvc.perform(post("/api/projects/1/conversions/PROCEDURE/P/draft").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("error.ai.declined"))
                .andExpect(jsonPath("$.uncertainties[0]").value("Package state has no equivalent"));
    }

    @Test
    @DisplayName("no configured provider is a message, not a 500")
    void anUnconfiguredProviderIsReportedAsAMessage() throws Exception {
        when(assistService.draft(anyLong(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("error.ai.notAvailable"));

        mvc.perform(post("/api/projects/1/conversions/PROCEDURE/P/draft").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("error.ai.notAvailable"));
    }

    @Test
    @DisplayName("a missing project is a message, not a 500")
    void aMissingProjectIsReportedAsAMessage() throws Exception {
        // The service throws IllegalArgumentException for this, which an IllegalStateException-only
        // catch would let escape as an HTML error page that fetch() cannot read.
        when(assistService.draft(anyLong(), anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("error.project.not.found"));

        mvc.perform(post("/api/projects/99/conversions/PROCEDURE/P/draft").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("error.project.not.found"));
    }

    @Test
    @DisplayName("an exception with no message still yields something the page can render")
    void aMessagelessExceptionStillYieldsAKey() throws Exception {
        when(assistService.draft(anyLong(), anyString(), anyString()))
                .thenThrow(new IllegalStateException());

        mvc.perform(post("/api/projects/1/conversions/PROCEDURE/P/draft").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("error.unexpected"));
    }

    @Test
    @DisplayName("a dotted object name reaches the service intact")
    void aDottedNameSurvivesTheUrl() throws Exception {
        when(assistService.draft(anyLong(), anyString(), anyString()))
                .thenReturn(drafted("x", List.of()));

        mvc.perform(post("/api/projects/1/conversions/VIEW/APP.V_SALES/draft").with(csrf()))
                .andExpect(status().isOk());

        verify(assistService).draft(1L, "VIEW", "APP.V_SALES");
    }

    @Test
    @DisplayName("a passing syntax check reports the temp name it used")
    void aPassingCheckReportsTheTempName() throws Exception {
        when(assistService.validate(1L, "GET_TOTAL", "CREATE PROCEDURE GET_TOTAL() BEGIN END"))
                .thenReturn(passed("SYNCTOOL_AI_CHECK_ABC", List.of()));

        mvc.perform(post("/api/projects/1/conversions/validate").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"PROCEDURE\",\"name\":\"GET_TOTAL\","
                                + "\"sql\":\"CREATE PROCEDURE GET_TOTAL() BEGIN END\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.tempName").value("SYNCTOOL_AI_CHECK_ABC"));
    }

    @Test
    @DisplayName("a failing syntax check passes the target's own error through")
    void aFailingCheckReportsTheEnginesError() throws Exception {
        when(assistService.validate(anyLong(), anyString(), anyString()))
                .thenReturn(rejected("Table \"NOPE\" not found", "SYNCTOOL_AI_CHECK_ABC"));

        mvc.perform(post("/api/projects/1/conversions/validate").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"VIEW\",\"name\":\"V\",\"sql\":\"CREATE VIEW V AS x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Table \"NOPE\" not found"));
    }

    @Test
    @DisplayName("caveats survive onto a passing check")
    void caveatsSurviveOntoAPassingCheck() throws Exception {
        when(assistService.validate(anyLong(), anyString(), anyString()))
                .thenReturn(passed("T", List.of("warn.validate.selfReference")));

        // "It compiled" plus "but the recursion was not really tested" is the honest answer; the
        // first half alone would read as a clean bill of health.
        mvc.perform(post("/api/projects/1/conversions/validate").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"PROCEDURE\",\"name\":\"F\",\"sql\":\"CREATE PROCEDURE F\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.caveats[0]").value("warn.validate.selfReference"));
    }

    @Test
    @DisplayName("a validate call missing a field is refused without touching the target")
    void anIncompleteValidateRequestIsRefused() throws Exception {
        mvc.perform(post("/api/projects/1/conversions/validate").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"VIEW\",\"name\":\"V\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        // This endpoint writes to the target, so an ambiguous request must stop here.
        verify(assistService, never()).validate(anyLong(), any(), any());
    }

    @Test
    @DisplayName("an unreachable target is reported, not thrown")
    void anUnreachableTargetIsReported() throws Exception {
        when(assistService.validate(anyLong(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("error.connection.missing"));

        mvc.perform(post("/api/projects/1/conversions/validate").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"VIEW\",\"name\":\"V\",\"sql\":\"CREATE VIEW V AS SELECT 1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("error.connection.missing"));
    }
}
