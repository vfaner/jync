package com.qqmu.jync.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * The server-side half of the shared pager bar: which slice of rows a page holds, and which
 * page numbers to render around the current one.
 *
 * <p>Page numbers stay windowed — first, last and current ± span — with each gap rendered as
 * an ellipsis. An ellipsis is not decoration: it links to the same page with that side's span
 * widened by {@link #EXPAND_STEP}, so a long log can be browsed progressively without ever
 * rendering hundreds of numbers at once. Spans travel in the URL, which keeps the bar stateless
 * and every state of it shareable.
 */
public final class Pager {

    public static final int DEFAULT_SIZE = 20;
    public static final List<Integer> SIZES = List.of(15, 20, 30, 50, 100);
    public static final int DEFAULT_SPAN = 1;
    public static final int EXPAND_STEP = 5;

    private final int page;
    private final int size;
    private final long totalItems;
    private final int totalPages;
    private final int spanLeft;
    private final int spanRight;

    private Pager(int page, int size, long totalItems, int totalPages,
                  int spanLeft, int spanRight) {
        this.page = page;
        this.size = size;
        this.totalItems = totalItems;
        this.totalPages = totalPages;
        this.spanLeft = spanLeft;
        this.spanRight = spanRight;
    }

    /** Clamps everything: an out-of-range page or size must never reach a query or a link. */
    public static Pager of(Integer page, Integer size, long totalItems,
                           Integer spanLeft, Integer spanRight) {
        int sz = size != null && SIZES.contains(size) ? size : DEFAULT_SIZE;
        int pages = (int) Math.max(1, (totalItems + sz - 1) / sz);
        int p = page == null ? 1 : Math.min(Math.max(1, page), pages);
        return new Pager(p, sz, totalItems, pages,
                clampSpan(spanLeft, pages), clampSpan(spanRight, pages));
    }

    private static int clampSpan(Integer span, int pages) {
        int s = span == null || span < DEFAULT_SPAN ? DEFAULT_SPAN : span;
        return Math.min(s, pages);
    }

    /** The rows of {@code all} belonging to this page, for in-memory lists. */
    public <T> List<T> slice(List<T> all) {
        int from = firstIndex();
        if (from >= all.size()) {
            return List.of();
        }
        return all.subList(from, (int) Math.min((long) from + size, all.size()));
    }

    /** Zero-based offset of this page's first row, for pageable repository queries. */
    public int firstIndex() {
        return (page - 1) * size;
    }

    /** Render slots: page numbers, with at most one ellipsis per side. */
    public List<Slot> getItems() {
        List<Slot> slots = new ArrayList<>();
        int winFrom = Math.max(2, page - spanLeft);
        int winTo = Math.min(totalPages - 1, page + spanRight);
        slots.add(Slot.page(1));
        if (winFrom > 2) {
            slots.add(Slot.gap(true));
        }
        for (int i = winFrom; i <= winTo; i++) {
            slots.add(Slot.page(i));
        }
        if (winTo < totalPages - 1) {
            slots.add(Slot.gap(false));
        }
        if (totalPages > 1) {
            slots.add(Slot.page(totalPages));
        }
        return slots;
    }

    /** Span values an ellipsis link navigates to: this side widened, the other untouched. */
    public int getExpandLeftSpan() {
        return Math.min(spanLeft + EXPAND_STEP, totalPages);
    }

    public int getExpandRightSpan() {
        return Math.min(spanRight + EXPAND_STEP, totalPages);
    }

    public int getPage() {
        return page;
    }

    public int getSize() {
        return size;
    }

    public long getTotalItems() {
        return totalItems;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public int getSpanLeft() {
        return spanLeft;
    }

    public int getSpanRight() {
        return spanRight;
    }

    public boolean isFirst() {
        return page <= 1;
    }

    public boolean isLast() {
        return page >= totalPages;
    }

    public List<Integer> getSizes() {
        return SIZES;
    }

    /** One rendered position: a page number, or an ellipsis marking a hidden range. */
    public static final class Slot {

        private final Integer page;
        private final boolean left;

        private Slot(Integer page, boolean left) {
            this.page = page;
            this.left = left;
        }

        static Slot page(int page) {
            return new Slot(page, false);
        }

        static Slot gap(boolean left) {
            return new Slot(null, left);
        }

        /** The page number, or null when this slot is an ellipsis. */
        public Integer getPage() {
            return page;
        }

        /** Which side's gap this ellipsis marks; meaningless for a number slot. */
        public boolean isLeft() {
            return left;
        }
    }
}
