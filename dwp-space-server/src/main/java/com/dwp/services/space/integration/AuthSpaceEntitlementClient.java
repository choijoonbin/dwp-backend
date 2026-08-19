package com.dwp.services.space.integration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.http.OutboundHttpHeaders;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;

@Component
public class AuthSpaceEntitlementClient implements SpaceEntitlementPort {

    private static final String TOKEN_HEADER = "X-DWP-Identity-Sync-Token";

    private final RestClient auth;
    private final String identitySyncToken;
    private final boolean enabled;

    public AuthSpaceEntitlementClient(
            RestClient.Builder builder,
            @Value("${dwp.services.auth-url:http://localhost:8001}") String authUrl,
            @Value("${dwp.space.identity-sync-token:}") String identitySyncToken,
            @Value("${dwp.space.entitlement-sync-enabled:true}") boolean enabled) {
        this.auth = builder.clone().baseUrl(authUrl).build();
        this.identitySyncToken = identitySyncToken == null ? "" : identitySyncToken.trim();
        this.enabled = enabled;
    }

    @Override
    @Bulkhead(name = "spaceEntitlementSync", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "spaceEntitlementSync")
    @Retry(name = "idempotentInternal")
    public Result synchronize(Command command) {
        if (!configured()) {
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Space identity synchronization is not configured.");
        }
        SyncResponse response;
        try {
            response = auth.put()
                    .uri("/internal/identity/v1/tenants/{tenantId}/space-entitlements/{sourceRef}",
                            command.tenantId(), command.sourceRef())
                    .headers(headers -> {
                        OutboundHttpHeaders.propagateObservability(headers);
                        if (command.correlationId() != null && !command.correlationId().isBlank()) {
                            headers.set("X-Correlation-ID", command.correlationId());
                        }
                    })
                    .header(TOKEN_HEADER, identitySyncToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new SyncRequest(
                            command.principalType(), command.principalRef(),
                            command.resourceKey(), command.resourceName(),
                            command.permissionCode(), command.action(), command.validUntil(),
                            command.justification(), command.actorUserId()))
                    .retrieve()
                    .body(SyncResponse.class);
        } catch (RestClientResponseException exception) {
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Central identity rejected Space entitlement synchronization with HTTP "
                            + exception.getStatusCode().value() + ".");
        } catch (RestClientException exception) {
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Central identity entitlement synchronization is unavailable.");
        }
        if (response == null) {
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Auth returned no Space entitlement result.");
        }
        return new Result(
                response.grantId(), response.lifecycleState(), response.version(),
                response.changed());
    }

    @Override
    @Bulkhead(name = "spaceEntitlementSync", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "spaceEntitlementSync")
    @Retry(name = "idempotentInternal")
    public ValidationResult validatePrincipal(ValidationCommand command) {
        if (!configured()) {
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Space identity validation is not configured.");
        }
        ValidationResponse response;
        try {
            response = auth.post()
                    .uri("/internal/identity/v1/tenants/{tenantId}/space-entitlements/principal-validations",
                            command.tenantId())
                    .headers(headers -> {
                        OutboundHttpHeaders.propagateObservability(headers);
                        if (command.correlationId() != null && !command.correlationId().isBlank()) {
                            headers.set("X-Correlation-ID", command.correlationId());
                        }
                    })
                    .header(TOKEN_HEADER, identitySyncToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ValidationRequest(
                            command.principalType(), command.principalRef(), command.actorUserId()))
                    .retrieve()
                    .body(ValidationResponse.class);
        } catch (RestClientResponseException exception) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Central identity rejected the Space principal with HTTP "
                            + exception.getStatusCode().value() + ".");
        } catch (RestClientException exception) {
            throw new BaseException(
                    ErrorCode.EXTERNAL_SERVICE_ERROR,
                    "Central identity principal validation is unavailable.");
        }
        if (response == null || !response.active()) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Auth returned no active Space principal.");
        }
        return new ValidationResult(
                response.principalType(), response.suppliedRef(),
                response.canonicalRef(), response.active());
    }

    @Override
    public boolean configured() {
        return enabled && !identitySyncToken.isBlank();
    }

    private record SyncRequest(
            String principalType,
            String principalRef,
            String resourceKey,
            String resourceName,
            String permissionCode,
            String action,
            Instant validTo,
            String justification,
            Long actorId) {
    }

    private record SyncResponse(
            String grantId,
            Long tenantId,
            String principalType,
            String principalRef,
            String resourceKey,
            String permissionCode,
            String sourceType,
            String sourceRef,
            String lifecycleState,
            Instant validFrom,
            Instant validTo,
            long version,
            boolean changed) {
    }

    private record ValidationRequest(
            String principalType,
            String principalRef,
            Long actorId) {
    }

    private record ValidationResponse(
            Long tenantId,
            String principalType,
            String suppliedRef,
            String canonicalRef,
            boolean active) {
    }
}
