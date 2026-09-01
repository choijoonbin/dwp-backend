package com.dwp.services.notification.integration;

import com.dwp.core.http.OutboundHttpHeaders;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Optional;

@Component
public class AuthNotificationRecipientEntitlementDirectory
        implements NotificationRecipientEntitlementDirectory {

    static final String TOKEN_HEADER = "X-DWP-Identity-Sync-Token";

    private final RestClient auth;
    private final String token;

    public AuthNotificationRecipientEntitlementDirectory(
            RestClient.Builder builder,
            @Value("${dwp.identity-sync.auth-url:http://localhost:8001}") String authUrl,
            @Value("${dwp.identity-sync.token:}") String token) {
        this.auth = builder.clone().baseUrl(authUrl).build();
        this.token = token == null ? "" : token.strip();
    }

    @Override
    @Bulkhead(name = "notificationIdentityDirectory", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "notificationIdentityDirectory")
    @Retry(name = "notificationIdentityDirectory")
    public Optional<Subject> find(long tenantId, long userId) {
        if (token.isBlank()) {
            throw new IllegalStateException(
                    "Notification recipient entitlement validation is not configured.");
        }
        try {
            Subject subject = auth.get()
                    .uri("/internal/identity/v1/tenants/{tenantId}/users/{userId}",
                            tenantId, userId)
                    .headers(headers -> OutboundHttpHeaders.propagateObservability(headers))
                    .header(TOKEN_HEADER, token)
                    .retrieve()
                    .body(Subject.class);
            if (subject == null || subject.tenantId() == null || subject.userId() == null
                    || subject.tenantId() != tenantId || subject.userId() != userId) {
                throw new IllegalStateException(
                        "Identity validation returned an invalid tenant or user binding.");
            }
            return Optional.of(subject);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode() == HttpStatus.NOT_FOUND) return Optional.empty();
            if (exception.getStatusCode().is5xxServerError()) throw exception;
            throw new IllegalStateException(
                    "Identity validation request was rejected.", exception);
        }
    }
}
