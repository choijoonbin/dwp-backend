package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

final class CalendarVerifiedGroups {

    private static final UUID EMPTY_GROUP_REF = new UUID(0L, 0L);

    private CalendarVerifiedGroups() {
    }

    static Set<UUID> parse(String header) {
        if (header == null || header.isBlank()) return Set.of();
        try {
            Set<UUID> values = Arrays.stream(header.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .limit(201)
                    .map(UUID::fromString)
                    .collect(Collectors.toUnmodifiableSet());
            if (values.size() > 200) throw invalid();
            return values;
        } catch (IllegalArgumentException exception) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN, "Verified group context is invalid.", exception);
        }
    }

    static UUID[] databaseArray(String header) {
        Set<UUID> values = parse(header);
        return values.isEmpty()
                ? new UUID[]{EMPTY_GROUP_REF}
                : values.toArray(UUID[]::new);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Too many verified groups.");
    }
}
