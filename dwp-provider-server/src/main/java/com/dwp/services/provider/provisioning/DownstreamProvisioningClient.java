package com.dwp.services.provider.provisioning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.http.OutboundHttpHeaders;
import com.dwp.core.provisioning.ProviderTenantCommand;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class DownstreamProvisioningClient {

    private static final String TOKEN_HEADER = "X-DWP-Provisioning-Token";

    private final RestClient auth;
    private final RestClient platform;
    private final RestClient people;
    private final String provisioningToken;

    public DownstreamProvisioningClient(
            RestClient.Builder builder,
            @Value("${dwp.services.auth-url:http://localhost:8001}") String authUrl,
            @Value("${dwp.services.platform-url:http://localhost:8002}") String platformUrl,
            @Value("${dwp.services.people-url:http://localhost:8003}") String peopleUrl,
            @Value("${dwp.provider.provisioning-token:}") String provisioningToken) {
        this.auth = builder.clone().baseUrl(authUrl).build();
        this.platform = builder.clone().baseUrl(platformUrl).build();
        this.people = builder.clone().baseUrl(peopleUrl).build();
        this.provisioningToken = provisioningToken == null ? "" : provisioningToken.trim();
    }

    @Bulkhead(name = "tenantProvisioning", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "tenantProvisioning")
    public AuthProvisioningResult provisionAuth(UUID tenantId, JsonNode plan) {
        requireConfigured();
        List<String> entitlements = entitlements(plan);
        AuthProvisioningResult result = auth.post()
                .uri("/internal/provider/v1/tenants")
                .headers(headers -> OutboundHttpHeaders.propagateObservability(headers))
                .header(TOKEN_HEADER, provisioningToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new AuthProvisioningRequest(
                        tenantId,
                        plan.path("tenantKey").asText(),
                        plan.path("displayName").asText(),
                        plan.path("dataRegion").asText(),
                        plan.path("isolationModel").asText(),
                        plan.path("defaultLocale").asText(),
                        plan.path("timeZone").asText(),
                        plan.path("initialAdministrator").path("displayName").asText(),
                        plan.path("initialAdministrator").path("email").asText(),
                        entitlements))
                .retrieve()
                .body(AuthProvisioningResult.class);
        if (result == null) throw unavailable("Auth provisioning returned no result.");
        return result;
    }

    @Bulkhead(name = "tenantProvisioning", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "tenantProvisioning")
    public ServiceProvisioningResult provisionPlatform(UUID tenantId, Long authTenantId, JsonNode plan) {
        requireConfigured();
        ServiceProvisioningResult result = platform.post()
                .uri("/internal/provider/v1/tenants")
                .headers(headers -> OutboundHttpHeaders.propagateObservability(headers))
                .header(TOKEN_HEADER, provisioningToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PlatformProvisioningRequest(
                        tenantId,
                        authTenantId,
                        plan.path("tenantKey").asText(),
                        plan.path("displayName").asText(),
                        plan.path("dataRegion").asText(),
                        plan.path("isolationModel").asText(),
                        plan.path("defaultLocale").asText(),
                        entitlements(plan)))
                .retrieve()
                .body(ServiceProvisioningResult.class);
        if (result == null) throw unavailable("Platform provisioning returned no result.");
        return result;
    }

    @Bulkhead(name = "tenantProvisioning", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "tenantProvisioning")
    public ServiceProvisioningResult provisionPeople(UUID tenantId, Long authTenantId, JsonNode plan) {
        requireConfigured();
        ServiceProvisioningResult result = people.post()
                .uri("/internal/provider/v1/tenants")
                .headers(headers -> OutboundHttpHeaders.propagateObservability(headers))
                .header(TOKEN_HEADER, provisioningToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PeopleProvisioningRequest(
                        tenantId,
                        authTenantId,
                        plan.path("tenantKey").asText(),
                        plan.path("displayName").asText(),
                        plan.path("dataRegion").asText(),
                        plan.path("isolationModel").asText()))
                .retrieve()
                .body(ServiceProvisioningResult.class);
        if (result == null) throw unavailable("People provisioning returned no result.");
        return result;
    }

    @Bulkhead(name = "tenantProvisioning", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "tenantProvisioning")
    public ServiceProvisioningResult provisionAssetStorage(UUID tenantId) {
        requireConfigured();
        ServiceProvisioningResult result = platform.post()
                .uri("/internal/provider/v1/tenants/{tenantId}/asset-storage", tenantId)
                .headers(headers -> OutboundHttpHeaders.propagateObservability(headers))
                .header(TOKEN_HEADER, provisioningToken)
                .retrieve()
                .body(ServiceProvisioningResult.class);
        if (result == null) throw unavailable("Asset storage provisioning returned no result.");
        return result;
    }

    @Bulkhead(name = "tenantProvisioning", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "tenantProvisioning")
    public void updateLifecycle(UUID tenantId, String lifecycleState) {
        requireConfigured();
        LifecycleRequest request = new LifecycleRequest(lifecycleState);
        updateLifecycle(auth, tenantId, request);
        updateLifecycle(platform, tenantId, request);
        updateLifecycle(people, tenantId, request);
    }

    @Bulkhead(name = "tenantProvisioning", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "tenantProvisioning")
    public void replaceEntitlements(UUID tenantId, List<String> entitlementKeys) {
        requireConfigured();
        EntitlementsRequest request = new EntitlementsRequest(List.copyOf(entitlementKeys));
        replaceEntitlements(auth, tenantId, request);
        replaceEntitlements(platform, tenantId, request);
    }

    @Bulkhead(name = "tenantProvisioning", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "tenantProvisioning")
    public ProviderTenantCommand.Receipt executeTenantCommand(
            String targetService,
            UUID tenantId,
            ProviderTenantCommand.Request command) {
        requireConfigured();
        RestClient client = switch (targetService) {
            case "AUTH" -> auth;
            case "PLATFORM" -> platform;
            case "PEOPLE" -> people;
            default -> throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE, "Unknown tenant command target service.");
        };
        ProviderTenantCommand.Receipt receipt = client.post()
                .uri("/internal/provider/v1/tenants/{tenantId}/commands", tenantId)
                .headers(headers -> OutboundHttpHeaders.propagateObservability(headers))
                .header(TOKEN_HEADER, provisioningToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(command)
                .retrieve()
                .body(ProviderTenantCommand.Receipt.class);
        if (receipt == null) throw unavailable(targetService + " returned no tenant command receipt.");
        if (!command.commandId().equals(receipt.commandId())
                || !tenantId.equals(receipt.providerTenantId())
                || !command.commandType().equals(receipt.commandType())
                || command.expectedRevision() != receipt.expectedRevision()
                || command.targetRevision() != receipt.targetRevision()
                || !command.payloadSha256().equals(receipt.payloadSha256())
                || receipt.appliedAt() == null
                || receipt.result() == null
                || !receipt.result().isObject()) {
            throw unavailable(targetService + " returned an invalid tenant command receipt.");
        }
        return receipt;
    }

    private void updateLifecycle(RestClient client, UUID tenantId, LifecycleRequest request) {
        client.patch()
                .uri("/internal/provider/v1/tenants/{tenantId}/lifecycle", tenantId)
                .headers(headers -> OutboundHttpHeaders.propagateObservability(headers))
                .header(TOKEN_HEADER, provisioningToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }

    private void replaceEntitlements(RestClient client, UUID tenantId, EntitlementsRequest request) {
        client.put()
                .uri("/internal/provider/v1/tenants/{tenantId}/entitlements", tenantId)
                .headers(headers -> OutboundHttpHeaders.propagateObservability(headers))
                .header(TOKEN_HEADER, provisioningToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toBodilessEntity();
    }

    private List<String> entitlements(JsonNode plan) {
        List<String> values = new ArrayList<>();
        plan.path("entitlements").forEach(value -> values.add(value.asText()));
        return values;
    }

    private void requireConfigured() {
        if (provisioningToken.isBlank()) {
            throw unavailable("Provider provisioning identity is not configured.");
        }
    }

    private BaseException unavailable(String message) {
        return new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR, message);
    }

    private record AuthProvisioningRequest(
            UUID providerTenantId,
            String tenantKey,
            String displayName,
            String dataRegion,
            String isolationModel,
            String defaultLocale,
            String timeZone,
            String administratorDisplayName,
            String administratorEmail,
            List<String> entitlementKeys) {
    }

    private record PlatformProvisioningRequest(
            UUID providerTenantId,
            Long tenantId,
            String tenantKey,
            String displayName,
            String dataRegion,
            String isolationModel,
            String defaultLocale,
            List<String> entitlementKeys) {
    }

    private record PeopleProvisioningRequest(
            UUID providerTenantId,
            Long tenantId,
            String tenantKey,
            String displayName,
            String dataRegion,
            String isolationModel) {
    }

    private record LifecycleRequest(String lifecycleState) {
    }

    private record EntitlementsRequest(List<String> entitlementKeys) {
    }

    public record AuthProvisioningResult(
            UUID providerTenantId,
            Long tenantId,
            Long administratorUserId,
            String administratorEmail,
            String lifecycleState,
            int schemaVersion) {
    }

    public record ServiceProvisioningResult(
            UUID providerTenantId,
            Long tenantId,
            String lifecycleState,
            int schemaVersion,
            String externalReference) {
    }

}
