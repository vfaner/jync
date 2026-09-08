package com.synctool.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
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

import com.synctool.model.AppUser;
import com.synctool.model.ChangeLog;
import com.synctool.model.ChangeType;
import com.synctool.model.ObjectType;
import com.synctool.model.UserRole;
import com.synctool.repository.ChangeLogRepository;
import com.synctool.service.ProjectService;
import com.synctool.service.auth.SyncUserDetails;

/**
 * The change-log page's refresh controls, rendered for real.
 *
 * <p>The page is a server-rendered snapshot of a table the sync engine keeps appending to, so
 * without these controls it silently goes stale the moment you open it. Two things are easy to
 * break and invisible in a unit test:
 *
 * <ul>
 *   <li>the controls landing <em>inside</em> the {@code th:if="${isAdmin}"} that wraps "clear
 *       logs" — refreshing is a read, and a viewer who can read the log needs it most;
 *   <li>the new {@code log.refresh.*} keys missing from a bundle, which Thymeleaf renders as
 *       {@code ??log.refresh??} rather than failing.
 * </ul>
 */
@WebMvcTest(ChangeLogController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class ChangeLogRefreshViewTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ChangeLogRepository changeLogRepository;

    @MockBean
    private ProjectService projectService;

    private static RequestPostProcessor as(UserRole role) {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername(role == UserRole.ADMIN ? "admin" : "view");
        user.setPasswordHash("not-checked-here");
        user.setRole(role);
        SyncUserDetails principal = new SyncUserDetails(user, false);
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
    }

    /** One entry is enough: it is the table's presence, not its contents, that matters here. */
    private void stubOnePageOf(int totalEntries, int pageNumber) {
        ChangeLog entry = new ChangeLog();
        entry.setId(1L);
        entry.setObjectType(ObjectType.TABLE);
        entry.setObjectName("orders");
        entry.setChangeType(ChangeType.CREATE);
        entry.setSuccess(true);
        entry.setOccurredAt(Instant.now());
        Pageable pageable = PageRequest.of(pageNumber, 50);
        Page<ChangeLog> page = new PageImpl<>(List.of(entry), pageable, totalEntries);
        when(changeLogRepository.findAllByOrderByOccurredAtDesc(any(Pageable.class))).thenReturn(page);
        when(projectService.findAll()).thenReturn(List.of());
    }

    private String render(RequestPostProcessor who, String query, String acceptLanguage)
            throws Exception {
        return mvc.perform(get("/change-logs" + query)
                        .header("Accept-Language", acceptLanguage)
                        .with(who))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void aViewerGetsTheRefreshControlsWithoutGettingTheClearButton() throws Exception {
        stubOnePageOf(1, 0);

        String html = render(as(UserRole.VIEWER), "", "zh-CN");

        // The whole reason the controls sit outside the isAdmin block.
        assertThat(html)
                .contains("data-role=\"log-refresh\"")
                .contains("data-role=\"log-auto\"");
        // ...and the reason they could not simply be added next to it.
        assertThat(html).doesNotContain("data-role=\"clear-logs-form\"");
    }

    @Test
    void anAdminGetsBothTheRefreshControlsAndTheClearButton() throws Exception {
        stubOnePageOf(1, 0);

        String html = render(as(UserRole.ADMIN), "", "zh-CN");

        assertThat(html)
                .contains("data-role=\"log-refresh\"")
                .contains("data-role=\"log-auto\"")
                .contains("data-role=\"clear-logs-form\"");
    }

    /**
     * The refresh swaps these two nodes by id and leaves the rest of the page alone. Renaming or
     * dropping either turns auto-refresh into a silent no-op — the timer keeps firing, the table
     * never changes.
     */
    @Test
    void thePageExposesTheTwoNodesTheRefreshSwaps() throws Exception {
        stubOnePageOf(1, 0);

        String html = render(as(UserRole.ADMIN), "", "zh-CN");

        assertThat(html).contains("id=\"log-card\"").contains("id=\"log-count\"");
    }

    @Test
    void bothBundlesResolveTheRefreshCopy() throws Exception {
        stubOnePageOf(1, 0);

        // A missing key renders as ??log.refresh_zh_CN?? instead of failing, so assert on the
        // marker rather than trusting the page to blow up.
        assertThat(render(as(UserRole.ADMIN), "", "zh-CN")).doesNotContain("??");
        assertThat(render(as(UserRole.ADMIN), "", "en-US")).doesNotContain("??");
    }

    /**
     * The log is newest-first, so on page 2 every arriving entry pushes the rows you are reading
     * downwards. Auto-refresh is disabled there rather than left to shuffle the page underneath
     * the reader — and disabled visibly, with the reason in the tooltip.
     */
    @Test
    void autoRefreshIsDisabledOnPagesOtherThanTheFirst() throws Exception {
        stubOnePageOf(120, 1);

        String html = render(as(UserRole.ADMIN), "?page=1", "zh-CN");

        int auto = html.indexOf("data-role=\"log-auto\"");
        assertThat(auto).as("the auto-refresh button is rendered").isGreaterThan(-1);
        String button = html.substring(auto, html.indexOf('>', auto));
        assertThat(button).contains("disabled").contains("title=");

        // The manual button stays usable: one refresh on demand does not shuffle anything.
        int manual = html.indexOf("data-role=\"log-refresh\"");
        assertThat(html.substring(manual, html.indexOf('>', manual))).doesNotContain("disabled");
    }

    @Test
    void theFirstPageLeavesAutoRefreshEnabled() throws Exception {
        stubOnePageOf(120, 0);

        String html = render(as(UserRole.ADMIN), "", "zh-CN");

        int auto = html.indexOf("data-role=\"log-auto\"");
        // No tooltip either: the explanation exists only to justify the disabled state, and an
        // empty title="" would leave an unexplained hover target on a perfectly usable button.
        assertThat(html.substring(auto, html.indexOf('>', auto)))
                .doesNotContain("disabled")
                .doesNotContain("title=");
    }
}
