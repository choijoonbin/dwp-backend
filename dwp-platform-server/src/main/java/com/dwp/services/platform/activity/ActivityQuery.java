package com.dwp.services.platform.activity;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import java.time.OffsetDateTime;
import java.util.Set;

/** Dates are instants: from inclusive, to exclusive. Filters apply before pagination. */
public record ActivityQuery(
        String actor, String state, String query, String source, String objectType,
        String objectId, String executionId, OffsetDateTime from, OffsetDateTime to,
        String cursor, Integer limit, Boolean includeUsage) {
    public static ActivityQuery defaults() {
        return new ActivityQuery(null, null, null, null, null, null, null,
                null, null, null, 50, false);
    }

    public ActivityQuery normalized() {
        String validActor = clean(actor, 16);
        String validState = clean(state, 24);
        if (validActor != null && !Set.of("AGENT", "PERSON", "SYSTEM").contains(validActor)
                || validState != null && !Set.of("RUNNING", "NEEDS_INPUT", "COMPLETED",
                    "POLICY_BLOCKED", "FAILED", "CANCELLED", "UNKNOWN").contains(validState)
                || limit != null && (limit < 1 || limit > 100)
                || from != null && to != null && !from.isBefore(to)) {
            throw invalid();
        }
        return new ActivityQuery(validActor, validState, clean(query, 200), clean(source, 120),
                clean(objectType, 80), clean(objectId, 240), clean(executionId, 240),
                from, to, clean(cursor, 2048), limit == null ? 50 : limit,
                Boolean.TRUE.equals(includeUsage));
    }

    private static String clean(String value, int max) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > max || value.indexOf('\0') >= 0) throw invalid();
        return value.trim();
    }

    static BaseException invalid() {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Invalid activity filter or cursor.");
    }
}
