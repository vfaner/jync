package com.qqmu.jync.service.cdc;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.service.lob.LobSpillStore;
import com.qqmu.jync.service.sync.SyncContext;

/**
 * Holds the one {@link CdcLobSpiller} a transaction may use.
 *
 * <p>Every engine keeps whole source transactions in memory until their commit boundary,
 * so each one needs a spiller that lives exactly as long as the buffered rows: created on
 * the first oversized value, closed when the buffer is dropped. The holder centralizes
 * that lifecycle; without it the same lazy-init and close sequence existed once per
 * engine and could drift apart.
 *
 * <p>No synchronization: a consumer drives its transaction from a single thread.
 */
final class CdcSpillerHolder {

    private CdcLobSpiller spiller;

    /**
     * This transaction's spiller, created on first use. {@code store} is the consumer's
     * spill store, or {@code null} when streaming is switched off — then the disabled
     * spiller is kept, so callers never branch on the setting.
     */
    CdcLobSpiller get(SyncContext ctx, SyncProperties properties, LobSpillStore store) {
        if (spiller == null) {
            spiller = store == null ? CdcLobSpiller.disabled()
                    : new CdcLobSpiller(store, properties.getLob().getCdcSpillThresholdBytes(),
                            ctx.getConfig());
        }
        return spiller;
    }

    /** Disposes the spilled values when the buffer (transaction or drain window) ends. */
    void reset() {
        if (spiller != null) {
            spiller.close();
            spiller = null;
        }
    }
}
