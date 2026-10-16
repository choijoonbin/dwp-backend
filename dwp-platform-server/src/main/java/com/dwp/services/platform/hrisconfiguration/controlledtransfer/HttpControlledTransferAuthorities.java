package com.dwp.services.platform.hrisconfiguration.controlledtransfer;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import static com.dwp.services.platform.hrisconfiguration.controlledtransfer.ControlledTransferAuthorityPorts.*;

/**
 * Fail-closed internal authority client. Each response is a closed record and is
 * still checked against the request by {@link ControlledTransferService}; a 2xx
 * response by itself is never treated as authority.
 */
@Component
@ConditionalOnProperty(
        name = "dwp.hris.controlled-transfer.authority.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class HttpControlledTransferAuthorities implements
        TrustedObjectMetadataAuthority, SignedMalwareScanAuthority,
        EgressPolicyAuthority {

    static final String METADATA_PATH =
            "/internal/object/v1/controlled-transfers/metadata:resolve";
    static final String SCAN_PATH =
            "/internal/security/v1/controlled-transfers/malware-scans:verify";
    static final String EGRESS_PATH =
            "/internal/security/v1/controlled-transfers/egress-policies:authorize";
    private static final String SERVICE_IDENTITY = "dwp-platform-server";
    private static final long MAX_TIMEOUT_MILLIS = 5_000;

    private final RestClient authority;
    private final boolean enabled;
    private final String serviceToken;

    @Autowired
    HttpControlledTransferAuthorities(
            RestClient.Builder builder,
            @Value("${dwp.hris.controlled-transfer.authority.enabled:false}")
            boolean enabled,
            @Value("${dwp.hris.controlled-transfer.authority.base-url:}")
            String baseUrl,
            @Value("${dwp.hris.controlled-transfer.authority.service-token:}")
            String serviceToken,
            @Value("${dwp.hris.controlled-transfer.authority.timeout:PT2S}")
            Duration timeout) {
        this(client(builder, baseUrl, timeout), enabled, serviceToken);
    }

    HttpControlledTransferAuthorities(
            RestClient authority, boolean enabled, String serviceToken) {
        this.authority = authority;
        this.enabled = enabled;
        this.serviceToken = canonical(serviceToken, 4_096) ? serviceToken.strip() : "";
    }

    @Override
    public TrustedObjectMetadata resolve(ObjectMetadataRequest request) {
        return post(METADATA_PATH, request, TrustedObjectMetadata.class);
    }

    @Override
    public VerifiedCleanScan verifySignedCleanSnapshot(SignedScanRequest request) {
        return post(SCAN_PATH, request, VerifiedCleanScan.class);
    }

    @Override
    public VerifiedEgressPolicy authorize(EgressPolicyRequest request) {
        return post(EGRESS_PATH, request, VerifiedEgressPolicy.class);
    }

    private <T> T post(String path, Object body, Class<T> responseType) {
        if (!enabled || authority == null || serviceToken.isBlank()) {
            throw unavailable("CONTROLLED_TRANSFER_AUTHORITY_UNCONFIGURED", null);
        }
        try {
            T result = authority.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("X-DWP-Service-Token", serviceToken)
                    .header("X-DWP-Service-Identity", SERVICE_IDENTITY)
                    .body(body)
                    .retrieve()
                    .body(responseType);
            if (result == null) {
                throw unavailable("CONTROLLED_TRANSFER_AUTHORITY_EMPTY_RESPONSE", null);
            }
            return result;
        } catch (BaseException exception) {
            throw exception;
        } catch (RestClientException | IllegalArgumentException exception) {
            throw unavailable("CONTROLLED_TRANSFER_AUTHORITY_UNAVAILABLE", exception);
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
