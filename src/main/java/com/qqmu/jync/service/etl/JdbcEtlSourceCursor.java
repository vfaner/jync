package com.qqmu.jync.service.etl;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link ResultSet} seen as an {@link EtlSourceCursor}. The metadata is read once, before
 * the first row, because the runner creates the target table and resolves the resume key from
 * it before deleting anything.
 */
final class JdbcEtlSourceCursor implements EtlSourceCursor {

    private final PreparedStatement statement;
    private final ResultSet resultSet;
    private final List<EtlSourceColumn> columns;
    private final int resumeKeyIndex;

    JdbcEtlSourceCursor(PreparedStatement statement, ResultSet resultSet,
                        com.qqmu.jync.model.EtlTask task, boolean resumable) throws SQLException {
        this.statement = statement;
        this.resultSet = resultSet;
        ResultSetMetaData meta = resultSet.getMetaData();
        this.columns = columns(meta);
        this.resumeKeyIndex = resumeKeyIndex(resultSet, task, resumable);
    }

    private static List<EtlSourceColumn> columns(ResultSetMetaData meta) throws SQLException {
        List<EtlSourceColumn> columns = new ArrayList<>();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String label = meta.getColumnLabel(i);
            if (label == null || !EtlSqlSupport.PLAIN_IDENTIFIER.matcher(label).matches()) {
                throw new EtlProgramException("result column '" + label
                        + "' is not a plain identifier; alias it in the extraction SQL");
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
        return resultSet.getObject(oneBasedIndex);
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
