package com.qqmu.jync.service.migration;

/** Throttled progress callback used to keep the running row informative. */
@FunctionalInterface
public interface MigrationProgressListener {

    void onProgress(String currentIndex, long documentsRead, long documentsWritten);

    MigrationProgressListener NONE = (index, read, written) -> {
    };
}
