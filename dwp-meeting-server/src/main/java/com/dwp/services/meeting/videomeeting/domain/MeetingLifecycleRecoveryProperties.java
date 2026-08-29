package com.dwp.services.meeting.videomeeting.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "dwp.meeting.lifecycle-recovery")
class MeetingLifecycleRecoveryProperties {

    private boolean enabled = true;
    private Duration retryDelay = Duration.ofSeconds(5);
    private int batchSize = 20;
    private int maximumAttempts = 8;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getRetryDelay() {
        Duration value = retryDelay == null ? Duration.ofSeconds(5) : retryDelay;
        if (value.isNegative() || value.isZero()) return Duration.ofSeconds(1);
        return value.compareTo(Duration.ofMinutes(5)) > 0
                ? Duration.ofMinutes(5) : value;
    }

    public void setRetryDelay(Duration retryDelay) {
        this.retryDelay = retryDelay;
    }

    public int getBatchSize() {
        return Math.max(1, Math.min(100, batchSize));
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaximumAttempts() {
        return Math.max(2, Math.min(50, maximumAttempts));
    }

    public void setMaximumAttempts(int maximumAttempts) {
        this.maximumAttempts = maximumAttempts;
    }
}
