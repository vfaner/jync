package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.DatabaseConfigService;
import com.qqmu.jync.service.auth.SyncUserDetails;

/**
 * The type picker is a long list, so the server renders it grouped by category and hands the
 * browser the copy its search box needs. The native select stays in the markup as the
 * form control (and the no-JS fallback); the grouping has to survive there because the
 * searchable picker is built from it.
 */
@WebMvcTest(DatabaseConfigController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class DatabaseTypePickerViewTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DatabaseConfigService service;

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

    private String page() throws Exception {
        return mvc.perform(get("/databases/new").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static String selectBlock(String html, String selectId) {
        Matcher m = Pattern.compile("(?s)<select[^>]*id=\"" + selectId + "\".*?</select>")
                .matcher(html);
        assertThat(m.find()).as("select#%s must be rendered", selectId).isTrue();
        return m.group();
    }

    @Test
    void theTypeSelectIsGroupedIntoCategories() throws Exception {
        String select = selectBlock(page(), "type");

        assertThat(select).contains("<optgroup");
        assertThat(select).contains("label=\"Basic databases\"",
                "label=\"Domestic databases\"",
                "label=\"Data warehouses and OLAP\"",
                "label=\"NoSQL\"",
                "label=\"Other\"");
        // Categories are shown in the order an operator scans: mainstream first.
        assertThat(select.indexOf("Basic databases"))
                .isLessThan(select.indexOf("Domestic databases"));
        assertThat(select.indexOf("Domestic databases"))
                .isLessThan(select.indexOf("Data warehouses and OLAP"));
        assertThat(select.indexOf("Data warehouses and OLAP"))
                .isLessThan(select.indexOf("NoSQL"));
    }

    @Test
    void everyDatabaseTypeIsStillAnOption() throws Exception {
        String select = selectBlock(page(), "type");

        for (DatabaseType type : DatabaseType.values()) {
            assertThat(select).contains("value=\"" + type.name() + "\"");
        }
    }

    @Test
    void theSelectCarriesTheSearchCopyThePickerNeeds() throws Exception {
        String select = selectBlock(page(), "type");

        assertThat(select).contains("data-search-placeholder=\"Search database type\"",
                "data-search-empty=\"No matching database type\"");
    }
}
