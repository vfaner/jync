package com.qqmu.jync.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The empty-set contract of the object selection.
 *
 * <p>An empty set is ambiguous on its own: a project that never saved a selection has one,
 * and a user who unchecked every box produces one. The legacy reading is "all" — that is the
 * default for new projects and for every config written before the explicit flag existed.
 * Once the form has persisted an explicit selection, an empty set must mean "none of this
 * kind": unchecking everything is a real choice, not a request to sync the world.
 */
class SyncConfigObjectSelectionTest {

    @Test
    void legacyConfigWithNoSavedSelectionIncludesEverything() {
        SyncConfig config = new SyncConfig();

        assertThat(config.isObjectSelectionExplicit()).isFalse();
        assertThat(config.includesTable("ANY_TABLE")).isTrue();
        assertThat(config.includesView("ANY_VIEW")).isTrue();
        assertThat(config.includesProcedure("ANY_ROUTINE")).isTrue();
    }

    @Test
    void explicitEmptySelectionIncludesNothing() {
        SyncConfig config = new SyncConfig();
        config.setObjectSelectionExplicit(true);

        assertThat(config.includesTable("ANY_TABLE")).isFalse();
        assertThat(config.includesView("ANY_VIEW")).isFalse();
        assertThat(config.includesProcedure("ANY_ROUTINE")).isFalse();
    }

    @Test
    void explicitSelectionIncludesOnlyTheListedNames() {
        SyncConfig config = new SyncConfig();
        config.setObjectSelectionExplicit(true);
        config.setTables(new LinkedHashSet<>(List.of("orders")));
        config.setViews(new LinkedHashSet<>(List.of("v_orders")));
        config.setProcedures(new LinkedHashSet<>(List.of("p_nightly")));

        assertThat(config.includesTable("ORDERS")).isTrue();       // case-insensitive
        assertThat(config.includesTable("customers")).isFalse();
        assertThat(config.includesView("V_ORDERS")).isTrue();
        assertThat(config.includesView("v_other")).isFalse();
        assertThat(config.includesProcedure("P_NIGHTLY")).isTrue();
        assertThat(config.includesProcedure("p_other")).isFalse();
    }

    @Test
    void legacyJsonWithoutTheFlagStillMeansAll() throws Exception {
        SyncConfig config = new ObjectMapper()
                .readValue("{\"tables\":[],\"views\":[],\"procedures\":[]}", SyncConfig.class);

        assertThat(config.isObjectSelectionExplicit()).isFalse();
        assertThat(config.includesTable("ORDERS")).isTrue();
    }

    @Test
    void anExplicitlyEmptySelectionHasNoSyncObjects() {
        SyncConfig config = new SyncConfig();
        config.setObjectSelectionExplicit(true);

        assertThat(config.hasNoSyncObjects()).isTrue();
    }

    @Test
    void aLegacyEmptySelectionAlwaysHasSomethingToSync() {
        // "Empty means all" is still a selection of everything: the start buttons must not
        // be disabled for a project that has simply never saved a picker choice.
        assertThat(new SyncConfig().hasNoSyncObjects()).isFalse();
    }

    @Test
    void anySelectedObjectKindCountsAsSomethingToSync() {
        SyncConfig viewsOnly = new SyncConfig();
        viewsOnly.setObjectSelectionExplicit(true);
        viewsOnly.setViews(new LinkedHashSet<>(List.of("v_orders")));
        assertThat(viewsOnly.hasNoSyncObjects()).isFalse();

        SyncConfig routinesOnly = new SyncConfig();
        routinesOnly.setObjectSelectionExplicit(true);
        routinesOnly.setProcedures(new LinkedHashSet<>(List.of("p_nightly")));
        assertThat(routinesOnly.hasNoSyncObjects()).isFalse();

        SyncConfig tablesOnly = new SyncConfig();
        tablesOnly.setObjectSelectionExplicit(true);
        tablesOnly.setTables(new LinkedHashSet<>(List.of("orders")));
        assertThat(tablesOnly.hasNoSyncObjects()).isFalse();
    }

    @Test
    void theExplicitFlagSurvivesJsonRoundTrip() throws Exception {
        SyncConfig saved = new SyncConfig();
        saved.setObjectSelectionExplicit(true);

        String json = new ObjectMapper().writeValueAsString(saved);
        SyncConfig read = new ObjectMapper().readValue(json, SyncConfig.class);

        assertThat(read.isObjectSelectionExplicit()).isTrue();
        assertThat(read.includesTable("ORDERS")).isFalse();
    }
}
