package com.dwp.services.meeting.videomeeting.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

@ConfigurationProperties("dwp.meeting.intelligence")
public class MeetingIntelligenceHttpProperties {

    private String provider = "disabled";
    private String baseUrl = "";
    private Set<String> allowedHosts = new LinkedHashSet<>();
    private String serviceToken = "";
    private String assertionKeyId = "";
    private String assertionSecretBase64 = "";
    private Duration assertionTtl = Duration.ofSeconds(30);
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration requestTimeout = Duration.ofSeconds(15);
    private int maximumResponseBytes = 2_000_000;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Set<String> getAllowedHosts() {
        return allowedHosts;
    }

    public void setAllowedHosts(Set<String> allowedHosts) {
        this.allowedHosts = allowedHosts == null ? new LinkedHashSet<>() : allowedHosts;
    }

    public String getServiceToken() {
        return serviceToken;
    }

    public void setServiceToken(String serviceToken) {
        this.serviceToken = serviceToken;
    }

    public String getAssertionKeyId() {
        return assertionKeyId;
    }

    public void setAssertionKeyId(String assertionKeyId) {
        this.assertionKeyId = assertionKeyId;
    }

    public String getAssertionSecretBase64() {
        return assertionSecretBase64;
    }

    public void setAssertionSecretBase64(String assertionSecretBase64) {
        this.assertionSecretBase64 = assertionSecretBase64;
    }

    public Duration getAssertionTtl() {
        return assertionTtl;
    }

    public void setAssertionTtl(Duration assertionTtl) {
        this.assertionTtl = assertionTtl;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public int getMaximumResponseBytes() {
        return maximumResponseBytes;
    }

    public void setMaximumResponseBytes(int maximumResponseBytes) {
        this.maximumResponseBytes = maximumResponseBytes;
    }
}
