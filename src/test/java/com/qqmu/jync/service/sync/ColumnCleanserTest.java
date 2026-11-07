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
    void aReplacementNamingAMissingGroupIsIgnoredInsteadOfBreakingTheSync() {
        // Matcher parses the replacement lazily, at the first match. The save path validates
        // it, but a rule stored before that validation existed (or written straight into the
        // metadata store) reaches the sync loop unchecked — and every row that matches then
        // throws "Illegal group reference" out of apply(), stopping the whole table.
        ColumnCleanser cleanser = ColumnCleanser.forTable(config(
                rule("users", "name", ColumnRuleType.REGEX_REPLACE, "(a)", "$2")),
                "users");

        assertThat(cleanser.apply(row("name", "alice")).get("name")).isEqualTo("alice");
    }

    @Test
    void aReplacementEndingInABackslashIsIgnoredInsteadOfBreakingTheSync() {
        ColumnCleanser cleanser = ColumnCleanser.forTable(config(
                rule("users", "name", ColumnRuleType.REGEX_REPLACE, "a", "x\\")),
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

    @Test
    void masksAPhoneNumberKeepingTheFirstThreeAndLastFourDigits() {
        ColumnCleanser cleanser = ColumnCleanser.forTable(config(
                rule("users", "phone", ColumnRuleType.MASK_PHONE, null, null)), "users");

        assertThat(cleanser.apply(row("phone", "13800138000")).get("phone"))
                .isEqualTo("138****8000");
        // Separators are part of the value, so the digits kept are still the outer ones.
        assertThat(cleanser.apply(row("phone", "138-0013-8000")).get("phone"))
                .isEqualTo("138******8000");
    }

    @Test
    void aPhoneNumberTooShortToKeepBothEndsIsMaskedEntirely() {
        ColumnCleanser cleanser = ColumnCleanser.forTable(config(
                rule("users", "phone", ColumnRuleType.MASK_PHONE, null, null)), "users");

        assertThat(cleanser.apply(row("phone", "1234567")).get("phone")).isEqualTo("*******");
        assertThat(cleanser.apply(row("phone", "12345")).get("phone")).isEqualTo("*****");
    }

    @Test
    void masksAnIdCardNumberKeepingTheFirstSixAndLastFour() {
        ColumnCleanser cleanser = ColumnCleanser.forTable(config(
                rule("users", "id_card", ColumnRuleType.MASK_ID_CARD, null, null)), "users");

        assertThat(cleanser.apply(row("id_card", "110101199003071234")).get("id_card"))
                .isEqualTo("110101********1234");
        assertThat(cleanser.apply(row("id_card", "1234567890")).get("id_card"))
                .isEqualTo("**********");
    }

    @Test
    void masksABankCardNumberKeepingOnlyTheLastFour() {
        ColumnCleanser cleanser = ColumnCleanser.forTable(config(
                rule("users", "card", ColumnRuleType.MASK_BANK_CARD, null, null)), "users");

        assertThat(cleanser.apply(row("card", "6222020200112233445")).get("card"))
                .isEqualTo("***************3445");
        assertThat(cleanser.apply(row("card", "12345678")).get("card")).isEqualTo("********");
    }

    @Test
    void masksANameKeepingOnlyTheFirstCharacter() {
        ColumnCleanser cleanser = ColumnCleanser.forTable(config(
                rule("users", "name", ColumnRuleType.MASK_NAME, null, null)), "users");

        assertThat(cleanser.apply(row("name", "张三丰")).get("name")).isEqualTo("张**");
        assertThat(cleanser.apply(row("name", "Alice")).get("name")).isEqualTo("A****");
        // A single-character name cannot keep a visible prefix without revealing it whole.
        assertThat(cleanser.apply(row("name", "李")).get("name")).isEqualTo("*");
    }

    @Test
    void masksAnEmailKeepingTheFirstLocalCharacterAndTheDomain() {
        ColumnCleanser cleanser = ColumnCleanser.forTable(config(
                rule("users", "email", ColumnRuleType.MASK_EMAIL, null, null)), "users");

        assertThat(cleanser.apply(row("email", "alice@example.com")).get("email"))
                .isEqualTo("a***@example.com");
    }

    @Test
    void anEmailWithoutAnAtSignFallsBackToTheNameMask() {
        ColumnCleanser cleanser = ColumnCleanser.forTable(config(
                rule("users", "email", ColumnRuleType.MASK_EMAIL, null, null)), "users");

        assertThat(cleanser.apply(row("email", "alice")).get("email")).isEqualTo("a****");
    }

    @Test
    void maskingLeavesAnEmptyValueEmpty() {
        ColumnCleanser cleanser = ColumnCleanser.forTable(config(
                rule("users", "name", ColumnRuleType.MASK_NAME, null, null),
                rule("users", "phone", ColumnRuleType.MASK_PHONE, null, null)), "users");

        Map<String, Object> row = cleanser.apply(row("name", "", "phone", ""));

        assertThat(row.get("name")).isEqualTo("");
        assertThat(row.get("phone")).isEqualTo("");
    }

    @Test
    void hashesAValueWithSha256SoEqualInputsStayJoinnable() {
        ColumnCleanser cleanser = ColumnCleanser.forTable(config(
                rule("users", "email", ColumnRuleType.HASH_SHA256, null, null)), "users");

        // Pinned to the standard digest of "alice", so a broken or truncated hash fails here.
        assertThat(cleanser.apply(row("email", "alice")).get("email"))
                .isEqualTo("2bd806c97f0e00af1a1fc3328fa763a9269723c8db8fac4f93af71db186d6e90");
        assertThat(cleanser.apply(row("email", "alice")).get("email"))
                .isEqualTo(cleanser.apply(row("email", "alice")).get("email"));
    }

    @Test
    void theHashSaltChangesTheDigestButStaysDeterministic() {
        ColumnCleanser salted = ColumnCleanser.forTable(config(
                rule("users", "email", ColumnRuleType.HASH_SHA256, "salt", null)), "users");

        assertThat(salted.apply(row("email", "alice")).get("email"))
                .isEqualTo("29b0186358ce0d738b48ddf514bd76d2fd3defb8a1b39de7b30d82d4cbf9ba85");
        assertThat(salted.apply(row("email", "alice")).get("email"))
                .isNotEqualTo(ColumnCleanser.forTable(config(
                        rule("users", "email", ColumnRuleType.HASH_SHA256, null, null)), "users")
                        .apply(row("email", "alice")).get("email"));
    }

    @Test
    void aBlankHashSaltHashesTheValueAlone() {
        ColumnCleanser blank = ColumnCleanser.forTable(config(
                rule("users", "email", ColumnRuleType.HASH_SHA256, "  ", null)), "users");

        assertThat(blank.apply(row("email", "alice")).get("email"))
                .isEqualTo("2bd806c97f0e00af1a1fc3328fa763a9269723c8db8fac4f93af71db186d6e90");
    }

    @Test
    void aMaskedNameStartingWithANonBmpCharacterKeepsItWhole() {
        // Names may start with an emoji; Java's char-based substring would split the surrogate
        // pair and write a broken character to the target.
        ColumnCleanser cleanser = ColumnCleanser.forTable(config(
                rule("users", "name", ColumnRuleType.MASK_NAME, null, null)), "users");

        assertThat(cleanser.apply(row("name", "😀小明")).get("name")).isEqualTo("😀**");
    }

    @Test
    void aMaskedEmailStartingWithANonBmpCharacterKeepsItWhole() {
        ColumnCleanser cleanser = ColumnCleanser.forTable(config(
                rule("users", "email", ColumnRuleType.MASK_EMAIL, null, null)), "users");

        assertThat(cleanser.apply(row("email", "😀alice@example.com")).get("email"))
                .isEqualTo("😀***@example.com");
    }

    @Test
    void aMaskedPhoneStartingOrEndingWithANonBmpCharacterKeepsThePairWhole() {
        ColumnCleanser cleanser = ColumnCleanser.forTable(config(
                rule("users", "phone", ColumnRuleType.MASK_PHONE, null, null)), "users");

        // Ten code points; the head and tail counts must be code points, not UTF-16 units.
        assertThat(cleanser.apply(row("phone", "12😀3456789")).get("phone"))
                .isEqualTo("12😀***6789");
        assertThat(cleanser.apply(row("phone", "123456😀789")).get("phone"))
                .isEqualTo("123***😀789");
    }

    @Test
    void aMaskedIdCardAndBankCardCountCodePointsToo() {
        ColumnCleanser idCard = ColumnCleanser.forTable(config(
                rule("users", "id_card", ColumnRuleType.MASK_ID_CARD, null, null)), "users");
        ColumnCleanser bankCard = ColumnCleanser.forTable(config(
                rule("users", "card", ColumnRuleType.MASK_BANK_CARD, null, null)), "users");

        assertThat(idCard.apply(row("id_card", "110101😀003071234")).get("id_card"))
                .isEqualTo("110101******1234");
        assertThat(bankCard.apply(row("card", "6222😀00200112233445")).get("card"))
                .isEqualTo("***************3445");
    }

    @Test
    void columnsListsEveryRuleColumnSoTheReaderCanKeepThemInMemory() {
        // A cleansed column must never reach apply() as a LobValue: the rules only look at
        // String values, so a streamed value would silently skip TRIM/UPPER. The read policy
        // uses this list to force those columns inline.
        ColumnCleanser cleanser = ColumnCleanser.forTable(config(
                rule("users", "name", ColumnRuleType.TRIM, null, null),
                rule("users", "NAME", ColumnRuleType.UPPER, null, null),
                rule("other", "note", ColumnRuleType.TRIM, null, null),
                rule("users", "broken", ColumnRuleType.STRIP_PREFIX, "", null)),
                "users");

        assertThat(cleanser.columns()).containsExactly("name", "NAME");
    }
}
