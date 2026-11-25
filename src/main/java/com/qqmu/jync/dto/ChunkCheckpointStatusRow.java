package com.qqmu.jync.dto;

import java.time.Instant;

/**
 * Lean projection of {@code chunk_checkpoint} for the status panel. The entity's resume
 * bounds and last-key fields are LOBs; the five-second poll never reads them.
 */
public record ChunkCheckpointStatusRow(String tableName, Integer rangeIndex, Integer chunkIndex,
                                       Integer rowsCopied, String status, Instant updatedAt) {
}
