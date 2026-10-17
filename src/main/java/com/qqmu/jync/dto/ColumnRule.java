package com.qqmu.jync.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * One cleansing rule: {@code column} of {@code table} is transformed with {@code type}
 * before the value is written to the target.
 *
 * <p>Rules are stored in the order the user saved them and applied in that order, so
 * TRIM followed by BLANK_TO_NULL turns a whitespace-only value into NULL.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ColumnRule {

    /** Source table name; matched case-insensitively. */
    private String table;

    /** Source column name; matched case-insensitively. */
    private String column;

    private ColumnRuleType type;

    /** Prefix/suffix text or the regular expression, depending on {@link #type}. */
    private String arg;

    /** Replacement used by {@link ColumnRuleType#REGEX_REPLACE}; null means empty. */
    private String replacement;
}
