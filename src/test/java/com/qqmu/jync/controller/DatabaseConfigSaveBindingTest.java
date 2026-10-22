package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.qqmu.jync.model.ConnectionRole;
import com.qqmu.jync.model.DatabaseConfig;
import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.DatabaseConfigService;

/**
 * The save form binds into a DTO, not the entity, so request parameters can only reach the fields
 * the form actually shows.
 *
 * <p>Binding the entity let a crafted POST set {@code password} (bypassing encryption),
 * {@code minerPassword} or {@code createdAt} directly — values the form never sends and the
 * service is supposed to own.
 */
@WebMvcTest(DatabaseConfigController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
@WithMockUser(roles = "ADMIN")
class DatabaseConfigSaveBindingTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private DatabaseConfigService service;

    @Test
    void theFormCannotInjectStoredOnlyFields() throws Exception {
        mvc.perform(post("/databases/save").with(csrf())
                        .param("name", "prod")
                        .param("type", "MYSQL")
                        .param("role", "SOURCE")
                        .param("host", "db.local")
                        .param("port", "3306")
                        .param("password", "attacker-chosen-ciphertext")
                        .param("minerPassword", "attacker-miner-ciphertext")
                        .param("updatedAt", "2001-01-01T00:00:00Z"))
                .andExpect(status().is3xxRedirection());

        DatabaseConfig saved = capture();
        assertThat(saved.getPassword()).isNull();
        assertThat(saved.getMinerPassword()).isNull();
        assertThat(saved.getUpdatedAt()).isNull();
    }

    @Test
    void everyFieldTheFormShowsStillReachesTheService() throws Exception {
        mvc.perform(post("/databases/save").with(csrf())
                        .param("id", "7")
                        .param("name", "prod")
                        .param("type", "CUSTOM")
                        .param("dialectFamily", "ORACLE")
                        .param("role", "TARGET")
                        .param("host", "db.local")
                        .param("port", "1521")
                        .param("databaseName", "ORCL")
                        .param("schemaName", "APP")
                        .param("username", "app")
                        .param("minerUsername", "C##MINER")
                        .param("customUrl", "jdbc:custom://db.local:1521/ORCL")
                        .param("customDriver", "com.example.Driver")
                        .param("customJarPath", "/opt/driver.jar")
                        .param("extraParams", "a=1&b=2"))
                .andExpect(status().is3xxRedirection());

        DatabaseConfig saved = capture();
        assertThat(saved.getId()).isEqualTo(7L);
        assertThat(saved.getName()).isEqualTo("prod");
        assertThat(saved.getType()).isEqualTo(DatabaseType.CUSTOM);
        assertThat(saved.getDialectFamily()).isEqualTo(DatabaseType.DialectFamily.ORACLE);
        assertThat(saved.getRole()).isEqualTo(ConnectionRole.TARGET);
        assertThat(saved.getHost()).isEqualTo("db.local");
        assertThat(saved.getPort()).isEqualTo(1521);
        assertThat(saved.getDatabaseName()).isEqualTo("ORCL");
        assertThat(saved.getSchemaName()).isEqualTo("APP");
        assertThat(saved.getUsername()).isEqualTo("app");
        assertThat(saved.getMinerUsername()).isEqualTo("C##MINER");
        assertThat(saved.getCustomUrl()).isEqualTo("jdbc:custom://db.local:1521/ORCL");
        assertThat(saved.getCustomDriver()).isEqualTo("com.example.Driver");
        assertThat(saved.getCustomJarPath()).isEqualTo("/opt/driver.jar");
        assertThat(saved.getExtraParams()).isEqualTo("a=1&b=2");
    }

    private DatabaseConfig capture() {
        ArgumentCaptor<DatabaseConfig> captor = ArgumentCaptor.forClass(DatabaseConfig.class);
        verify(service).save(captor.capture(), isNull(), isNull());
        return captor.getValue();
    }
}
