package com.dwp.services.people.hris.separation.hrm013;

import java.net.URI;
import java.time.Duration;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Default-off, bounded runtime settings. No credential material is configurable here. */
@ConfigurationProperties(prefix = "dwp.hris.separation")
public final class Hrm013RuntimeProperties implements InitializingBean {
    private boolean enabled;
    private boolean waiverRelayEnabled;
    private boolean taskDispatchRelayEnabled;
    private URI authorityEndpoint = URI.create(
            "https://dwp-auth.internal/internal/auth/v1/hris-service-grant-authority/resolve");
    private URI approvalEndpoint = URI.create(
            "https://dwp-approval.internal/internal/hris-approval/v1/separation-task-waiver-requests");
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration requestTimeout = Duration.ofSeconds(5);
    private Duration relayLease = Duration.ofSeconds(30);
    private Duration retryBase = Duration.ofSeconds(1);
    private int relayBatchSize = 25;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isWaiverRelayEnabled() { return waiverRelayEnabled; }
    public void setWaiverRelayEnabled(boolean value) { this.waiverRelayEnabled = value; }
    public boolean isTaskDispatchRelayEnabled() { return taskDispatchRelayEnabled; }
    public void setTaskDispatchRelayEnabled(boolean value) { this.taskDispatchRelayEnabled = value; }
    public URI getAuthorityEndpoint() { return authorityEndpoint; }
    public void setAuthorityEndpoint(URI value) { authorityEndpoint = https(value, "authorityEndpoint"); }
    public URI getApprovalEndpoint() { return approvalEndpoint; }
    public void setApprovalEndpoint(URI value) { approvalEndpoint = https(value, "approvalEndpoint"); }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration value) { connectTimeout = bounded(value, "connectTimeout", 1, 10); }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration value) { requestTimeout = bounded(value, "requestTimeout", 1, 30); }
    public Duration getRelayLease() { return relayLease; }
    public void setRelayLease(Duration value) { relayLease = bounded(value, "relayLease", 1, 60); }
    public Duration getRetryBase() { return retryBase; }
    public void setRetryBase(Duration value) { retryBase = bounded(value, "retryBase", 1, 30); }
    public int getRelayBatchSize() { return relayBatchSize; }
    public void setRelayBatchSize(int value) {
        if (value < 1 || value > 100) throw new IllegalArgumentException("relayBatchSize");
        relayBatchSize = value;
    }

    public void requireCoherent() {
        if ((waiverRelayEnabled || taskDispatchRelayEnabled) && !enabled) {
            throw new IllegalStateException("HRM-013 relays require the owner runtime");
        }
    }

    @Override
    public void afterPropertiesSet() {
        requireCoherent();
    }

    private static URI https(URI value, String name) {
        if (value == null || !"https".equalsIgnoreCase(value.getScheme())
                || value.getHost() == null || value.getUserInfo() != null
                || value.getFragment() != null || value.getQuery() != null) {
            throw new IllegalArgumentException(name + " must be one exact HTTPS endpoint");
        }
        return value;
    }

    private static Duration bounded(Duration value, String name, long minimum, long maximum) {
        if (value == null || value.compareTo(Duration.ofSeconds(minimum)) < 0
                || value.compareTo(Duration.ofSeconds(maximum)) > 0) {
            throw new IllegalArgumentException(name + " is outside its sealed bound");
        }
        return value;
    }
}
