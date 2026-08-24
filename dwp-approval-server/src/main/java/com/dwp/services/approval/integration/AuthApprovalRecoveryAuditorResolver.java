package com.dwp.services.approval.integration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.http.OutboundHttpHeaders;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.UUID;

@Component
public class AuthApprovalRecoveryAuditorResolver
        implements ApprovalRecoveryAuditorResolver {

    static final String TOKEN_HEADER = "X-DWP-Approval-Recovery-Token";
    static final String SERVICE_IDENTITY_HEADER = "X-DWP-Service-Identity";
    static final String SERVICE_IDENTITY = "dwp-approval-server";

    private final RestClient auth;
    private final String token;

    public AuthApprovalRecoveryAuditorResolver(
            RestClient.Builder builder,
            @Value("${dwp.approval.recovery-auditor-assignment.auth-url:http://localhost:8001}")
                    String authUrl,
            @Value("${dwp.approval.recovery-auditor-assignment.token:}") String token) {
        this.auth = builder.clone().baseUrl(authUrl).build();
        this.token = token == null ? "" : token.strip();
    }

    @Override
    @Bulkhead(name = "authApprovalRecoveryAuditor", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "authApprovalRecoveryAuditor")
    @Retry(name = "idempotentInternal")
    public Assignment resolve(
            long tenantId,
            UUID outboxId,
            long originatorUserId,
            String resourceSetKey) {
        if (token.isBlank() || !validResourceSetKey(resourceSetKey)) {
            throw unavailable("Approval recovery auditor assignment is not configured.");
        }
        try {
            ResolveResponse response = auth.post()
                    .uri("/internal/auth/v1/approval-recovery-auditor/resolve")
                    .headers(headers -> OutboundHttpHeaders.propagateObservability(headers))
                    .header(TOKEN_HEADER, token)
                    .header(SERVICE_IDENTITY_HEADER, SERVICE_IDENTITY)
                    .body(new ResolveRequest(
                            tenantId, outboxId, originatorUserId, resourceSetKey))
                    .retrieve()
                    .body(ResolveResponse.class);
            return validate(response, originatorUserId, resourceSetKey);
        } catch (RestClientException exception) {
            throw unavailable("Approval recovery auditor resolution is unavailable.", exception);
        }
    }

    private Assignment validate(
            ResolveResponse response,
            long originatorUserId,
            String expectedResourceSetKey) {
        if (response == null || response.selectedUserId() == null
                || response.selectedUserId() <= 0
                || response.selectedUserId() == originatorUserId
                || !expectedResourceSetKey.equals(response.resourceSetKey())
                || response.assignmentRevision() == null
                || response.assignmentRevision().isBlank()
                || response.assignmentRevision().length() > 240) {
            throw unavailable("Approval recovery auditor resolution returned invalid evidence.");
        }
        return new Assignment(
                response.selectedUserId(),
                response.resourceSetKey(),
                response.assignmentRevision().strip());
    }

    private BaseException unavailable(String message) {
        return new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR, message);
    }

    private BaseException unavailable(String message, Exception cause) {
        return new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR, message, cause);
    }

    private boolean validResourceSetKey(String value) {
        return value != null
                && value.length() >= 3
                && value.length() <= 80
                && value.matches("^[A-Z][A-Z0-9_]{2,79}$");
    }

    record ResolveRequest(
            long tenantId,
            UUID outboxId,
            long originatorUserId,
            String resourceSetKey) {
    }

    record ResolveResponse(
            Long selectedUserId,
            String resourceSetKey,
            String assignmentRevision) {
    }
}
