package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.http.OutboundHttpHeaders;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/** Production fail-closed adapter for Auth's one-snapshot R6-B batch endpoint. */
@Component
@ConditionalOnProperty(
        name = "dwp.hris.navigation-policy.authority.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class AuthNavigationPolicyAuthorityDecisionProvider
        implements NavigationPolicyAuthorityDecisionProvider {

    static final String PATH =
            "/internal/auth/v1/hris-navigation-policy-decisions/evaluate";
    private static final String SERVICE_IDENTITY = "dwp-platform-server";
    private static final int MAX_RESPONSE_BYTES = 262_144;
    private static final long MAX_TIMEOUT_MILLIS = 5_000;

    private final RestClient auth;
    private final String productSurfaceToken;
    private final ObjectMapper strictJson;

    @Autowired
    AuthNavigationPolicyAuthorityDecisionProvider(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            @Value("${dwp.hris.navigation-policy.authority.auth-base-url:}")
            String authBaseUrl,
            @Value("${dwp.hris.navigation-policy.authority.product-surface-token:}")
            String productSurfaceToken,
            @Value("${dwp.hris.navigation-policy.authority.timeout:PT2S}")
            Duration timeout) {
        this(client(builder, authBaseUrl, timeout), productSurfaceToken, objectMapper);
    }

    AuthNavigationPolicyAuthorityDecisionProvider(
            RestClient auth,
            String productSurfaceToken,
            ObjectMapper objectMapper) {
        this.auth = auth;
        this.productSurfaceToken = canonical(productSurfaceToken, 4_096)
                ? productSurfaceToken.strip() : "";
        this.strictJson = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    @Override
    public AuthoritySnapshot evaluate(AuthorityRequest request) {
        if (auth == null || productSurfaceToken.isBlank() || request == null) {
            throw unavailable("HRIS_NAV_V3_AUTHORITY_UNCONFIGURED", null);
        }
        try {
            byte[] payload = auth.post().uri(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("X-DWP-Service-Identity", SERVICE_IDENTITY)
                    .header("X-DWP-Product-Surface-Token", productSurfaceToken)
                    .headers(headers ->
                            OutboundHttpHeaders.propagateObservability(headers))
                    .body(request)
                    .retrieve()
                    .body(byte[].class);
            if (payload == null || payload.length == 0
                    || payload.length > MAX_RESPONSE_BYTES) {
                throw unavailable("HRIS_NAV_V3_AUTHORITY_RESPONSE_INVALID", null);
            }
            return strictJson.readValue(payload, AuthoritySnapshot.class);
        } catch (BaseException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 403) {
                throw new BaseException(
                        ErrorCode.FORBIDDEN,
                        "HRIS_NAV_V3_AUTHORITY_IDENTITY_OR_SCOPE_DENIED",
                        exception);
            }
            if (exception.getStatusCode().value() == 409) {
                throw new BaseException(
                        ErrorCode.DECISION_REVISION_CONFLICT,
                        "HRIS_NAV_V3_AUTHORITY_REVISION_CONFLICT",
                        exception);
            }
            throw unavailable("HRIS_NAV_V3_AUTHORITY_UNAVAILABLE", exception);
        } catch (RestClientException | IllegalArgumentException exception) {
            throw unavailable("HRIS_NAV_V3_AUTHORITY_UNAVAILABLE", exception);
        } catch (Exception exception) {
            throw unavailable("HRIS_NAV_V3_AUTHORITY_RESPONSE_INVALID", exception);
        }
    }

    private static RestClient client(
            RestClient.Builder builder, String rawBaseUrl, Duration timeout) {
        String baseUrl = rawBaseUrl == null ? "" : rawBaseUrl.strip();
        if (!validBaseUrl(baseUrl) || timeout == null || timeout.isZero()
                || timeout.isNegative() || timeout.toMillis() > MAX_TIMEOUT_MILLIS) {
            return null;
        }
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return builder.clone().baseUrl(baseUrl)
                .requestFactory(requestFactory).build();
    }

    private static boolean validBaseUrl(String value) {
        if (!canonical(value, 2_048)) return false;
        try {
            URI uri = URI.create(value);
            if (uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || uri.getHost() == null) {
                return false;
            }
            if ("https".equalsIgnoreCase(uri.getScheme())) return true;
            return "http".equalsIgnoreCase(uri.getScheme())
                    && ("localhost".equalsIgnoreCase(uri.getHost())
                    || "127.0.0.1".equals(uri.getHost())
                    || "::1".equals(uri.getHost()));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean canonical(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum
                && value.equals(value.strip()) && value.indexOf('\r') < 0
                && value.indexOf('\n') < 0;
    }

    private static BaseException unavailable(String message, Throwable cause) {
        return cause == null
                ? new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message)
                : new BaseException(
                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message, cause);
    }
}
