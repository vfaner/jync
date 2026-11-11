package com.qqmu.jync.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.EntityType;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Widens metadata columns that Hibernate created too small to hold their values on MySQL.
 *
 * <p>A {@code @Lob} string with {@code @Column} but no explicit length resolves through the
 * dialect's capacity-dependent DDL type. On MySQL that type's lowest capacity is
 * {@code TINYTEXT} (255 characters), so a fresh metadata database creates
 * {@code project.sync_config}, {@code etl_task.source_sql}, {@code metadata_snapshot.snapshot_json}
 * and the rest as {@code TINYTEXT}. The first real value then fails with error 1406,
 * "Data too long for column" — for a project that is the first configuration package import.
 *
 * <p>New schemas are correct because the mappings now declare {@code length = Integer.MAX_VALUE}.
 * This runner repairs schemas created by earlier releases: it inspects the application's own
 * tables and rewrites every {@code TINYTEXT} column as {@code LONGTEXT}. Only {@code TINYTEXT}
 * is touched — a capacity no mapping asks for, so it can only be the defective default; columns
 * declared with an explicit length are created as {@code TEXT} and keep it. It is idempotent —
 * after one run there is nothing left to widen — and only acts on MySQL and MariaDB;
 * PostgreSQL, Oracle and H2 map the same properties to unbounded text types and are left alone.
 *
 * <p>A failure is logged, not rethrown: booting with a narrow column is still better than not
 * booting, and the operator can see which statement failed and run it by hand.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class MetadataTextColumnWidener implements ApplicationRunner {

    private static final String COLUMNS_QUERY =
            "SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, IS_NULLABLE "
            + "FROM information_schema.COLUMNS "
            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN (%s) "
            + "ORDER BY TABLE_NAME, ORDINAL_POSITION";

    private final JdbcTemplate jdbcTemplate;
    private final EntityManagerFactory entityManagerFactory;

    public MetadataTextColumnWidener(JdbcTemplate jdbcTemplate,
                                     EntityManagerFactory entityManagerFactory) {
        this.jdbcTemplate = jdbcTemplate;
        this.entityManagerFactory = entityManagerFactory;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int widened = widen();
            if (widened > 0) {
                log.info("Widened {} metadata text column(s) to LONGTEXT", widened);
            }
        } catch (RuntimeException e) {
            log.warn("Could not widen narrow metadata text columns; saving a large value may fail "
                    + "with \"Data too long\" until the column is widened by hand: {}",
                    e.getMessage());
        }
    }

    /** @return how many columns were rewritten */
    int widen() {
        String product = jdbcTemplate.execute(
                (ConnectionCallback<String>) connection ->
                        connection.getMetaData().getDatabaseProductName());
        if (!isMySqlFamily(product)) {
            return 0;
        }
        List<String> tables = tables();
        if (tables.isEmpty()) {
            return 0;
        }
        String sql = COLUMNS_QUERY.formatted(
                String.join(", ", Collections.nCopies(tables.size(), "?")));
        List<Column> columns = jdbcTemplate.query(sql,
                (rs, rowNum) -> new Column(rs.getString(1), rs.getString(2), rs.getString(3),
                        "YES".equalsIgnoreCase(rs.getString(4))),
                tables.toArray());

        int widened = 0;
        for (String statement : widenStatements(product, columns)) {
            try {
                jdbcTemplate.execute(statement);
                widened++;
            } catch (RuntimeException e) {
                log.warn("Could not widen a metadata text column ({}): {}",
                        statement, e.getMessage());
            }
        }
        return widened;
    }

    private List<String> tables() {
        List<String> tables = new ArrayList<>();
        for (EntityType<?> entity : entityManagerFactory.getMetamodel().getEntities()) {
            Table table = entity.getJavaType().getAnnotation(Table.class);
            if (table != null && !table.name().isBlank()) {
                tables.add(table.name());
            }
        }
        return tables;
    }

    static boolean isMySqlFamily(String productName) {
        if (productName == null) {
            return false;
        }
        String normalized = productName.toLowerCase(Locale.ROOT);
        return normalized.contains("mysql") || normalized.contains("mariadb");
    }

    static List<String> widenStatements(String productName, List<Column> columns) {
        if (!isMySqlFamily(productName)) {
            return List.of();
        }
        List<String> statements = new ArrayList<>();
        for (Column column : columns) {
            if ("tinytext".equalsIgnoreCase(column.dataType())) {
                statements.add("ALTER TABLE `" + column.table() + "` MODIFY COLUMN `"
                        + column.name() + "` LONGTEXT " + (column.nullable() ? "NULL" : "NOT NULL"));
            }
        }
        return statements;
    }

    /** One row of {@code information_schema.COLUMNS} narrowed to what the decision needs. */
    record Column(String table, String name, String dataType, boolean nullable) {
    }
}
