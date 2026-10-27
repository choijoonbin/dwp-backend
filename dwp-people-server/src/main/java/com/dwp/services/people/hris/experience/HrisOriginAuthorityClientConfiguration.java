package com.dwp.services.people.hris.experience;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.time.Duration;

/** Validates and builds the bounded origin-authority HTTP client. */
final class HrisOriginAuthorityClientConfiguration {

    private static final long MAX_TIMEOUT_MILLIS = 5_000L;

    static RestClient client(
            RestClient.Builder builder,
            String baseUrl,
            Duration timeout) {
        if (!canonical(baseUrl, 2_048) || timeout == null || timeout.isZero()
                || timeout.isNegative() || timeout.toMillis() > MAX_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException(
                    "HRIS origin authority URL/timeout is invalid.");
        }
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return builder.clone().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    static String canonicalToken(String value) {
        return canonicalOptional(value, 4_096);
    }

    static String canonicalOptional(String value, int maximum) {
        if (value == null || value.isBlank()) return "";
        if (!canonical(value, maximum)) {
            throw new IllegalArgumentException("HRIS origin authority value is not canonical.");
        }
        return value;
    }

    static boolean canonical(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum
                && value.equals(value.strip()) && value.indexOf('\r') < 0
                && value.indexOf('\n') < 0;
    }
}
