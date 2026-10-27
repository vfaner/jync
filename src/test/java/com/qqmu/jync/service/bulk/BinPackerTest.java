package com.qqmu.jync.service.bulk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Binning for the bulk task planner: split a table listing into N groups of roughly equal
 * size, so N sync tasks finish in roughly the same time instead of one carrying every big
 * table.
 *
 * <p>Two properties matter more than optimality: the result must be <em>deterministic</em> (the
 * preview and the create call bin independently and must agree), and a table whose size is
 * unknown must not be treated as empty — on a database without catalog statistics every table
 * is unknown, and "all zero" would put everything in the first bin.
 */
class BinPackerTest {

    private static BinPacker.Item item(String name, Long rows) {
        return new BinPacker.Item(name, rows);
    }

    private static List<String> names(BinPacker.Bin bin) {
        return bin.items().stream().map(BinPacker.Item::name).toList();
    }

    @Test
    void oneBinKeepsEveryTableSortedBySize() {
        List<BinPacker.Bin> bins = BinPacker.pack(
                List.of(item("small", 10L), item("huge", 1_000L), item("mid", 100L)), 1);

        assertThat(bins).hasSize(1);
        assertThat(names(bins.get(0))).containsExactly("huge", "mid", "small");
        assertThat(bins.get(0).estimatedRows()).isEqualTo(1_110L);
        assertThat(bins.get(0).unknownCount()).isZero();
    }

    @Test
    void moreBinsThanTablesClampsToTheTableCount() {
        List<BinPacker.Bin> bins = BinPacker.pack(
                List.of(item("a", 1L), item("b", 2L), item("c", 3L)), 10);

        assertThat(bins).hasSize(3);
        assertThat(bins).allSatisfy(bin -> assertThat(bin.items()).hasSize(1));
    }

    @Test
    void theLargestTablesGoFirstSoTheBinsEndUpBalanced() {
        // Classic LPT example: 100 + 1 + 1 + 1 + 1 into two bins must be 100 vs 4, not 101 vs 3.
        List<BinPacker.Bin> bins = BinPacker.pack(
                List.of(item("big", 100L), item("a", 1L), item("b", 1L),
                        item("c", 1L), item("d", 1L)), 2);

        assertThat(bins).hasSize(2);
        assertThat(bins.get(0).estimatedRows()).isEqualTo(100L);
        assertThat(names(bins.get(0))).containsExactly("big");
        assertThat(bins.get(1).estimatedRows()).isEqualTo(4L);
        assertThat(names(bins.get(1))).containsExactly("a", "b", "c", "d");
    }

    @Test
    void anUnknownSizeIsWeighedAtTheMedianOfTheKnownOnes() {
        // Known sizes 10, 20, 30 -> median (lower) 20. The unknown table is therefore bigger
        // than "small" (10) and smaller than "large" (30).
        List<BinPacker.Bin> bins = BinPacker.pack(
                List.of(item("large", 30L), item("mystery", null),
                        item("mid", 20L), item("small", 10L)), 1);

        assertThat(names(bins.get(0))).containsExactly("large", "mid", "mystery", "small");
        assertThat(bins.get(0).estimatedRows()).isEqualTo(80L);
        assertThat(bins.get(0).unknownCount()).isEqualTo(1);
    }

    @Test
    void withoutAnyKnownSizeTheTablesSplitEvenlyByCount() {
        List<BinPacker.Bin> bins = BinPacker.pack(
                List.of(item("a", null), item("b", null), item("c", null), item("d", null)), 2);

        assertThat(bins).hasSize(2);
        assertThat(bins.get(0).items()).hasSize(2);
        assertThat(bins.get(1).items()).hasSize(2);
        assertThat(bins).allSatisfy(bin -> assertThat(bin.unknownCount()).isEqualTo(2));
    }

    @Test
    void equalSizesBreakTiesByNameSoTheSplitIsRepeatable() {
        List<BinPacker.Item> items = List.of(
                item("delta", 5L), item("charlie", 5L), item("bravo", 5L), item("alpha", 5L));

        List<BinPacker.Bin> first = BinPacker.pack(items, 2);
        List<BinPacker.Bin> second = BinPacker.pack(List.of(
                item("alpha", 5L), item("bravo", 5L), item("charlie", 5L), item("delta", 5L)), 2);

        assertThat(names(first.get(0))).containsExactly("alpha", "charlie");
        assertThat(names(first.get(1))).containsExactly("bravo", "delta");
        assertThat(names(second.get(0))).isEqualTo(names(first.get(0)));
        assertThat(names(second.get(1))).isEqualTo(names(first.get(1)));
    }

    @Test
    void anEmptyListingHasNoBins() {
        assertThat(BinPacker.pack(List.of(), 4)).isEmpty();
    }

    @Test
    void aNonPositiveBinCountStillProducesOneBin() {
        assertThat(BinPacker.pack(List.of(item("a", 1L)), 0)).hasSize(1);
        assertThat(BinPacker.pack(List.of(item("a", 1L)), -3)).hasSize(1);
    }

    @Test
    void everyTableAppearsExactlyOnceAndUnknownRowsDoNotInflateTheTotal() {
        List<BinPacker.Bin> bins = BinPacker.pack(
                List.of(item("a", 10L), item("b", null), item("c", 30L)), 2);

        assertThat(bins).flatExtracting(BinPacker.Bin::items)
                .extracting(BinPacker.Item::name)
                .containsExactlyInAnyOrder("a", "b", "c");
        assertThat(bins.stream().mapToLong(BinPacker.Bin::estimatedRows).sum()).isEqualTo(60L);
    }
}
