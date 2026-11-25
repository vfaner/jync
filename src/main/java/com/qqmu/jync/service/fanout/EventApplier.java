package com.qqmu.jync.service.fanout;

import java.util.List;

/**
 * Applies one source transaction (or a DDL) to one target. Implementations own their target
 * connection/transaction; the worker only decides when a transaction is complete.
 */
public interface EventApplier extends AutoCloseable {

    /**
     * @param rows the transaction's row events in source order
     * @param commitPosition the source position of the COMMIT event
     */
    void applyTransaction(String transactionId, String commitPosition, List<ChangeEvent> rows)
            throws Exception;

    void applyDdl(ChangeEvent event) throws Exception;

    @Override
    default void close() throws Exception {
    }
}
