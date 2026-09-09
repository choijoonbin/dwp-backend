package com.dwp.services.meeting.videomeeting.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("dwp.meeting.invitation-delivery")
public class MeetingInvitationDeliveryProperties {

    private boolean enabled;
    private String baseUrl = "";
    private String serviceToken = "";
    private boolean allowHttp;
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration requestTimeout = Duration.ofSeconds(3);
    private int maximumResponseBytes = 65_536;
    private int batchSize = 20;
    private int maximumAttempts = 8;
    private Duration pollDelay = Duration.ofSeconds(10);
    private Duration retryDelay = Duration.ofSeconds(30);
    private Duration leaseDuration = Duration.ofMinutes(2);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getServiceToken() { return serviceToken; }
    public void setServiceToken(String serviceToken) { this.serviceToken = serviceToken; }
    public boolean isAllowHttp() { return allowHttp; }
    public void setAllowHttp(boolean allowHttp) { this.allowHttp = allowHttp; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
    public int getMaximumResponseBytes() { return maximumResponseBytes; }
    public void setMaximumResponseBytes(int maximumResponseBytes) { this.maximumResponseBytes = maximumResponseBytes; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getMaximumAttempts() { return maximumAttempts; }
    public void setMaximumAttempts(int maximumAttempts) { this.maximumAttempts = maximumAttempts; }
    public Duration getPollDelay() { return pollDelay; }
    public void setPollDelay(Duration pollDelay) { this.pollDelay = pollDelay; }
    public Duration getRetryDelay() { return retryDelay; }
    public void setRetryDelay(Duration retryDelay) { this.retryDelay = retryDelay; }
    public Duration getLeaseDuration() { return leaseDuration; }
    public void setLeaseDuration(Duration leaseDuration) { this.leaseDuration = leaseDuration; }

    public boolean validDispatchConfiguration() {
        return batchSize >= 1 && batchSize <= 100
                && maximumAttempts >= 1 && maximumAttempts <= 32
                && bounded(pollDelay, Duration.ofSeconds(1), Duration.ofHours(1))
                && bounded(retryDelay, Duration.ofSeconds(1), Duration.ofHours(24))
                && bounded(leaseDuration, Duration.ofSeconds(30), Duration.ofMinutes(15))
                && requestTimeout != null && leaseDuration.compareTo(requestTimeout) > 0;
    }

    private boolean bounded(Duration value, Duration minimum, Duration maximum) {
        return value != null && value.compareTo(minimum) >= 0
                && value.compareTo(maximum) <= 0;
    }
}
