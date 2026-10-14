package com.dwp.services.people.hris.performance.review;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.util.UUID;

final class PerformanceReviewEtags {

    private PerformanceReviewEtags() {
    }

    static String format(UUID reviewId, long version) {
        return '"' + reviewId.toString() + ':' + version + '"';
    }

    static long parse(UUID reviewId, String value) {
        if (value == null || value.length() > 100
                || !value.startsWith("\"") || !value.endsWith("\"")) {
            throw invalid();
        }
        String prefix = '"' + reviewId.toString() + ':';
        if (!value.startsWith(prefix)) throw invalid();
        String version = value.substring(prefix.length(), value.length() - 1);
        try {
            long parsed = Long.parseLong(version);
            if (parsed < 0 || !Long.toString(parsed).equals(version)) throw invalid();
            return parsed;
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    private static BaseException invalid() {
        return new BaseException(
                ErrorCode.INVALID_INPUT_VALUE,
                "If-Match must be the exact assigned-review ETag.");
    }
}
