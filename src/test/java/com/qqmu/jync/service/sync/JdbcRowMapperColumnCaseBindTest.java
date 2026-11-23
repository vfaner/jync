package com.qqmu.jync.service.sync;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Vastbase may spell a quoted column in the catalog's own case (for example {@code werks}
 * while the source reports {@code WERKS}). The bind order follows the target spelling and
 * the row map follows the source's, so the lookup must fall back to a case-insensitive scan
 * instead of binding NULL.
 */
class JdbcRowMapperColumnCaseBindTest {

    private final PreparedStatement ps = mock(PreparedStatement.class);

    @Test
    void targetSpellingFindsTheSourceKeyIgnoringCase() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("WERKS", "W429");
        Map<String, Integer> types = Map.of("werks", Types.VARCHAR);

        JdbcRowMapper.bind(ps, row, List.of("werks"), types);

        verify(ps).setObject(1, "W429");
    }

    @Test
    void aPresentNullStillBindsNullRatherThanFallingThrough() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("WERKS", null);
        Map<String, Integer> types = Map.of("werks", Types.VARCHAR);

        JdbcRowMapper.bind(ps, row, List.of("werks"), types);

        verify(ps).setNull(1, Types.VARCHAR);
    }
}
