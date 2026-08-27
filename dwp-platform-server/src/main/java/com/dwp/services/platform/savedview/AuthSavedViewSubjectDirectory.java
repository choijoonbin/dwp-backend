package com.dwp.services.platform.savedview;

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
import org.springframework.web.client.RestClientResponseException;

import java.util.Arrays;
import java.util.List;

@Component
public class AuthSavedViewSubjectDirectory implements SavedViewSubjectDirectory {

    private static final String TOKEN_HEADER = "X-DWP-Identity-Sync-Token";

    private final RestClient auth;
    private final String token;

    public AuthSavedViewSubjectDirectory(
            RestClient.Builder builder,
            @Value("${dwp.identity-sync.auth-url:http://localhost:8001}") String authUrl,
            @Value("${dwp.identity-sync.token:}") String token) {
        this.auth = builder.clone().baseUrl(authUrl).build();
        this.token = token == null ? "" : token.strip();
    }

    @Override
    @Bulkhead(name = "authSubjectDirectory", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "authSubjectDirectory")
    @Retry(name = "idempotentInternal")
    public Subject require(Long tenantId, Long userId) {
        if (token.isBlank()) {
            throw unavailable("Identity subject validation is not configured.");
        }
        try {
            Subject subject = auth.get()
                    .uri("/internal/identity/v1/tenants/{tenantId}/users/{userId}", tenantId, userId)
                    .headers(headers -> OutboundHttpHeaders.propagateObservability(headers))
                    .header(TOKEN_HEADER, token)
                    .retrieve()
                    .body(Subject.class);
            if (subject == null || !tenantId.equals(subject.tenantId())
                    || !userId.equals(subject.userId()) || !subject.tenantPlane()) {
                throw unavailable("Identity subject validation returned an invalid response.");
            }
            return subject;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new BaseException(ErrorCode.NOT_FOUND, "The tenant user does not exist.");
            }
            throw unavailable("Identity subject validation is unavailable.", exception);
        }
    }

    @Override
    @Bulkhead(name = "authSubjectDirectory", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "authSubjectDirectory")
    @Retry(name = "idempotentInternal")
    public List<DirectorySubject> search(
            Long tenantId, String query, boolean activeOnly, int limit) {
        if (token.isBlank()) {
            throw unavailable("Identity subject search is not configured.");
        }
        try {
            DirectorySubject[] subjects = auth.get()
                    .uri(builder -> builder
                            .path("/internal/identity/v1/tenants/{tenantId}/users")
                            .queryParam("query", query == null ? "" : query.strip())
                            .queryParam("limit", Math.max(1, Math.min(limit, 30)))
                            .queryParam("activeOnly", activeOnly)
                            .build(tenantId))
                    .headers(headers -> OutboundHttpHeaders.propagateObservability(headers))
                    .header(TOKEN_HEADER, token)
                    .retrieve()
                    .body(DirectorySubject[].class);
            if (subjects == null || Arrays.stream(subjects).anyMatch(
                    subject -> subject == null || !tenantId.equals(subject.tenantId())
                            || !"TENANT".equalsIgnoreCase(subject.identityPlane()))) {
                throw unavailable("Identity subject search returned an invalid response.");
            }
            return List.copyOf(Arrays.asList(subjects));
        } catch (RestClientResponseException exception) {
            throw unavailable("Identity subject search is unavailable.", exception);
        }
    }

    private BaseException unavailable(String message) {
        return new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR, message);
    }

    private BaseException unavailable(String message, Exception cause) {
        return new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR, message, cause);
    }
}
