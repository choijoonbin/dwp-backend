package com.dwp.services.auth.tenantfeatureactivation;

import com.dwp.services.auth.config.ProductAuthorizationOperationsSecurityConfig;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Purpose-token protected internal workflow API. No endpoint has a permissive user/JWT lane. */
@RestController
@RequestMapping(
        value = ProductAuthorizationOperationsSecurityConfig.FEATURE_CONTROL_PATH_ROOT,
        produces = MediaType.APPLICATION_JSON_VALUE)
public class TenantFeatureActivationController {

    private final TenantFeatureActivationService service;

    public TenantFeatureActivationController(TenantFeatureActivationService service) {
        this.service = service;
    }

    @PostMapping(value = "/global/{featureKey}/changes", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TenantFeatureActivationDtos.GlobalChange requestGlobal(
            @PathVariable String featureKey,
            @Valid @RequestBody TenantFeatureActivationDtos.GlobalRequest command) {
        return service.requestGlobal(
                featureKey, command.targetBundleVersion(), command.justification(),
                command.requesterRef(), command.correlationId());
    }

    @PostMapping(value = "/global/changes/{changeId}/submit", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TenantFeatureActivationDtos.GlobalChange submitGlobal(
            @PathVariable UUID changeId,
            @Valid @RequestBody TenantFeatureActivationDtos.GlobalVersionedCommand command) {
        return service.submitGlobal(
                changeId, command.expectedVersion(), command.actorRef(), command.correlationId());
    }

    @PostMapping(value = "/global/changes/{changeId}/approve", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TenantFeatureActivationDtos.GlobalChange approveGlobal(
            @PathVariable UUID changeId,
            @Valid @RequestBody TenantFeatureActivationDtos.GlobalVersionedCommand command) {
        return service.approveGlobal(
                changeId, command.expectedVersion(), command.actorRef(), command.correlationId());
    }

    @PostMapping(value = "/global/changes/{changeId}/activate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TenantFeatureActivationDtos.GlobalChange activateGlobal(
            @PathVariable UUID changeId,
            @Valid @RequestBody TenantFeatureActivationDtos.GlobalActivationCommand command) {
        return service.activateGlobal(
                changeId, command.expectedVersion(), command.actorRef(), command.receiptId(),
                command.correlationId());
    }

    @PostMapping(value = "/global/changes/{changeId}/revoke", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TenantFeatureActivationDtos.GlobalChange revokeGlobal(
            @PathVariable UUID changeId,
            @Valid @RequestBody TenantFeatureActivationDtos.GlobalRevocationCommand command) {
        return service.revokeGlobal(
                changeId, command.expectedVersion(), command.actorRef(), command.reason(),
                command.correlationId());
    }

    @PostMapping(
            value = "/tenants/{tenantId}/features/{featureKey}/changes",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public TenantFeatureActivationDtos.TenantEnrollment requestTenant(
            @PathVariable Long tenantId,
            @PathVariable String featureKey,
            @Valid @RequestBody TenantFeatureActivationDtos.TenantRequest command) {
        return service.requestTenant(
                tenantId, featureKey, command.justification(), command.requesterId(),
                command.correlationId());
    }

    @PostMapping(value = "/tenant/changes/{changeId}/submit", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TenantFeatureActivationDtos.TenantEnrollment submitTenant(
            @PathVariable UUID changeId,
            @Valid @RequestBody TenantFeatureActivationDtos.TenantVersionedCommand command) {
        return service.submitTenant(
                changeId, command.expectedVersion(), command.actorId(), command.correlationId());
    }

    @PostMapping(value = "/tenant/changes/{changeId}/approve", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TenantFeatureActivationDtos.TenantEnrollment approveTenant(
            @PathVariable UUID changeId,
            @Valid @RequestBody TenantFeatureActivationDtos.TenantVersionedCommand command) {
        return service.approveTenant(
                changeId, command.expectedVersion(), command.actorId(), command.correlationId());
    }

    @PostMapping(value = "/tenant/changes/{changeId}/activate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TenantFeatureActivationDtos.TenantEnrollment activateTenant(
            @PathVariable UUID changeId,
            @Valid @RequestBody TenantFeatureActivationDtos.TenantActivationCommand command) {
        return service.activateTenant(
                changeId, command.expectedVersion(), command.actorId(), command.receiptId(),
                command.correlationId());
    }

    @PostMapping(value = "/tenant/changes/{changeId}/revoke", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TenantFeatureActivationDtos.TenantEnrollment revokeTenant(
            @PathVariable UUID changeId,
            @Valid @RequestBody TenantFeatureActivationDtos.TenantRevocationCommand command) {
        return service.revokeTenant(
                changeId, command.expectedVersion(), command.actorId(), command.reason(),
                command.correlationId());
    }
}
