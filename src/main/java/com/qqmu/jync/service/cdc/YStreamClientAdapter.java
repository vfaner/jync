package com.qqmu.jync.service.cdc;

/**
 * The boundary between Jync and the YStream client library.
 *
 * <p>{@link YStreamCdcConsumer} only ever sees this interface, so its transaction
 * assembly, savepoint handling and checkpoint discipline are unit-testable without a
 * YashanDB source — the real implementation ({@link YstreamLcrAdapter}) is the only place
 * the library's types appear.
 */
public interface YStreamClientAdapter extends AutoCloseable {

    /**
     * The next LCR of the stream.
     *
     * @return the next event, or {@code null} when the client's poll interval elapsed with
     *         nothing to deliver (an idle source, not an error)
     */
    YStreamEvent next() throws Exception;

    /** Stops the stream. Never throws — teardown must not mask the real failure. */
    @Override
    void close();
}
