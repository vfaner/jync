package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.qqmu.jync.dto.ColumnRule;
import com.qqmu.jync.dto.ColumnRuleType;
import com.qqmu.jync.dto.SyncConfig;

/**
 * Column cleansing is a closed, string-only rule set applied to the row map just before it
 * is written to the target. The rules are deliberately applied in the order the user saved
 * them: TRIM before BLANK_TO_NULL is the only way "  " becomes NULL, while the reverse order
 * leaves it as an empty string.
 */
class ColumnCleanserTest {

    private static ColumnRule rule(String table, String column, ColumnRuleType type,
                                   String arg, String replacement) {
        ColumnRule rule = new ColumnRule();
        rule.setTable(table);
        rule.setColumn(column);
        rule.setType(type);
        rule.setArg(arg);
        rule.setReplacement(replacement);
        return rule;
    }

    private static SyncConfig config(ColumnRule... rules) {
        SyncConfig config = new SyncConfig();
        config.setColumnRules(new ArrayList<>(List.of(rules)));
        return config;
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    @Test
    void trimsAndCaseFoldsInTheOrderTheRulesWereSaved() {
        ColumnCleanser cleanser = ColumnCleanser.forTable(config(
                rule("users", "name", ColumnRuleType.TRIM, null, null),
                rule("users", "name", ColumnRuleType.UPPER, null, null)),
                "users");

        Map<String, Object> row = cleanser.apply(row("id", 1, "name", "  Alice  "));

        assertThat(row.get("name")).isEqualTo("ALICE");
        assertThat(row.get("id")).isEqualTo(1);
    }

    @Test
    void theEmptyStringRuleOnlySeesTheValueTheEarlierRulesProduced() {
        // TRIM first: whitespace collapses to "" and then to NULL.
        Map<String, Object> trimmedThenNull = ColumnCleanser.forTable(config(
                rule("users", "name", ColumnRuleType.TRIM, null, null),
                rule("users", "name", ColumnRuleType.BLANK_TO_NULL, null, null)),
                "users").apply(row("name", "   "));
        assertThat(trimmedThenNull.get("name")).isNull();

        // Reversed: BLANK_TO_NULL already considers "   " blank, so it is still NULL.
        Map<String, Object> nullFirst = ColumnCleanser.forTable(config(
                rule("users", "name", ColumnRuleType.BLANK_TO_NULL, null, null),
                rule("users", "name", ColumnRuleType.TRIM, null, null)),
                "users").apply(row("name", "   "));
        assertThat(nullFirst.get("name")).isNull();
    }

    @Test
    void prefixAndSuffixAreStrippedOnce() {
        ColumnCleanser cleanser = ColumnCleanser.forTable(config(
                rule("users", "name", ColumnRuleType.STRIP_PREFIX, "tmp_", null),
                rule("users", "name", ColumnRuleType.STRIP_SUFFIX, "_old", null)),
                "users");

        assertThat(cleanser.apply(row("name", "tmp_tmp_alice_old")).get("name"))
                .isEqualTo("tmp_alice");
        // A value that does not start or end with the given text is left alone.
        assertThat(cleanser.apply(row("name", "alice")).get("name")).isEqualTo("alice");
    }

    @Test
    void regexReplaceRewritesTheValue() {
        ColumnCleanser cleanser = ColumnCleanser.forTable(config(
                rule("users", "phone", ColumnRuleType.REGEX_REPLACE, "\\D", "")),
                "users");

        assertThat(cleanser.apply(row("phone", "138-0013-8000")).get("phone"))
                .isEqualTo("13800138000");
    }

    @Test
    void nonStringValuesAreLeftUntouched() {
        ColumnCleanser cleanser = ColumnCleanser.forTable(config(
                rule("users", "amount", ColumnRuleType.UPPER, null, null),
                rule("users", "created", ColumnRuleType.TRIM, null, null)),
                "users");

        Map<String, Object> row = cleanser.apply(row("amount", 12, "created", new java.util.Date()));

        assertThat(row.get("amount")).isEqualTo(12);
        assertThat(row.get("created")).isInstanceOf(java.util.Date.class);
    }

    @Test
    void nullValuesAreLeftUntouched() {
        ColumnCleanser cleanser = ColumnCleanser.forTable(config(
                rule("users", "name", ColumnRuleType.UPPER, null, null)), "users");

        assertThat(cleanser.apply(row("name", null)).get("name")).isNull();
    }

    @Test
    void rulesForAnotherTableOrColumnDoNotApply() {
        ColumnCleanser cleanser = ColumnCleanser.forTable(config(
                rule("orders", "name", ColumnRuleType.UPPER, null, null),
                rule("users", "email", ColumnRuleType.UPPER, null, null)),
                "users");

        assertThat(cleanser.apply(row("name", "alice")).get("name")).isEqualTo("alice");
    }

    @Test
    void tableAndColumnMatchingIsCaseInsensitiveLikeTheDatabaseIdentifiers() {
        ColumnCleanser cleanser = ColumnCleanser.forTable(config(
                rule("USERS", "NAME", ColumnRuleType.UPPER, null, null)), "users");

        assertThat(cleanser.apply(row("Name", "alice")).get("Name")).isEqualTo("ALICE");
    }

    @Test
    void anInvalidRegexLeavesTheValueUntouchedInsteadOfBreakingTheSync() {
        ColumnCleanser cleanser = ColumnCleanser.forTable(config(
                rule("users", "name", ColumnRuleType.REGEX_REPLACE, "[unclosed", "")),
                "users");

        assertThat(cleanser.apply(row("name", "alice")).get("name")).isEqualTo("alice");
    }

    @Test
    void aRuleMissingItsArgumentIsIgnored() {
        ColumnCleanser cleanser = ColumnCleanser.forTable(config(
                rule("users", "name", ColumnRuleType.STRIP_PREFIX, " ", null),
                rule("users", "name", ColumnRuleType.STRIP_SUFFIX, null, null),
                rule("users", "name", ColumnRuleType.REGEX_REPLACE, null, null)),
                "users");

        assertThat(cleanser.apply(row("name", "alice")).get("name")).isEqualTo("alice");
    }
}
