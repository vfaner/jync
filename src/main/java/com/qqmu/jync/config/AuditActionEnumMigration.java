package com.qqmu.jync.config;

import java.util.List;
import java.util.Locale;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Turns {@code audit_event.action} from a native enum into text on metadata stores created by
 * an earlier release.
 *
 * <p>Hibernate 7 maps {@code @Enumerated(STRING)} to the database's native enum type where the
 * dialect has one, so the column is created as {@code enum('LOGIN', …)} — frozen at the
 * constants of that release. {@code ddl-auto=update} adds missing tables and columns but never
 * widens an existing enum, so after an upgrade the first insert of a newer constant fails with
 * H2 error 22030 ("value not permitted for column"), and saving the alert or external-auth
 * settings answers 500 even though its own table accepted the change.
 *
 * <p>Widening the column to {@code VARCHAR(32)} once removes the trap for good: every future
 * constant is accepted without another migration. It is lossless — existing labels are kept,
 * only the domain check goes — idempotent, and it runs after Hibernate has created the schema,
 * so it also covers a fresh install's enum. Databases without a native enum on this column are
 * left alone; another database's column is never touched because the table and column are
 * pinned to the audit trail.
 *
 * <p>A failure is logged, not rethrown: booting with a narrow column is still better than not
 * booting, and the operator sees which statement to run by hand.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class AuditActionEnumMigration implements ApplicationRunner {

    private static final String TABLE = "audit_event";
    private static final String COLUMN = "action";
    private static final String TEXT = "VARCHAR(32)";

    private static final String H2_TYPE =
            "select DATA_TYPE from INFORMATION_SCHEMA.COLUMNS "
            + "where TABLE_NAME = 'AUDIT_EVENT' and COLUMN_NAME = 'ACTION'";

    private static final String MYSQL_TYPE =
            "select DATA_TYPE, IS_NULLABLE from information_schema.COLUMNS "
            + "where TABLE_SCHEMA = DATABASE() and TABLE_NAME = 'audit_event' "
            + "and COLUMN_NAME = 'action'";

    private static final String POSTGRES_TYPE =
            "select data_type from information_schema.columns "
            + "where table_schema = current_schema() and table_name = 'audit_event' "
            + "and column_name = 'action'";

    private final JdbcTemplate jdbcTemplate;

    public AuditActionEnumMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            String statement = relaxStatement();
            if (statement == null) {
                return;
            }
            jdbcTemplate.execute(statement);
            log.info("Converted metadata column {}.{} to {}", TABLE, COLUMN, TEXT);
        } catch (RuntimeException e) {
            log.warn("Could not convert metadata column {}.{} to {}; saving the alert or "
                    + "external-auth settings may fail until it is converted by hand: {}",
                    TABLE, COLUMN, TEXT, e.getMessage());
        }
    }

    /** @return the one repair statement, or null when the column is already text */
    String relaxStatement() {
        String product = jdbcTemplate.execute(
                (ConnectionCallback<String>) connection ->
                        connection.getMetaData().getDatabaseProductName());
        if (isH2(product)) {
            return h2Statement(first(jdbcTemplate.query(H2_TYPE,
                    (rs, rowNum) -> rs.getString(1))));
        }
        if (isMySqlFamily(product)) {
            List<ColumnType> columns = jdbcTemplate.query(MYSQL_TYPE,
                    (rs, rowNum) -> new ColumnType(rs.getString(1),
                            "YES".equalsIgnoreCase(rs.getString(2))));
            return columns.isEmpty() ? null
                    : mysqlStatement(columns.get(0).dataType(), columns.get(0).nullable());
        }
        if (isPostgres(product)) {
            return postgresStatement(first(jdbcTemplate.query(POSTGRES_TYPE,
                    (rs, rowNum) -> rs.getString(1))));
        }
        return null;
    }

    private static <T> T first(List<T> rows) {
        return rows.isEmpty() ? null : rows.get(0);
    }

    static String h2Statement(String dataType) {
        return "ENUM".equalsIgnoreCase(dataType)
                ? "ALTER TABLE " + TABLE + " ALTER COLUMN " + COLUMN + " SET DATA TYPE " + TEXT
                : null;
    }

    static String mysqlStatement(String dataType, boolean nullable) {
        return "enum".equalsIgnoreCase(dataType)
                ? "ALTER TABLE " + TABLE + " MODIFY COLUMN " + COLUMN + " " + TEXT
                        + (nullable ? " NULL" : " NOT NULL")
                : null;
    }

    static String postgresStatement(String dataType) {
        return "USER-DEFINED".equalsIgnoreCase(dataType)
                ? "ALTER TABLE " + TABLE + " ALTER COLUMN " + COLUMN + " TYPE " + TEXT
                        + " USING " + COLUMN + "::text"
                : null;
    }

    static boolean isH2(String productName) {
        return productName != null && productName.toLowerCase(Locale.ROOT).contains("h2");
    }

    static boolean isMySqlFamily(String productName) {
        if (productName == null) {
            return false;
        }
        String normalized = productName.toLowerCase(Locale.ROOT);
        return normalized.contains("mysql") || normalized.contains("mariadb");
    }

    static boolean isPostgres(String productName) {
        return productName != null && productName.toLowerCase(Locale.ROOT).contains("postgresql");
    }

    /** One row of {@code information_schema.COLUMNS} narrowed to what the decision needs. */
    private record ColumnType(String dataType, boolean nullable) {
    }
}
