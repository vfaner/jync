package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.ChangeLogDetailsLocalizer;
import com.qqmu.jync.service.ChangeLogService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.auth.SyncUserDetails;

/**
 * The shared DDL dialog rewrites its two action buttons for whichever row opened it, so its
 * URL prefix must come from the server. A root-absolute literal would still work on the
 * default {@code context-path: /} and then post to the wrong place under any other context
 * path — a silent 404 on an operation that changes the target database.
 */
@WebMvcTest(ChangeLogController.class)
@Import({GlobalModelAdvice.class, ChangeLogDetailsLocalizer.class})
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class ChangeLogDdlDialogUrlTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ChangeLogService changeLogService;

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
    void theDdlDialogCarriesTheServerRenderedActionBase() throws Exception {
        when(changeLogService.page(any(), anyBoolean(), any(Pageable.class)))
                .thenReturn(Page.empty());
        when(changeLogService.count(any(), anyBoolean())).thenReturn(0L);
        when(projectService.findAll()).thenReturn(List.of());

        String page = mvc.perform(get("/change-logs").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        int modal = page.indexOf("id=\"ddl-execute-modal\"");
        assertThat(modal).as("the dialog is rendered").isNotNegative();
        assertThat(page.substring(page.lastIndexOf("<div", modal), page.indexOf(">", modal)))
                .as("the action prefix is resolved by the server")
                .contains("data-action-base=\"/change-logs\"");

        assertThat(script())
                .as("the page script takes the prefix from the dialog instead of hardcoding it")
                .doesNotContain("'/change-logs/'")
                .contains("data-action-base");
    }

    private static String script() throws IOException {
        return Files.readString(Path.of("src/main/resources/static/js/change-logs.js"));
    }
}
