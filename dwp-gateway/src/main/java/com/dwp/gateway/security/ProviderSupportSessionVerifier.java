package com.dwp.gateway.security;

import com.dwp.gateway.filter.VerifiedIdentityFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

@Component
public class ProviderSupportSessionVerifier implements SupportSessionVerifier {

    public static final String SUPPORT_COOKIE = "DWP_SUPPORT_SESSION";
    public static final String VALIDATION_TOKEN_HEADER = "X-DWP-Support-Validation-Token";
    public static final String RESOURCE_METHOD_HEADER = "X-DWP-Support-Resource-Method";
    public static final String RESOURCE_PATH_HEADER = "X-DWP-Support-Resource-Path";
    private static final String SERVICE_TOKEN_HEADER = "X-DWP-Service-Token";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final String TRACE_PARENT_HEADER = "traceparent";
    private static final String TRACE_STATE_HEADER = "tracestate";

    private final WebClient providerClient;
    private final String serviceToken;
    private final String validationToken;
    private final Duration timeout;

    public ProviderSupportSessionVerifier(
            WebClient.Builder webClientBuilder,
            @Value("${SERVICE_PROVIDER_URL:http://localhost:8004}") String providerServiceUrl,
            @Value("${dwp.provider.service-token:}") String serviceToken,
            @Value("${dwp.provider.support-validation-token:}") String validationToken,
            @Value("${dwp.provider.support-validation-timeout:2s}") Duration timeout) {
        this.providerClient = webClientBuilder.baseUrl(providerServiceUrl).build();
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
        this.validationToken = validationToken == null ? "" : validationToken.trim();
        this.timeout = timeout;
    }

    @Override
    public Mono<VerifiedSupportAccess> verify(
            ServerHttpRequest request,
            String supportSessionToken) {
        if (serviceToken.isBlank() || validationToken.isBlank()) {
            return Mono.error(new SupportValidationUnavailableException());
        }
        return providerClient.post()
                .uri("/internal/provider/v1/support-access/resolve")
                .headers(headers -> {
                    headers.set(SERVICE_TOKEN_HEADER, serviceToken);
                    headers.set(VALIDATION_TOKEN_HEADER, validationToken);
                    copyHeader(request.getHeaders(), headers, VerifiedIdentityFilter.USER_HEADER);
                    copyHeader(request.getHeaders(), headers, VerifiedIdentityFilter.TENANT_HEADER);
                    copyHeader(request.getHeaders(), headers, VerifiedIdentityFilter.ROLES_HEADER);
                    copyHeader(request.getHeaders(), headers, VerifiedIdentityFilter.AUTH_SESSION_ID_HEADER);
                    copyHeader(request.getHeaders(), headers, VerifiedIdentityFilter.IDENTITY_PLANE_HEADER);
                    copyHeader(request.getHeaders(), headers, CORRELATION_HEADER);
                    copyHeader(request.getHeaders(), headers, TRACE_PARENT_HEADER);
                    copyHeader(request.getHeaders(), headers, TRACE_STATE_HEADER);
                    headers.set(RESOURCE_METHOD_HEADER, request.getMethod().name());
                    headers.set(RESOURCE_PATH_HEADER, request.getURI().getPath());
                    headers.set(HttpHeaders.ACCEPT, "application/json");
                })
                .cookie(SUPPORT_COOKIE, supportSessionToken)
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(SupportContextEnvelope.class)
                                .filter(envelope -> Boolean.TRUE.equals(envelope.success()))
                                .map(SupportContextEnvelope::data);
                    }
                    if (response.statusCode() == HttpStatus.UNAUTHORIZED
                            || response.statusCode() == HttpStatus.FORBIDDEN
                            || response.statusCode() == HttpStatus.CONFLICT) {
                        return response.releaseBody().then(Mono.empty());
                    }
                    if (response.statusCode().is4xxClientError()) {
                        return response.releaseBody().then(Mono.error(
                                new SupportValidationRejectedException(
                                        response.statusCode())));
                    }
                    return response.createException().flatMap(Mono::error);
                })
                .timeout(timeout)
                .onErrorMap(
                        error -> !(error instanceof SupportValidationUnavailableException)
                                && !(error instanceof SupportValidationRejectedException),
                        SupportValidationUnavailableException::new);
    }

    private void copyHeader(HttpHeaders source, HttpHeaders target, String name) {
        List<String> values = source.get(name);
        if (values != null && !values.isEmpty()) target.put(name, List.copyOf(values));
    }

    private record SupportContextEnvelope(Boolean success, VerifiedSupportAccess data) {
    }

    public static final class SupportValidationRejectedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final HttpStatusCode statusCode;

        public SupportValidationRejectedException(HttpStatusCode statusCode) {
            this.statusCode = statusCode;
        }

        public HttpStatusCode statusCode() {
            return statusCode;
        }
    }

    public static final class SupportValidationUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public SupportValidationUnavailableException() {
        }

        public SupportValidationUnavailableException(Throwable cause) {
            super(cause);
        }
    }
}
