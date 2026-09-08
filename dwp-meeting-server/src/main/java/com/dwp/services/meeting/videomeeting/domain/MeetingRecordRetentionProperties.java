package com.dwp.services.meeting.videomeeting.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("dwp.meeting.record-retention")
public class MeetingRecordRetentionProperties {
    private boolean enabled = false;
    private int batchSize = 10;
    private Duration pollDelay = Duration.ofMinutes(5);
    private Duration leaseDuration = Duration.ofMinutes(1);
    private String workerId = "meeting-record-retention";
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int value) { batchSize = value; }
    public Duration getPollDelay() { return pollDelay; }
    public void setPollDelay(Duration value) { pollDelay = value; }
    public Duration getLeaseDuration() { return leaseDuration; }
    public void setLeaseDuration(Duration value) { leaseDuration = value; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String value) { workerId = value; }
}
