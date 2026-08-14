package com.dwp.core.autoconfig;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("dwp.http.client")
public record DwpHttpClientProperties(Duration connectTimeout, Duration readTimeout) {

    public DwpHttpClientProperties {
        connectTimeout = valid(connectTimeout, Duration.ofSeconds(2), "connect-timeout");
        readTimeout = valid(readTimeout, Duration.ofSeconds(5), "read-timeout");
    }

    private static Duration valid(Duration value, Duration fallback, String property) {
        Duration resolved = value == null ? fallback : value;
        if (resolved.isZero() || resolved.isNegative() || resolved.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("dwp.http.client." + property + " must be between 1ms and 30s");
        }
        return resolved;
    }
}
