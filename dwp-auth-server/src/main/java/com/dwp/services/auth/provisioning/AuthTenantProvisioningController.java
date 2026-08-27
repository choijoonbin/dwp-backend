package com.dwp.services.auth.provisioning;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.provisioning.ProviderTenantCommand;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;

import java.util.UUID;

@RestController
public class AuthTenantProvisioningController {

    private final AuthTenantProvisioningService service;

    public AuthTenantProvisioningController(AuthTenantProvisioningService service) {
        this.service = service;
    }

    @PostMapping("/internal/provider/v1/tenants")
    public AuthTenantProvisioningDtos.ProvisionTenantResponse provision(
            @Valid @RequestBody AuthTenantProvisioningDtos.ProvisionTenantRequest request) {
        return service.provision(request);
    }

    @PatchMapping("/internal/provider/v1/tenants/{providerTenantId}/lifecycle")
    public AuthTenantProvisioningDtos.ProvisionTenantResponse lifecycle(
            @PathVariable UUID providerTenantId,
            @Valid @RequestBody AuthTenantProvisioningDtos.UpdateLifecycleRequest request) {
        return service.updateLifecycle(providerTenantId, request);
    }

    @PutMapping("/internal/provider/v1/tenants/{providerTenantId}/entitlements")
    public AuthTenantProvisioningDtos.ProvisionTenantResponse replaceEntitlements(
            @PathVariable UUID providerTenantId,
            @Valid @RequestBody AuthTenantProvisioningDtos.ReplaceEntitlementsRequest request) {
        return service.replaceEntitlements(providerTenantId, request);
    }

    @PostMapping("/internal/provider/v1/tenants/{providerTenantId}/commands")
    public ProviderTenantCommand.Receipt command(
            @PathVariable UUID providerTenantId,
            @RequestBody ProviderTenantCommand.Request request) {
        return service.command(providerTenantId, request);
    }

    @PostMapping("/internal/provider/v1/tenants/{providerTenantId}/administrator-invitations")
    @ApiResponses(@io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "Provider-origin administrator invitation issuance is disabled until "
                    + "customer-owned out-of-band delivery is configured.",
            content = @Content(schema = @Schema(
                    implementation = AuthTenantProvisioningDtos.AdministratorInvitationConflictError.class))))
    public ApiResponse<Void> invitation(
            @PathVariable UUID providerTenantId,
            @Valid @RequestBody AuthTenantProvisioningDtos.IssueInvitationRequest request) {
        service.issueInvitation(providerTenantId, request);
        return ApiResponse.success();
    }

    @GetMapping("/auth/activations/{token}")
    public ApiResponse<AuthTenantProvisioningDtos.ActivationSummary> activation(
            @PathVariable String token) {
        return ApiResponse.success(service.activation(token));
    }

    @PostMapping("/auth/activations/{token}")
    public ApiResponse<AuthTenantProvisioningDtos.ActivateAccountResponse> activate(
            @PathVariable String token,
            @Valid @RequestBody AuthTenantProvisioningDtos.ActivateAccountRequest request) {
        return ApiResponse.success(service.activate(token, request));
    }
}
