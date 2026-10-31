package com.qqmu.jync.service.etl;

/**
 * A defect in the task configuration or in Jync itself, not a database failure.
 *
 * <p>Top-level (it began as a private nested class of the runner) so the source adapters and
 * the runner can share the same signal without changing the side it is reported under.
 */
final class EtlProgramException extends RuntimeException {

    EtlProgramException(String message) {
        super(message);
    }
}
