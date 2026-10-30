package com.qqmu.jync.controller.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.connection.DatabaseHealthService;
import com.qqmu.jync.service.connection.DriverLoader;

/**
 * The {@code bundled} flag the connection form uses to decide whether the jar-path card is
 * needed. It mirrors the application classpath via {@code DriverPresence}, not a hardcoded
 * list, so these assertions pin the packaging decision: the ten preset drivers ship inside
 * the distribution, GBase / Oscar do not.
 */
@WebMvcTest(DatabaseApiController.class)
@WithMockUser(roles = "ADMIN")
class DatabaseTypeDefaultsApiTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private DatabaseConfigService service;

    @MockBean
    private DriverLoader driverLoader;

    /** The slice builds every constructor argument of the controller, health included. */
    @MockBean
    private DatabaseHealthService healthService;

    @Test
    @DisplayName("达梦驱动已内置:bundled=true 且不要求 custom")
    void damengIsBundled() throws Exception {
        mvc.perform(get("/api/databases/type-defaults").param("type", "DM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.custom").value(false))
                .andExpect(jsonPath("$.bundled").value(true))
                .andExpect(jsonPath("$.driverClass").value("dm.jdbc.driver.DmDriver"));
    }

    @Test
    @DisplayName("GBase 未内置:bundled=false,表单应显示 jar 路径卡片")
    void gbaseIsNotBundled() throws Exception {
        mvc.perform(get("/api/databases/type-defaults").param("type", "GBASE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.custom").value(false))
                .andExpect(jsonPath("$.bundled").value(false));
    }

    @Test
    @DisplayName("Oscar 未内置:bundled=false")
    void oscarIsNotBundled() throws Exception {
        mvc.perform(get("/api/databases/type-defaults").param("type", "OSCAR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.custom").value(false))
                .andExpect(jsonPath("$.bundled").value(false));
    }

    @Test
    @DisplayName("GoldenDB 复用内置 MySQL 驱动,但厂商可要求专属驱动:vendorDriver=true")
    void goldenDbOffersTheVendorDriverOverride() throws Exception {
        mvc.perform(get("/api/databases/type-defaults").param("type", "GOLDENDB"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bundled").value(true))
                .andExpect(jsonPath("$.vendorDriver").value(true));
    }

    @Test
    @DisplayName("MySQL 是标准产品:不提供厂商驱动覆盖")
    void mysqlDoesNotOfferTheVendorDriverOverride() throws Exception {
        mvc.perform(get("/api/databases/type-defaults").param("type", "MYSQL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vendorDriver").value(false));
    }

    @Test
    @DisplayName("达梦驱动已内置:不提供厂商驱动覆盖")
    void damengDoesNotOfferTheVendorDriverOverride() throws Exception {
        mvc.perform(get("/api/databases/type-defaults").param("type", "DM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vendorDriver").value(false));
    }

    @Test
    @DisplayName("TiDB 的官方接入就是 Connector/J:不提供厂商驱动覆盖")
    void tidbDoesNotOfferTheVendorDriverOverride() throws Exception {
        mvc.perform(get("/api/databases/type-defaults").param("type", "TIDB"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vendorDriver").value(false));
    }

    @Test
    @DisplayName("CUSTOM:custom=true 且 bundled=false")
    void customIsNeverBundled() throws Exception {
        mvc.perform(get("/api/databases/type-defaults").param("type", "CUSTOM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.custom").value(true))
                .andExpect(jsonPath("$.bundled").value(false));
    }

    @Test
    @DisplayName("SQL Server 命名实例:portOptional=true,表单不自动填 1433")
    void sqlServerPortIsOptional() throws Exception {
        mvc.perform(get("/api/databases/type-defaults").param("type", "SQLSERVER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.portOptional").value(true));
    }

    @Test
    @DisplayName("其他类型端口必填:portOptional=false")
    void otherTypesRequireAPort() throws Exception {
        mvc.perform(get("/api/databases/type-defaults").param("type", "MYSQL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.portOptional").value(false));
        mvc.perform(get("/api/databases/type-defaults").param("type", "ORACLE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.portOptional").value(false));
    }

    @Test
    @DisplayName("未知类型回退 CUSTOM,而不是报错")
    void unknownTypeFallsBackToCustom() throws Exception {
        mvc.perform(get("/api/databases/type-defaults").param("type", "WHATEVER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("CUSTOM"))
                .andExpect(jsonPath("$.bundled").value(false));
    }
}
