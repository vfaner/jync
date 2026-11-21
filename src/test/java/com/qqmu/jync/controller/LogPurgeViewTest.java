package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.AdminAuditService;
import com.qqmu.jync.service.ChangeLogService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.audit.AuditDetailRenderer;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.util.LogPurgeDates;

/**
 * The date-purge entry points: administrators get the modal on both log pages (with the
 * picker capped at yesterday), operators get nothing on the change log and no audit page at
 * all, and the purge endpoints answer JSON the modal can toast — including a refused date.
 */
@WebMvcTest({ChangeLogController.class, AuditController.class, SystemLogController.class})
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class LogPurgeViewTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private ChangeLogService changeLogService;
    @MockitoBean private ProjectService projectService;
    @MockitoBean private AdminAuditService auditService;
    @MockitoBean private AuditDetailRenderer detailRenderer;
    @MockitoBean private com.qqmu.jync.service.log.SystemLogService logService;

    private final LocalDate yesterday = LocalDate.now(ZoneId.systemDefault()).minusDays(1);

    private static RequestPostProcessor asRole(UserRole role) {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername(role.name().toLowerCase());
        user.setPasswordHash("x");
        user.setRole(role);
        SyncUserDetails principal = new SyncUserDetails(user, false);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    private void stubLists() {
        when(changeLogService.count(any(), anyBoolean())).thenReturn(0L);
        when(changeLogService.page(any(), anyBoolean(), any())).thenReturn(Page.empty());
        when(projectService.findAll()).thenReturn(List.of());
        when(auditService.count()).thenReturn(0L);
        when(auditService.page(anyInt(), anyInt())).thenReturn(Page.empty());
        when(logService.listFiles()).thenReturn(List.of());
        when(logService.query(any(), any(), any())).thenReturn(
                new com.qqmu.jync.service.log.SystemLogService.LogQueryResult(
                        List.of(), 0, false, null));
    }

    @Test
    void adminGetsThePurgeModalOnBothLogPagesWithYesterdayAsTheCap() throws Exception {
        stubLists();

        String logs = mvc.perform(get("/change-logs").with(asRole(UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(logs).contains("data-open-modal=\"log-purge-modal\"")
                .contains("data-purge-url=\"/change-logs/purge\"")
                .contains("max=\"" + yesterday + "\"")
                .contains("data-purge-days=\"30\"")
                .doesNotContain("/change-logs/clear");

        String audits = mvc.perform(get("/audits").with(asRole(UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(audits).contains("data-open-modal=\"log-purge-modal\"")
                .contains("data-purge-url=\"/audits/purge\"")
                .contains("max=\"" + yesterday + "\"")
                .contains("<h1>Audit log</h1>");
    }

    @Test
    void anOperatorGetsNoPurgeEntryOnTheChangeLog() throws Exception {
        stubLists();

        String logs = mvc.perform(get("/change-logs").with(asRole(UserRole.OPS)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(logs).doesNotContain("log-purge-modal");
        // The admin-only surface itself (audit page, both purge POSTs) is pinned by
        // SecurityConfigTest against the real filter chain, not by this template slice.
    }

    @Test
    void theChangeLogPurgeAnswersJsonWithTheLocalizedCount() throws Exception {
        when(changeLogService.purgeBefore(eq(yesterday), eq(7L), eq(true))).thenReturn(3);

        mvc.perform(post("/change-logs/purge").with(asRole(UserRole.ADMIN)).with(csrf())
                        .param("date", yesterday.toString())
                        .param("projectId", "7")
                        .param("failedOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Deleted 3 record(s)"));
    }

    @Test
    void aTooNewDateIsRefusedAsJsonNotAsAnException() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        when(changeLogService.purgeBefore(eq(today), any(), anyBoolean()))
                .thenThrow(new IllegalArgumentException(LogPurgeDates.INVALID_DATE));

        mvc.perform(post("/change-logs/purge").with(asRole(UserRole.ADMIN)).with(csrf())
                        .param("date", today.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message")
                        .value("The cutoff date must be yesterday or earlier"));
    }

    @Test
    void adminGetsThePurgeModalOnTheSystemLogPageToo() throws Exception {
        stubLists();

        String page = mvc.perform(get("/system-logs").with(asRole(UserRole.ADMIN)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains("data-open-modal=\"log-purge-modal\"")
                .contains("data-purge-url=\"/system-logs/purge\"")
                .contains("max=\"" + yesterday + "\"");

        String ops = mvc.perform(get("/system-logs").with(asRole(UserRole.OPS)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ops).doesNotContain("log-purge-modal");
    }

    @Test
    void theSystemLogPurgeAnswersJsonWithTheLocalizedCount() throws Exception {
        when(logService.purgeBefore(yesterday)).thenReturn(2);

        mvc.perform(post("/system-logs/purge").with(asRole(UserRole.ADMIN)).with(csrf())
                        .param("date", yesterday.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Deleted 2 record(s)"));
    }

    @Test
    void theAuditPurgeAnswersJsonWithTheLocalizedCount() throws Exception {
        when(auditService.purgeBefore(yesterday)).thenReturn(4);

        mvc.perform(post("/audits/purge").with(asRole(UserRole.ADMIN)).with(csrf())
                        .param("date", yesterday.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Deleted 4 record(s)"));
    }

}
