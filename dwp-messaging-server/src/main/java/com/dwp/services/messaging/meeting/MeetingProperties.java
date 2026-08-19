package com.dwp.services.messaging.meeting;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "dwp.messaging.meeting")
public class MeetingProperties {

    private String provider = "disabled";
    private Duration tokenTtl = Duration.ofMinutes(5);
    private final LiveKit livekit = new LiveKit();

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider == null ? "disabled" : provider.trim();
    }

    public Duration getTokenTtl() {
        Duration resolved = tokenTtl == null ? Duration.ofMinutes(5) : tokenTtl;
        if (resolved.compareTo(Duration.ofMinutes(1)) < 0) return Duration.ofMinutes(1);
        if (resolved.compareTo(Duration.ofMinutes(15)) > 0) return Duration.ofMinutes(15);
        return resolved;
    }

    public void setTokenTtl(Duration tokenTtl) {
        this.tokenTtl = tokenTtl;
    }

    public LiveKit getLivekit() {
        return livekit;
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
            this.apiUrl = normalized(apiUrl);
        }

        public String getClientUrl() {
            return clientUrl;
        }

        public void setClientUrl(String clientUrl) {
            this.clientUrl = normalized(clientUrl);
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = normalized(apiKey);
        }

        public String getApiSecret() {
            return apiSecret;
        }

        public void setApiSecret(String apiSecret) {
            this.apiSecret = normalized(apiSecret);
        }

        public boolean configured() {
            return !apiUrl.isBlank()
                    && !clientUrl.isBlank()
                    && !apiKey.isBlank()
                    && !apiSecret.isBlank();
        }

        private static String normalized(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
