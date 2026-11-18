package com.qqmu.jync.service.etl;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.qqmu.jync.model.DatabaseType;
import com.qqmu.jync.service.sync.JdbcRowMapper;
import com.qqmu.jync.service.sync.JdbcValueNormalizer;

/**
 * A {@link ResultSet} seen as an {@link EtlSourceCursor}. The metadata is read once, before
 * the first row, because the runner creates the target table and resolves the resume key from
 * it before deleting anything.
 */
final class JdbcEtlSourceCursor implements EtlSourceCursor {

    private final PreparedStatement statement;
    private final ResultSet resultSet;
    private final List<EtlSourceColumn> columns;
    private final boolean[] vendorHandled;
    private final int resumeKeyIndex;
    private final DatabaseType sourceType;

    JdbcEtlSourceCursor(PreparedStatement statement, ResultSet resultSet,
                        com.qqmu.jync.model.EtlTask task, boolean resumable,
                        DatabaseType sourceType) throws SQLException {
        this.statement = statement;
        this.resultSet = resultSet;
        this.sourceType = sourceType;
        ResultSetMetaData meta = resultSet.getMetaData();
        this.columns = columns(meta);
        // Resolved once per task, like the sync reader's RowReadPlan: the vendor-type
        // check is a function of the column, and running it per cell would allocate an
        // upper-cased type name on every value.
        this.vendorHandled = new boolean[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            vendorHandled[i] = JdbcValueNormalizer.handles(columns.get(i).typeName());
        }
        this.resumeKeyIndex = resumeKeyIndex(resultSet, task, resumable);
    }

