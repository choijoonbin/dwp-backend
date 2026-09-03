package com.dwp.services.meeting.videomeeting.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("dwp.meeting.transcript-source.deletion")
public class MeetingTranscriptDeletionProperties {

    private boolean enabled;
    private int batchSize = 50;
    private Duration pollDelay = Duration.ofMinutes(5);
    private Duration leaseDuration = Duration.ofMinutes(1);
    private String workerId = "meeting-transcript-retention";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public Duration getPollDelay() { return pollDelay; }
    public void setPollDelay(Duration pollDelay) { this.pollDelay = pollDelay; }
    public Duration getLeaseDuration() { return leaseDuration; }
    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = leaseDuration;
    }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
}
