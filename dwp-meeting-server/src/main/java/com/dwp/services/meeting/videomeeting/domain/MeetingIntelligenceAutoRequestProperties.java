package com.dwp.services.meeting.videomeeting.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("dwp.meeting.intelligence.auto-request")
public class MeetingIntelligenceAutoRequestProperties {

    private boolean enabled = true;
    private int batchSize = 10;
    private Duration pollDelay = Duration.ofSeconds(10);
    private Duration retryDelay = Duration.ofSeconds(30);
    private Duration leaseDuration = Duration.ofMinutes(3);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public Duration getPollDelay() { return pollDelay; }
    public void setPollDelay(Duration pollDelay) { this.pollDelay = pollDelay; }
    public Duration getRetryDelay() { return retryDelay; }
    public void setRetryDelay(Duration retryDelay) { this.retryDelay = retryDelay; }
    public Duration getLeaseDuration() { return leaseDuration; }
    public void setLeaseDuration(Duration leaseDuration) { this.leaseDuration = leaseDuration; }

    boolean valid() {
        return batchSize > 0 && batchSize <= 100
                && bounded(pollDelay, Duration.ofSeconds(1), Duration.ofHours(1))
                && bounded(retryDelay, Duration.ofSeconds(1), Duration.ofHours(24))
                && bounded(leaseDuration, Duration.ofSeconds(30), Duration.ofMinutes(15));
    }

    private boolean bounded(Duration value, Duration minimum, Duration maximum) {
        return value != null && value.compareTo(minimum) >= 0
                && value.compareTo(maximum) <= 0;
    }
}
