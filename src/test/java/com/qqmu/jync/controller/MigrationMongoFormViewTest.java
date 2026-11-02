package com.qqmu.jync.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qqmu.jync.model.AppUser;
import com.qqmu.jync.model.MigrationConnection;
import com.qqmu.jync.model.MigrationConnectionKind;
import com.qqmu.jync.model.MigrationType;
import com.qqmu.jync.model.UserRole;
import com.qqmu.jync.repository.MigrationRunRepository;
import com.qqmu.jync.service.auth.SyncUserDetails;
import com.qqmu.jync.service.migration.MigrationConnectionService;
import com.qqmu.jync.service.migration.MigrationTaskService;
import com.qqmu.jync.service.migration.MigrationTypeHandler;
import com.qqmu.jync.service.migration.MigrationTypeRegistry;

/** The two MongoDB forms differ only in their options fragment and allowed connection kinds. */
@WebMvcTest(MigrationController.class)
@Import(GlobalModelAdvice.class)
@TestPropertySource(properties = "app.github-url=https://example.com/repo")
class MigrationMongoFormViewTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @MockBean
    private MigrationTaskService taskService;

    @MockBean
    private MigrationConnectionService connectionService;

    @MockBean
    private MigrationTypeRegistry registry;

    @MockBean
    private MigrationRunRepository runRepository;

    @MockBean
    private MigrationTypeHandler handler;

    @BeforeEach
    void setUp() {
        lenient().when(connectionService.findAll()).thenReturn(List.of(
                connection(1L, "mongo-source", MigrationConnectionKind.MONGODB),
                connection(2L, "mongo-target", MigrationConnectionKind.MONGODB),
                connection(3L, "kingbase-target", MigrationConnectionKind.KINGBASE),
                connection(4L, "es-source", MigrationConnectionKind.ELASTICSEARCH)));
    }

    @Test
    void theMongoToMongoFormRendersItsOwnOptionsAndOnlyMongoConnections() throws Exception {
        stub(MigrationType.MONGODB_TO_MONGODB,
                Set.of(MigrationConnectionKind.MONGODB),
                Set.of(MigrationConnectionKind.MONGODB),
                "~{migration-form/options-mongo :: options}", mongoToMongoDefaults());

        String page = mvc.perform(get("/migrations/mongodb-to-mongodb/new").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(view().name("migration-form"))
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("data-role=\"options-mongo\"")
                .contains("name=\"opt.collectionsText\"")
                .contains("name=\"opt.writeMode\"")
                .contains("name=\"opt.batchSize\"")
                .doesNotContain("data-role=\"options-mongo-kingbase\"")
                .doesNotContain("data-role=\"options-es\"");

        assertThat(select(page, "sourceConnectionId")).contains("mongo-source");
        assertThat(select(page, "targetConnectionId")).contains("mongo-target");
        assertThat(select(page, "sourceConnectionId"))
                .doesNotContain("kingbase-target").doesNotContain("es-source");
        assertThat(select(page, "targetConnectionId"))
                .doesNotContain("kingbase-target").doesNotContain("es-source");
    }

    @Test
    void theMongoToKingbaseFormRendersItsOwnOptionsAndFiltersEachSide() throws Exception {
        stub(MigrationType.MONGODB_TO_KINGBASE,
                Set.of(MigrationConnectionKind.MONGODB),
                Set.of(MigrationConnectionKind.KINGBASE),
                "~{migration-form/options-mongo-kingbase :: options}", kingbaseDefaults());

        String page = mvc.perform(get("/migrations/mongodb-to-kingbase/new").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(view().name("migration-form"))
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("data-role=\"options-mongo-kingbase\"")
                .contains("name=\"opt.collectionsText\"")
                .contains("name=\"opt.nestedMode\"")
                .contains("name=\"opt.createPrimaryKey\"")
                .contains("name=\"opt.upsertOnId\"")
                .doesNotContain("data-role=\"options-mongo\"");

        assertThat(select(page, "sourceConnectionId")).contains("mongo-source")
                .doesNotContain("kingbase-target");
        assertThat(select(page, "targetConnectionId")).contains("kingbase-target")
                .doesNotContain("mongo-target");
    }

    private void stub(MigrationType type, Set<MigrationConnectionKind> sourceKinds,
                      Set<MigrationConnectionKind> targetKinds, String fragment,
                      ObjectNode defaults) {
        when(registry.isRegistered(type)).thenReturn(true);
        when(registry.find(type)).thenReturn(Optional.of(handler));
        when(handler.type()).thenReturn(type);
        when(handler.sourceKinds()).thenReturn(sourceKinds);
        when(handler.targetKinds()).thenReturn(targetKinds);
        when(handler.optionsFragment()).thenReturn(fragment);
        when(handler.defaultOptions()).thenReturn(defaults);
        when(handler.parseOptions(any())).thenReturn(defaults);
    }

    private static String select(String page, String name) {
        int start = page.indexOf("id=\"" + name + "\"");
        assertThat(start).as("select %s", name).isGreaterThanOrEqualTo(0);
        int end = page.indexOf("</select>", start);
        return page.substring(start, end);
    }

    private static ObjectNode mongoToMongoDefaults() {
        ObjectNode options = JSON.createObjectNode();
        options.put("collectionsText", "");
        options.put("targetNaming", "SAME");
        options.put("targetPrefix", "");
        options.put("targetSuffix", "_migrated");
        options.put("writeMode", "OVERWRITE");
        options.put("copyCollectionOptions", true);
        options.put("copyIndexes", true);
        options.put("copyDocuments", true);
        options.put("continueOnError", true);
        options.put("batchSize", 1000);
        options.put("maxBatchBytes", 8388608);
        options.put("noCursorTimeout", true);
        options.put("verifyCount", true);
        options.put("verifySampleSize", 100);
        options.put("maxCollections", 200);
        return options;
    }

    private static ObjectNode kingbaseDefaults() {
        ObjectNode options = JSON.createObjectNode();
        options.put("collectionsText", "");
        options.put("targetNaming", "SAME");
        options.put("targetPrefix", "");
        options.put("targetSuffix", "");
        options.put("writeMode", "OVERWRITE");
        options.put("nestedMode", "JSON");
        options.put("createPrimaryKey", true);
        options.put("upsertOnId", false);
        options.put("continueOnError", true);
        options.put("batchSize", 1000);
        options.put("sampleLimit", 200);
        options.put("verifyCount", true);
        options.put("verifySampleSize", 100);
        options.put("maxCollections", 200);
        return options;
    }

    private static MigrationConnection connection(long id, String name,
                                                  MigrationConnectionKind kind) {
        MigrationConnection connection = new MigrationConnection();
        connection.setId(id);
        connection.setName(name);
        connection.setKind(kind);
        return connection;
    }

    private static RequestPostProcessor asAdmin() {
        return authentication(principal(UserRole.ADMIN));
    }

    private static UsernamePasswordAuthenticationToken principal(UserRole role) {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername(role.name().toLowerCase());
        user.setPasswordHash("not-checked-here");
        user.setRole(role);
        SyncUserDetails details = new SyncUserDetails(user, false);
        return new UsernamePasswordAuthenticationToken(
                details, null, details.getAuthorities());
    }
}
