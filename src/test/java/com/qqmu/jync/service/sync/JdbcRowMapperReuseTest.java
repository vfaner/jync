package com.qqmu.jync.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * A million-row load allocates a million row maps. The write loops can hand the mapper the
 * same map for every row instead — but only if reusing it can never leak a column from the
 * previous row (each row must look exactly like a freshly built one).
 */
class JdbcRowMapperReuseTest {

    @Test
    void readRowIntoLeavesOnlyTheCurrentRowInTheMap() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject(1)).thenReturn(1L, 3L);
        when(rs.getObject(2)).thenReturn("first", "second");

        Map<String, Object> row = new LinkedHashMap<>();
        JdbcRowMapper.readRowInto(rs, List.of("A", "B"), List.of(Types.BIGINT, Types.VARCHAR),
                null, null, row);
        assertThat(row).containsEntry("A", 1L).containsEntry("B", "first");

        JdbcRowMapper.readRowInto(rs, List.of("A", "B"), List.of(Types.BIGINT, Types.VARCHAR),
                null, null, row);
        assertThat(row).hasSize(2).containsEntry("A", 3L).containsEntry("B", "second");
    }

    @Test
    void readRowIntoKeepsTheColumnsInResultSetOrder() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject(1)).thenReturn("x");
        when(rs.getObject(2)).thenReturn("y");
        when(rs.getObject(3)).thenReturn("z");

        Map<String, Object> row = new LinkedHashMap<>();
        JdbcRowMapper.readRowInto(rs, List.of("C", "A", "B"),
                List.of(Types.VARCHAR, Types.VARCHAR, Types.VARCHAR), null, null, row);

        assertThat(new ArrayList<>(row.keySet())).containsExactly("C", "A", "B");
    }

    @Test
    void aPreviousRowWithMoreColumnsDoesNotSurviveReuse() throws Exception {
        ResultSet wide = mock(ResultSet.class);
        when(wide.getObject(1)).thenReturn("a");
        when(wide.getObject(2)).thenReturn("b");
        Map<String, Object> row = new LinkedHashMap<>();
        JdbcRowMapper.readRowInto(wide, List.of("A", "B"),
                List.of(Types.VARCHAR, Types.VARCHAR), null, null, row);

        ResultSet narrow = mock(ResultSet.class);
        when(narrow.getObject(1)).thenReturn("only");
        JdbcRowMapper.readRowInto(narrow, List.of("A"), List.of(Types.VARCHAR), null, null, row);

        assertThat(row).hasSize(1).containsEntry("A", "only");
    }
}
