package com.qqmu.jync.dto;

/**
 * The closed set of column-cleansing operations. Deliberately not an expression
 * language: a sync tool should not become a script runner, and a fixed set keeps the
 * rules portable across every source and target dialect.
 */
public enum ColumnRuleType {

    /** Strip leading and trailing whitespace. */
    TRIM,
    /** Upper-case the value. */
    UPPER,
    /** Lower-case the value. */
    LOWER,
    /** Replace blank strings (empty or whitespace-only) with NULL. */
    BLANK_TO_NULL,
    /** Strip one leading occurrence of {@code arg}. */
    STRIP_PREFIX,
    /** Strip one trailing occurrence of {@code arg}. */
    STRIP_SUFFIX,
    /** Replace every match of the regular expression {@code arg} with {@code replacement}. */
    REGEX_REPLACE
}
