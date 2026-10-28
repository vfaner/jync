package com.qqmu.jync.service.cdc;

/**
 * The server sent bytes the binlog decoder refused.
 *
 * <p>Kept distinct from every other way a stream probe can fail — wrong password, missing
 * REPLICATION grant, unreachable host, silent stream — because only this one means "the
 * product's event format is not one this engine can read". The capability probe says so
 * in the check detail, and telling a user their format is incompatible when the real
 * problem is a typo in the password sends them down the wrong path entirely.
 */
public class CdcDecodeException extends Exception {

    private static final long serialVersionUID = 1L;

    public CdcDecodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
