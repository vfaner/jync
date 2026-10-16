package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.qqmu.jync.model.ConnectionRole;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.auth.SyncUserDetails;

/**
 * Server-side first-paint state of the driver-jar card on the connection form.
 *
 * <p>The card must already be hidden (or shown) in the served HTML; waiting for the
 * type-defaults AJAX call would flash the card on every GBase / Oscar edit page. The rule is
 * classpath-driven through {@code DriverPresence}: bundled presets hide it, GBase and Oscar
 * show it, and CUSTOM always shows it.
 */
@WebMvcTest(DatabaseConfigController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class DatabaseConfigFormViewTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private DatabaseConfigService service;

    /** Matches the jar-card element while it carries the hidden attribute. */
    private static final Pattern JAR_CARD_HIDDEN =
            Pattern.compile("id=\"jar-card\"[^>]*hidden", Pattern.DOTALL);

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

    private String html(org.springframework.test.web.servlet.ResultActions action) throws Exception {
        return action.andReturn().getResponse().getContentAsString();
    }

    @Test
    void newFormDefaultsToMysqlAndHidesTheJarCard() throws Exception {
        String page = html(mvc.perform(get("/databases/new").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk()));
        assertThat(JAR_CARD_HIDDEN.matcher(page).find())
                .as("MySQL is bundled, jar card must be hidden on first paint")
                .isTrue();
        assertThat(page).doesNotContain("??db.jarCard", "??db.jarCardHint");
        // hidden alone still submits the (blank or stale) value; the control must be disabled too
        assertThat(page).containsPattern("id=\"customJarPath\"[^>]*disabled");
    }

    @Test
    void newFormOffersSourceAndTargetRoleAndDefaultsToSource() throws Exception {
        String page = html(mvc.perform(get("/databases/new").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk()));
        assertThat(page).contains("name=\"role\"");
        assertThat(page).contains("value=\"SOURCE\"");
        assertThat(page).contains("value=\"TARGET\"");
        assertThat(page).containsPattern("value=\"SOURCE\"[^>]*checked");
        assertThat(page).doesNotContain("??db.role");
    }

    @Test
    void newFormPreselectsRoleFromQueryParameter() throws Exception {
        String page = html(mvc.perform(get("/databases/new").param("role", "TARGET")
                        .with(as(UserRole.ADMIN)))
                .andExpect(status().isOk()));
        assertThat(page).containsPattern("value=\"TARGET\"[^>]*checked");
        assertThat(page).doesNotContainPattern(
                Pattern.compile("value=\"SOURCE\"[^>]*checked"));
    }

    @Test
    void newFormMarksThePrefilledPortAsAutofilled() throws Exception {
        // Without the flag app.js treats the server-prefilled 3306 as a user value and never
        // replaces it when the type changes.
        String page = html(mvc.perform(get("/databases/new").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk()));
        assertThat(page).containsPattern(
                "id=\"port\"[^>]*value=\"3306\"[^>]*data-autofilled=\"true\"|"
                + "id=\"port\"[^>]*data-autofilled=\"true\"[^>]*value=\"3306\"");
    }

    @Test
    void editFormDoesNotMarkTheStoredPortAsAutofilled() throws Exception {
        DatabaseConfig dm = new DatabaseConfig();
        dm.setId(9L);
        dm.setName("dm-prod");
        dm.setType(DatabaseType.DM);
        dm.setPort(5236);
        when(service.findById(9L)).thenReturn(Optional.of(dm));

        String page = html(mvc.perform(get("/databases/9/edit").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk()));
        assertThat(page).contains("id=\"port\"");
        assertThat(page).doesNotContain("data-autofilled");
    }

    /** Extracts the #miner-section element's opening div tag. */
    private static String minerSectionTag(String page) {
        int at = page.indexOf("id=\"miner-section\"");
        assertThat(at).as("miner section is rendered").isPositive();
        int start = page.lastIndexOf("<div", at);
        int end = page.indexOf(">", at);
        return page.substring(start, end + 1);
    }

    @Test
    void oracleSourceEditShowsTheMinerSection() throws Exception {
        DatabaseConfig oracle = new DatabaseConfig();
        oracle.setId(10L);
        oracle.setName("ora-src");
        oracle.setType(DatabaseType.ORACLE);
        oracle.setRole(ConnectionRole.SOURCE);
        when(service.findById(10L)).thenReturn(Optional.of(oracle));

        String page = html(mvc.perform(get("/databases/10/edit").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk()));
        assertThat(minerSectionTag(page)).doesNotContain("hidden");
        // 输入框不带 disabled 才会随表单提交
        assertThat(extractInput(page, "minerUsername")).doesNotContain("disabled");
    }

    @Test
    void oracleTargetEditHidesTheMinerSection() throws Exception {
        // 挖掘账号只服务于源端 LogMiner;Oracle 目标库显示这两个输入框是纯误导。
        DatabaseConfig oracle = new DatabaseConfig();
        oracle.setId(11L);
        oracle.setName("ora-tgt");
        oracle.setType(DatabaseType.ORACLE);
        oracle.setRole(ConnectionRole.TARGET);
        when(service.findById(11L)).thenReturn(Optional.of(oracle));

        String page = html(mvc.perform(get("/databases/11/edit").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk()));
        assertThat(minerSectionTag(page)).contains("hidden");
        // 隐藏必须连控件一起禁用,否则残留的挖掘账号会随目标库表单提交
        assertThat(extractInput(page, "minerUsername")).contains("disabled");
        assertThat(extractInput(page, "rawMinerPassword")).contains("disabled");
    }

    private static String extractInput(String page, String inputId) {
        int at = page.indexOf("id=\"" + inputId + "\"");
        assertThat(at).as("input %s rendered", inputId).isPositive();
        int start = page.lastIndexOf("<input", at);
        int end = page.indexOf(">", at);
        return page.substring(start, end + 1);
    }

    @Test
    void jsMessageBundleCarriesAllCdcCheckKeys() throws Exception {
        // 连接测试结果由 app.js 用 window.JyncMessages 翻译;Oracle/PG/SQLServer 的
        // check key 没进这本字典,页面就显示 cdc.check.ORACLE_SOURCE 这种 raw key。
        String page = html(mvc.perform(get("/databases/new").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk()));
        assertThat(page).contains(
                "'cdc.probe.title.oracle'", "'cdc.probe.title.postgres'",
                "'cdc.probe.title.sqlserver'", "'cdc.probe.title.target'",
                "'cdc.check.ORACLE_SOURCE'", "'cdc.check.CDB_MINING_MODE'",
                "'cdc.check.ARCHIVELOG'", "'cdc.check.MINING_PRIVILEGES'",
                "'cdc.check.TARGET_DDL_PRIVILEGES'",
                "'cdc.check.PG_SOURCE'", "'cdc.check.PG_WAL_LEVEL'",
                "'cdc.check.MSSQL_SOURCE'", "'cdc.check.MSSQL_CDC_DB_ENABLED'");
        // properties 缺 key 时 Thymeleaf 内联会输出 ??cdc.check.XXX_zh_CN??
        assertThat(page).doesNotContain("??cdc.");
    }

    @Test
    void editingAGbaseConnectionShowsTheJarCard() throws Exception {
        DatabaseConfig gbase = new DatabaseConfig();
        gbase.setId(7L);
        gbase.setName("gbase-prod");
        gbase.setType(DatabaseType.GBASE);
        when(service.findById(7L)).thenReturn(Optional.of(gbase));

        String page = html(mvc.perform(get("/databases/7/edit").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk()));
        assertThat(JAR_CARD_HIDDEN.matcher(page).find())
                .as("GBase has no bundled driver, jar card must be visible on first paint")
                .isFalse();
        assertThat(page).contains("id=\"jar-card\"");
        // The path must actually submit for an external-driver type.
        assertThat(page).doesNotContainPattern(
                java.util.regex.Pattern.compile("id=\"customJarPath\"[^>]*disabled"));
    }

    private DatabaseConfig config(long id, String name, ConnectionRole role) {
        DatabaseConfig c = new DatabaseConfig();
        c.setId(id);
        c.setName(name);
        c.setType(DatabaseType.MYSQL);
        c.setRole(role);
        c.setHost("localhost");
        c.setPort(3306);
        return c;
    }

    @Test
    void listRendersSourceAndTargetSections() throws Exception {
        when(service.findPageByRole(eq(ConnectionRole.SOURCE), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(config(1L, "src-one", ConnectionRole.SOURCE)),
                        PageRequest.of(0, 10), 1));
        when(service.findPageByRole(eq(ConnectionRole.TARGET), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(config(2L, "tgt-one", ConnectionRole.TARGET)),
                        PageRequest.of(0, 10), 1));

        String page = html(mvc.perform(get("/databases").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk()));

        assertThat(page).contains("src-one", "tgt-one");
        assertThat(page).contains("/databases/new?role=SOURCE", "/databases/new?role=TARGET");
        assertThat(page).doesNotContain("??db.section", "??db.noSources", "??db.noTargets");
    }

    @Test
    void listPagerKeepsBothPageParameters() throws Exception {
        List<DatabaseConfig> eleven = new ArrayList<>();
        IntStream.rangeClosed(1, 11).forEach(i ->
                eleven.add(config(i, "src-" + i, ConnectionRole.SOURCE)));
        when(service.findPageByRole(eq(ConnectionRole.SOURCE), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(eleven.subList(0, 10), PageRequest.of(0, 10), 11));
        when(service.findPageByRole(eq(ConnectionRole.TARGET), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 10), 0));

        String page = html(mvc.perform(get("/databases").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk()));

        assertThat(page).contains("sourcePage=1");
        assertThat(page).contains("targetPage=0");
        // The target section has a single page: no pager there.
        assertThat(page).doesNotContain("targetPage=1");
    }

    @Test
    void listHidesActionsFromViewer() throws Exception {
        when(service.findPageByRole(eq(ConnectionRole.SOURCE), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(config(1L, "src-one", ConnectionRole.SOURCE)),
                        PageRequest.of(0, 10), 1));
        when(service.findPageByRole(eq(ConnectionRole.TARGET), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(config(2L, "tgt-one", ConnectionRole.TARGET)),
                        PageRequest.of(0, 10), 1));

        String page = html(mvc.perform(get("/databases").with(as(UserRole.VIEWER)))
                .andExpect(status().isOk()));

        assertThat(page).contains("src-one", "tgt-one");
        assertThat(page).doesNotContain("/delete", "/databases/new?role=", "data-role=\"api-btn\"");
    }

    @Test
    void editingADamengConnectionHidesTheJarCard() throws Exception {
        DatabaseConfig dm = new DatabaseConfig();
        dm.setId(8L);
        dm.setName("dm-prod");
        dm.setType(DatabaseType.DM);
        when(service.findById(8L)).thenReturn(Optional.of(dm));

        String page = html(mvc.perform(get("/databases/8/edit").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk()));
        assertThat(JAR_CARD_HIDDEN.matcher(page).find())
                .as("DM is bundled now, jar card must stay hidden")
                .isTrue();
    }
}
