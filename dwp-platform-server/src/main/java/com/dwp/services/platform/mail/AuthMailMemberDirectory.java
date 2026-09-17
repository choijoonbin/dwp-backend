package com.dwp.services.platform.mail;

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
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;

@Component
class AuthMailMemberDirectory implements MailMemberDirectory {

    private static final String TOKEN_HEADER = "X-DWP-Identity-Sync-Token";

    private final RestClient auth;
    private final String token;

    AuthMailMemberDirectory(
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
    public MemberIdentity requireActive(long tenantId, long userId) {
        configured();
        try {
            IdentityResponse response = auth.get()
                    .uri("/internal/identity/v1/tenants/{tenantId}/users/{userId}",
                            tenantId, userId)
                    .headers(OutboundHttpHeaders::propagateObservability)
                    .header(TOKEN_HEADER, token)
                    .retrieve()
                    .body(IdentityResponse.class);
            MemberIdentity identity = response == null ? null : response.identity();
            if (identity == null || !identity.activeTenantUser(tenantId, userId)) {
                throw rejected("SHARED_MEMBER_USER_NOT_ACTIVE");
            }
            return identity;
        } catch (RestClientResponseException response) {
            if (response.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw rejected("SHARED_MEMBER_USER_NOT_FOUND");
            }
            throw unavailable(response);
        } catch (RestClientException failure) {
            throw unavailable(failure);
        }
    }

    @Override
    @Bulkhead(name = "authSubjectDirectory", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "authSubjectDirectory")
    @Retry(name = "idempotentInternal")
    public List<MemberIdentity> searchActive(long tenantId, String query, int limit) {
        configured();
        int bounded = Math.max(1, Math.min(limit, 30));
        try {
            IdentityResponse[] responses = auth.get()
                    .uri(builder -> builder
                            .path("/internal/identity/v1/tenants/{tenantId}/users")
                            .queryParam("query", query == null ? "" : query.strip())
                            .queryParam("activeOnly", true)
                            .queryParam("limit", bounded)
                            .build(tenantId))
                    .headers(OutboundHttpHeaders::propagateObservability)
                    .header(TOKEN_HEADER, token)
                    .retrieve()
                    .body(IdentityResponse[].class);
            if (responses == null) throw unavailable(null);
            List<MemberIdentity> identities = Arrays.stream(responses)
                    .map(IdentityResponse::identity)
                    .toList();
            if (identities.stream().anyMatch(identity -> identity == null
                    || !identity.activeTenantUser(tenantId))) {
                throw unavailable(null);
            }
            return identities;
        } catch (RestClientException failure) {
            throw unavailable(failure);
        }
    }

    private void configured() {
        if (token.isBlank()) throw unavailable(null);
    }

    private ResponseStatusException rejected(String code) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, code);
    }

    private ResponseStatusException unavailable(Exception cause) {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "IDENTITY_DIRECTORY_UNAVAILABLE", cause);
    }

    private record IdentityResponse(
            Long tenantId,
            Long userId,
            String displayName,
            String email,
            String department,
            String status,
            String identityPlane) {

        MemberIdentity identity() {
            return new MemberIdentity(
                    tenantId, userId, displayName, department, email, status, identityPlane);
        }
    }
}
