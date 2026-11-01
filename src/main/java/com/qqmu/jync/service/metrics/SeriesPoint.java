package com.qqmu.jync.service.metrics;

/**
 * One chart point. The label is the bucket's start as an ISO-8601 instant — the browser
 * formats it for the reader's locale, so the server never guesses a time zone.
 *
 * <p>A null value is a gap: nothing was sampled in that bucket. It is not zero.
 */
public record SeriesPoint(String label, Double value) {
}
