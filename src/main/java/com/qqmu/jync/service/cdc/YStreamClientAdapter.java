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

    /**
     * Tells the client library (and, through its check points, the source) that everything
     * up to {@code position} is durable in the target.
     *
     * <p>The library reports this value in every check point it sends; leaving it unset tells
     * the source the stream has applied nothing, which freezes the source's view of the
     * project's progress and holds redo the source could otherwise release.
     */
    void setAppliedPosition(YStreamPositionCodec.YStreamPosition position);

    /** Stops the stream. Never throws — teardown must not mask the real failure. */
    @Override
    void close();
}
