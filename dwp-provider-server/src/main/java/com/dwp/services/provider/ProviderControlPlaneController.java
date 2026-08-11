package com.dwp.services.provider;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.provider.support.ProviderSupportAccessService;
import com.dwp.services.provider.support.ProviderSupportCookie;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/admin")
public class ProviderControlPlaneController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final ProviderControlPlaneService service;
    private final ProviderSupportAccessService supportAccessService;
    private final boolean supportCookieSecure;

    public ProviderControlPlaneController(
            ProviderControlPlaneService service,
            ProviderSupportAccessService supportAccessService,
            @Value("${dwp.provider.support-cookie-secure:true}") boolean supportCookieSecure) {
        this.service = service;
        this.supportAccessService = supportAccessService;
        this.supportCookieSecure = supportCookieSecure;
    }

    @GetMapping("/me")
    public ApiResponse<ProviderDtos.OperatorProfile> me() {
        return ApiResponse.success(service.operatorProfile());
    }

    @GetMapping("/overview")
    public ApiResponse<ProviderDtos.EstateOverview> overview() {
        return ApiResponse.success(service.overview());
    }

    @GetMapping("/command-center")
    public ApiResponse<ProviderDtos.CommandCenter> commandCenter() {
        return ApiResponse.success(service.commandCenter());
    }

    @GetMapping("/service-health")
    public ApiResponse<ProviderDtos.ServiceHealthOverview> serviceHealth() {
        return ApiResponse.success(service.serviceHealth());
    }

    @GetMapping("/reliability-control")
    public ApiResponse<ProviderDtos.ReliabilityControlOverview> reliabilityControl() {
        return ApiResponse.success(service.reliabilityControl());
    }

    @GetMapping("/commercial")
    public ApiResponse<ProviderDtos.CommercialOverview> commercial() {
        return ApiResponse.success(service.commercialOverview());
    }

    @GetMapping("/regions")
    public ApiResponse<List<ProviderDtos.RegionSummary>> regions() {
        return ApiResponse.success(service.regions());
    }

    @GetMapping("/tenants")
    public ApiResponse<ProviderDtos.PageResult<ProviderDtos.TenantSummary>> tenants(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String state,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(service.tenants(query, state, page, size));
    }

    @GetMapping("/tenants/{tenantId}")
    public ApiResponse<ProviderDtos.TenantSummary> tenant(@PathVariable UUID tenantId) {
        return ApiResponse.success(service.tenant(tenantId));
    }

    @PatchMapping("/tenants/{tenantId}/lifecycle")
    public ApiResponse<ProviderDtos.TenantSummary> lifecycle(
            @PathVariable UUID tenantId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody ProviderDtos.LifecycleRequest request) {
        return ApiResponse.success(service.lifecycle(tenantId, correlationId, request));
    }

    @PutMapping("/tenants/{tenantId}/entitlements")
    public ApiResponse<ProviderDtos.TenantSummary> replaceEntitlements(
            @PathVariable UUID tenantId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody ProviderDtos.ReplaceEntitlementsRequest request) {
        return ApiResponse.success(service.replaceEntitlements(tenantId, correlationId, request));
    }

    @GetMapping("/entitlements")
    public ApiResponse<List<ProviderDtos.EntitlementSummary>> entitlements() {
        return ApiResponse.success(service.entitlementCatalog());
    }

    @PostMapping("/onboarding-plans")
    public ApiResponse<ProviderDtos.OperationSummary> previewOnboarding(
            @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody ProviderDtos.OnboardingPlanRequest request) {
        return ApiResponse.success(service.previewOnboarding(
                idempotencyKey, correlationId, request));
    }

    @PostMapping("/operations/{operationId}/execute")
    public ApiResponse<ProviderDtos.OperationSummary> execute(
            @PathVariable UUID operationId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody ProviderDtos.ExecuteOperationRequest request) {
        return ApiResponse.success(service.execute(operationId, correlationId, request));
    }

    @PostMapping("/operations/{operationId}/retry")
    public ApiResponse<ProviderDtos.OperationSummary> retry(
            @PathVariable UUID operationId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody ProviderDtos.RetryOperationRequest request) {
        return ApiResponse.success(service.retry(operationId, correlationId, request));
    }

    @GetMapping("/operations")
    public ApiResponse<ProviderDtos.PageResult<ProviderDtos.OperationSummary>> operations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success(service.operations(page, size));
    }

    @GetMapping("/operation-approvals")
    public ApiResponse<List<ProviderDtos.OperationApprovalSummary>> operationApprovals(
            @RequestParam(required = false) String state) {
        return ApiResponse.success(service.operationApprovals(state));
    }

    @PostMapping("/operation-approvals/{approvalId}/decision")
    public ApiResponse<ProviderDtos.OperationApprovalSummary> decideOperationApproval(
            @PathVariable UUID approvalId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody ProviderDtos.DecideOperationApprovalRequest request) {
        return ApiResponse.success(service.decideOperationApproval(approvalId, correlationId, request));
    }

    @PostMapping("/incidents")
    public ApiResponse<ProviderDtos.ServiceIncidentSummary> createIncident(
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody ProviderDtos.CreateIncidentRequest request) {
        return ApiResponse.success(service.createIncident(correlationId, request));
    }

    @PatchMapping("/incidents/{incidentId}")
    public ApiResponse<ProviderDtos.ServiceIncidentSummary> updateIncident(
            @PathVariable UUID incidentId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody ProviderDtos.UpdateIncidentRequest request) {
        return ApiResponse.success(service.updateIncident(incidentId, correlationId, request));
    }

    @PostMapping("/maintenance-windows")
    public ApiResponse<ProviderDtos.MaintenanceWindowSummary> createMaintenanceWindow(
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody ProviderDtos.CreateMaintenanceWindowRequest request) {
        return ApiResponse.success(service.createMaintenanceWindow(correlationId, request));
    }

    @PostMapping("/tenants/{tenantId}/domains")
    public ApiResponse<ProviderDtos.DomainChallenge> createDomain(
            @PathVariable UUID tenantId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody ProviderDtos.CreateDomainRequest request) {
        return ApiResponse.success(service.createDomain(tenantId, correlationId, request));
    }

    @GetMapping("/tenants/{tenantId}/domains/{domainId}/challenge")
    public ApiResponse<ProviderDtos.DomainChallenge> domainChallenge(
            @PathVariable UUID tenantId,
            @PathVariable UUID domainId) {
        return ApiResponse.success(service.domainChallenge(tenantId, domainId));
    }

    @PostMapping("/tenants/{tenantId}/domains/{domainId}/verify")
    public ApiResponse<ProviderDtos.TenantDomainSummary> verifyDomain(
            @PathVariable UUID tenantId,
            @PathVariable UUID domainId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody ProviderDtos.VerifyDomainRequest request) {
        return ApiResponse.success(service.verifyDomain(
                tenantId, domainId, correlationId, request));
    }

    @PostMapping("/tenants/{tenantId}/administrators/{administratorId}/invitations")
    public ApiResponse<ProviderDtos.AdministratorInvitation> issueAdministratorInvitation(
            @PathVariable UUID tenantId,
            @PathVariable UUID administratorId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody ProviderDtos.IssueAdministratorInvitationRequest request) {
        return ApiResponse.success(service.issueAdministratorInvitation(
                tenantId, administratorId, correlationId, request));
    }

    @GetMapping("/support-sessions")
    public ApiResponse<List<ProviderDtos.SupportSessionSummary>> supportSessions(
            @RequestParam(required = false) UUID tenantId) {
        return ApiResponse.success(service.supportSessions(tenantId));
    }

    @GetMapping("/support-scopes")
    public ApiResponse<List<ProviderDtos.SupportScopeSummary>> supportScopes() {
        return ApiResponse.success(service.supportScopes());
    }

    @PostMapping("/support-sessions")
    public ApiResponse<ProviderDtos.SupportSessionSummary> createSupportSession(
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody ProviderDtos.CreateSupportSessionRequest request,
            HttpServletResponse response) {
        ProviderDtos.SupportSessionGrant grant = service.createSupportSession(correlationId, request);
        ProviderSupportCookie.issue(
                grant.sessionToken(), grant.session().expiresAt(), supportCookieSecure)
                .forEach(cookie -> response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString()));
        return ApiResponse.success(grant.session());
    }

    @GetMapping("/support-session-context")
    public ApiResponse<ProviderDtos.SupportSessionContext> supportSessionContext(
            @CookieValue(value = ProviderSupportCookie.NAME, required = false) String supportSessionToken,
            HttpServletResponse response) {
        if (supportSessionToken == null || supportSessionToken.isBlank()) {
            return ApiResponse.success(null);
        }
        try {
            return ApiResponse.success(supportAccessService.inspect(supportSessionToken));
        } catch (com.dwp.core.exception.BaseException exception) {
            ProviderSupportCookie.clear(supportCookieSecure)
                    .forEach(cookie -> response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString()));
            return ApiResponse.success(null);
        }
    }

    @PostMapping("/support-sessions/{sessionId}/revoke")
    public ApiResponse<ProviderDtos.SupportSessionSummary> revokeSupportSession(
            @PathVariable UUID sessionId,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody ProviderDtos.RevokeSupportSessionRequest request,
            HttpServletResponse response) {
        ProviderDtos.SupportSessionSummary session =
                service.revokeSupportSession(sessionId, correlationId, request);
        ProviderSupportCookie.clear(supportCookieSecure)
                .forEach(cookie -> response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString()));
        return ApiResponse.success(session);
    }

    @GetMapping("/audit-events")
    public ApiResponse<List<ProviderDtos.AuditEventSummary>> auditEvents(
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(defaultValue = "200") int limit) {
        return ApiResponse.success(service.auditEvents(tenantId, limit));
    }

    @GetMapping("/audit-insights")
    public ApiResponse<ProviderDtos.AuditInsights> auditInsights() {
        return ApiResponse.success(service.auditInsights());
    }
}
