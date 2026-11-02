package com.qqmu.jync.service.migration;

/** Thrown at a checkpoint after a run was cancelled or exceeded its deadline. */
public class MigrationCancelledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MigrationCancelledException() {
        super("migration cancelled");
    }
}
