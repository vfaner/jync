package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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

    private void stubOneLog(String details) {
        ChangeLog entry = new ChangeLog();
        entry.setId(1L);
        entry.setObjectType(ObjectType.PROJECT);
        entry.setObjectName("orders");
        entry.setChangeType(ChangeType.INFO);
        entry.setDetails(details);
        entry.setSuccess(true);
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
}
