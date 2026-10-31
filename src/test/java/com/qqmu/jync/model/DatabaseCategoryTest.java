package com.qqmu.jync.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The connection form groups its 30-plus presets so that the operator can find one without
 * reading a wall of names. The grouping is only worth anything if it is exhaustive and
 * disjoint: a preset added later without a category would silently disappear from the form,
 * and a preset listed twice would render twice.
 */
class DatabaseCategoryTest {

    @Test
    void everyPresetBelongsToExactlyOneCategory() {
        Map<DatabaseType, Integer> seen = new EnumMap<>(DatabaseType.class);
        for (DatabaseCategory category : DatabaseCategory.values()) {
            for (DatabaseType type : category.getTypes()) {
                seen.merge(type, 1, Integer::sum);
            }
        }

        for (DatabaseType type : DatabaseType.values()) {
            assertThat(seen.getOrDefault(type, 0))
                    .as("%s must appear in exactly one category", type)
                    .isEqualTo(1);
        }
        assertThat(seen).hasSize(DatabaseType.values().length);
    }

    @Test
    void theCategoriesAnchorTheProductsOperatorsLookFor() {
        assertThat(DatabaseCategory.of(DatabaseType.MYSQL)).isEqualTo(DatabaseCategory.BASIC);
        assertThat(DatabaseCategory.of(DatabaseType.ORACLE)).isEqualTo(DatabaseCategory.BASIC);
        assertThat(DatabaseCategory.of(DatabaseType.DM)).isEqualTo(DatabaseCategory.DOMESTIC);
        assertThat(DatabaseCategory.of(DatabaseType.YASHANDB))
                .isEqualTo(DatabaseCategory.DOMESTIC);
        assertThat(DatabaseCategory.of(DatabaseType.CLICKHOUSE))
                .isEqualTo(DatabaseCategory.ANALYTICS);
        assertThat(DatabaseCategory.of(DatabaseType.MONGODB_NATIVE))
                .isEqualTo(DatabaseCategory.NOSQL);
        assertThat(DatabaseCategory.of(DatabaseType.CUSTOM)).isEqualTo(DatabaseCategory.OTHER);
    }
}
