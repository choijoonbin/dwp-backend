package com.dwp.gateway.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

@Component
public class AuthSessionVerifier implements SessionVerifier {

    private static final String TENANT_HEADER = "X-Tenant-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final String TRACE_PARENT_HEADER = "traceparent";

    private final WebClient authClient;
    private final Duration timeout;

    public AuthSessionVerifier(
            WebClient.Builder webClientBuilder,
            @Value("${SERVICE_AUTH_URL:http://localhost:8001}") String authServiceUrl,
            @Value("${DWP_AUTH_VERIFICATION_TIMEOUT:2s}") Duration timeout) {
        this.authClient = webClientBuilder.baseUrl(authServiceUrl).build();
        this.timeout = timeout;
    }

    @Override
    public Mono<VerifiedIdentity> verify(ServerHttpRequest request) {
        String requestedTenant = request.getHeaders().getFirst(TENANT_HEADER);
        boolean tenantAssertionPresent = requestedTenant != null && !requestedTenant.isBlank();

        return authClient.get()
                .uri("/auth/me")
                .headers(headers -> copySecurityContext(request.getHeaders(), headers))
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToMono(MeEnvelope.class)
                                .filter(envelope -> Boolean.TRUE.equals(envelope.success()))
                                .map(MeEnvelope::data)
                                .filter(data -> data != null
                                        && data.userId() != null
                                        && data.tenantId() != null)
                                .map(data -> new VerifiedIdentity(
                                        data.userId().toString(),
                                        data.tenantId().toString(),
                                        data.roles()))
                                .filter(identity -> !tenantAssertionPresent
                                        || requestedTenant.equals(identity.tenantId()));
                    }
                    if (response.statusCode() == HttpStatus.UNAUTHORIZED
                            || response.statusCode() == HttpStatus.FORBIDDEN) {
                        return response.releaseBody().then(Mono.empty());
                    }
                    return response.createException().flatMap(Mono::error);
                })
                .timeout(timeout);
    }

    private void copySecurityContext(HttpHeaders source, HttpHeaders target) {
        copyHeader(source, target, HttpHeaders.COOKIE);
        copyHeader(source, target, HttpHeaders.AUTHORIZATION);
        copyHeader(source, target, TENANT_HEADER);
        copyHeader(source, target, CORRELATION_HEADER);
        copyHeader(source, target, TRACE_PARENT_HEADER);
        copyHeader(source, target, HttpHeaders.USER_AGENT);
        target.set(HttpHeaders.ACCEPT, "application/json");
    }

    private void copyHeader(HttpHeaders source, HttpHeaders target, String name) {
        List<String> values = source.get(name);
        if (values != null && !values.isEmpty()) {
            target.put(name, List.copyOf(values));
        }
    }

    private record MeEnvelope(Boolean success, MeData data) {
    }

    private record MeData(Long userId, Long tenantId, List<String> roles) {
    }
}
