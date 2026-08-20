package com.dwp.services.approval.integration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.http.OutboundHttpHeaders;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Arrays;
import java.util.List;

@Component
public class AuthApprovalIdentityDirectory implements ApprovalIdentityDirectory {

    private static final String TOKEN_HEADER = "X-DWP-Identity-Sync-Token";

    private final RestClient auth;
    private final String token;

    public AuthApprovalIdentityDirectory(
            RestClient.Builder builder,
            @Value("${dwp.identity-sync.auth-url:http://localhost:8001}") String authUrl,
            @Value("${dwp.identity-sync.token:}") String token) {
        this.auth = builder.clone().baseUrl(authUrl).build();
        this.token = token == null ? "" : token.strip();
    }

    @Override
    @Bulkhead(name = "authApprovalIdentityDirectory", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "authApprovalIdentityDirectory")
    @Retry(name = "idempotentInternal")
    public Subject require(long tenantId, long userId) {
        configured();
        try {
            Subject subject = auth.get()
                    .uri("/internal/identity/v1/tenants/{tenantId}/users/{userId}", tenantId, userId)
                    .headers(headers -> OutboundHttpHeaders.propagateObservability(headers))
                    .header(TOKEN_HEADER, token)
                    .retrieve()
                    .body(Subject.class);
            if (subject == null || subject.tenantId() == null || subject.userId() == null
                    || subject.tenantId() != tenantId || subject.userId() != userId) {
                throw unavailable("Identity validation returned an invalid subject.");
            }
            return subject;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new BaseException(ErrorCode.NOT_FOUND, "The delegate is not an active tenant user.");
            }
            throw unavailable("Identity validation is unavailable.", exception);
        } catch (RestClientException exception) {
            throw unavailable("Identity validation is unavailable.", exception);
        }
    }

    @Override
    @Bulkhead(name = "authApprovalIdentityDirectory", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "authApprovalIdentityDirectory")
    @Retry(name = "idempotentInternal")
    public List<Subject> search(long tenantId, String query, int limit) {
        configured();
        try {
            Subject[] subjects = auth.get()
                    .uri(builder -> builder
                            .path("/internal/identity/v1/tenants/{tenantId}/users")
                            .queryParam("query", query == null ? "" : query.strip())
                            .queryParam("limit", Math.max(1, Math.min(limit, 30)))
                            .build(tenantId))
                    .headers(headers -> OutboundHttpHeaders.propagateObservability(headers))
                    .header(TOKEN_HEADER, token)
                    .retrieve()
                    .body(Subject[].class);
            if (subjects == null) return List.of();
            return Arrays.stream(subjects)
                    .filter(subject -> subject != null
                            && subject.tenantId() != null
                            && subject.tenantId() == tenantId
                            && subject.userId() != null
                            && subject.active())
                    .toList();
        } catch (RestClientException exception) {
            throw unavailable("Identity directory search is unavailable.", exception);
        }
    }

    private void configured() {
        if (token.isBlank()) {
            throw unavailable("Approval identity validation is not configured.");
        }
    }

    private BaseException unavailable(String message) {
        return new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR, message);
    }

    private BaseException unavailable(String message, Exception cause) {
        return new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR, message, cause);
    }
}
