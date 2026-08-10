package com.dwp.services.platform.apihistory;

import java.time.Duration;

public enum ApiHistoryWindow {
    H1(Duration.ofHours(1), 300),
    H6(Duration.ofHours(6), 900),
    H24(Duration.ofHours(24), 3_600),
    D7(Duration.ofDays(7), 21_600),
    D30(Duration.ofDays(30), 86_400);

    private final Duration duration;
    private final long bucketSeconds;

    ApiHistoryWindow(Duration duration, long bucketSeconds) {
        this.duration = duration;
        this.bucketSeconds = bucketSeconds;
    }

    public Duration duration() {
        return duration;
    }

    public long bucketSeconds() {
        return bucketSeconds;
    }
}
