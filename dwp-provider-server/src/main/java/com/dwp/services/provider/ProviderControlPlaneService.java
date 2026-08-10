package com.dwp.services.provider;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.entitlement.Entitlement;
import com.dwp.services.provider.entitlement.EntitlementRepository;
import com.dwp.services.provider.entitlement.TenantEntitlement;
import com.dwp.services.provider.entitlement.TenantEntitlementRepository;
import com.dwp.services.provider.operation.ProviderOperation;
import com.dwp.services.provider.operation.ProviderOperationRepository;
import com.dwp.services.provider.operation.ProviderOperationStep;
import com.dwp.services.provider.operation.ProviderOperationStepRepository;
import com.dwp.services.provider.provisioning.DownstreamProvisioningClient;
import com.dwp.services.provider.provisioning.ProviderProvisioningOrchestrator;
import com.dwp.services.provider.security.ProviderRequestContext;
import com.dwp.services.provider.tenant.ProviderTenant;
import com.dwp.services.provider.tenant.ProviderTenantRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ProviderControlPlaneService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ProviderTenantRepository tenantRepository;
    private final EntitlementRepository entitlementRepository;
    private final TenantEntitlementRepository tenantEntitlementRepository;
    private final ProviderOperationRepository operationRepository;
    private final ProviderOperationStepRepository stepRepository;
    private final ProviderEstateRepository estateRepository;
    private final ProviderOperationsRepository operationsRepository;
    private final ProviderProvisioningOrchestrator orchestrator;
    private final DownstreamProvisioningClient provisioningClient;
    private final ProviderAuditService auditService;
    private final ObjectMapper objectMapper;

    public ProviderControlPlaneService(
            ProviderTenantRepository tenantRepository,
            EntitlementRepository entitlementRepository,
            TenantEntitlementRepository tenantEntitlementRepository,
            ProviderOperationRepository operationRepository,
            ProviderOperationStepRepository stepRepository,
            ProviderEstateRepository estateRepository,
            ProviderOperationsRepository operationsRepository,
            ProviderProvisioningOrchestrator orchestrator,
            DownstreamProvisioningClient provisioningClient,
            ProviderAuditService auditService,
            ObjectMapper objectMapper) {
        this.tenantRepository = tenantRepository;
        this.entitlementRepository = entitlementRepository;
        this.tenantEntitlementRepository = tenantEntitlementRepository;
        this.operationRepository = operationRepository;
        this.stepRepository = stepRepository;
        this.estateRepository = estateRepository;
        this.operationsRepository = operationsRepository;
        this.orchestrator = orchestrator;
        this.provisioningClient = provisioningClient;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    public ProviderDtos.OperatorProfile operatorProfile() {
        ProviderRequestContext.Actor actor = ProviderRequestContext.require();
        return new ProviderDtos.OperatorProfile(
                actor.operatorId(), actor.userId(), actor.displayName(), actor.roles(), actor.permissions());
    }

    public ProviderDtos.EstateOverview overview() {
        ProviderRequestContext.requirePermission("ESTATE_READ");
        return estateRepository.overview();
    }

    public ProviderDtos.CommandCenter commandCenter() {
        ProviderRequestContext.requirePermission("ESTATE_READ");
        return operationsRepository.commandCenter(estateRepository.overview());
    }

    public ProviderDtos.ServiceHealthOverview serviceHealth() {
        ProviderRequestContext.requirePermission("HEALTH_READ");
        return operationsRepository.serviceHealth();
    }

    public ProviderDtos.CommercialOverview commercialOverview() {
        ProviderRequestContext.requirePermission("COMMERCIAL_READ");
        return operationsRepository.commercialOverview();
    }

    public ProviderDtos.AuditInsights auditInsights() {
        ProviderRequestContext.requirePermission("AUDIT_READ");
        return operationsRepository.auditInsights();
    }

    @Transactional(readOnly = true)
    public ProviderDtos.PageResult<ProviderDtos.TenantSummary> tenants(
            String query,
            String state,
            int requestedPage,
            int requestedSize) {
        ProviderRequestContext.requirePermission("ESTATE_READ");
        int pageNumber = Math.max(0, requestedPage);
        int size = Math.min(100, Math.max(1, requestedSize));
        Specification<ProviderTenant> specification = Specification.where(null);
        if (query != null && !query.isBlank()) {
            String pattern = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
            List<UUID> matchingOrganizations = estateRepository.organizationIdsMatching(pattern);
            specification = specification.and((root, ignored, builder) -> matchingOrganizations.isEmpty()
                    ? builder.or(
                            builder.like(builder.lower(root.get("tenantKey")), pattern),
                            builder.like(builder.lower(root.get("displayName")), pattern))
                    : builder.or(
                            builder.like(builder.lower(root.get("tenantKey")), pattern),
                            builder.like(builder.lower(root.get("displayName")), pattern),
                            root.get("organizationId").in(matchingOrganizations)));
        }
        if (state != null && !state.isBlank()) {
            specification = specification.and((root, ignored, builder) ->
                    builder.equal(root.get("lifecycleState"), state.trim().toUpperCase(Locale.ROOT)));
        }
        Page<ProviderTenant> page = tenantRepository.findAll(
                specification,
                PageRequest.of(pageNumber, size, Sort.by("tenantKey").ascending()));
        return new ProviderDtos.PageResult<>(
                page.stream().map(this::tenantSummary).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    @Transactional(readOnly = true)
    public ProviderDtos.TenantSummary tenant(UUID tenantId) {
        ProviderRequestContext.requirePermission("ESTATE_READ");
        return tenantSummary(requireTenant(tenantId));
    }

    @Transactional(readOnly = true)
    public List<ProviderDtos.RegionSummary> regions() {
        ProviderRequestContext.requirePermission("ESTATE_READ");
        return estateRepository.regions();
    }

    @Transactional(readOnly = true)
    public List<ProviderDtos.EntitlementSummary> entitlementCatalog() {
        ProviderRequestContext.requirePermission("ESTATE_READ");
        return entitlementRepository.findByLifecycleStateOrderByEntitlementKeyAsc("ACTIVE")
                .stream().map(entitlement -> entitlementSummary(entitlement, null)).toList();
    }

    @Transactional(readOnly = true)
    public List<ProviderDtos.SupportScopeSummary> supportScopes() {
        ProviderRequestContext.requirePermission("ESTATE_READ");
        return operationsRepository.supportScopes();
    }

    @Transactional
    public ProviderDtos.OperationSummary previewOnboarding(
            String idempotencyKey,
            String correlationId,
            ProviderDtos.OnboardingPlanRequest request) {
        ProviderRequestContext.requirePermission("TENANT_WRITE");
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        List<Entitlement> entitlements = requireEntitlements(request.entitlementKeys());
        requireRegion(request.dataRegion());
        Map<String, Object> plan = onboardingPlan(request, entitlements);
        String planJson = json(plan);
        String planHash = sha256(planJson);
        ProviderOperation existing = operationRepository.findByIdempotencyKey(normalizedKey).orElse(null);
        if (existing != null) {
            if (!constantTimeEquals(existing.getPlanHash(), planHash)) {
                throw new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "The idempotency key was used with a different provider plan.");
            }
            return operationSummary(existing);
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
        return operationSummary(operation);
    }

    public ProviderDtos.OperationSummary execute(
            UUID operationId,
            String correlationId,
            ProviderDtos.ExecuteOperationRequest request) {
        ProviderOperation current = requireOperation(operationId);
        if ("L3".equals(current.getRiskTier()) && !operationsRepository.operationApproved(operationId)) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "All required approvals must be completed before this high-risk operation can run.");
        }
        ProviderOperation operation = orchestrator.execute(
                operationId, request.planHash(), request.version(), false, correlationId);
        return operationSummary(operation);
    }

    public ProviderDtos.OperationSummary retry(
            UUID operationId,
            String correlationId,
            ProviderDtos.RetryOperationRequest request) {
        ProviderOperation current = requireOperation(operationId);
        ProviderOperation operation = orchestrator.execute(
                operationId, null, request.version(), true, correlationId);
        auditService.success(
                "provider.operation.retried", "PROVIDER_OPERATION", operationId.toString(),
                operation.getProviderTenantId(),
                operation.getProviderTenantId() == null
                        ? null
                        : requireTenant(operation.getProviderTenantId()).getOrganizationId(),
                correlationId,
                Map.of(
                        "justification", request.justification(),
                        "previousState", current.getLifecycleState(),
                        "resultState", operation.getLifecycleState()));
        return operationSummary(operation);
    }

    @Transactional(readOnly = true)
    public ProviderDtos.PageResult<ProviderDtos.OperationSummary> operations(int page, int size) {
        ProviderRequestContext.requirePermission("ESTATE_READ");
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        Page<ProviderOperation> result = operationRepository.findAllByOrderByCreatedAtDesc(
                PageRequest.of(safePage, safeSize));
        return new ProviderDtos.PageResult<>(
                result.stream().map(this::operationSummary).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    public List<ProviderDtos.OperationApprovalSummary> operationApprovals(String state) {
        ProviderRequestContext.requirePermission("ESTATE_READ");
        String normalized = state == null || state.isBlank()
                ? null
                : state.trim().toUpperCase(Locale.ROOT);
        if (normalized != null
                && !Set.of("PENDING", "APPROVED", "REJECTED", "CANCELLED", "EXPIRED").contains(normalized)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Unknown approval state.");
        }
        return operationsRepository.operationApprovals(normalized);
    }

    @Transactional
    public ProviderDtos.OperationApprovalSummary decideOperationApproval(
            UUID approvalId,
            String correlationId,
            ProviderDtos.DecideOperationApprovalRequest request) {
        ProviderRequestContext.requirePermission("CHANGE_APPROVE");
        ProviderOperationsRepository.ApprovalRecord approval = operationsRepository.approval(approvalId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireVersion(approval.version(), request.version());
        if (!"PENDING".equals(approval.lifecycleState())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The approval is no longer pending.");
        }
        ProviderRequestContext.Actor actor = ProviderRequestContext.require();
        if (!actor.roles().contains(approval.requiredRoleCode())) {
            throw new BaseException(ErrorCode.FORBIDDEN, "The required approval role is not assigned.");
        }
        if (approval.separationOfDuties() && Objects.equals(approval.requestedBy(), actor.operatorId())) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Separation of duties prevents the requester from approving this change.");
        }
        boolean decided = operationsRepository.decideApproval(
                approvalId, request.decision(), request.reason().trim(),
                actor.operatorId(), request.version());
        if (!decided) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "The approval changed. Refresh and try again.");
        }
        ProviderOperation operation = requireOperation(approval.operationId());
        if ("REJECTED".equals(request.decision()) && "PREVIEWED".equals(operation.getLifecycleState())) {
            operation.setLifecycleState("CANCELLED");
            operation.setFailureCode("CHANGE_REJECTED");
            operation.setFailureMessage(request.reason().trim());
            operationRepository.saveAndFlush(operation);
        }
        auditService.success(
                "provider.operation-approval.decided", "OPERATION_APPROVAL", approvalId.toString(),
                operation.getProviderTenantId(),
                operation.getProviderTenantId() == null
                        ? null
                        : requireTenant(operation.getProviderTenantId()).getOrganizationId(),
                correlationId,
                Map.of("decision", request.decision(), "reason", request.reason().trim()));
        return operationsRepository.operationApprovals(null).stream()
                .filter(item -> item.operationApprovalId().equals(approvalId))
                .findFirst().orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    @Transactional
    public ProviderDtos.ServiceIncidentSummary createIncident(
            String correlationId,
            ProviderDtos.CreateIncidentRequest request) {
        ProviderRequestContext.requirePermission("INCIDENT_WRITE");
        validateIncidentScope(request);
        ProviderRequestContext.Actor actor = ProviderRequestContext.require();
        UUID incidentId = operationsRepository.createIncident(
                request, actor.operatorId(), correlationId);
        auditService.success(
                "provider.incident.created", "SERVICE_INCIDENT", incidentId.toString(),
                request.tenantId(),
                request.tenantId() == null ? null : requireTenant(request.tenantId()).getOrganizationId(),
                correlationId,
                Map.of(
                        "severity", request.severity(),
                        "impactScope", request.impactScope(),
                        "title", request.title().trim()));
        return operationsRepository.incidents(200).stream()
                .filter(item -> item.incidentId().equals(incidentId))
                .findFirst().orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    @Transactional
    public ProviderDtos.ServiceIncidentSummary updateIncident(
            UUID incidentId,
            String correlationId,
            ProviderDtos.UpdateIncidentRequest request) {
        ProviderRequestContext.requirePermission("INCIDENT_WRITE");
        ProviderOperationsRepository.IncidentRecord incident = operationsRepository.incident(incidentId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireVersion(incident.version(), request.version());
        boolean changed = operationsRepository.updateIncident(
                incidentId, request.state(), request.message().trim(), request.visibility(),
                ProviderRequestContext.require().operatorId(), request.version());
        if (!changed) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "The incident changed. Refresh and try again.");
        }
        auditService.success(
                "provider.incident.updated", "SERVICE_INCIDENT", incidentId.toString(),
                correlationId,
                Map.of("state", request.state(), "visibility", request.visibility()));
        return operationsRepository.incidents(200).stream()
                .filter(item -> item.incidentId().equals(incidentId))
                .findFirst().orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    public ProviderDtos.TenantSummary lifecycle(
            UUID tenantId,
            String correlationId,
            ProviderDtos.LifecycleRequest request) {
        ProviderRequestContext.requirePermission("TENANT_WRITE");
        ProviderTenant tenant = requireTenant(tenantId);
        requireVersion(tenant.getVersion(), request.version());
        if ("ACTIVE".equals(request.state()) && !"READY".equals(tenant.getOnboardingState())) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "A tenant cannot be activated until downstream onboarding is ready.");
        }
        provisioningClient.updateLifecycle(tenantId, request.state());
        tenant.setLifecycleState(request.state());
        tenant = tenantRepository.saveAndFlush(tenant);
        auditService.success(
                "provider.tenant.lifecycle-changed", "PROVIDER_TENANT", tenantId.toString(),
                tenantId, tenant.getOrganizationId(), correlationId,
                Map.of("state", request.state(), "justification", request.justification()));
        return tenantSummary(tenant);
    }

    public ProviderDtos.TenantSummary replaceEntitlements(
            UUID tenantId,
            String correlationId,
            ProviderDtos.ReplaceEntitlementsRequest request) {
        ProviderRequestContext.requirePermission("ENTITLEMENT_WRITE");
        ProviderTenant tenant = requireTenant(tenantId);
        requireVersion(tenant.getVersion(), request.version());
        List<Entitlement> entitlements = requireEntitlements(request.entitlementKeys());
        provisioningClient.replaceEntitlements(
                tenantId, entitlements.stream().map(Entitlement::getEntitlementKey).toList());
        replaceTenantEntitlements(tenant, entitlements);
        tenant.setEntitlementRevision(valueOrZero(tenant.getEntitlementRevision()) + 1L);
        tenant = tenantRepository.saveAndFlush(tenant);
        auditService.success(
                "provider.tenant-entitlements.replaced", "PROVIDER_TENANT", tenantId.toString(),
                tenantId, tenant.getOrganizationId(), correlationId,
                Map.of(
                        "entitlements", entitlements.stream().map(Entitlement::getEntitlementKey).toList(),
                        "justification", request.justification()));
        return tenantSummary(tenant);
    }

    @Transactional
    public ProviderDtos.DomainChallenge createDomain(
            UUID tenantId,
            String correlationId,
            ProviderDtos.CreateDomainRequest request) {
        ProviderRequestContext.requirePermission("TENANT_WRITE");
        ProviderTenant tenant = requireTenant(tenantId);
        String domainName = request.domainName().trim().toLowerCase(Locale.ROOT);
        String challenge = "dwp-verification=" + randomToken();
        UUID domainId;
        try {
            domainId = estateRepository.createDomain(
                    tenantId, domainName, request.domainType(), request.primaryDomain(),
                    challenge, sha256(challenge), ProviderRequestContext.require().operatorId());
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

    public ProviderDtos.DomainChallenge domainChallenge(UUID tenantId, UUID domainId) {
        ProviderRequestContext.requirePermission("ESTATE_READ");
        requireTenant(tenantId);
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

    @Transactional
    public ProviderDtos.TenantDomainSummary verifyDomain(
            UUID tenantId,
            UUID domainId,
            String correlationId,
            ProviderDtos.VerifyDomainRequest request) {
        ProviderRequestContext.requirePermission("TENANT_WRITE");
        ProviderTenant tenant = requireTenant(tenantId);
        ProviderEstateRepository.DomainRecord record = estateRepository.domainRecord(tenantId, domainId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireVersion(record.version(), request.version());
        boolean verified = "INTERNAL".equals(record.verificationMethod())
                || dnsTxtRecords("_dwp-verification." + record.domainName()).stream()
                .map(value -> value.replace("\"", "").trim())
                .map(this::sha256)
                .anyMatch(hash -> constantTimeEquals(hash, record.tokenHash()));
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

    public ProviderDtos.AdministratorInvitation issueAdministratorInvitation(
            UUID tenantId,
            UUID administratorId,
            String correlationId,
            ProviderDtos.IssueAdministratorInvitationRequest request) {
        ProviderRequestContext.requirePermission("TENANT_WRITE");
        ProviderTenant tenant = requireTenant(tenantId);
        if (!"READY".equals(tenant.getOnboardingState()) || tenant.getAuthTenantId() == null) {
            throw new BaseException(ErrorCode.INVALID_STATE, "Tenant onboarding must be ready first.");
        }
        ProviderEstateRepository.AdministratorRecord administrator =
                estateRepository.administrator(tenantId, administratorId)
                        .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (administrator.authUserId() == null) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The administrator is not linked to auth.");
        }
        DownstreamProvisioningClient.InvitationResult result =
                provisioningClient.issueAdministratorInvitation(
                        tenantId, administrator.authUserId(), request.expiresInMinutes());
        estateRepository.markAdministratorInvited(
                administratorId, ProviderRequestContext.require().operatorId());
        auditService.success(
                "provider.tenant-administrator.invited", "TENANT_ADMINISTRATOR",
                administratorId.toString(), tenantId, tenant.getOrganizationId(), correlationId,
                Map.of(
                        "email", administrator.email(),
                        "expiresAt", result.expiresAt(),
                        "justification", request.justification()));
        return new ProviderDtos.AdministratorInvitation(
                administratorId,
                result.tenantId(),
                result.administratorUserId(),
                result.email(),
                result.activationToken(),
                "/activate?token=" + result.activationToken(),
                result.expiresAt());
    }

    @Transactional
    public ProviderDtos.SupportSessionGrant createSupportSession(
            String correlationId,
            ProviderDtos.CreateSupportSessionRequest request) {
        ProviderRequestContext.requirePermission("SUPPORT_SESSION_WRITE");
        ProviderTenant tenant = requireTenant(request.tenantId());
        LinkedHashSet<String> scopeSet = request.scopes().stream()
                .map(String::trim).collect(Collectors.toCollection(LinkedHashSet::new));
        if (scopeSet.contains("TENANT_CONFIGURATION_WRITE")) {
            scopeSet.add("TENANT_CONFIGURATION_READ");
        }
        ProviderOperationsRepository.SupportPolicy supportPolicy =
                operationsRepository.supportPolicy(scopeSet);
        if (supportPolicy.matchedScopes() != scopeSet.size()) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "One or more support scopes are unknown or inactive.");
        }
        String accessMode;
        String approvalReference = normalized(request.approvalReference());
        if (request.emergencyAccess()) {
            ProviderRequestContext.requirePermission("BREAK_GLASS_SUPPORT");
            accessMode = "BREAK_GLASS";
        } else {
            accessMode = "STANDARD";
            if (supportPolicy.requiresCustomerApproval() && approvalReference == null) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "A customer approval reference is required for this support scope.");
            }
        }
        String token = randomToken();
        Instant expiresAt = Instant.now().plusSeconds(request.durationMinutes() * 60L);
        ProviderRequestContext.Actor actor = ProviderRequestContext.require();
        UUID sessionId = estateRepository.createSupportSession(
                tenant.getProviderTenantId(), actor.operatorId(), request.justification(),
                sha256(token), expiresAt, accessMode, approvalReference,
                !request.emergencyAccess() && supportPolicy.requiresCustomerApproval(),
                request.emergencyAccess() ? "L3" : supportPolicy.riskTier());
        estateRepository.addSupportScopes(sessionId, List.copyOf(scopeSet));
        ProviderDtos.SupportSessionSummary session = estateRepository.supportSessions(tenant.getProviderTenantId())
                .stream().filter(item -> item.supportSessionId().equals(sessionId))
                .findFirst().orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        auditService.success(
                "provider.support-session.created", "SUPPORT_SESSION", sessionId.toString(),
                tenant.getProviderTenantId(), tenant.getOrganizationId(), correlationId,
                Map.of(
                        "scopes", scopeSet,
                        "expiresAt", expiresAt,
                        "accessMode", accessMode,
                        "customerApprovalRequired",
                        !request.emergencyAccess() && supportPolicy.requiresCustomerApproval(),
                        "riskTier", request.emergencyAccess() ? "L3" : supportPolicy.riskTier(),
                        "justification", request.justification()));
        return new ProviderDtos.SupportSessionGrant(session, token);
    }

    public List<ProviderDtos.SupportSessionSummary> supportSessions(UUID tenantId) {
        ProviderRequestContext.requirePermission("ESTATE_READ");
        if (tenantId != null) requireTenant(tenantId);
        return estateRepository.supportSessions(tenantId);
    }

    @Transactional
    public ProviderDtos.SupportSessionSummary revokeSupportSession(
            UUID sessionId,
            String correlationId,
            ProviderDtos.RevokeSupportSessionRequest request) {
        ProviderRequestContext.requirePermission("SUPPORT_SESSION_WRITE");
        ProviderEstateRepository.SupportSessionRecord record = estateRepository.supportSession(sessionId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireVersion(record.version(), request.version());
        if (!"ACTIVE".equals(record.lifecycleState())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The support session is not active.");
        }
        ProviderTenant tenant = requireTenant(record.tenantId());
        estateRepository.revokeSupportSession(sessionId, ProviderRequestContext.require().operatorId());
        auditService.success(
                "provider.support-session.revoked", "SUPPORT_SESSION", sessionId.toString(),
                tenant.getProviderTenantId(), tenant.getOrganizationId(), correlationId,
                Map.of("justification", request.justification()));
        return estateRepository.supportSessions(record.tenantId()).stream()
                .filter(item -> item.supportSessionId().equals(sessionId))
                .findFirst().orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    public List<ProviderDtos.AuditEventSummary> auditEvents(UUID tenantId, int limit) {
        ProviderRequestContext.requirePermission("AUDIT_READ");
        if (tenantId != null) requireTenant(tenantId);
        return estateRepository.auditEvents(tenantId, limit);
    }

    private void validateIncidentScope(ProviderDtos.CreateIncidentRequest request) {
        int targetCount = 0;
        if (normalized(request.serviceKey()) != null) targetCount++;
        if (normalized(request.regionKey()) != null) targetCount++;
        if (request.deploymentCellId() != null) targetCount++;
        if (request.tenantId() != null) targetCount++;
        if (("GLOBAL".equals(request.impactScope()) && targetCount != 0)
                || (!"GLOBAL".equals(request.impactScope()) && targetCount != 1)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The incident scope must have exactly one matching target.");
        }
        switch (request.impactScope()) {
            case "GLOBAL" -> {
                // A global incident intentionally has no mandatory target.
            }
            case "REGION" -> {
                if (request.regionKey() == null || estateRepository.regions().stream()
                        .noneMatch(region -> region.regionKey().equals(request.regionKey()))) {
                    throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "A valid region is required.");
                }
            }
            case "CELL" -> {
                if (request.deploymentCellId() == null) {
                    throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "A deployment cell is required.");
                }
            }
            case "SERVICE" -> {
                if (request.serviceKey() == null || operationsRepository.servicePostures().stream()
                        .noneMatch(service -> service.serviceKey().equals(request.serviceKey()))) {
                    throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "A valid service is required.");
                }
            }
            case "TENANT" -> {
                if (request.tenantId() == null) {
                    throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "A tenant is required.");
                }
                requireTenant(request.tenantId());
            }
            default -> throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Unknown incident scope.");
        }
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
        plan.put("legalName", normalized(request.legalName()));
        plan.put("customerReference", normalized(request.customerReference()));
        plan.put("tenantKey", request.tenantKey());
        plan.put("displayName", request.displayName().trim());
        plan.put("environmentKey", request.environmentKey());
        plan.put("serviceTier", request.serviceTier());
        plan.put("dataRegion", request.dataRegion());
        plan.put("isolationModel", request.isolationModel());
        plan.put("defaultLocale", request.defaultLocale());
        plan.put("timeZone", request.timeZone());
        plan.put("primaryDomain", normalized(request.primaryDomain()));
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

    private List<Entitlement> requireEntitlements(List<String> requestedKeys) {
        Set<String> keys = requestedKeys.stream()
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Entitlement> available = entitlementRepository
                .findByLifecycleStateOrderByEntitlementKeyAsc("ACTIVE")
                .stream().collect(Collectors.toMap(Entitlement::getEntitlementKey, Function.identity()));
        if (!available.keySet().containsAll(keys)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "An unknown entitlement was selected.");
        }
        return keys.stream().map(available::get)
                .sorted(Comparator.comparing(Entitlement::getEntitlementKey)).toList();
    }

    private void requireRegion(String regionKey) {
        boolean active = estateRepository.regions().stream()
                .anyMatch(region -> region.regionKey().equals(regionKey)
                        && "ACTIVE".equals(region.lifecycleState()));
        if (!active) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Unknown or inactive data region.");
    }

    private void replaceTenantEntitlements(ProviderTenant tenant, List<Entitlement> entitlements) {
        Map<Long, TenantEntitlement> current = tenantEntitlementRepository
                .findByProviderTenantIdOrderByTenantEntitlementIdAsc(tenant.getProviderTenantId())
                .stream().collect(Collectors.toMap(TenantEntitlement::getEntitlementId, Function.identity()));
        Set<Long> requestedIds = entitlements.stream()
                .map(Entitlement::getEntitlementId).collect(Collectors.toSet());
        current.values().forEach(assignment -> assignment.setLifecycleState(
                requestedIds.contains(assignment.getEntitlementId()) ? "ACTIVE" : "RETIRED"));
        List<TenantEntitlement> additions = entitlements.stream()
                .filter(entitlement -> !current.containsKey(entitlement.getEntitlementId()))
                .map(entitlement -> TenantEntitlement.builder()
                        .providerTenantId(tenant.getProviderTenantId())
                        .entitlementId(entitlement.getEntitlementId())
                        .lifecycleState("ACTIVE")
                        .configuration("{}")
                        .build())
                .toList();
        tenantEntitlementRepository.saveAll(current.values());
        tenantEntitlementRepository.saveAll(additions);
    }

    private ProviderDtos.TenantSummary tenantSummary(ProviderTenant tenant) {
        ProviderDtos.OrganizationSummary organization = estateRepository
                .organization(tenant.getOrganizationId())
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        Map<Long, Entitlement> catalog = entitlementRepository.findAll().stream()
                .collect(Collectors.toMap(Entitlement::getEntitlementId, Function.identity()));
        List<ProviderDtos.EntitlementSummary> entitlements = tenantEntitlementRepository
                .findByProviderTenantIdOrderByTenantEntitlementIdAsc(tenant.getProviderTenantId())
                .stream()
                .filter(assignment -> catalog.containsKey(assignment.getEntitlementId()))
                .map(assignment -> entitlementSummary(catalog.get(assignment.getEntitlementId()), assignment))
                .toList();
        return new ProviderDtos.TenantSummary(
                tenant.getProviderTenantId(),
                tenant.getOrganizationId(),
                organization.organizationKey(),
                organization.displayName(),
                tenant.getTenantKey(),
                tenant.getDisplayName(),
                tenant.getEnvironmentKey(),
                tenant.getServiceTier(),
                tenant.getDataRegion(),
                tenant.getIsolationModel(),
                tenant.getDefaultLocale(),
                tenant.getTimeZone(),
                tenant.getLifecycleState(),
                tenant.getOnboardingState(),
                tenant.getAuthTenantId(),
                tenant.getSchemaVersion(),
                tenant.getConfiguration(),
                valueOrZero(tenant.getVersion()),
                tenant.getCreatedAt() == null
                        ? null
                        : tenant.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant(),
                tenant.getUpdatedAt() == null
                        ? null
                        : tenant.getUpdatedAt().atZone(ZoneId.systemDefault()).toInstant(),
                estateRepository.currentSubscription(tenant.getOrganizationId()).orElse(null),
                entitlements,
                estateRepository.serviceInstances(tenant.getProviderTenantId()),
                estateRepository.domains(tenant.getProviderTenantId()),
                estateRepository.administrators(tenant.getProviderTenantId()));
    }

    private ProviderDtos.EntitlementSummary entitlementSummary(
            Entitlement entitlement,
            TenantEntitlement assignment) {
        return new ProviderDtos.EntitlementSummary(
                entitlement.getEntitlementId(), entitlement.getEntitlementKey(),
                entitlement.getName(), entitlement.getEntitlementType(),
                assignment == null ? entitlement.getLifecycleState() : assignment.getLifecycleState(),
                assignment == null ? "{}" : assignment.getConfiguration(),
                assignment == null ? 0 : valueOrZero(assignment.getVersion()));
    }

    private ProviderDtos.OperationSummary operationSummary(ProviderOperation operation) {
        List<ProviderDtos.OperationStep> steps = stepRepository
                .findByOperationIdOrderByStepOrderAsc(operation.getOperationId())
                .stream().map(step -> new ProviderDtos.OperationStep(
                        step.getOperationStepId(),
                        step.getStepOrder(),
                        step.getStepKey(),
                        step.getLifecycleState(),
                        step.getTargetService(),
                        step.getExternalReference(),
                        step.getRedactedResult(),
                        step.getAttemptCount(),
                        step.getLastErrorCode(),
                        step.getLastErrorMessage(),
                        step.getNextRetryAt(),
                        step.getStartedAt(),
                        step.getCompletedAt()))
                .toList();
        return new ProviderDtos.OperationSummary(
                operation.getOperationId(), operation.getProviderTenantId(), operation.getOperationType(),
                operation.getLifecycleState(), operation.getRiskTier(), operation.getPlanHash(),
                operation.getPlan(), operation.getFailureCode(), operation.getFailureMessage(),
                operation.getStartedAt(), operation.getCompletedAt(), operation.getCreatedAt(),
                valueOrZero(operation.getVersion()), steps);
    }

    private ProviderTenant requireTenant(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private ProviderOperation requireOperation(UUID operationId) {
        return operationRepository.findById(operationId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private String normalizeIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Idempotency-Key is required.");
        }
        String normalized = value.trim();
        if (normalized.length() > 160 || !normalized.matches("[A-Za-z0-9:._-]+")) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Idempotency-Key format is invalid.");
        }
        return normalized;
    }

    private void requireVersion(Long actual, Long expected) {
        if (!Objects.equals(valueOrZero(actual), expected)) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Provider state changed after it was loaded. Refresh and try again.");
        }
    }

    private List<String> dnsTxtRecords(String recordName) {
        Hashtable<String, String> environment = new Hashtable<>();
        environment.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        environment.put("com.sun.jndi.dns.timeout.initial", "2500");
        environment.put("com.sun.jndi.dns.timeout.retries", "1");
        try {
            Attributes attributes = new InitialDirContext(environment)
                    .getAttributes(recordName, new String[]{"TXT"});
            if (attributes.get("TXT") == null) return List.of();
            List<String> values = new ArrayList<>();
            for (int index = 0; index < attributes.get("TXT").size(); index++) {
                values.add(String.valueOf(attributes.get("TXT").get(index)));
            }
            return values;
        } catch (NamingException exception) {
            return List.of();
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private boolean constantTimeEquals(String left, String right) {
        return right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Provider plan serialization failed.", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }
}
