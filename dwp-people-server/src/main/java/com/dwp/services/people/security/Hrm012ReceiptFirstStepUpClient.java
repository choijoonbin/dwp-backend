package com.dwp.services.people.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/** People-only service client; callers invoke it only after durable receipt/hash precedence. */
@Component
public final class Hrm012ReceiptFirstStepUpClient {

    private static final String PATH =
            "/internal/auth/v1/hris/hrm012/receipt-first-step-up/verify";
    private static final String TOKEN_HEADER = "X-DWP-Hris-Origin-Authority-Token";
    private static final String IDENTITY_HEADER = "X-DWP-Service-Identity";

    private final RestClient client;
    private final String token;

    public Hrm012ReceiptFirstStepUpClient(
            RestClient.Builder builder,
            @Value("${dwp.people.hrm012-receipt-first.auth-url:${SERVICE_AUTH_URL:http://localhost:8001}}")
            String authUrl,
            @Value("${dwp.people.hrm012-receipt-first.token:}") String token,
            @Value("${dwp.people.hrm012-receipt-first.timeout:PT2S}") Duration timeout) {
        this.client = client(builder, authUrl, timeout);
        this.token = canonical(token, 4_096) ? token : "";
    }

    Hrm012ReceiptFirstStepUpClient(RestClient client, String token) {
        this.client = client;
        this.token = canonical(token, 4_096) ? token : "";
    }

    public Hrm012ReceiptFirstStepUpDtos.VerifyResponse verify(
            Hrm012ReceiptFirstStepUpDtos.VerifyRequest request) {
        if (client == null || token.isBlank() || request == null
                || !canonical(request.correlationId(), 200)) throw unavailable();
        try {
            Hrm012ReceiptFirstStepUpDtos.VerifyResponse response = client.post()
                    .uri(PATH)
                    .headers(headers -> trustedHeaders(headers, request))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(Hrm012ReceiptFirstStepUpDtos.VerifyResponse.class);
            if (!valid(response)) throw unavailable();
            return response;
        } catch (BaseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private void trustedHeaders(
            HttpHeaders headers,
            Hrm012ReceiptFirstStepUpDtos.VerifyRequest request) {
        headers.set(TOKEN_HEADER, token);
        headers.set(IDENTITY_HEADER, "dwp-people-server");
        headers.set("X-Correlation-ID", request.correlationId());
        headers.set(HttpHeaders.ACCEPT, "application/json");
    }

    private boolean valid(Hrm012ReceiptFirstStepUpDtos.VerifyResponse value) {
        return value != null && "ALLOWED".equals(value.decision())
                && present(value.authorizationRevision())
                && present(value.dutyEvidenceRevision())
                && present(value.featureDecisionRevision())
                && present(value.stepUpChallengeId())
                && value.stepUpExpiresAt() != null
                && value.stepUpExpiresAt().isAfter(java.time.OffsetDateTime.now());
    }

    private boolean present(String value) {
        return canonical(value, 500);
    }

    private static RestClient client(
            RestClient.Builder builder, String rawBaseUrl, Duration timeout) {
        String baseUrl = rawBaseUrl == null ? "" : rawBaseUrl.strip();
        if (!validBaseUrl(baseUrl) || timeout == null || timeout.isZero()
                || timeout.isNegative() || timeout.toMillis() > 10_000) return null;
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return builder.clone().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    private static boolean validBaseUrl(String value) {
        if (!canonical(value, 2_048)) return false;
        try {
            URI uri = URI.create(value);
            if (uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || uri.getHost() == null
                    || !(uri.getPath() == null || uri.getPath().isEmpty())) return false;
            if ("https".equalsIgnoreCase(uri.getScheme())) return true;
            return "http".equalsIgnoreCase(uri.getScheme())
                    && ("localhost".equalsIgnoreCase(uri.getHost())
                        || "127.0.0.1".equals(uri.getHost())
                        || "::1".equals(uri.getHost()));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean canonical(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum
                && value.equals(value.strip()) && value.indexOf(',') < 0
                && value.chars().noneMatch(c -> c < 0x20 || c == 0x7f);
    }

    private BaseException unavailable() {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                "HRM-012 post-receipt authority verification is unavailable.");
    }
}
