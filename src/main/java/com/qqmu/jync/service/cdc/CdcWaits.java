package com.qqmu.jync.service.cdc;

import java.util.function.BooleanSupplier;

/** Sleep helpers shared by the CDC engines. */
final class CdcWaits {

    /** Slice length: short enough that a stop request is noticed promptly. */
    private static final long SLICE_MS = 100L;

    private CdcWaits() {
    }

    /**
     * Sleeps in 100 ms slices, re-checking the stream's running flag and the interrupt bit
     * between slices. On interruption the interrupt status is restored and {@code false} is
     * returned so the caller can exit its loop without logging a spurious failure.
     *
     * @param stillRunning the engine's running flag (a volatile boolean, read per slice)
     */
    static boolean sleepWhileRunning(BooleanSupplier stillRunning, long delayMs) {
        long remaining = delayMs;
        while (remaining > 0 && stillRunning.getAsBoolean()
                && !Thread.currentThread().isInterrupted()) {
            long slice = Math.min(SLICE_MS, remaining);
            try {
                Thread.sleep(slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            remaining -= slice;
        }
        return stillRunning.getAsBoolean() && !Thread.currentThread().isInterrupted();
    }
}
