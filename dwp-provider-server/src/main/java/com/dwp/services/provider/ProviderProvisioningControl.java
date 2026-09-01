package com.dwp.services.provider;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.entitlement.Entitlement;
import com.dwp.services.provider.operation.ProviderOperation;
import com.dwp.services.provider.operation.ProviderOperationRepository;
import com.dwp.services.provider.operation.ProviderOperationStep;
import com.dwp.services.provider.operation.ProviderOperationStepRepository;
import com.dwp.services.provider.provisioning.ProviderProvisioningOrchestrator;
import com.dwp.services.provider.provisioning.TenantMutationOrchestrator;
import com.dwp.services.provider.security.ProviderRequestContext;
import com.dwp.services.provider.tenant.ProviderTenant;
import com.dwp.services.provider.tenant.ProviderTenantRepository;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class ProviderProvisioningControl {

    private final ProviderTenantRepository tenantRepository;
    private final ProviderOperationRepository operationRepository;
    private final ProviderOperationStepRepository stepRepository;
    private final ProviderEstateRepository estateRepository;
    private final ProviderOperationsRepository operationsRepository;
    private final ProviderProvisioningOrchestrator orchestrator;
    private final TenantMutationOrchestrator tenantMutationOrchestrator;
    private final ProviderAuditService auditService;
    private final ProviderControlPlaneContext context;

    ProviderProvisioningControl(
            ProviderTenantRepository tenantRepository,
            ProviderOperationRepository operationRepository,
            ProviderOperationStepRepository stepRepository,
            ProviderEstateRepository estateRepository,
            ProviderOperationsRepository operationsRepository,
            ProviderProvisioningOrchestrator orchestrator,
            TenantMutationOrchestrator tenantMutationOrchestrator,
            ProviderAuditService auditService,
            ProviderControlPlaneContext context) {
        this.tenantRepository = tenantRepository;
        this.operationRepository = operationRepository;
        this.stepRepository = stepRepository;
        this.estateRepository = estateRepository;
        this.operationsRepository = operationsRepository;
        this.orchestrator = orchestrator;
        this.tenantMutationOrchestrator = tenantMutationOrchestrator;
        this.auditService = auditService;
        this.context = context;
    }

    ProviderDtos.OperationSummary previewOnboarding(
            String idempotencyKey,
            String correlationId,
            ProviderDtos.OnboardingPlanRequest request) {
        ProviderRequestContext.requirePermission("TENANT_WRITE");
        String normalizedKey = context.normalizeIdempotencyKey(idempotencyKey);
        List<Entitlement> entitlements = context.requireEntitlements(request.entitlementKeys());
        context.requireRegion(request.dataRegion());
        Map<String, Object> plan = onboardingPlan(request, entitlements);
        String planJson = context.json(plan);
        String planHash = context.sha256(planJson);
        ProviderOperation existing = operationRepository.findByIdempotencyKey(normalizedKey).orElse(null);
        if (existing != null) {
            if (!context.constantTimeEquals(existing.getPlanHash(), planHash)) {
                throw new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "The idempotency key was used with a different provider plan.");
            }
            return context.operationSummary(existing);
        }
        if (tenantRepository.findByTenantKey(request.tenantKey()).isPresent()) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "The tenant key already exists.");
        }
        estateRepository.organizationIdByKey(request.organizationKey()).ifPresent(organizationId -> {
            if (estateRepository.environmentExists(organizationId, request.environmentKey())) {
                throw new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "The organization environment already exists.");
            }
        });
        ProviderOperation operation = ProviderOperation.builder()
                .operationType("TENANT_ONBOARD")
                .idempotencyKey(normalizedKey)
                .lifecycleState("PREVIEWED")
                .riskTier("REGULATED".equals(request.serviceTier()) ? "L3" : "L2")
                .requestedBy(ProviderRequestContext.require().operatorId())
                .justification(request.justification().trim())
                .planHash(planHash)
                .plan(planJson)
                .build();
        try {
            operation = operationRepository.saveAndFlush(operation);
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "Provider plan already exists.", exception);
        }
        stepRepository.saveAll(onboardingSteps(operation.getOperationId()));
        operationsRepository.ensureOperationApproval(operation);
        auditService.success(
                "provider.tenant-onboarding.previewed", "PROVIDER_OPERATION",
                operation.getOperationId().toString(), correlationId,
                Map.of(
                        "planHash", planHash,
                        "tenantKey", request.tenantKey(),
                        "organizationKey", request.organizationKey(),
                        "riskTier", operation.getRiskTier()));
        return context.operationSummary(operation);
    }

    ProviderDtos.OperationSummary execute(
            UUID operationId,
            String correlationId,
            ProviderDtos.ExecuteOperationRequest request) {
        ProviderOperation current = context.requireOperation(operationId);
        if ("L3".equals(current.getRiskTier()) && !operationsRepository.operationApproved(operationId)) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "All required approvals must be completed before this high-risk operation can run.");
        }
        ProviderOperation operation = orchestrator.execute(
                operationId, request.planHash(), request.version(), false, correlationId);
        return context.operationSummary(operation);
    }

    ProviderDtos.OperationSummary retry(
            UUID operationId,
            String correlationId,
            ProviderDtos.RetryOperationRequest request) {
        ProviderOperation current = context.requireOperation(operationId);
        ProviderOperation operation = orchestrator.execute(
                operationId, null, request.version(), true, correlationId);
        auditService.success(
                "provider.operation.retried", "PROVIDER_OPERATION", operationId.toString(),
                operation.getProviderTenantId(),
                operation.getProviderTenantId() == null
                        ? null
                        : context.requireTenant(operation.getProviderTenantId()).getOrganizationId(),
                correlationId,
                Map.of(
                        "justification", request.justification(),
                        "previousState", current.getLifecycleState(),
                        "resultState", operation.getLifecycleState()));
        return context.operationSummary(operation);
    }

    ProviderDtos.TenantSummary lifecycle(
            UUID tenantId,
            String correlationId,
            ProviderDtos.LifecycleRequest request) {
        ProviderRequestContext.requirePermission("TENANT_WRITE");
        ProviderTenant tenant = context.requireTenant(tenantId);
        if ("ACTIVE".equals(request.state())) context.requireOnboardingReady(tenant);
        tenantMutationOrchestrator.lifecycle(
                tenant, request.version(), request.state(), request.justification(), correlationId);
        return context.tenantSummary(context.requireTenant(tenantId));
    }

    ProviderDtos.TenantSummary replaceEntitlements(
            UUID tenantId,
            String correlationId,
            ProviderDtos.ReplaceEntitlementsRequest request) {
        ProviderRequestContext.requirePermission("ENTITLEMENT_WRITE");
        ProviderTenant tenant = context.requireTenant(tenantId);
        context.requireOnboardingReady(tenant);
        List<Entitlement> entitlements = context.requireEntitlements(request.entitlementKeys());
        tenantMutationOrchestrator.replaceEntitlements(
                tenant, request.version(),
                entitlements.stream().map(Entitlement::getEntitlementKey).toList(),
                request.justification(), correlationId);
        return context.tenantSummary(context.requireTenant(tenantId));
    }

    ProviderDtos.DomainChallenge createDomain(
            UUID tenantId,
            String correlationId,
            ProviderDtos.CreateDomainRequest request) {
        ProviderRequestContext.requirePermission("TENANT_WRITE");
        ProviderTenant tenant = context.requireTenant(tenantId);
        String domainName = request.domainName().trim().toLowerCase(Locale.ROOT);
        String challenge = "dwp-verification=" + context.randomToken();
        UUID domainId;
        try {
            domainId = estateRepository.createDomain(
                    tenantId, domainName, request.domainType(), request.primaryDomain(),
                    challenge, context.sha256(challenge), ProviderRequestContext.require().operatorId());
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "The domain is already registered.", exception);
        }
        ProviderDtos.TenantDomainSummary domain = estateRepository.domains(tenantId).stream()
                .filter(item -> item.domainId().equals(domainId))
                .findFirst().orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        auditService.success(
                "provider.tenant-domain.created", "TENANT_DOMAIN", domainId.toString(),
                tenantId, tenant.getOrganizationId(), correlationId,
                Map.of("domainName", domainName, "primary", request.primaryDomain()));
        return new ProviderDtos.DomainChallenge(
                domain, "_dwp-verification." + domainName, "TXT", challenge);
    }

    ProviderDtos.DomainChallenge domainChallenge(UUID tenantId, UUID domainId) {
        ProviderRequestContext.requirePermission("ESTATE_READ");
        context.requireTenant(tenantId);
        ProviderEstateRepository.DomainRecord record = estateRepository.domainRecord(tenantId, domainId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        ProviderDtos.TenantDomainSummary domain = estateRepository.domains(tenantId).stream()
                .filter(item -> item.domainId().equals(domainId))
                .findFirst().orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        return new ProviderDtos.DomainChallenge(
                domain,
                "_dwp-verification." + record.domainName(),
                "TXT",
                record.recordValue());
    }

    ProviderDtos.TenantDomainSummary verifyDomain(
            UUID tenantId,
            UUID domainId,
            String correlationId,
            ProviderDtos.VerifyDomainRequest request) {
        ProviderRequestContext.requirePermission("TENANT_WRITE");
        ProviderTenant tenant = context.requireTenant(tenantId);
        ProviderEstateRepository.DomainRecord record = estateRepository.domainRecord(tenantId, domainId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        context.requireVersion(record.version(), request.version());
        boolean verified = "INTERNAL".equals(record.verificationMethod())
                || context.dnsTxtRecords("_dwp-verification." + record.domainName()).stream()
                .map(value -> value.replace("\"", "").trim())
                .map(context::sha256)
                .anyMatch(hash -> context.constantTimeEquals(hash, record.tokenHash()));
        estateRepository.markDomainChecked(
                tenantId, domainId, verified, ProviderRequestContext.require().operatorId());
        auditService.success(
                "provider.tenant-domain.verified", "TENANT_DOMAIN", domainId.toString(),
                tenantId, tenant.getOrganizationId(), correlationId,
                Map.of(
                        "domainName", record.domainName(),
                        "verified", verified,
                        "justification", request.justification()));
        return estateRepository.domains(tenantId).stream()
                .filter(item -> item.domainId().equals(domainId))
                .findFirst().orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    void issueAdministratorInvitation(
            UUID tenantId,
            UUID administratorId,
            String correlationId,
            ProviderDtos.IssueAdministratorInvitationRequest request) {
        ProviderRequestContext.requirePermission("TENANT_WRITE");
        ProviderTenant tenant = context.requireTenant(tenantId);
        if (!"READY".equals(tenant.getOnboardingState()) || tenant.getAuthTenantId() == null) {
            throw new BaseException(ErrorCode.INVALID_STATE, "Tenant onboarding must be ready first.");
        }
        ProviderEstateRepository.AdministratorRecord administrator =
                estateRepository.administrator(tenantId, administratorId)
                        .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (administrator.authUserId() == null) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The administrator is not linked to auth.");
        }
        auditService.denied(
                "provider.tenant-administrator.invitation-blocked", "TENANT_ADMINISTRATOR",
                administratorId.toString(), tenantId, tenant.getOrganizationId(), correlationId,
                Map.of(
                        "decision", "DENY",
                        "policyId", "CUSTOMER_OWNED_ADMIN_INVITATION_BOUNDARY_V1",
                        "reasonCode", "CUSTOMER_OWNED_DELIVERY_UNAVAILABLE",
                        "deliveryChannelConfigured", false,
                        "administratorLinkedToAuth", administrator.authUserId() != null));
        throw new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "Tenant administrator activation is disabled until customer-owned out-of-band delivery is configured.");
    }

    private Map<String, Object> onboardingPlan(
            ProviderDtos.OnboardingPlanRequest request,
            List<Entitlement> entitlements) {
        Map<String, Object> administrator = new LinkedHashMap<>();
        administrator.put("displayName", request.initialAdminDisplayName().trim());
        administrator.put("email", request.initialAdminEmail().trim().toLowerCase(Locale.ROOT));
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("contract", "dwp.provider.tenant-onboarding.v2");
        plan.put("organizationKey", request.organizationKey());
        plan.put("organizationName", request.organizationName().trim());
        plan.put("legalName", context.normalized(request.legalName()));
        plan.put("customerReference", context.normalized(request.customerReference()));
        plan.put("tenantKey", request.tenantKey());
        plan.put("displayName", request.displayName().trim());
        plan.put("environmentKey", request.environmentKey());
        plan.put("serviceTier", request.serviceTier());
        plan.put("dataRegion", request.dataRegion());
        plan.put("isolationModel", request.isolationModel());
        plan.put("defaultLocale", request.defaultLocale());
        plan.put("timeZone", request.timeZone());
        plan.put("primaryDomain", context.normalized(request.primaryDomain()));
        plan.put("initialAdministrator", administrator);
        plan.put("entitlements", entitlements.stream()
                .map(Entitlement::getEntitlementKey).sorted().toList());
        plan.put("steps", List.of(
                "CONTROL_RECORD", "AUTH_TENANT", "PLATFORM_TENANT",
                "PEOPLE_TENANT", "ASSET_STORAGE", "ACTIVATE_TENANT"));
        plan.put("executionModel", "IDEMPOTENT_SAGA");
        return plan;
    }

    private List<ProviderOperationStep> onboardingSteps(UUID operationId) {
        return List.of(
                step(operationId, 1, "CONTROL_RECORD", "dwp-provider-server"),
                step(operationId, 2, "AUTH_TENANT", "dwp-auth-server"),
                step(operationId, 3, "PLATFORM_TENANT", "dwp-platform-server"),
                step(operationId, 4, "PEOPLE_TENANT", "dwp-people-server"),
                step(operationId, 5, "ASSET_STORAGE", "dwp-platform-server"),
                step(operationId, 6, "ACTIVATE_TENANT", "dwp-provider-server"));
    }

    private ProviderOperationStep step(UUID operationId, int order, String key, String target) {
        return ProviderOperationStep.builder()
                .operationId(operationId)
                .stepOrder(order)
                .stepKey(key)
                .lifecycleState("PENDING")
                .targetService(target)
                .redactedResult("{}")
                .build();
    }
}
