package com.qqmu.jync.service.converter;

import com.qqmu.jync.model.DatabaseType;

/**
 * TDengine read-only dialect: backtick identifiers and {@code LIMIT / OFFSET} paging.
 *
 * <p>TDengine 3.3.6 rejects window functions, so the full-compare strategies that rely on
 * them are off for this source. It never takes part in a write path — the ETL engine writes
 * through the customer's own SQL, and real-time sync treats it as a polling source only.
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
