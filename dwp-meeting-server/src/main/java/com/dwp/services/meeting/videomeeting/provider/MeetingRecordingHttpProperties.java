package com.dwp.services.meeting.videomeeting.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

@ConfigurationProperties("dwp.meeting.recording")
public class MeetingRecordingHttpProperties {

    private String provider = "disabled";
    private String baseUrl = "";
    private Set<String> allowedHosts = new LinkedHashSet<>();
    private Set<String> accessTicketAllowedHosts = new LinkedHashSet<>();
    private String accessTicketPathPrefix = "/playback/";
    private String serviceToken = "";
    private String processingRegion = "";
    private String assertionKeyId = "";
    private String assertionSecretBase64 = "";
    private Duration assertionTtl = Duration.ofSeconds(30);
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration requestTimeout = Duration.ofSeconds(10);
    private int maximumResponseBytes = 16_384;
    private Duration commandLease = Duration.ofMinutes(2);
    private Duration accessTicketTtl = Duration.ofMinutes(2);

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public Set<String> getAllowedHosts() { return allowedHosts; }
    public void setAllowedHosts(Set<String> allowedHosts) {
        this.allowedHosts = allowedHosts == null ? new LinkedHashSet<>() : allowedHosts;
    }
    public Set<String> getAccessTicketAllowedHosts() { return accessTicketAllowedHosts; }
    public void setAccessTicketAllowedHosts(Set<String> accessTicketAllowedHosts) {
        this.accessTicketAllowedHosts = accessTicketAllowedHosts == null
                ? new LinkedHashSet<>() : accessTicketAllowedHosts;
    }
    public String getAccessTicketPathPrefix() { return accessTicketPathPrefix; }
    public void setAccessTicketPathPrefix(String accessTicketPathPrefix) {
        this.accessTicketPathPrefix = accessTicketPathPrefix;
    }
    public String getServiceToken() { return serviceToken; }
    public void setServiceToken(String serviceToken) { this.serviceToken = serviceToken; }
    public String getProcessingRegion() { return processingRegion; }
    public void setProcessingRegion(String processingRegion) {
        this.processingRegion = processingRegion;
    }
    public String getAssertionKeyId() { return assertionKeyId; }
    public void setAssertionKeyId(String assertionKeyId) { this.assertionKeyId = assertionKeyId; }
    public String getAssertionSecretBase64() { return assertionSecretBase64; }
    public void setAssertionSecretBase64(String assertionSecretBase64) {
        this.assertionSecretBase64 = assertionSecretBase64;
    }
    public Duration getAssertionTtl() { return assertionTtl; }
    public void setAssertionTtl(Duration assertionTtl) { this.assertionTtl = assertionTtl; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
    public int getMaximumResponseBytes() { return maximumResponseBytes; }
    public void setMaximumResponseBytes(int maximumResponseBytes) {
        this.maximumResponseBytes = maximumResponseBytes;
    }
    public Duration getCommandLease() { return commandLease; }
    public void setCommandLease(Duration commandLease) { this.commandLease = commandLease; }
    public Duration getAccessTicketTtl() { return accessTicketTtl; }
    public void setAccessTicketTtl(Duration accessTicketTtl) {
        this.accessTicketTtl = accessTicketTtl;
    }
}
