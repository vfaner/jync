package com.qqmu.jync.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * A deferred run is not a failure: the pools were busy with another task's workers, nothing
 * was attempted, and the next fire tries again. The summary has to say that instead of
 * "No changes" — the task row would otherwise claim a successful look at the data.
 */
class SyncResultDeferredTest {

    @Test
    void aDeferredRunIsNotAFailureAndSaysSo() {
        SyncResult result = new SyncResult();

        result.setDeferred(true);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isDeferred()).isTrue();
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.summary()).startsWith("Deferred");
    }

    @Test
    void anOrdinaryRunIsNeverDeferred() {
        assertThat(new SyncResult().isDeferred()).isFalse();
    }
}
