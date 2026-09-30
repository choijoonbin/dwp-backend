package com.dwp.services.platform.support;

import java.util.List;

/** A bounded first page whose completeness is proved with one extra fetched row. */
public record CappedList<T>(List<T> items, boolean hasMore, int limit) {
    public CappedList {
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        items = List.copyOf(items);
        if (items.size() > limit) throw new IllegalArgumentException("items exceed limit");
    }

    public static <T> CappedList<T> from(List<T> fetched, int limit) {
        if (fetched == null) throw new IllegalArgumentException("fetched rows are required");
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        boolean hasMore = fetched.size() > limit;
        return new CappedList<>(
                fetched.subList(0, Math.min(fetched.size(), limit)), hasMore, limit);
    }
}
