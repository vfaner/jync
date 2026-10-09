package com.qqmu.jync.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The duplicate-target guard on {@link SyncConfig#getTableNameMapping()}.
 *
 * <p>A mapping that resolves two sources onto one target is not a conversion edge case — it
 * makes both sources write the same table — so it is pinned at the config level, where both
 * the save form and the sync engine call the same check.
 */
class TableNameMappingValidationTest {

    private SyncConfig configWith(java.lang.String... pairs) {
        SyncConfig config = new SyncConfig();
        for (int i = 0; i < pairs.length; i += 2) {
            config.getTableNameMapping().put(pairs[i], pairs[i + 1]);
        }
        return config;
    }

    @Test
    void twoMappingsToTheSameTargetAreReported() {
        SyncConfig config = configWith("A", "X", "B", "X");

        List<String> conflicts = config.tableMappingConflicts(null);

        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0))
                .contains("\"A\"", "\"B\"")
                .contains("\"X\"");
    }

    @Test
    void duplicateTargetsAreCaughtCaseInsensitively() {
        // targetTableName ignores case, so x and X must not be treated as distinct targets.
        SyncConfig config = configWith("A", "x", "B", "X");

        assertThat(config.tableMappingConflicts(null)).hasSize(1);
    }

    @Test
    void distinctTargetsProduceNoConflicts() {
        SyncConfig config = configWith("A", "X", "B", "Y");

        assertThat(config.tableMappingConflicts(null)).isEmpty();
    }

    @Test
    void aSwapMappingIsValid() {
        // A -> B, B -> A is a genuine rename pair: effective targets differ.
        SyncConfig config = configWith("A", "B", "B", "A");

        assertThat(config.tableMappingConflicts(null)).isEmpty();
    }

    @Test
    void blankMappedValuesAreIgnored() {
        // A blank target means "keep the name" (see targetTableName), so A and A cannot
        // collide with themselves.
        SyncConfig config = configWith("A", "  ");

        assertThat(config.tableMappingConflicts(null)).isEmpty();
    }

    @Test
    void mappingOntoAnExistingUnmappedSourceIsAConflict() {
        // A -> B while source table B exists and keeps its name: both write B. This shape is
        // invisible to the mapping-only check and is why the engine validates against the
        // tables actually present.
        SyncConfig config = configWith("A", "B");

        assertThat(config.tableMappingConflicts(List.of("A", "B"))).hasSize(1);
    }

    @Test
    void mappingToANewNameThatNoSourceHoldsIsValid() {
        // The ordinary rename: old table keeps syncing under a brand-new target name.
        SyncConfig config = configWith("A", "NEW_NAME");

        assertThat(config.tableMappingConflicts(List.of("A", "OTHER"))).isEmpty();
    }
}
