package com.qqmu.jync.service.sync;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * LogMiner/DM redo text carries every numeric column as a literal string
 * ({@link com.qqmu.jync.service.cdc.OracleLiteralConverters} keeps the server's text
 * verbatim), so a CDC row reaches the binder with e.g. {@code "42"} for a NUMBER column.
 * Binding that as a String makes pgjdbc send VARCHAR, and PostgreSQL refuses the assignment
 * ("column ... is of type integer but expression is of type character varying"), so the
 * text is parsed into a {@link BigDecimal} for numeric target columns. Text that is not a
 * plain decimal still binds verbatim, and a text target column keeps the source's own text.
 */
class JdbcRowMapperNumericTextBindTest {

    private final PreparedStatement ps = mock(PreparedStatement.class);

    private static void bind(PreparedStatement ps, Object value, int targetJdbcType)
            throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("c", value);
        Map<String, Integer> types = new LinkedHashMap<>();
        types.put("c", targetJdbcType);
        JdbcRowMapper.bind(ps, row, List.of("c"), types);
    }

    @Test
    void numericTextBindsAsBigDecimalToBigintTarget() throws Exception {
        bind(ps, "42", Types.BIGINT);
        verify(ps).setObject(1, new BigDecimal("42"));
    }

    @Test
    void numericTextKeepsItsScale() throws Exception {
        // Scale is part of the value's identity for a NUMERIC column: 12.50 must not
        // become 12.5 on the way to the target.
        bind(ps, "12.50", Types.NUMERIC);
        verify(ps).setObject(1, new BigDecimal("12.50"));
    }

    @Test
    void scientificTextBindsAsBigDecimal() throws Exception {
        bind(ps, "1.2E3", Types.DOUBLE);
        verify(ps).setObject(1, new BigDecimal("1.2E3"));
    }

    @Test
    void nonNumericTextStillBindsVerbatim() throws Exception {
        // BINARY_DOUBLE renders its specials as words; only a plain decimal is parsed.
        bind(ps, "Infinity", Types.DOUBLE);
        verify(ps).setObject(1, "Infinity");
    }

    @Test
    void numericTextIntoTextTargetStaysText() throws Exception {
        // A VARCHAR2 source column lands in a text target column; its "42" is text.
        bind(ps, "0042", Types.VARCHAR);
        verify(ps).setObject(1, "0042");
    }
}
