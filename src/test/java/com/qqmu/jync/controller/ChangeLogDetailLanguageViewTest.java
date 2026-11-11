package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.ChangeLog;
import com.qqmu.jync.model.ChangeType;
import com.qqmu.jync.model.ObjectType;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.ChangeLogDetailsLocalizer;
import com.qqmu.jync.service.ChangeLogService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.cdc.CdcDdlTaskService;
import com.qqmu.jync.service.sync.SyncStateWriter;

/**
 * The 详情 column follows the page's language, rendered for real.
 *
 * <p>The stored sentence stays English in the database; the page translates it at render time.
 * This pins the wiring end to end — the bean is reachable from the template, the locale the
 * request resolved reaches it, and switching back to English shows the original wording.
 */
@WebMvcTest(ChangeLogController.class)
@Import({GlobalModelAdvice.class, ChangeLogDetailsLocalizer.class})
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class ChangeLogDetailLanguageViewTest {

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

    private void stubOneLog(String details) {
        stubOneLog(details, true, ChangeType.INFO, null);
    }

    private void stubOneLog(String details, boolean success, ChangeType changeType) {
        stubOneLog(details, success, changeType, null);
    }

    private void stubOneLog(String details, boolean success, ChangeType changeType,
                            String resolutionStatus) {
        ChangeLog entry = new ChangeLog();
        entry.setId(1L);
        entry.setObjectType(ObjectType.PROJECT);
        entry.setObjectName("orders");
        entry.setChangeType(changeType);
        entry.setDetails(details);
        entry.setSuccess(success);
        entry.setResolutionStatus(resolutionStatus);
        entry.setOccurredAt(Instant.now());
        when(changeLogService.count(any(), anyBoolean())).thenReturn(1L);
        when(changeLogService.page(any(), anyBoolean(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entry), PageRequest.of(0, 20), 1));
        when(projectService.findAll()).thenReturn(List.of());
    }

    private String render(Locale locale) throws Exception {
        return mvc.perform(get("/change-logs").locale(locale).with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void theStoredSentenceIsShownInChinese() throws Exception {
        stubOneLog("CDC consumer started");

        String html = render(Locale.SIMPLIFIED_CHINESE);

        assertThat(html).contains("CDC 消费器已启动")
                .doesNotContain("CDC consumer started");
    }

    @Test
    void switchingToEnglishShowsTheStoredWording() throws Exception {
        stubOneLog("CDC consumer started");

        String html = render(Locale.ENGLISH);

        assertThat(html).contains("CDC consumer started").doesNotContain("消费器");
    }

    @Test
    void anUnknownSentenceIsShownExactlyAsStored() throws Exception {
        stubOneLog("ORA-00942: table or view does not exist");

        String html = render(Locale.SIMPLIFIED_CHINESE);

        assertThat(html).contains("ORA-00942: table or view does not exist");
    }

    @Test
    void aSkippedDdlIsExpandableWithItsFullStatement() throws Exception {
        String sql = "ALTER TABLE `app`.`file_info` \n"
                + "ADD COLUMN `suffix` varchar(20) NULL COMMENT '后缀名' AFTER `size`, \n"
                + "ADD COLUMN `expiry` datetime NULL";
        stubOneLog(SyncStateWriter.SKIPPED_DDL_PREFIX + sql, false, ChangeType.ERROR);

        String html = render(Locale.SIMPLIFIED_CHINESE);

        assertThat(html).contains("<details")
                .contains("查看完整 DDL")
                .contains("row-bad")
                .contains("badge-danger")
                .contains("ADD COLUMN")
                .contains("后缀名")
                .contains("expiry")
                .doesNotContain("CDC_SKIPPED_DDL:");
    }

    @Test
    void aPendingDdlTaskShowsItsResolutionBadge() throws Exception {
        stubOneLog(SyncStateWriter.SKIPPED_DDL_PREFIX + "ALTER TABLE t ADD COLUMN c INT",
                false, ChangeType.ERROR, ChangeLog.RESOLUTION_PENDING);

        String html = render(Locale.SIMPLIFIED_CHINESE);

        assertThat(html).contains("待处理").contains("badge-warn")
                .contains("结构同步")
                .contains("查看完整 DDL")
                .contains("data-role=\"ddl-open\"")
                .contains("data-ddl-id=\"1\"")
                .contains("ddl-sql-1")
                .contains("ddl-execute-modal")
                .contains("data-role=\"ddl-execute\"");
    }

    @Test
    void anAdminCanExecuteTheEditedDdlFromTheList() throws Exception {
        when(ddlTaskService.executeDdl(7L, "ALTER TABLE t ADD COLUMN c INT"))
                .thenReturn(new CdcDdlTaskService.DdlTaskOutcome(true, "orders(c)"));
        mvc.perform(post("/change-logs/7/execute")
                        .with(csrf()).with(asAdmin())
                        .param("sql", "ALTER TABLE t ADD COLUMN c INT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.needsReplay").value(true))
                .andExpect(jsonPath("$.affectedTables").value("orders(c)"));

        verify(ddlTaskService).executeDdl(7L, "ALTER TABLE t ADD COLUMN c INT");
    }

    @Test
    void aProcessedDdlTaskShowsTheDoneBadge() throws Exception {
        stubOneLog(SyncStateWriter.SKIPPED_DDL_PREFIX + "ALTER TABLE t ADD COLUMN c INT",
                true, ChangeType.INFO, ChangeLog.RESOLUTION_PROCESSED);

        String html = render(Locale.SIMPLIFIED_CHINESE);

        assertThat(html).contains("已处理").contains("badge-ok")
                .doesNotContain("待处理");
    }

    @Test
    void anOrdinaryAuditRowShowsNoTaskBadge() throws Exception {
        stubOneLog("CDC consumer started");

        String html = render(Locale.SIMPLIFIED_CHINESE);

        assertThat(html).doesNotContain("log.ddl.pending")
                .doesNotContain("log.ddl.processed");
    }
}
