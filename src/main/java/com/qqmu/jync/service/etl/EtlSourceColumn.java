package com.qqmu.jync.service.etl;

/**
 * One column of an extraction result, independent of the source technology.
 *
 * <p>For JDBC sources the fields mirror {@link java.sql.ResultSetMetaData}; for MongoDB they
 * come from schema inference over a sample. The target table is created from these values, so
 * a column always knows the JDBC type its values must be written as.
 */
public record EtlSourceColumn(
        String name,
        int jdbcType,
        String typeName,
        Integer size,
        Integer decimalDigits,
        boolean nullable) {
}
