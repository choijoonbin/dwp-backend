package com.dwp.services.provider;

import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.commercial.ProviderCommercialRenewalRepository;
import com.dwp.services.provider.entitlement.EntitlementRepository;
import com.dwp.services.provider.entitlement.TenantEntitlementRepository;
import com.dwp.services.provider.operation.ProviderOperationRepository;
import com.dwp.services.provider.operation.ProviderOperationStepAttemptRepository;
import com.dwp.services.provider.operation.ProviderOperationStepRepository;
import com.dwp.services.provider.provisioning.ProviderProvisioningOrchestrator;
import com.dwp.services.provider.provisioning.TenantMutationOrchestrator;
import com.dwp.services.provider.support.CustomerApprovalEvidencePolicy;
import com.dwp.services.provider.support.ProviderSupportActivationGate;
import com.dwp.services.provider.support.ProviderSupportRequestRepository;
import com.dwp.services.provider.support.ProviderSupportRequestSecurityPolicy;
import com.dwp.services.provider.support.ProviderSupportSessionLifecycleService;
import com.dwp.services.provider.support.ProviderSupportSessionRepository;
import com.dwp.services.provider.tenant.ProviderTenantRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ProviderControlPlaneService {

    private final ProviderEstateControl estate;
    private final ProviderCommercialControl commercial;
    private final ProviderProvisioningControl provisioning;
    private final ProviderReliabilityControl reliability;
    private final ProviderSupportControl support;

    public ProviderControlPlaneService(
            ProviderTenantRepository tenantRepository,
            EntitlementRepository entitlementRepository,
            TenantEntitlementRepository tenantEntitlementRepository,
            ProviderOperationRepository operationRepository,
            ProviderOperationStepRepository stepRepository,
            ProviderOperationStepAttemptRepository attemptRepository,
            ProviderEstateRepository estateRepository,
            ProviderOperationsRepository operationsRepository,
            ProviderCommercialRenewalRepository commercialRenewalRepository,
            ProviderSupportRequestRepository supportRequestRepository,
            ProviderSupportRequestSecurityPolicy supportRequestSecurityPolicy,
            ProviderSupportSessionRepository supportSessionRepository,
            ProviderSupportSessionLifecycleService supportSessionLifecycleService,
            ProviderSupportActivationGate supportActivationGate,
            CustomerApprovalEvidencePolicy customerApprovalEvidencePolicy,
            ProviderProvisioningOrchestrator orchestrator,
            TenantMutationOrchestrator tenantMutationOrchestrator,
            ProviderAuditService auditService,
            ObjectMapper objectMapper) {
        ProviderControlPlaneContext context = new ProviderControlPlaneContext(
                tenantRepository, entitlementRepository, tenantEntitlementRepository,
                operationRepository, stepRepository, attemptRepository,
                estateRepository, objectMapper);
        this.estate = new ProviderEstateControl(
                tenantRepository, entitlementRepository, operationRepository,
                estateRepository, operationsRepository, context);
        this.commercial = new ProviderCommercialControl(
                operationsRepository, commercialRenewalRepository, auditService, context);
        this.provisioning = new ProviderProvisioningControl(
                tenantRepository, operationRepository, stepRepository,
                estateRepository, operationsRepository, orchestrator,
                tenantMutationOrchestrator, auditService, context);
        this.reliability = new ProviderReliabilityControl(
                operationRepository, stepRepository, estateRepository,
                operationsRepository, auditService, context);
        this.support = new ProviderSupportControl(
                operationsRepository, supportRequestRepository, supportRequestSecurityPolicy,
                supportSessionRepository, supportSessionLifecycleService, supportActivationGate,
                customerApprovalEvidencePolicy, auditService, context);
    }

    public ProviderDtos.OperatorProfile operatorProfile() {
        return estate.operatorProfile();
    }

    public ProviderDtos.EstateOverview overview() {
        return estate.overview();
    }

    public ProviderDtos.CommandCenter commandCenter() {
        return estate.commandCenter();
    }

    public ProviderDtos.ServiceHealthOverview serviceHealth() {
        return estate.serviceHealth();
    }

    public ProviderDtos.ReliabilityControlOverview reliabilityControl() {
        return estate.reliabilityControl();
    }

    public ProviderDtos.CommercialOverview commercialOverview() {
        return commercial.commercialOverview();
    }

    public List<ProviderDtos.SubscriptionRenewalRevision> subscriptionRenewals() {
        return commercial.subscriptionRenewals();
    }

    @Transactional
    public ProviderDtos.SubscriptionRenewalRevision createSubscriptionRenewal(
            String correlationId,
            ProviderDtos.CreateSubscriptionRenewalRequest request) {
        return commercial.createSubscriptionRenewal(correlationId, request);
    }

    @Transactional
    public ProviderDtos.SubscriptionRenewalRevision decideSubscriptionRenewal(
            UUID revisionId,
            String correlationId,
            ProviderDtos.DecideSubscriptionRenewalRequest request) {
        return commercial.decideSubscriptionRenewal(revisionId, correlationId, request);
    }

    @Transactional
    public ProviderDtos.SubscriptionRenewalRevision publishSubscriptionRenewal(
            UUID revisionId,
            String correlationId,
            ProviderDtos.PublishSubscriptionRenewalRequest request) {
        return commercial.publishSubscriptionRenewal(revisionId, correlationId, request);
    }

    public ProviderDtos.AuditInsights auditInsights() {
        return estate.auditInsights();
    }

    @Transactional(readOnly = true)
    public ProviderDtos.PageResult<ProviderDtos.TenantSummary> tenants(
            String query,
            String state,
            String region,
            String serviceTier,
            String isolationModel,
            int requestedPage,
            int requestedSize) {
        return estate.tenants(
                query, state, region, serviceTier, isolationModel, requestedPage, requestedSize);
    }

    @Transactional(readOnly = true)
    public ProviderDtos.TenantSummary tenant(UUID tenantId) {
        return estate.tenant(tenantId);
    }

    @Transactional(readOnly = true)
    public List<ProviderDtos.RegionSummary> regions() {
        return estate.regions();
    }

    @Transactional(readOnly = true)
    public List<ProviderDtos.EntitlementSummary> entitlementCatalog() {
        return estate.entitlementCatalog();
    }

    @Transactional(readOnly = true)
    public List<ProviderDtos.SupportScopeSummary> supportScopes() {
        return estate.supportScopes();
    }

    @Transactional
    public ProviderDtos.OperationSummary previewOnboarding(
            String idempotencyKey,
            String correlationId,
            ProviderDtos.OnboardingPlanRequest request) {
        return provisioning.previewOnboarding(idempotencyKey, correlationId, request);
    }

    public ProviderDtos.OperationSummary execute(
            UUID operationId,
            String correlationId,
            ProviderDtos.ExecuteOperationRequest request) {
        return provisioning.execute(operationId, correlationId, request);
    }

    public ProviderDtos.OperationSummary retry(
            UUID operationId,
            String correlationId,
            ProviderDtos.RetryOperationRequest request) {
        return provisioning.retry(operationId, correlationId, request);
    }

    @Transactional(readOnly = true)
    public ProviderDtos.PageResult<ProviderDtos.OperationSummary> operations(int page, int size) {
        return estate.operations(page, size);
    }

    public List<ProviderDtos.OperationApprovalSummary> operationApprovals(String state) {
        return estate.operationApprovals(state);
    }

    @Transactional
    public ProviderDtos.OperationApprovalSummary decideOperationApproval(
            UUID approvalId,
            String correlationId,
            ProviderDtos.DecideOperationApprovalRequest request) {
        return reliability.decideOperationApproval(approvalId, correlationId, request);
    }

    @Transactional
    public ProviderDtos.ServiceIncidentSummary createIncident(
            String correlationId,
            ProviderDtos.CreateIncidentRequest request) {
        return reliability.createIncident(correlationId, request);
    }

    @Transactional
    public ProviderDtos.ServiceIncidentSummary updateIncident(
            UUID incidentId,
            String correlationId,
            ProviderDtos.UpdateIncidentRequest request) {
        return reliability.updateIncident(incidentId, correlationId, request);
    }

    @Transactional
    public ProviderDtos.MaintenanceWindowSummary createMaintenanceWindow(
            String correlationId,
            ProviderDtos.CreateMaintenanceWindowRequest request) {
        return reliability.createMaintenanceWindow(correlationId, request);
    }

    public ProviderDtos.TenantSummary lifecycle(
            UUID tenantId,
            String correlationId,
            ProviderDtos.LifecycleRequest request) {
        return provisioning.lifecycle(tenantId, correlationId, request);
    }

    public ProviderDtos.TenantSummary replaceEntitlements(
            UUID tenantId,
            String correlationId,
            ProviderDtos.ReplaceEntitlementsRequest request) {
        return provisioning.replaceEntitlements(tenantId, correlationId, request);
    }

    @Transactional
    public ProviderDtos.DomainChallenge createDomain(
            UUID tenantId,
            String correlationId,
            ProviderDtos.CreateDomainRequest request) {
        return provisioning.createDomain(tenantId, correlationId, request);
    }

    public ProviderDtos.DomainChallenge domainChallenge(UUID tenantId, UUID domainId) {
        return provisioning.domainChallenge(tenantId, domainId);
    }

    @Transactional
    public ProviderDtos.TenantDomainSummary verifyDomain(
            UUID tenantId,
            UUID domainId,
            String correlationId,
            ProviderDtos.VerifyDomainRequest request) {
        return provisioning.verifyDomain(tenantId, domainId, correlationId, request);
    }

    public void issueAdministratorInvitation(
            UUID tenantId,
            UUID administratorId,
            String correlationId,
            ProviderDtos.IssueAdministratorInvitationRequest request) {
        provisioning.issueAdministratorInvitation(
                tenantId, administratorId, correlationId, request);
    }

    @Transactional
    public ProviderDtos.SupportSessionGrant createSupportSession(
            String correlationId,
            ProviderDtos.CreateSupportSessionRequest request) {
        return support.createSupportSession(correlationId, request);
    }

    @Transactional
    public ProviderDtos.SupportAccessRequestSummary createSupportAccessRequest(
            String correlationId,
            ProviderDtos.CreateSupportAccessRequest request) {
        return support.createSupportAccessRequest(correlationId, request);
    }

    @Transactional
    public ProviderDtos.SupportAccessRequestSummary decideSupportAccessRequest(
            UUID requestId,
            String correlationId,
            ProviderDtos.DecideSupportAccessRequest request) {
        return support.decideSupportAccessRequest(requestId, correlationId, request);
    }

    @Transactional
    public ProviderDtos.SupportSessionGrant activateSupportAccessRequest(
            UUID requestId,
            String correlationId,
            ProviderDtos.ActivateSupportAccessRequest request) {
        return support.activateSupportAccessRequest(requestId, correlationId, request);
    }

    @Transactional
    public ProviderDtos.SupportAccessRequestSummary cancelSupportAccessRequest(
            UUID requestId,
            String correlationId,
            ProviderDtos.CancelSupportAccessRequest request) {
        return support.cancelSupportAccessRequest(requestId, correlationId, request);
    }

    @Transactional
    public ProviderDtos.SupportAccessRequestSummary reviewSupportAccessRequest(
            UUID requestId,
            String correlationId,
            ProviderDtos.ReviewSupportAccessRequest request) {
        return support.reviewSupportAccessRequest(requestId, correlationId, request);
    }

    @Transactional
    public ProviderDtos.SupportSessionSummary revokeSupportSession(
            UUID sessionId,
            String correlationId,
            ProviderDtos.RevokeSupportSessionRequest request) {
        return support.revokeSupportSession(sessionId, correlationId, request);
    }

    public List<ProviderDtos.AuditEventSummary> auditEvents(UUID tenantId, int limit) {
        return estate.auditEvents(tenantId, limit);
    }
}
