package com.qqmu.jync.service.sync;

/** Published after a skipped CDC DDL task is committed, so an optional auto-executor can
 * apply the safe subset without the consumer thread holding a metadata transaction. */
public record SkippedDdlEvent(Long projectId, Long logId, String sql) {
}
