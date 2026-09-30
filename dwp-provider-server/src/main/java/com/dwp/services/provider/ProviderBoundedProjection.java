package com.dwp.services.provider;

import java.util.List;

record ProviderBoundedProjection<T>(List<T> items, boolean hasMore) {

    static <T> ProviderBoundedProjection<T> from(List<T> fetched, int visibleLimit) {
        if (visibleLimit < 1) {
            throw new IllegalArgumentException("visibleLimit must be positive");
        }
        boolean hasMore = fetched.size() > visibleLimit;
        return new ProviderBoundedProjection<>(
                List.copyOf(fetched.subList(0, Math.min(fetched.size(), visibleLimit))),
                hasMore);
    }
}
