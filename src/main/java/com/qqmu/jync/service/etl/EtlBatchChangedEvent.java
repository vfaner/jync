package com.qqmu.jync.service.etl;

/** Published after a batch is created, edited or deleted; the scheduler re-registers on it. */
public record EtlBatchChangedEvent(Long batchId, boolean deleted) {
}
