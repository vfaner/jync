package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
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
import com.qqmu.jync.service.ChangeLogService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.auth.SyncUserDetails;

/**
 * The log replaced its old fixed-50 prev/next footer with the shared pager: the requested
 * size must reach the repository query, the window must render around the current page, and
 * an out-of-range page must clamp instead of rendering an empty last slice.
 */
@WebMvcTest(ChangeLogController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class ChangeLogPagerViewTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ChangeLogService changeLogService;

    @MockBean
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

    private void stubLogOf(long totalEntries, Pageable echo) {
        ChangeLog entry = new ChangeLog();
        entry.setId(1L);
        entry.setObjectType(ObjectType.TABLE);
        entry.setObjectName("orders");
        entry.setChangeType(ChangeType.CREATE);
        entry.setSuccess(true);
        entry.setOccurredAt(Instant.now());
        Page<ChangeLog> page = new PageImpl<>(List.of(entry), echo, totalEntries);
        when(changeLogService.count(any(), anyBoolean())).thenReturn(totalEntries);
        when(changeLogService.page(any(), anyBoolean(), any(Pageable.class))).thenReturn(page);
        when(projectService.findAll()).thenReturn(List.of());
    }

    private String render(String query) throws Exception {
        return mvc.perform(get("/change-logs" + query).with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void theRequestedSizeReachesTheQueryAndTheWindowRendersAroundThePage() throws Exception {
        stubLogOf(400, PageRequest.of(1, 15));

        String html = render("?size=15&page=2");

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(changeLogService).page(any(), anyBoolean(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(15);
        assertThat(captor.getValue().getPageNumber()).isEqualTo(1);

        assertThat(html)
                .contains("is-current\">2</span>")
                .contains("value=\"15\" selected=\"selected\"")
                // 27 pages: the right ellipsis widens spanRight by five in place.
                .contains("spanR=6")
                .contains("max=\"27\"");
    }

    @Test
    void anOutOfRangePageClampsToTheLastPage() throws Exception {
        stubLogOf(400, PageRequest.of(19, 20));

        String html = render("?page=999");

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(changeLogService).page(any(), anyBoolean(), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(19);
        assertThat(html).contains("is-current\">20</span>");
    }

    @Test
    void theFailureFilterIsThreadedToTheServiceAndShownChecked() throws Exception {
        stubLogOf(3, PageRequest.of(0, 20));

        String html = render("?failedOnly=true");

        verify(changeLogService).count(any(), org.mockito.ArgumentMatchers.eq(true));
        verify(changeLogService).page(any(), org.mockito.ArgumentMatchers.eq(true),
                any(Pageable.class));
        assertThat(html).contains("name=\"failedOnly\"")
                .contains("checked=\"checked\"");
        // Paging must not drop the filter.
        assertThat(html).contains("failedOnly=true");
    }

    @Test
    void clearingKeepsTheFailureFilterInTheRedirect() throws Exception {
        mvc.perform(post("/change-logs/clear")
                        .with(org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf())
                        .param("failedOnly", "true").with(asAdmin()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/change-logs?failedOnly=true"));
    }

    @Test
    void withoutTheFilterTheCheckboxIsUncheckedAndTheFlagIsFalse() throws Exception {
        stubLogOf(3, PageRequest.of(0, 20));

        String html = render("");

        verify(changeLogService).count(any(), org.mockito.ArgumentMatchers.eq(false));
        int checkbox = html.indexOf("name=\"failedOnly\"");
        assertThat(checkbox).as("the filter control is rendered").isGreaterThan(-1);
        assertThat(html.substring(checkbox, html.indexOf('>', checkbox)))
                .doesNotContain("checked");
        assertThat(html).doesNotContain("failedOnly=true");
    }
}
