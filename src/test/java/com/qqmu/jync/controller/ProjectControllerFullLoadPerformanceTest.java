package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.qqmu.jync.dto.SyncConfig;
import com.qqmu.jync.model.CaptureStrategy;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.Project;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.ProjectService;
import com.qqmu.jync.service.cdc.CdcConsumerManager;
import com.qqmu.jync.service.cdc.CdcOperationsService;
import com.qqmu.jync.service.cdc.CdcPositionStore;

/**
 * The full-load knobs (batch size, worker count) are loading performance, not scheduling.
 * They must survive a save whether or not the custom-schedule switch is on, be clamped at the
 * server, and stay visible on the detail page — a CDC project's baseline load uses exactly
 * the same code path, so hiding the card there would hide the only lever it has.
 */
@WebMvcTest(ProjectController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
@WithMockUser(roles = "ADMIN")
class ProjectControllerFullLoadPerformanceTest {

    @Autowired private MockMvc mvc;
    @MockBean private ProjectService projectService;
    @MockBean private DatabaseConfigService databaseConfigService;
    @MockBean private CdcOperationsService cdcOperationsService;
    @MockBean private CdcConsumerManager cdcConsumerManager;
    @MockBean private CdcPositionStore cdcPositionStore;

    private Project stubDetail(CaptureStrategy strategy) {
        Project project = new Project();
        project.setId(1L);
        project.setName("proj");
        project.setEnabled(false);
        project.setSourceDbId(5L);
        when(projectService.findById(1L)).thenReturn(Optional.of(project));

        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(strategy);
        when(projectService.loadConfig(project)).thenReturn(config);

        DatabaseConfig source = new DatabaseConfig();
        source.setId(5L);
        source.setType(DatabaseType.ORACLE);
        when(databaseConfigService.findById(5L)).thenReturn(Optional.of(source));
        when(databaseConfigService.findAll()).thenReturn(List.of());
        when(projectService.findTask(1L)).thenReturn(Optional.empty());
        when(projectService.findProgress(1L)).thenReturn(List.of());
        when(projectService.isScheduled(1L)).thenReturn(false);
        when(projectService.listSourceObjects(1L)).thenReturn(new ProjectService.SourceObjects());
        return project;
    }

    /** The full saveConfig payload, without the scheduling-side parameters. */
    private MockHttpServletRequestBuilder configPost() {
        return post("/projects/1/config").with(csrf())
                .param("tables", "orders")
                .param("syncStructure", "true")
                .param("syncData", "true")
                .param("captureStrategy", "POLLING");
    }

    private SyncConfig savedConfig() {
        ArgumentCaptor<SyncConfig> captor = ArgumentCaptor.forClass(SyncConfig.class);
        verify(projectService).saveConfig(eq(1L), captor.capture(), eq("POLLING"));
        return captor.getValue();
    }

    @Test
    void performanceSettingsPersistEvenWithTheCustomScheduleSwitchOff() throws Exception {
        stubDetail(CaptureStrategy.POLLING);

        mvc.perform(configPost()
                        .param("batchSize", "1500")
                        .param("fullLoadParallelism", "3"))
                .andExpect(status().is3xxRedirection());

        SyncConfig saved = savedConfig();
        assertThat(saved.getBatchSize()).isEqualTo(1500);
        assertThat(saved.getFullLoadParallelism()).isEqualTo(3);
        // The schedule switch still governs the schedule fields alone.
        assertThat(saved.getPollIntervalMs()).isNull();
        assertThat(saved.getCronExpression()).isNull();
    }

    @Test
    void omittedPerformanceSettingsAreStoredAsNull() throws Exception {
        stubDetail(CaptureStrategy.POLLING);

        mvc.perform(configPost()).andExpect(status().is3xxRedirection());

        SyncConfig saved = savedConfig();
        assertThat(saved.getBatchSize()).isNull();
        assertThat(saved.getFullLoadParallelism()).isNull();
    }

    @Test
    void parallelismAboveTheCeilingIsClampedToEight() throws Exception {
        stubDetail(CaptureStrategy.POLLING);

        mvc.perform(configPost().param("fullLoadParallelism", "99"))
                .andExpect(status().is3xxRedirection());

        assertThat(savedConfig().getFullLoadParallelism()).isEqualTo(8);
    }

    @Test
    void aZeroOrNegativeBatchSizeFallsBackToOneInsteadOfRejectingTheSave() throws Exception {
        stubDetail(CaptureStrategy.POLLING);

        mvc.perform(configPost().param("batchSize", "0"))
                .andExpect(status().is3xxRedirection());

        assertThat(savedConfig().getBatchSize()).isEqualTo(1);
    }

    @Test
    void detailShowsThePerformanceCardInBothPollingAndCdcModes() throws Exception {
        stubDetail(CaptureStrategy.POLLING);
        String polling = mvc.perform(get("/projects/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(polling).contains("name=\"fullLoadParallelism\"");
        assertThat(polling).contains("name=\"batchSize\"");

        stubDetail(CaptureStrategy.LOGMINER_CDC);
        String cdc = mvc.perform(get("/projects/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(cdc).contains("name=\"fullLoadParallelism\"");
        assertThat(cdc).contains("name=\"batchSize\"");
    }

    @Test
    void detailStampsTheSavedPerformanceValuesIntoTheCard() throws Exception {
        Project project = stubDetail(CaptureStrategy.POLLING);
        SyncConfig config = new SyncConfig();
        config.setCaptureStrategy(CaptureStrategy.POLLING);
        config.setBatchSize(1500);
        config.setFullLoadParallelism(3);
        when(projectService.loadConfig(project)).thenReturn(config);

        String page = mvc.perform(get("/projects/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(inputTag(page, "batchSize")).contains("value=\"1500\"");
        assertThat(inputTag(page, "fullLoadParallelism")).contains("value=\"3\"");
    }

    /**
     * An empty field means "use the global default", which is a fine rule only if the user
     * can see what that default is — otherwise there is no baseline to tune against. The
     * placeholder carries it without saving an override the user never chose.
     */
    @Test
    void anEmptyPerformanceFieldShowsTheGlobalDefaultAsItsPlaceholder() throws Exception {
        stubDetail(CaptureStrategy.POLLING);

        String page = mvc.perform(get("/projects/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(inputTag(page, "batchSize")).contains("placeholder=\"2000\"");
        assertThat(inputTag(page, "fullLoadParallelism")).contains("placeholder=\"2\"");
        assertThat(page).as("the help text repeats the number for when the field is filled")
                .contains("2000");
    }

    /**
     * "实际值受连接池上限约束" tells the user a cap exists but not what it is, so a value
     * like 8 looks inert: saved, then silently run as 4. The card must show the number the
     * worker count will actually be clamped to.
     */
    @Test
    void theParallelismFieldShowsHowManyWorkersThePoolCanSeat() throws Exception {
        stubDetail(CaptureStrategy.POLLING);

        String page = mvc.perform(get("/projects/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Default pool of 5; source and target are distinct connections, and the main cycle
        // holds one connection per side for the whole load: 5 - 1 = 4 workers.
        assertThat(textOf(page, "fullLoadParallelismCap")).contains("5").contains("4");
    }

    /**
     * Both sides pointing at one connection share a single pool: the main cycle holds two
     * connections and each worker borrows one of each, so the same pool of 5 seats one
     * worker — not four. A hard-coded "4" would pass the test above and fail here.
     */
    @Test
    void theShownCapFollowsTheSharedPoolWhenBothSidesAreTheSameConnection() throws Exception {
        Project project = stubDetail(CaptureStrategy.POLLING);
        project.setTargetDbId(5L);

        String page = mvc.perform(get("/projects/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(textOf(page, "fullLoadParallelismCap")).contains("5").contains("1");
    }

    /**
     * Bigger is not simply better, and neither field wears that on its face: the batch size
     * trades speed for undo/redo and rollback time, the worker count trades speed for database
     * load. The recommendation has to be reachable from the field itself, without a manual.
     */
    @Test
    void thePerformanceFieldsCarryAHoverExplanationWithTheRecommendedRange() throws Exception {
        stubDetail(CaptureStrategy.POLLING);

        String page = mvc.perform(get("/projects/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("#question-circle");
        assertThat(textOf(page, "batchSizeTip")).contains("1000-5000");
        assertThat(textOf(page, "fullLoadParallelismTip")).contains("1-4");
    }

    /**
     * The browser validates the form before it posts it, and an HTML number input only accepts
     * values on the grid {@code min + k * step}. {@code min="1" step="500"} therefore made
     * 1, 501, 1001, … the only submittable batch sizes: typing the documented default 2000 or
     * a round 5000 stopped the form from posting at all — no request, no error, the Save button
     * looked dead and the dirty badge never cleared. Any integer the server keeps must be
     * submittable, so the grid cannot be coarser than 1.
     */
    @Test
    void everyNumberFieldAcceptsAnyIntegerItsBoundsAllow() throws Exception {
        stubDetail(CaptureStrategy.POLLING);
        String page = mvc.perform(get("/projects/1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        java.util.regex.Matcher inputs = java.util.regex.Pattern
                .compile("<input[^>]*type=\"number\"[^>]*>").matcher(page);
        int checked = 0;
        while (inputs.find()) {
            String tag = inputs.group();
            int at = tag.indexOf("step=\"");
            String step = at < 0 ? null : tag.substring(at + 6, tag.indexOf('"', at + 6));
            assertThat(step)
                    .as("a coarser step makes the browser reject valid values: %s", tag)
                    .isIn(null, "1", "any");
            checked++;
        }
        assertThat(checked).as("the performance and schedule fields are number inputs")
                .isGreaterThanOrEqualTo(3);
    }

    /** Text content of the element with the given id (the tip body holds plain text only). */
    private static String textOf(String page, String id) {
        int at = page.indexOf("id=\"" + id + "\"");
        assertThat(at).as("element " + id).isPositive();
        int open = page.indexOf('>', at) + 1;
        return page.substring(open, page.indexOf('<', open));
    }

    /** The opening tag of the input with the given id, so value assertions cannot drift. */
    private static String inputTag(String page, String id) {
        int at = page.indexOf("id=\"" + id + "\"");
        assertThat(at).as("input " + id).isPositive();
        return page.substring(at, page.indexOf(">", at) + 1);
    }
}
