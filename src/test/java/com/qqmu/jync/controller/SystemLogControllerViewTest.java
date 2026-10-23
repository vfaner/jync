package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.service.log.SystemLogService;

/**
 * The system-log page's three controls and the panel below them. Reading rules and escaping are
 * pinned in {@code SystemLogServiceTest}; this class pins the markup contract: the service hands
 * over escaped lines with markers, and the template must insert them as markup, not text.
 */
@WebMvcTest(SystemLogController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://github.com/vfaner/jync")
class SystemLogControllerViewTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private SystemLogService logService;
    @MockBean
    private BuildProperties buildProperties;

    @BeforeEach
    void setUp() {
        List<SystemLogService.LogFile> files = List.of(
                new SystemLogService.LogFile("jync.log", 1024, Instant.parse("2026-10-23T10:00:00Z")),
                new SystemLogService.LogFile("jync.log.2026-10-01.0.gz", 2048,
                        Instant.parse("2026-10-01T10:00:00Z")));
        when(logService.listFiles()).thenReturn(files);
        when(logService.query(any(), any(), any())).thenReturn(
                new SystemLogService.LogQueryResult(
                        List.of("12:00 INFO <mark>needle</mark> found", "2 &lt;tag&gt;"), 2, true, null));
    }

    /** Whitespace-collapsed markup; Thymeleaf wraps long attributes onto their own lines. */
    private String page(String query) throws Exception {
        String html = mvc.perform(get("/system-logs" + query).locale(Locale.SIMPLIFIED_CHINESE))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return html.replaceAll("\\s+", " ");
    }

    @Test
    void theToolbarOffersAFileLevelAndKeyword() throws Exception {
        String page = page("");

        assertThat(page).contains("name=\"file\"", "name=\"level\"", "name=\"q\"");
        assertThat(page).contains("jync.log.2026-10-01.0.gz");
        assertThat(page).contains("查询");
        assertThat(page).doesNotContain("??");
    }

    @Test
    void theNewestFileIsSelectedByDefault() throws Exception {
        String page = page("");

        assertThat(page).contains("value=\"jync.log\" selected=\"selected\"");
    }

    @Test
    void thePanelInsertsEscapedLinesAsMarkup() throws Exception {
        String page = page("?file=jync.log&level=INFO&q=needle");

        assertThat(page).contains("class=\"log-panel\"");
        assertThat(page).contains("<mark>needle</mark>");
        assertThat(page).contains("2 &lt;tag&gt;");
        // The level and keyword survive the round trip into the form.
        assertThat(page).contains("value=\"WARN\""); // options are listed even when INFO is chosen
        assertThat(page).contains("value=\"needle\"");
        assertThat(page).contains("2 条匹配");
    }

    @Test
    void aChosenLevelIsSelected() throws Exception {
        assertThat(page("?level=WARN")).contains("value=\"WARN\" selected=\"selected\"");
    }

    @Test
    void anEmptyResultShowsTheEmptyState() throws Exception {
        when(logService.query(any(), any(), any())).thenReturn(
                new SystemLogService.LogQueryResult(List.of(), 0, false, null));

        assertThat(page("")).contains("没有匹配的日志");
    }

    @Test
    void aReadFailureShowsTheLocalizedError() throws Exception {
        when(logService.query(any(), any(), any())).thenReturn(
                new SystemLogService.LogQueryResult(List.of(), 0, false, "syslog.error.file"));

        assertThat(page("?file=missing.log")).contains("日志文件不可用");
    }
}
