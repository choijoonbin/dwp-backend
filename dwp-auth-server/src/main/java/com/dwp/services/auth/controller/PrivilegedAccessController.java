package com.dwp.services.auth.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.dto.PrivilegedAccessDtos;
import com.dwp.services.auth.security.AuthenticatedUserResolver;
import com.dwp.services.auth.security.TenantContextResolver;
import com.dwp.services.auth.service.DelegatedAdminScopeService;
import com.dwp.services.auth.service.PrivilegedAccessService;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/auth/admin/access/privileged")
public class PrivilegedAccessController {

    private static final String TENANT_HEADER = "X-Tenant-ID";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final PrivilegedAccessService service;
    private final DelegatedAdminScopeService delegatedScopeService;

    public PrivilegedAccessController(
            PrivilegedAccessService service,
            DelegatedAdminScopeService delegatedScopeService) {
        this.service = service;
        this.delegatedScopeService = delegatedScopeService;
    }

    @GetMapping("/policies")
    public ApiResponse<List<PrivilegedAccessDtos.PolicySummary>> policies(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader) {
        return ApiResponse.success(service.policies(tenantAdmin(authentication, tenantHeader)));
    }

    @PutMapping("/policies/{policyId}")
    public ApiResponse<PrivilegedAccessDtos.PolicySummary> updatePolicy(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable Long policyId,
            @Valid @RequestBody PrivilegedAccessDtos.UpdatePolicyRequest request) {
        Long tenantId = tenantAdmin(authentication, tenantHeader);
        return ApiResponse.success(service.updatePolicy(
                tenantId, AuthenticatedUserResolver.requireUserId(authentication),
                correlationId, policyId, request));
    }

    @GetMapping("/eligibilities")
    public ApiResponse<List<PrivilegedAccessDtos.EligibilitySummary>> eligibilities(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader) {
        return ApiResponse.success(service.eligibilities(tenantAdmin(authentication, tenantHeader)));
    }

    @GetMapping("/me/eligibilities")
    public ApiResponse<List<PrivilegedAccessDtos.EligibilitySummary>> myEligibilities(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader) {
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(service.myEligibilities(
                tenantId, AuthenticatedUserResolver.requireUserId(authentication)));
    }

    @PostMapping("/eligibilities")
    public ApiResponse<PrivilegedAccessDtos.EligibilitySummary> createEligibility(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody PrivilegedAccessDtos.CreateEligibilityRequest request) {
        Long tenantId = tenantAdmin(authentication, tenantHeader);
        return ApiResponse.success(service.createEligibility(
                tenantId, AuthenticatedUserResolver.requireUserId(authentication),
                correlationId, request));
    }

    @PatchMapping("/eligibilities/{eligibilityId}/revoke")
    public ApiResponse<PrivilegedAccessDtos.EligibilitySummary> revokeEligibility(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID eligibilityId,
            @RequestParam Long version) {
        Long tenantId = tenantAdmin(authentication, tenantHeader);
        return ApiResponse.success(service.revokeEligibility(
                tenantId, AuthenticatedUserResolver.requireUserId(authentication),
                correlationId, eligibilityId, version));
    }

    @GetMapping("/requests")
    public ApiResponse<List<PrivilegedAccessDtos.RequestSummary>> requests(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader) {
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        return ApiResponse.success(service.requests(
                tenantId, actorId,
                AuthenticatedUserResolver.hasTenantAdminRole(authentication)));
    }

    @GetMapping("/me/requests")
    public ApiResponse<List<PrivilegedAccessDtos.RequestSummary>> myRequests(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader) {
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        Long actorId = AuthenticatedUserResolver.requireUserId(authentication);
        return ApiResponse.success(service.requests(tenantId, actorId, false));
    }

    @PostMapping("/requests")
    public ApiResponse<PrivilegedAccessDtos.RequestSummary> requestActivation(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody PrivilegedAccessDtos.ActivationRequest request) {
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(service.requestActivation(
                tenantId, AuthenticatedUserResolver.requireUserId(authentication),
                assuranceLevel(authentication), correlationId, request));
    }

