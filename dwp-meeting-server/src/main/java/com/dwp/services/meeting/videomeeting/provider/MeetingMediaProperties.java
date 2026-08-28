package com.dwp.services.meeting.videomeeting.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "dwp.meeting")
public class MeetingMediaProperties {

    private String provider = "disabled";
    private Duration tokenTtl = Duration.ofMinutes(5);
    private Duration lifecycleOperationLease = Duration.ofMinutes(2);
    private int joinCodeLength = 12;
    private String recordingPolicy = "NEVER";
    private final LiveKit livekit = new LiveKit();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = normalized(provider, "disabled");
    }

    public Duration getTokenTtl() {
        Duration value = tokenTtl == null ? Duration.ofMinutes(5) : tokenTtl;
        if (value.compareTo(Duration.ofMinutes(1)) < 0) return Duration.ofMinutes(1);
        if (value.compareTo(Duration.ofMinutes(10)) > 0) return Duration.ofMinutes(10);
        return value;
    }

    public void setTokenTtl(Duration tokenTtl) {
        this.tokenTtl = tokenTtl;
    }

    public Duration getLifecycleOperationLease() {
        Duration value = lifecycleOperationLease == null
                ? Duration.ofMinutes(2) : lifecycleOperationLease;
        if (value.compareTo(Duration.ofSeconds(15)) < 0) return Duration.ofSeconds(15);
        if (value.compareTo(Duration.ofMinutes(10)) > 0) return Duration.ofMinutes(10);
        return value;
    }

    public void setLifecycleOperationLease(Duration lifecycleOperationLease) {
        this.lifecycleOperationLease = lifecycleOperationLease;
    }

    public int getJoinCodeLength() {
        return Math.max(10, Math.min(16, joinCodeLength));
    }

    public void setJoinCodeLength(int joinCodeLength) {
        this.joinCodeLength = joinCodeLength;
    }

    public String getRecordingPolicy() {
        return normalized(recordingPolicy, "NEVER");
    }

    public void setRecordingPolicy(String recordingPolicy) {
        this.recordingPolicy = recordingPolicy;
    }

    public LiveKit getLivekit() {
        return livekit;
    }

    private static String normalized(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    public static class LiveKit {
        private String apiUrl = "";
        private String clientUrl = "";
        private String apiKey = "";
        private String apiSecret = "";

        public String getApiUrl() {
            return apiUrl;
        }

        public void setApiUrl(String apiUrl) {
            this.apiUrl = normalized(apiUrl, "");
        }

        public String getClientUrl() {
            return clientUrl;
        }

        public void setClientUrl(String clientUrl) {
            this.clientUrl = normalized(clientUrl, "");
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = normalized(apiKey, "");
        }

        public String getApiSecret() {
            return apiSecret;
        }

        public void setApiSecret(String apiSecret) {
            this.apiSecret = normalized(apiSecret, "");
        }

        public boolean configured() {
            return !apiUrl.isBlank()
                    && !clientUrl.isBlank()
                    && !apiKey.isBlank()
                    && !apiSecret.isBlank();
        }
    }
}
