package com.dwp.services.platform.auditcontrol;

import java.time.Duration;

public enum AuditWindow {
    H24(Duration.ofHours(24), 3_600),
    D7(Duration.ofDays(7), 21_600),
    D30(Duration.ofDays(30), 86_400),
    D90(Duration.ofDays(90), 259_200);

    private final Duration duration;
    private final long bucketSeconds;

    AuditWindow(Duration duration, long bucketSeconds) {
        this.duration = duration;
        this.bucketSeconds = bucketSeconds;
    }

    public Duration duration() { return duration; }
    public long bucketSeconds() { return bucketSeconds; }
}