    @PostMapping("/requests/{requestId}/decision")
    public ApiResponse<PrivilegedAccessDtos.RequestSummary> decide(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID requestId,
            @Valid @RequestBody PrivilegedAccessDtos.ApprovalDecisionRequest request) {
        Long tenantId = tenantAdmin(authentication, tenantHeader);
        return ApiResponse.success(service.decide(
                tenantId, AuthenticatedUserResolver.requireUserId(authentication),
                correlationId, requestId, request));
    }

    @PostMapping("/requests/{requestId}/revoke")
    public ApiResponse<PrivilegedAccessDtos.RequestSummary> revoke(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID requestId,
            @Valid @RequestBody PrivilegedAccessDtos.RevokeRequest request) {
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(service.revoke(
                tenantId, AuthenticatedUserResolver.requireUserId(authentication),
                AuthenticatedUserResolver.hasTenantAdminRole(authentication),
                correlationId, requestId, request));
    }

    @GetMapping("/emergency-principals")
    public ApiResponse<List<PrivilegedAccessDtos.EmergencyPrincipalSummary>>
            emergencyPrincipals(
                    Authentication authentication,
                    @RequestHeader(value = TENANT_HEADER, required = false)
                            String tenantHeader) {
        return ApiResponse.success(service.emergencyPrincipals(
                tenantAdmin(authentication, tenantHeader)));
    }

    @PostMapping("/emergency-principals")
    public ApiResponse<PrivilegedAccessDtos.EmergencyPrincipalSummary>
            registerEmergencyPrincipal(
                    Authentication authentication,
                    @RequestHeader(value = TENANT_HEADER, required = false)
                            String tenantHeader,
                    @RequestHeader(value = CORRELATION_HEADER, required = false)
                            String correlationId,
                    @Valid @RequestBody
                            PrivilegedAccessDtos.RegisterEmergencyPrincipalRequest request) {
        Long tenantId = tenantAdmin(authentication, tenantHeader);
        return ApiResponse.success(service.registerEmergencyPrincipal(
                tenantId, AuthenticatedUserResolver.requireUserId(authentication),
                correlationId, request));
    }

    @GetMapping("/delegated-scopes")
    public ApiResponse<List<PrivilegedAccessDtos.DelegatedScopeSummary>> delegatedScopes(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader) {
        return ApiResponse.success(delegatedScopeService.scopes(
                tenantAdmin(authentication, tenantHeader)));
    }

    @PostMapping("/delegated-scopes")
    public ApiResponse<PrivilegedAccessDtos.DelegatedScopeSummary> createDelegatedScope(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody PrivilegedAccessDtos.CreateDelegatedScopeRequest request) {
        Long tenantId = tenantAdmin(authentication, tenantHeader);
        return ApiResponse.success(delegatedScopeService.create(
                tenantId, AuthenticatedUserResolver.requireUserId(authentication),
                correlationId, request));
    }

    @PatchMapping("/delegated-scopes/{scopeId}/revoke")
    public ApiResponse<PrivilegedAccessDtos.DelegatedScopeSummary> revokeDelegatedScope(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @PathVariable UUID scopeId,
            @RequestParam Long version) {
        Long tenantId = tenantAdmin(authentication, tenantHeader);
        return ApiResponse.success(delegatedScopeService.revoke(
                tenantId, AuthenticatedUserResolver.requireUserId(authentication),
                correlationId, scopeId, version));
    }

    private Long tenantAdmin(Authentication authentication, String tenantHeader) {
        AuthenticatedUserResolver.requireTenantAdmin(authentication);
        return TenantContextResolver.requireTenantId(tenantHeader, authentication);
    }

    private String assuranceLevel(Authentication authentication) {
        if (!(authentication.getPrincipal() instanceof Jwt jwt)) return "SESSION";
        Object claim = jwt.getClaims().get("amr");
        if (!(claim instanceof Collection<?> methods)) return "SESSION";
        List<String> normalized = methods.stream()
                .map(String::valueOf)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .toList();
        if (normalized.stream().anyMatch(
                value -> List.of("fido", "fido2", "webauthn", "hwk").contains(value))) {
            return "PHISHING_RESISTANT";
        }
        return normalized.stream().anyMatch(
                value -> List.of("mfa", "otp", "sms", "oath").contains(value))
                ? "MFA"
                : "SESSION";
    }
}
