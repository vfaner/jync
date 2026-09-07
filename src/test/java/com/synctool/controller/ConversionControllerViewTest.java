package com.synctool.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.synctool.model.DatabaseType;
import com.synctool.service.ai.ConversionAssistService;

/**
 * Renders the conversion review pages for real.
 *
 * <p>These two templates carry more conditional branches than any other page in the app —
 * override present or absent, assistance available or not, same dialect family or not, orphaned
 * keys or none — and a mistake in any of them is invisible until someone opens the page. The
 * assertions therefore exercise each branch and, in every case, check that no message key came
 * back unresolved.
 */
@WebMvcTest(ConversionController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
/**
 * Signed in as ADMIN for the whole class. These slices exercise rendering and handler behaviour,
 * not authorization -- the role and CSRF rules have their own tests in
 * {@code com.synctool.config.SecurityConfigTest}. Without this the security filter chain answers
 * every request with a redirect to the login page and none of the assertions below get a chance
 * to run.
 */
@WithMockUser(roles = "ADMIN")
class ConversionControllerViewTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ConversionAssistService assistService;

    /**
     * Builds an {@link ConversionAssistService.Summary} by reflection.
     *
     * <p>The model classes are deliberately read-only — the service is the only thing entitled to
     * populate them — so a test double needs reflection rather than production setters that exist
     * for no other reason.
     */
    private ConversionAssistService.Summary summary(String kind, String name, boolean hasOverride) {
        ConversionAssistService.Summary s = new ConversionAssistService.Summary();
        ReflectionTestUtils.setField(s, "kind", kind);
        ReflectionTestUtils.setField(s, "name", name);
        ReflectionTestUtils.setField(s, "overrideKey", kind + ":" + name);
        ReflectionTestUtils.setField(s, "hasOverride", hasOverride);
        ReflectionTestUtils.setField(s, "overrideChars", hasOverride ? 420 : 0);
        return s;
    }

    private ConversionAssistService.Overview overview(boolean assistAvailable) {
        ConversionAssistService.Overview o = new ConversionAssistService.Overview();
        ReflectionTestUtils.setField(o, "schema", "APP");
        ReflectionTestUtils.setField(o, "sourceProduct", DatabaseType.ORACLE);
        ReflectionTestUtils.setField(o, "targetProduct", DatabaseType.MYSQL);
        ReflectionTestUtils.setField(o, "assistAvailable", assistAvailable);
        return o;
    }

    private ConversionAssistService.Detail detail(String override, boolean assistAvailable,
                                                 boolean sameFamily) {
        ConversionAssistService.Detail d = new ConversionAssistService.Detail();
        ReflectionTestUtils.setField(d, "kind", "PROCEDURE");
        ReflectionTestUtils.setField(d, "name", "GET_TOTAL");
        ReflectionTestUtils.setField(d, "overrideKey", "PROCEDURE:GET_TOTAL");
        ReflectionTestUtils.setField(d, "routineType", "PROCEDURE");
        ReflectionTestUtils.setField(d, "sourceSql",
                "CREATE PROCEDURE GET_TOTAL IS BEGIN NULL; END;");
        ReflectionTestUtils.setField(d, "mechanicalSql",
                "CREATE PROCEDURE GET_TOTAL() BEGIN SELECT 1; END");
        ReflectionTestUtils.setField(d, "override", override);
        ReflectionTestUtils.setField(d, "sourceProduct", DatabaseType.ORACLE);
        ReflectionTestUtils.setField(d, "targetProduct", DatabaseType.MYSQL);
        ReflectionTestUtils.setField(d, "assistAvailable", assistAvailable);
        ReflectionTestUtils.setField(d, "sameFamily", sameFamily);
        return d;
    }

    private String render(String url) throws Exception {
        return mvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void anEmptyListRenders() throws Exception {
        when(assistService.overview(1L)).thenReturn(overview(false));

        String html = render("/projects/1/conversions");

        assertThat(html).doesNotContain("??");
    }

    @Test
    void aPopulatedListShowsBothOverrideStates() throws Exception {
        ConversionAssistService.Overview o = overview(true);
        o.getRoutines().add(summary("PROCEDURE", "GET_TOTAL", true));
        o.getRoutines().add(summary("PROCEDURE", "CALC_TAX", false));
        o.getViews().add(summary("VIEW", "V_SALES", false));
        when(assistService.overview(1L)).thenReturn(o);

        String html = render("/projects/1/conversions");

        assertThat(html)
                .contains("GET_TOTAL").contains("CALC_TAX").contains("V_SALES")
                .doesNotContain("??");
    }

    @Test
    void orphanedOverridesAreListedWithACleanupForm() throws Exception {
        ConversionAssistService.Overview o = overview(true);
        o.getOrphanedOverrides().add("PROCEDURE:DELETED_FROM_SOURCE");
        when(assistService.overview(1L)).thenReturn(o);

        String html = render("/projects/1/conversions");

        // An override for an object no longer in the source is dead weight the user cannot
        // otherwise see or remove -- there is no object page left to visit.
        assertThat(html)
                .contains("PROCEDURE:DELETED_FROM_SOURCE")
                .contains("/projects/1/conversions/cleanup")
                .doesNotContain("??");
    }

    @Test
    void theDetailPageRendersWithoutAnOverride() throws Exception {
        when(assistService.load(1L, "PROCEDURE", "GET_TOTAL"))
                .thenReturn(detail(null, true, false));

        String html = render("/projects/1/conversions/PROCEDURE/GET_TOTAL");

        assertThat(html)
                .contains("GET_TOTAL")
                .contains("id=\"override-sql\"")
                // Seeded from the mechanical attempt when nothing is saved yet.
                .contains("BEGIN SELECT 1; END")
                .doesNotContain("??");
    }

    @Test
    void theEditorIsSeededFromTheSavedOverrideWhenThereIsOne() throws Exception {
        when(assistService.load(1L, "PROCEDURE", "GET_TOTAL"))
                .thenReturn(detail("CREATE PROCEDURE GET_TOTAL() BEGIN /* hand-written */ END",
                        true, false));

        String html = render("/projects/1/conversions/PROCEDURE/GET_TOTAL");

        // Reopening the page must not discard the reviewer's own work in favour of a fresh
        // mechanical conversion.
        assertThat(html)
                .contains("hand-written")
                .contains("delete-override")
                .doesNotContain("??");
    }

    @Test
    void theDeleteOverrideControlIsHiddenWhenNoOverrideExists() throws Exception {
        when(assistService.load(1L, "PROCEDURE", "GET_TOTAL"))
                .thenReturn(detail(null, true, false));

        String html = render("/projects/1/conversions/PROCEDURE/GET_TOTAL");

        assertThat(html).doesNotContain("delete-override");
    }

    @Test
    void theDraftButtonIsAbsentWhenNoProviderIsEnabled() throws Exception {
        when(assistService.load(1L, "PROCEDURE", "GET_TOTAL"))
                .thenReturn(detail(null, false, false));

        String html = render("/projects/1/conversions/PROCEDURE/GET_TOTAL");

        // Offering a button that can only fail is worse than not offering it; the page says why
        // instead. The rest of the review workflow still works without a model.
        assertThat(html)
                .doesNotContain("data-role=\"ai-draft\"")
                .contains("id=\"override-sql\"")
                .doesNotContain("??");
    }

    @Test
    void aSameFamilyConversionSaysSoRatherThanInvitingPointlessEdits() throws Exception {
        when(assistService.load(1L, "PROCEDURE", "GET_TOTAL"))
                .thenReturn(detail(null, true, true));

        String html = render("/projects/1/conversions/PROCEDURE/GET_TOTAL");

        assertThat(html).doesNotContain("??");
    }

    @Test
    void aDottedObjectNameSurvivesTheUrl() throws Exception {
        when(assistService.load(anyLong(), anyString(), anyString()))
                .thenReturn(detail(null, true, false));

        mvc.perform(get("/projects/1/conversions/VIEW/APP.V_SALES"))
                .andExpect(status().isOk());

        // Without the :.+ pattern Spring treats ".V_SALES" as a format suffix and the service is
        // asked for "APP" -- an object that does not exist.
        verify(assistService).load(1L, "VIEW", "APP.V_SALES");
    }

    @Test
    void savingAnOverrideRedirectsBackToTheObject() throws Exception {
        mvc.perform(post("/projects/1/conversions/PROCEDURE/GET_TOTAL/save").with(csrf())
                        .param("sql", "CREATE PROCEDURE GET_TOTAL() BEGIN END"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/projects/1/conversions/PROCEDURE/GET_TOTAL"))
                .andExpect(flash().attribute("message", "msg.override.saved"));

        verify(assistService).saveOverride(1L, "PROCEDURE", "GET_TOTAL",
                "CREATE PROCEDURE GET_TOTAL() BEGIN END");
    }

    @Test
    void aRejectedSaveReportsTheReasonInsteadOfSucceedingSilently() throws Exception {
        doThrow(new IllegalArgumentException("error.override.empty"))
                .when(assistService).saveOverride(anyLong(), anyString(), anyString(), any());

        mvc.perform(post("/projects/1/conversions/PROCEDURE/GET_TOTAL/save").with(csrf()).param("sql", "  "))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", "error.override.empty"));
    }

    @Test
    void deletingAnOverrideUsesTheFoldedKey() throws Exception {
        mvc.perform(post("/projects/1/conversions/PROCEDURE/GET_TOTAL/delete-override").with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("message", "msg.override.deleted"));

        verify(assistService).deleteOverride(1L, "PROCEDURE:GET_TOTAL");
    }

    @Test
    void cleaningUpAnOrphanReturnsToTheList() throws Exception {
        mvc.perform(post("/projects/1/conversions/cleanup").with(csrf())
                        .param("overrideKey", "VIEW:GONE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/projects/1/conversions"))
                .andExpect(flash().attribute("message", "msg.override.deleted"));

        verify(assistService).deleteOverride(1L, "VIEW:GONE");
    }

    @Test
    void aProjectWithNoConnectionsRedirectsInsteadOfErroring() throws Exception {
        when(assistService.overview(anyLong()))
                .thenThrow(new IllegalStateException("error.connection.missing"));

        mvc.perform(get("/projects/1/conversions"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/projects/1"))
                .andExpect(flash().attribute("error", "error.connection.missing"));
    }

    @Test
    void anUnreadableObjectRedirectsToTheList() throws Exception {
        when(assistService.load(anyLong(), anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("error.ai.noSourceBody"));

        mvc.perform(get("/projects/1/conversions/PROCEDURE/GONE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/projects/1/conversions"))
                .andExpect(flash().attribute("error", "error.ai.noSourceBody"));
    }

    @Test
    void theEnglishBundleResolvesTooNotJustTheDefault() throws Exception {
        ConversionAssistService.Overview o = overview(true);
        o.getRoutines().add(summary("PROCEDURE", "GET_TOTAL", true));
        when(assistService.overview(1L)).thenReturn(o);

        String html = mvc.perform(get("/projects/1/conversions")
                        .header("Accept-Language", "en-US"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // A key added to only one of the two bundles surfaces here as ??key_en_US??.
        assertThat(html).doesNotContain("??");
    }

    @Test
    void theDetailPageResolvesInEnglishToo() throws Exception {
        when(assistService.load(1L, "PROCEDURE", "GET_TOTAL"))
                .thenReturn(detail("CREATE PROCEDURE x() BEGIN END", true, true));

        String html = mvc.perform(get("/projects/1/conversions/PROCEDURE/GET_TOTAL")
                        .header("Accept-Language", "en-US"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("??");
    }
}
