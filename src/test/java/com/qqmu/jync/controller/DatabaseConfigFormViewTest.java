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

import com.qqmu.jync.EnglishRenderingSupport;
import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.auth.SyncUserDetails;

/**
 * Server-side first-paint state of the driver-jar card on the connection form.
 *
 * <p>The card is rendered for every type and its initial state must already be right in the
 * served HTML; waiting for the type-defaults AJAX call would flash it on every edit page.
 * The rule is classpath-driven through {@code DriverPresence}: CUSTOM and presets whose
 * driver is not bundled (GBase, Oscar, GaussDB, ...) get an open, required card; a bundled
 * preset (MySQL, DM, GoldenDB, ...) gets a locked card behind the vendor-driver switch,
 * unlocked again when the stored connection carries an override.
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
    void newFormHidesTheDriverCardForAStandardType() throws Exception {
        // 驱动设置只为商业信创预设(及 CUSTOM)准备。MySQL 这类标准产品用内置驱动,
        // 卡片留在 DOM 里(切换类型时要能复用)但隐藏,控件同时禁用。
        String page = html(mvc.perform(get("/databases/new").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk()));
        assertThat(JAR_CARD_HIDDEN.matcher(page).find())
                .as("MySQL is a standard product; no driver card")
                .isTrue();
        assertThat(extractInput(page, "customUrl")).contains("disabled");
        assertThat(extractInput(page, "customJarPath")).contains("disabled");
        assertThat(extractInput(page, "customDriver")).contains("disabled");
    }

    @Test
    void editingAGoldenDbPresetWithVendorOverridesOpensTheDriverCard() throws Exception {
        // 厂商要求专属驱动时(GoldenDB 的 com.goldendb.jdbc.Driver + jdbc:goldendb://),
        // 已保存的覆盖值必须原样回显且控件可提交,而不是被内置驱动预设锁死。
        DatabaseConfig golden = new DatabaseConfig();
        golden.setId(21L);
        golden.setName("goldendb-prod");
        golden.setType(DatabaseType.GOLDENDB);
        golden.setCustomUrl("jdbc:goldendb://host:8880/appdb");
        golden.setCustomDriver("com.goldendb.jdbc.Driver");
        golden.setCustomJarPath("/opt/drivers/goldendb-jdbc.jar");
        when(service.findById(21L)).thenReturn(Optional.of(golden));

        String page = html(mvc.perform(get("/databases/21/edit").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk()));

        assertThat(JAR_CARD_HIDDEN.matcher(page).find()).isFalse();
        assertThat(extractInput(page, "vendor-driver-toggle")).contains("checked");
        assertThat(driverCardBodyTag(page)).doesNotContain("hidden");
        assertThat(extractInput(page, "customUrl")).doesNotContain("disabled");
        assertThat(extractInput(page, "customJarPath")).doesNotContain("disabled");
        assertThat(extractInput(page, "customDriver")).doesNotContain("disabled");
        assertThat(page).contains("jdbc:goldendb://host:8880/appdb");
        assertThat(page).contains("com.goldendb.jdbc.Driver");
    }

    @Test
    void theJdbcUrlOverrideLivesInTheDriverCardNotTheCustomSection() throws Exception {
        // URL 覆盖对所有类型都有意义,所以从 CUSTOM 区块搬进驱动卡片;同一表单只能
        // 有一个 customUrl 控件,否则提交顺序决定哪个值生效。
        DatabaseConfig custom = new DatabaseConfig();
        custom.setId(22L);
        custom.setName("custom-src");
        custom.setType(DatabaseType.CUSTOM);
        custom.setCustomUrl("jdbc:other://host:5236/db");
        custom.setCustomDriver("com.example.jdbc.Driver");
        when(service.findById(22L)).thenReturn(Optional.of(custom));

        String page = html(mvc.perform(get("/databases/22/edit").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk()));

        int jarAt = page.indexOf("id=\"jar-card\"");
        int customAt = page.indexOf("id=\"custom-section\"");
        assertThat(jarAt).isPositive();
        assertThat(customAt).isGreaterThan(jarAt);
        assertThat(page.indexOf("id=\"customUrl\"")).isBetween(jarAt, customAt);
        assertThat(page.split("id=\"customUrl\"", -1)).hasSize(2);
    }

    @Test
    void theEnglishFormNamesEveryDatabaseTypeInEnglish() throws Exception {
        String page = html(mvc.perform(get("/databases/new").with(as(UserRole.ADMIN))
                        .header("Accept-Language", "en-US"))
                .andExpect(status().isOk()));

        // Type names used to be hardcoded bilingual literals ("达梦 DM", "自定义 Custom"), which
        // read as Chinese to an English user and which the ??-guard cannot see.
        assertThat(page).contains("Custom").contains("MySQL").contains("DAMENG");
        assertThat(EnglishRenderingSupport.findChinese(page))
                .as("the English form must not contain any Chinese text")
                .isNull();
    }

    @Test
    void theChineseFormKeepsTheDomesticProductNames() throws Exception {
        String page = html(mvc.perform(get("/databases/new").with(as(UserRole.ADMIN))
                        .header("Accept-Language", "zh-CN"))
                .andExpect(status().isOk()));

        assertThat(page).contains("达梦 DM").contains("自定义");
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

    /** Extracts the #driver-card-body opening tag. */
    private static String driverCardBodyTag(String page) {
        int at = page.indexOf("id=\"driver-card-body\"");
        assertThat(at).as("driver card body rendered").isPositive();
        int start = page.lastIndexOf("<div", at);
        int end = page.indexOf(">", at);
        return page.substring(start, end + 1);
    }

    /** Extracts the #dialectFamily &lt;select&gt; opening tag. */
    private static String extractSelect(String page, String selectId) {
        int at = page.indexOf("id=\"" + selectId + "\"");
        assertThat(at).as("select %s rendered", selectId).isPositive();
        int start = page.lastIndexOf("<select", at);
        int end = page.indexOf(">", at);
        return page.substring(start, end + 1);
    }

    @Test
    void customConnectionOffersTheDialectFamilyAndKeepsTheStoredOne() throws Exception {
        // A custom JDBC URL says nothing about the grammar the product speaks, so the user
        // declares the family; generic ANSI SQL is wrong for most real products.
        DatabaseConfig custom = new DatabaseConfig();
        custom.setId(13L);
        custom.setName("kingbase-prod");
        custom.setType(DatabaseType.CUSTOM);
        custom.setDialectFamily(DatabaseType.DialectFamily.POSTGRES);
        custom.setCustomUrl("jdbc:kingbase8://host:54321/db");
        custom.setCustomDriver("com.kingbase8.Driver");
        when(service.findById(13L)).thenReturn(Optional.of(custom));

        String page = html(mvc.perform(get("/databases/13/edit").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk()));

        assertThat(extractSelect(page, "dialectFamily")).doesNotContain("disabled");
        assertThat(page).contains("value=\"ORACLE\"", "value=\"POSTGRES\"",
                "value=\"SQLSERVER\"", "value=\"GENERIC\"");
        // th:field marks the stored enum value as selected
        assertThat(page).containsPattern(
                "<option[^>]*value=\"POSTGRES\"[^>]*selected|"
                + "value=\"POSTGRES\"[^>]*selected[^>]*>");
        assertThat(page).doesNotContain("??db.dialectFamily", "??db.family");
    }

    @Test
    void aPresetConnectionDisablesTheDialectFamilySelect() throws Exception {
        // The family only means something for CUSTOM; a disabled control also keeps a stale
        // value from riding along when the user switches a connection back to a preset.
        String page = html(mvc.perform(get("/databases/new").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk()));
        assertThat(extractSelect(page, "dialectFamily")).contains("disabled");
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

    @Test
    void editingAnExternalDriverPresetAllowsADriverClassOverride() throws Exception {
        // 厂商 jar 里的驱动类名常与预设不同（GaussDB 的 gsjdbc200.jar 就是
        // com.huawei.gauss200.jdbc.Driver），外部驱动类型的表单必须能改驱动类。
        DatabaseConfig gauss = new DatabaseConfig();
        gauss.setId(12L);
        gauss.setName("gauss-prod");
        gauss.setType(DatabaseType.GAUSSDB);
        when(service.findById(12L)).thenReturn(Optional.of(gauss));

        String page = html(mvc.perform(get("/databases/12/edit").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk()));
        assertThat(JAR_CARD_HIDDEN.matcher(page).find()).isFalse();
        assertThat(extractInput(page, "customDriver")).doesNotContain("disabled");

        // 驱动类属于 jar 卡片。若同时留在 CUSTOM 卡片里会出现两个同名控件，
        // 提交顺序决定谁生效——这正是必须搬出来的原因。
        int jarAt = page.indexOf("id=\"jar-card\"");
        int customAt = page.indexOf("id=\"custom-section\"");
        assertThat(jarAt).isPositive();
        assertThat(customAt).isGreaterThan(jarAt);
        assertThat(page.substring(jarAt, customAt)).contains("id=\"customDriver\"");
        assertThat(page.substring(customAt)).doesNotContain("id=\"customDriver\"");
        // 预设驱动类作为提示展示，用户留空就回退到它
        assertThat(page.substring(jarAt, customAt)).contains("com.huawei.gaussdb.jdbc.Driver");
    }

    @Test
    void bundledStandardTypesDoNotOfferADriverClassOverride() throws Exception {
        // 标准产品用内置驱动;卡片隐藏时控件必须一起禁用,残留值不随表单提交。
        String page = html(mvc.perform(get("/databases/new").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk()));
        assertThat(extractInput(page, "customDriver")).contains("disabled");
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
    void editingADamengConnectionHidesTheDriverCard() throws Exception {
        // 达梦的驱动随发行包内置:没有可填的厂商驱动,整张卡片不应出现(控件仍禁用,
        // 切换类型复用 DOM 时残留值不提交)。
        DatabaseConfig dm = new DatabaseConfig();
        dm.setId(8L);
        dm.setName("dm-prod");
        dm.setType(DatabaseType.DM);
        when(service.findById(8L)).thenReturn(Optional.of(dm));

        String page = html(mvc.perform(get("/databases/8/edit").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk()));
        assertThat(JAR_CARD_HIDDEN.matcher(page).find())
                .as("DM ships its driver; no vendor-driver card")
                .isTrue();
        assertThat(extractInput(page, "customUrl")).contains("disabled");
        assertThat(extractInput(page, "customJarPath")).contains("disabled");
        assertThat(extractInput(page, "customDriver")).contains("disabled");
    }

    @Test
    void editingAGoldenDbPresetWithoutOverridesKeepsTheCardCollapsed() throws Exception {
        // GoldenDB 的专属驱动没有随发行包内置,但默认链路用内置 Connector/J 也能工作,
        // 所以卡片出现、开关在头部且默认关闭,字段收起并禁用。
        DatabaseConfig golden = new DatabaseConfig();
        golden.setId(23L);
        golden.setName("goldendb-plain");
        golden.setType(DatabaseType.GOLDENDB);
        when(service.findById(23L)).thenReturn(Optional.of(golden));

        String page = html(mvc.perform(get("/databases/23/edit").with(as(UserRole.ADMIN)))
                .andExpect(status().isOk()));
        assertThat(JAR_CARD_HIDDEN.matcher(page).find()).isFalse();
        assertThat(extractInput(page, "vendor-driver-toggle")).doesNotContain("checked");
        int toggleAt = page.indexOf("id=\"vendor-driver-toggle\"");
        assertThat(toggleAt).isBetween(page.indexOf("id=\"jar-card\""),
                page.indexOf("id=\"driver-card-body\""));
        assertThat(driverCardBodyTag(page)).contains("hidden");
        assertThat(extractInput(page, "customUrl")).contains("disabled");
        assertThat(extractInput(page, "customJarPath")).contains("disabled");
        assertThat(extractInput(page, "customDriver")).contains("disabled");
    }
}
