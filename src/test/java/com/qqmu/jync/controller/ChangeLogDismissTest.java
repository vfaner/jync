package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.ChangeLogDetailsLocalizer;
import com.qqmu.jync.service.ChangeLogService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.cdc.CdcDdlTaskService;

/**
 * An administrator must be able to close a skipped-DDL task without running anything: the
 * column may have been fixed by hand, or the table may no longer be synced. Left open, the
 * task keeps the column projection on and is never pruned.
 */
@WebMvcTest(ChangeLogController.class)
@Import({GlobalModelAdvice.class, ChangeLogDetailsLocalizer.class})
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class ChangeLogDismissTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ChangeLogService changeLogService;

    @MockitoBean
    private CdcDdlTaskService ddlTaskService;

    @MockitoBean
    private ProjectService projectService;

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
    void anAdminCanDismissAnOpenTask() throws Exception {
        mvc.perform(post("/change-logs/9/dismiss").with(csrf()).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").isNotEmpty());

        verify(ddlTaskService).dismissDdl(9L);
    }

    @Test
    void thePageOffersADismissControlForOpenTasks() throws Exception {
        ChangeLog entry = new ChangeLog();
        entry.setId(9L);
        entry.setProjectId(3L);
        entry.setObjectType(com.qqmu.jync.model.ObjectType.TABLE);
        entry.setObjectName("orders");
        entry.setChangeType(com.qqmu.jync.model.ChangeType.ERROR);
        entry.setSuccess(false);
        entry.setDetails(com.qqmu.jync.service.sync.SyncStateWriter.SKIPPED_DDL_PREFIX
                + "ALTER TABLE orders ADD COLUMN note VARCHAR(64)");
        entry.setResolutionStatus(ChangeLog.RESOLUTION_PENDING);
        entry.setOccurredAt(java.time.Instant.now());
        when(changeLogService.count(any(), anyBoolean())).thenReturn(1L);
        when(changeLogService.page(any(), anyBoolean(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(java.util.List.of(entry),
                        PageRequest.of(0, 20), 1));
        when(projectService.findAll()).thenReturn(java.util.List.of());

        String html = mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .get("/change-logs").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("data-role=\"ddl-dismiss\"")
                .contains("data-ddl-dismiss-confirm=");
    }

    @Test
    void aRefusedDismissReturnsTheReasonInsteadOfFailing() throws Exception {
        doThrow(new IllegalStateException("error.ddl.notPending")).when(ddlTaskService)
                .dismissDdl(anyLong());

        mvc.perform(post("/change-logs/9/dismiss").with(csrf()).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("This change-log row is not a pending DDL task"));
    }
}