    private static List<EtlSourceColumn> columns(ResultSetMetaData meta) throws SQLException {
        List<EtlSourceColumn> columns = new ArrayList<>();
        // Unquoted identifiers fold, so two labels that differ only in case name the same
        // target column and the INSERT cannot carry both. Refusing here is before the runner
        // deletes anything; the statement would otherwise fail after the write-mode DELETE.
        Set<String> labels = new HashSet<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String label = meta.getColumnLabel(i);
            if (label == null || !EtlSqlSupport.PLAIN_IDENTIFIER.matcher(label).matches()) {
                throw new EtlProgramException("result column '" + label
                        + "' is not a plain identifier; alias it in the extraction SQL");
            }
            if (!labels.add(label.toLowerCase(Locale.ROOT))) {
                throw new EtlProgramException("error.etl.column.duplicate:" + label);
            }
            int precision = meta.getPrecision(i);
            int scale = meta.getScale(i);
            boolean nullable;
            try {
                nullable = meta.isNullable(i) != ResultSetMetaData.columnNoNulls;
            } catch (SQLException e) {
                nullable = true;
            }
            columns.add(new EtlSourceColumn(label, meta.getColumnType(i),
                    meta.getColumnTypeName(i), precision > 0 ? precision : null,
                    scale >= 0 ? scale : null, nullable));
        }
        return columns;
    }

    private static int resumeKeyIndex(ResultSet resultSet, com.qqmu.jync.model.EtlTask task,
                                      boolean resumable) throws SQLException {
        if (!resumable) {
            return -1;
        }
        try {
            return resultSet.findColumn(task.getResumeKeyColumn().trim());
        } catch (SQLException e) {
            throw new EtlProgramException("resume key '" + task.getResumeKeyColumn()
                    + "' is not a column of the extraction query");
        }
    }

    @Override
    public List<EtlSourceColumn> columns() {
        return columns;
    }

    @Override
    public int resumeKeyIndex() {
        return resumeKeyIndex;
    }

    @Override
    public boolean next() throws SQLException {
        return resultSet.next();
    }

    @Override
    public Object getObject(int oneBasedIndex) throws SQLException {
        EtlSourceColumn column = columns.get(oneBasedIndex - 1);
        if (vendorHandled[oneBasedIndex - 1]) {
            // The same first step as the sync reader: a vendor type whose getObject is a
            // driver-private object must become the portable value before it crosses to
            // another connection. SQL Server's datetimeoffset/uniqueidentifier, Oracle's
            // XMLTYPE and the geometry types are the ones that reach here.
            Object normalized = JdbcValueNormalizer.read(resultSet, oneBasedIndex,
                    column.jdbcType(), column.typeName(), sourceType);
            if (normalized != JdbcValueNormalizer.UNHANDLED) {
                return normalized;
            }
        }
        if (column.jdbcType() == Types.TIMESTAMP
                && JdbcRowMapper.bindsZoneLessThroughUtcCalendar(sourceType)) {
            // Oracle DATE/TIMESTAMP and PostgreSQL timestamp are zone-less wall clocks; the
            // plain getObject builds a Timestamp in the JVM zone and moves pre-1949 DST-gap
            // times (Asia/Shanghai) forward an hour. The typed getter keeps the wall clock.
            return JdbcRowMapper.readTemporal(resultSet, oneBasedIndex, column.jdbcType(),
                    column.typeName(), sourceType);
        }
        // A LOB column's getObject is a driver-private handle (Oracle answers a
        // {@code oracle.sql.CLOB}) that belongs to this ResultSet's connection; the copy
        // loop hands the value to another connection's setObject. Materialize it here, the
        // way the sync reader does, so every target driver receives a String/byte[].
        switch (column.jdbcType()) {
            case Types.BLOB:
            case Types.LONGVARBINARY:
            case Types.VARBINARY:
            case Types.BINARY:
                return resultSet.getBytes(oneBasedIndex);
            case Types.CLOB:
            case Types.NCLOB:
            case Types.LONGVARCHAR:
            case Types.LONGNVARCHAR:
                return resultSet.getString(oneBasedIndex);
            case Types.TIMESTAMP_WITH_TIMEZONE:
                // A zone-aware timestamp reaches the target as an instant. Oracle's
                // getObject answers with a driver-private oracle.sql.TIMESTAMPTZ bound
                // to this source connection, which no other target driver can bind; the
                // shared reader's getTimestamp is the cross-driver form.
                return JdbcRowMapper.readValue(resultSet, oneBasedIndex, column.jdbcType(),
                        column.typeName(), sourceType);
            case Types.DATE:
            case Types.OTHER:
            case Types.ARRAY:
                // These carry the vendor quirks the shared reader already encodes: MySQL's
                // YEAR reports DATE but holds an integer (the target column is SMALLINT),
                // and PostgreSQL json/jsonb/uuid/xml/enums/arrays arrive as driver-private
                // objects no other target driver can bind. Reading them here instead of
                // through getObject is what keeps the copy path equal to the sync path.
                return JdbcRowMapper.readValue(resultSet, oneBasedIndex, column.jdbcType(),
                        column.typeName(), sourceType);
            case Types.TIME:
            case Types.TIME_WITH_TIMEZONE:
                return timeValue(oneBasedIndex);
            default:
                return resultSet.getObject(oneBasedIndex);
        }
    }

    /**
     * A TIME value, or the server's literal when the driver refuses to build one. MySQL's
     * TIME domain runs to ±838 hours, which no {@code java.time}/JDBC type can carry, and
     * Connector/J rejects such a value while reading the object; one extreme row would
     * otherwise fail the whole task before it is bound. Ordinary values keep the driver's
     * object so every target can bind a real temporal value, exactly as before.
     */
    private Object timeValue(int oneBasedIndex) throws SQLException {
        try {
            return resultSet.getObject(oneBasedIndex);
        } catch (RuntimeException driverFailure) {
            String literal = resultSet.getString(oneBasedIndex);
            if (literal == null) {
                // The fallback produced nothing either: surface the driver's own failure
                // instead of silently turning the cell into NULL.
                throw driverFailure;
            }
            return literal;
        }
    }

    @Override
    public String getString(int oneBasedIndex) throws SQLException {
        return resultSet.getString(oneBasedIndex);
    }

    @Override
    public void close() throws SQLException {
        try {
            resultSet.close();
        } finally {
            statement.close();
        }
    }
}
