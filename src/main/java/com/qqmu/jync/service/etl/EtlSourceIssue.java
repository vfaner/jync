package com.qqmu.jync.service.etl;

/**
 * A source-side finding produced while probing. The precheck service appends these as-is;
 * {@code code} is an i18n key and {@code detail} is raw database or driver text.
 */
public record EtlSourceIssue(EtlPrecheckResult.Level level, String code, String detail) {
}
