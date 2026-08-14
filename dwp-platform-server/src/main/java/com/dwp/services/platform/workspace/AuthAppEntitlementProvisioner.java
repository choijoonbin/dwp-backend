package com.dwp.services.platform.workspace;

import com.dwp.core.constant.HeaderConstants;
import com.dwp.core.http.OutboundHttpHeaders;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class AuthAppEntitlementProvisioner implements AppEntitlementProvisioner {

    private static final String TOKEN_HEADER = "X-DWP-Identity-Sync-Token";

    private final RestClient auth;
    private final String token;

    public AuthAppEntitlementProvisioner(
            RestClient.Builder builder,
            @Value("${dwp.identity-sync.auth-url:http://localhost:8001}") String authUrl,
            @Value("${dwp.identity-sync.token:}") String token) {
        this.auth = builder.clone().baseUrl(authUrl).build();
        this.token = token == null ? "" : token.strip();
    }

    @Override
    @Bulkhead(name = "authEntitlementSync", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "authEntitlementSync")
    @Retry(name = "idempotentInternal")
    public Result synchronize(Command command) {
        if (token.isBlank()) {
            throw new ProvisioningException(
                    "Runtime entitlement synchronization is not configured.");
        }
        try {
            RestClient.RequestBodySpec request = auth.put()
                    .uri(
                            "/internal/identity/v1/tenants/{tenantId}/app-entitlements/{sourceRef}",
                            command.tenantId(), command.sourceRef())
                    .headers(headers -> OutboundHttpHeaders.propagateObservability(headers))
                    .header(TOKEN_HEADER, token);
            if (command.correlationId() != null && !command.correlationId().isBlank()) {
                request.headers(headers ->
                        headers.set(HeaderConstants.X_CORRELATION_ID, command.correlationId().strip()));
            }
            SyncResponse response = request
                    .body(new SyncRequest(
                            "USER", command.userId().toString(), command.resourceKey(),
                            command.permissionCode(), command.action(), command.validTo(),
                            command.actorId(), command.justification()))
                    .retrieve()
                    .body(SyncResponse.class);
            if (response == null || response.grantId() == null
                    || response.lifecycleState() == null) {
                throw new ProvisioningException(
                        "Runtime entitlement synchronization returned an invalid response.");
            }
            return new Result(
                    response.grantId(), response.lifecycleState(),
                    response.version(), response.changed());
        } catch (RestClientResponseException exception) {
            throw new ProvisioningException(
                    "Runtime entitlement synchronization failed with HTTP "
                            + exception.getStatusCode().value() + ".",
                    exception);
        }
    }

    private record SyncRequest(
            String principalType,
            String principalRef,
            String resourceKey,
            String permissionCode,
            String action,
            java.time.OffsetDateTime validTo,
            Long actorId,
            String justification) {
    }

    private record SyncResponse(
            String grantId,
            String lifecycleState,
            long version,
            boolean changed) {
    }
}
