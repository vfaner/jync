package com.qqmu.jync.service.cdc;

import com.qqmu.jync.config.SyncProperties;
import com.qqmu.jync.service.lob.LobSpillStore;

/**
 * Lazily creates the single LOB spill store a CDC consumer shares across its engines.
 *
 * <p>{@link #get} returns {@code null} when LOB streaming is switched off, so every caller
 * can treat "no spilling" as a null store instead of re-checking the flag. The double-checked
 * creation keeps one store per consumer even if two engines first ask concurrently.
 */
final class LazyLobSpillStore {

    private volatile LobSpillStore store;

    LobSpillStore get(SyncProperties properties) {
        if (!properties.getLob().isStreamingEnabled()) {
            return null;
        }
        LobSpillStore current = store;
        if (current == null) {
            synchronized (this) {
                current = store;
                if (current == null) {
                    current = LobSpillStore.from(properties);
                    store = current;
                }
            }
        }
        return current;
    }
}
