package com.synctool.controller.api;

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

import com.synctool.service.DatabaseConfigService;
import com.synctool.service.connection.DriverLoader;

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
    @DisplayName("CUSTOM:custom=true 且 bundled=false")
    void customIsNeverBundled() throws Exception {
        mvc.perform(get("/api/databases/type-defaults").param("type", "CUSTOM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.custom").value(true))
                .andExpect(jsonPath("$.bundled").value(false));
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
