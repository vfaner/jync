package com.qqmu.jync.service.lob;

/**
 * A column that must be materialized (primary key, cursor, cleansed) is larger than the
 * configured ceiling. Failing loudly beats a silently truncated cursor or a heap dump.
 */
public class LobLimitExceededException extends IllegalStateException {

    public LobLimitExceededException(String message) {
        super(message);
    }
}
