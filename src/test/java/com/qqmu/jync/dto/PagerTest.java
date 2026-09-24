package com.qqmu.jync.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/**
 * The pager's page-number window is the whole reason long lists stay browsable: a fixed
 * window around the current page with one ellipsis per hidden range, and an ellipsis that
 * widens its own side instead of jumping. Get the window wrong and either numbers or the
 * ellipses disappear.
 */
class PagerTest {

    private static List<Integer> pages(Pager pager) {
        return pager.getItems().stream()
                .filter(slot -> slot.getPage() != null)
                .map(Pager.Slot::getPage)
                .collect(Collectors.toList());
    }

    private static long gaps(Pager pager) {
        return pager.getItems().stream()
                .filter(slot -> slot.getPage() == null)
                .count();
    }

    @Test
    void defaultsToTwentyPerPageAndClampsWhatItCannotUse() {
        Pager pager = Pager.of(99, 25, 400, null, null);

        assertThat(pager.getSize()).isEqualTo(Pager.DEFAULT_SIZE);
        assertThat(pager.getTotalPages()).isEqualTo(20);
        // 99 pages do not exist; the clamp must happen before any query or link is built.
        assertThat(pager.getPage()).isEqualTo(20);
    }

    @Test
    void firstPageShowsTheLeadingWindowAndOneTrailingGap() {
        Pager pager = Pager.of(1, 20, 400, null, null);

        assertThat(pages(pager)).containsExactly(1, 2, 20);
        assertThat(gaps(pager)).isEqualTo(1);
    }

    @Test
    void aMiddlePageShowsNeighboursAndGapsOnBothSides() {
        Pager pager = Pager.of(10, 20, 400, null, null);

        assertThat(pages(pager)).containsExactly(1, 9, 10, 11, 20);
        assertThat(gaps(pager)).isEqualTo(2);
    }

    @Test
    void expandingTheLeftGapWidensOnlyThatSide() {
        Pager pager = Pager.of(10, 20, 400, null, null);
        Pager widened = Pager.of(10, 20, 400, pager.getExpandLeftSpan(), pager.getSpanRight());

        assertThat(pages(widened)).containsExactly(1, 4, 5, 6, 7, 8, 9, 10, 11, 20);
        // Pages 2-3 are still hidden, so the left ellipsis remains; the right one is untouched.
        assertThat(gaps(widened)).isEqualTo(2);
    }

    @Test
    void aWideEnoughSpanRemovesItsGapEntirely() {
        Pager pager = Pager.of(10, 20, 400, 9, 1);

        assertThat(pages(pager)).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 20);
        assertThat(gaps(pager)).isEqualTo(1);
    }

    @Test
    void aSinglePageRendersJustThatPage() {
        Pager pager = Pager.of(1, null, 3, null, null);

        assertThat(pages(pager)).containsExactly(1);
        assertThat(gaps(pager)).isZero();
        assertThat(pager.isFirst()).isTrue();
        assertThat(pager.isLast()).isTrue();
    }

    @Test
    void sliceReturnsThePageWindow() {
        List<Integer> all = IntStream.rangeClosed(1, 45).boxed().collect(Collectors.toList());
        Pager pager = Pager.of(3, 20, 45, null, null);

        assertThat(pager.firstIndex()).isEqualTo(40);
        assertThat(pager.slice(all)).containsExactly(41, 42, 43, 44, 45);
    }
}
