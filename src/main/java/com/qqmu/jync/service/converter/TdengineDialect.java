package com.qqmu.jync.service.converter;

import com.qqmu.jync.model.DatabaseType;

/**
 * TDengine read-only dialect: backtick identifiers and {@code LIMIT / OFFSET} paging.
 *
 * <p>TDengine 3.3.6 rejects window functions, so the full-compare strategies that rely on
 * them are off for this source. Real-time sync treats it as a polling source only; the one
 * write path is ETL, whose target table the operator creates in advance.
 */
public class TdengineDialect extends PollingSourceDialect {

    public TdengineDialect() {
        super(DatabaseType.TDENGINE, '`', true);
    }

    @Override
    public boolean supportsWindowFunctions() {
        return false;
    }

    /**
     * The driver refuses text bound into a TIMESTAMP column — docker 实测 3.3.6:
     * {@code setObject(idx, "2026-10-13 12:34:56")} answers
     * {@code ERROR (0x2303): unsupported timestamp data type : [B} and fails the whole ETL
     * batch. The reader hands MySQL DATETIME/TIME to the binder as wall-clock text, so those
     * values are parsed into a real temporal before binding; a {@code LocalDateTime} writes
     * through {@code setObject} unchanged.
     */
    @Override
    public boolean requiresTypedTemporalBind() {
        return true;
    }

    /**
     * TDengine rejects {@code MAX}/{@code MIN} over a TIMESTAMP column
     * ({@code 0x2802 "Invalid parameter data type : max"}). {@code LAST} returns the value
     * of the row with the greatest timestamp — the same upper bound the watermark wants.
     *
     * <p>Only a TIMESTAMP cursor may use it. {@code LAST(id)} is the id of the newest row
     * by timestamp, not the largest id: with rows (t1,1), (t2,3), (t3,2) it answers 2 while
     * {@code MAX} answers 3, and the window would rewind. TDengine accepts {@code MAX} on
     * INT, BIGINT and VARCHAR, so every other cursor type keeps it.
     */
    @Override
    public String highWatermarkMaxExpression(String columnRef, int jdbcType) {
        switch (jdbcType) {
            case java.sql.Types.TIMESTAMP:
            case java.sql.Types.TIMESTAMP_WITH_TIMEZONE:
                return "LAST(" + columnRef + ")";
            default:
                return "MAX(" + columnRef + ")";
        }
    }
}
