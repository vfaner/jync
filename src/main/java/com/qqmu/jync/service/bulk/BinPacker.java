package com.qqmu.jync.service.bulk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Splits a table listing into N groups of roughly equal estimated size.
 *
 * <p>Largest-first greedy ("LPT"): sort by weight descending, then hand each table to the
 * lightest bin so far. With the skewed table sizes a real database has — a handful of tables
 * holding most of the data — this balances the bins far better than splitting by table count,
 * and the largest table automatically gets a bin (usually a task) to itself.
 *
 * <p>The result is deterministic: ties break by weight and then by name, so the preview page
 * and the create call, which read the source independently, bin the same listing identically.
 *
 * <p>An unknown size is weighed at the median of the known sizes rather than zero: on a
 * database without catalog statistics every table is unknown, and "all zero" would assign
 * everything to the first bin.
 */
final class BinPacker {

    /** One table to place; {@code rows} is null when the dictionary could not say. */
    record Item(String name, Long rows) {
    }

    /** One group: the tables placed in it, in descending size. */
    record Bin(int index, List<Item> items) {
    }

    private BinPacker() {
    }

    static List<Bin> pack(List<Item> items, int requestedBins) {
        if (items.isEmpty()) {
            return List.of();
        }
        int binCount = Math.max(1, Math.min(Math.max(requestedBins, 0), items.size()));
        long fallbackWeight = medianOfKnown(items);

        List<Item> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingLong((Item item) -> weightOf(item, fallbackWeight))
                .reversed()
                .thenComparing(Item::name));

        List<List<Item>> buckets = new ArrayList<>(binCount);
        long[] totals = new long[binCount];
        for (int i = 0; i < binCount; i++) {
            buckets.add(new ArrayList<>());
        }
        for (Item item : sorted) {
            int lightest = 0;
            for (int bin = 1; bin < binCount; bin++) {
                if (totals[bin] < totals[lightest]) {
                    lightest = bin;
                }
            }
            buckets.get(lightest).add(item);
            totals[lightest] += weightOf(item, fallbackWeight);
        }

        List<Bin> bins = new ArrayList<>(binCount);
        for (int i = 0; i < binCount; i++) {
            bins.add(new Bin(i, List.copyOf(buckets.get(i))));
        }
        return List.copyOf(bins);
    }

    private static boolean known(Item item) {
        // Zero is a real answer (an empty table, usually from exact counting); only a null
        // means the dictionary could not say.
        return item.rows() != null;
    }

    private static long weightOf(Item item, long fallbackWeight) {
        // A known zero weighs one unit rather than nothing: with every weight at zero the
        // greedy step would put the whole listing in the first bin. One unit changes no
        // ordering that matters and keeps empty tables spread by count.
        return known(item) ? Math.max(1L, item.rows()) : fallbackWeight;
    }

    /**
     * Median of the known sizes (average of the two middle values for an even count), never
     * below 1 — unknown tables must still spread across bins even when every known size is 0.
     */
    private static long medianOfKnown(List<Item> items) {
        List<Long> known = items.stream()
                .filter(BinPacker::known)
                .map(Item::rows)
                .sorted()
                .toList();
        if (known.isEmpty()) {
            return 1L;
        }
        int middle = known.size() / 2;
        long median = known.size() % 2 == 1
                ? known.get(middle)
                : (known.get(middle - 1) + known.get(middle)) / 2;
        return Math.max(1L, median);
    }
}
