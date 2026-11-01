package com.qqmu.jync.service.metrics.runtime;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link ResultSet} over literal rows, so reader tests assert on the parsing rule rather
 * than on mock choreography. Cell text is parsed by whichever getter the reader calls.
 */
final class StubResultSet {

    private StubResultSet() {
    }

    static ResultSet of(String[]... rows) throws SQLException {
        return of(List.of(rows));
    }

    static ResultSet of(List<String[]> rows) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        AtomicInteger cursor = new AtomicInteger(-1);
        when(rs.next()).thenAnswer(inv -> cursor.incrementAndGet() < rows.size());
        when(rs.getString(anyInt())).thenAnswer(inv -> cell(rows, cursor, inv.getArgument(0)));
        when(rs.getInt(anyInt())).thenAnswer(inv -> {
            String value = cell(rows, cursor, inv.getArgument(0));
            return value == null || value.isBlank() ? 0 : Integer.parseInt(value);
        });
        when(rs.getLong(anyInt())).thenAnswer(inv -> {
            String value = cell(rows, cursor, inv.getArgument(0));
            return value == null || value.isBlank() ? 0L : Long.parseLong(value);
        });
        return rs;
    }

    private static String cell(List<String[]> rows, AtomicInteger cursor, int column) {
        String[] row = rows.get(cursor.get());
        return column <= row.length ? row[column - 1] : null;
    }
}
