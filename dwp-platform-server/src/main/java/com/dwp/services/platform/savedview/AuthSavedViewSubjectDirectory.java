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
                    || !userId.equals(subject.userId())) {
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

    private BaseException unavailable(String message) {
        return new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR, message);
    }

    private BaseException unavailable(String message, Exception cause) {
        return new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR, message, cause);
    }
}
