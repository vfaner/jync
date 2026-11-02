package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.qqmu.jync.dto.MigrationConnectionForm;
import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionAuthType;
import com.qqmu.jync.model.MigrationConnectionKind;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.migration.MigrationConnectionService;

@WebMvcTest(MigrationConnectionController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class MigrationConnectionViewTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private MigrationConnectionService service;

    @Test
    void theListShowsNamesButNeverStoredSecrets() throws Exception {
        MigrationConnection connection = connection();
        connection.setPasswordEnc("enc:top-secret-cipher");
        when(service.findPage(0, 10))
                .thenReturn(new PageImpl<>(List.of(connection)));

        String page = mvc.perform(get("/migrations/connections").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("es-prod").contains("ELASTICSEARCH")
                .doesNotContain("top-secret-cipher")
                .doesNotContain("enc:");
    }

    @Test
    void aViewerGetsNoWriteControls() throws Exception {
        when(service.findPage(0, 10)).thenReturn(new PageImpl<>(List.of(connection())));

        String page = mvc.perform(get("/migrations/connections").with(asViewer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).doesNotContain("href=\"/migrations/connections/new\"")
                .doesNotContain("/migrations/connections/3/delete")
                .doesNotContain("/migrations/connections/3/edit");
    }

    @Test
    void theEditFormNeverReceivesTheStoredSecret() throws Exception {
        MigrationConnectionForm form = new MigrationConnectionForm();
        form.setId(3L);
        form.setName("es-prod");
        form.setKind(MigrationConnectionKind.ELASTICSEARCH);
        form.setBaseUrl("http://127.0.0.1:9200");
        form.setAuthType(MigrationConnectionAuthType.BASIC);
        form.setUsername("elastic");
        form.setVerifyTls(true);
        form.setConnectTimeoutMs(5000);
        form.setRequestTimeoutMs(60000);
        when(service.formFor(3L)).thenReturn(form);

        String page = mvc.perform(get("/migrations/connections/3/edit").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("name=\"rawPassword\"").contains("name=\"rawApiKey\"")
                .contains("autocomplete=\"new-password\"")
                .doesNotContain("enc:");
    }

    @Test
    void theNewFormOffersTheDatabaseFieldsEachProductNeeds() throws Exception {
        String page = mvc.perform(get("/migrations/connections/new").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("name=\"databaseName\"")
                .contains("name=\"schemaName\"")
                .contains("name=\"extraParams\"")
                .contains("data-role=\"connection-kind\"")
                .contains("data-role=\"database-name-field\"")
                .contains("data-role=\"kingbase-only\"")
                .contains("data-role=\"extra-params-field\"")
                .contains("data-placeholder-mongo=\"mongodb://127.0.0.1:27017/shop\"")
                .contains("data-placeholder-kingbase=\"jdbc:kingbase8://127.0.0.1:54321/test\"")
                .contains("value=\"MONGODB\"")
                .contains("value=\"KINGBASE\"");
    }

    private static MigrationConnection connection() {
        MigrationConnection connection = new MigrationConnection();
        connection.setId(3L);
        connection.setName("es-prod");
        connection.setKind(MigrationConnectionKind.ELASTICSEARCH);
        connection.setBaseUrl("http://127.0.0.1:9200");
        connection.setAuthType(MigrationConnectionAuthType.BASIC);
        connection.setUsername("elastic");
        return connection;
    }

    private static RequestPostProcessor asAdmin() {
        return authentication(principal(UserRole.ADMIN));
    }

    private static RequestPostProcessor asViewer() {
        return authentication(principal(UserRole.VIEWER));
    }

    private static UsernamePasswordAuthenticationToken principal(UserRole role) {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername(role.name().toLowerCase());
        user.setPasswordHash("not-checked-here");
        user.setRole(role);
        SyncUserDetails details = new SyncUserDetails(user, false);
        return new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
    }
}
