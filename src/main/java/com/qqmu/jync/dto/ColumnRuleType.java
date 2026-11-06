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
    REGEX_REPLACE,
    /** Keep the first three and last four characters, masking the rest with {@code *}. */
    MASK_PHONE,
    /** Keep the first six and last four characters, masking the rest with {@code *}. */
    MASK_ID_CARD,
    /** Keep the last four characters, masking the rest with {@code *}. */
    MASK_BANK_CARD,
    /** Keep the first character, masking the rest with {@code *}. */
    MASK_NAME,
    /** Keep the first local-part character and the whole domain, masking the local part. */
    MASK_EMAIL,
    /**
     * Replace the value with its SHA-256 hex digest. A non-blank {@code arg} is prepended
     * as a per-rule salt, which keeps the same salt deterministic across rows and batches.
     */
    HASH_SHA256
}
