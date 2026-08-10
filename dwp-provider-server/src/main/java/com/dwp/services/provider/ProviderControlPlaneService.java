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
import com.dwp.services.provider.security.ProviderRequestContext;
import com.dwp.services.provider.tenant.ProviderTenant;
import com.dwp.services.provider.tenant.ProviderTenantRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
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

    private final ProviderTenantRepository tenantRepository;
    private final EntitlementRepository entitlementRepository;
    private final TenantEntitlementRepository tenantEntitlementRepository;
    private final ProviderOperationRepository operationRepository;
    private final ProviderOperationStepRepository stepRepository;
    private final ProviderAuditService auditService;
    private final ObjectMapper objectMapper;

    public ProviderControlPlaneService(
            ProviderTenantRepository tenantRepository,
            EntitlementRepository entitlementRepository,
            TenantEntitlementRepository tenantEntitlementRepository,
            ProviderOperationRepository operationRepository,
            ProviderOperationStepRepository stepRepository,
            ProviderAuditService auditService,
            ObjectMapper objectMapper) {
        this.tenantRepository = tenantRepository;
        this.entitlementRepository = entitlementRepository;
        this.tenantEntitlementRepository = tenantEntitlementRepository;
        this.operationRepository = operationRepository;
        this.stepRepository = stepRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ProviderDtos.PageResult<ProviderDtos.TenantSummary> tenants(
            String query,
            String state,
            int requestedPage,
            int requestedSize) {
        int pageNumber = Math.max(0, requestedPage);
        int size = Math.min(100, Math.max(1, requestedSize));
        Specification<ProviderTenant> specification = Specification.where(null);
        if (query != null && !query.isBlank()) {
            String pattern = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, ignored, builder) -> builder.or(
                    builder.like(builder.lower(root.get("tenantKey")), pattern),
                    builder.like(builder.lower(root.get("displayName")), pattern)));
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
        return tenantSummary(requireTenant(tenantId));
    }

    @Transactional(readOnly = true)
    public List<ProviderDtos.EntitlementSummary> entitlementCatalog() {
        return entitlementRepository.findByLifecycleStateOrderByEntitlementKeyAsc("ACTIVE")
                .stream().map(entitlement -> entitlementSummary(entitlement, null)).toList();
    }

    @Transactional
    public ProviderDtos.OperationSummary previewOnboarding(
            String idempotencyKey,
            String correlationId,
            ProviderDtos.OnboardingPlanRequest request) {
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        List<Entitlement> entitlements = requireEntitlements(request.entitlementKeys());
        Map<String, Object> plan = onboardingPlan(request, entitlements);
        String planJson = json(plan);
        String planHash = sha256(planJson);
        ProviderOperation existing = operationRepository.findByIdempotencyKey(normalizedKey).orElse(null);
        if (existing != null) {
            if (!MessageDigest.isEqual(
                    existing.getPlanHash().getBytes(StandardCharsets.US_ASCII),
                    planHash.getBytes(StandardCharsets.US_ASCII))) {
                throw new BaseException(
                        ErrorCode.RESOURCE_CONFLICT,
                        "The idempotency key was used with a different provider plan.");
            }
            return operationSummary(existing);
        }
        if (tenantRepository.findByTenantKey(request.tenantKey()).isPresent()) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "The tenant key already exists.");
        }
        ProviderOperation operation = ProviderOperation.builder()
                .operationType("TENANT_ONBOARD")
                .idempotencyKey(normalizedKey)
                .lifecycleState("PREVIEWED")
                .riskTier("REGULATED".equals(request.serviceTier()) ? "L3" : "L2")
                .requestedBy(ProviderRequestContext.require().userId())
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
        auditService.success(
                "provider.tenant-onboarding.previewed", "PROVIDER_OPERATION",
                operation.getOperationId().toString(), correlationId,
                Map.of("planHash", planHash, "tenantKey", request.tenantKey(), "riskTier", operation.getRiskTier()));
        return operationSummary(operation);
    }

    @Transactional
    public ProviderDtos.OperationSummary execute(
            UUID operationId,
            String correlationId,
            ProviderDtos.ExecuteOperationRequest request) {
        ProviderOperation operation = requireOperation(operationId);
        requireVersion(operation.getVersion(), request.version());
        if (!MessageDigest.isEqual(
                operation.getPlanHash().getBytes(StandardCharsets.US_ASCII),
                request.planHash().getBytes(StandardCharsets.US_ASCII))) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "The provider plan hash does not match.");
        }
        if ("PARTIAL".equals(operation.getLifecycleState())
                || "SUCCEEDED".equals(operation.getLifecycleState())) {
            return operationSummary(operation);
        }
        if (!"PREVIEWED".equals(operation.getLifecycleState())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "Only a previewed operation can execute.");
        }
        JsonNode plan = read(operation.getPlan());
        String tenantKey = plan.path("tenantKey").asText();
        if (tenantRepository.findByTenantKey(tenantKey).isPresent()) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "The tenant key already exists.");
        }

        operation.setLifecycleState("EXECUTING");
        operation.setStartedAt(Instant.now());
        operation = operationRepository.saveAndFlush(operation);

        ProviderTenant tenant = ProviderTenant.builder()
                .tenantKey(tenantKey)
                .displayName(plan.path("displayName").asText())
                .serviceTier(plan.path("serviceTier").asText())
                .dataRegion(plan.path("dataRegion").asText())
                .isolationModel(plan.path("isolationModel").asText())
                .lifecycleState("PROVISIONING")
                .onboardingState("PENDING_EXTERNAL")
                .build();
        tenant = tenantRepository.saveAndFlush(tenant);
        operation.setProviderTenantId(tenant.getProviderTenantId());

        List<String> entitlementKeys = new ArrayList<>();
        plan.path("entitlements").forEach(node -> entitlementKeys.add(node.asText()));
        replaceTenantEntitlements(tenant, requireEntitlements(entitlementKeys));

        Instant now = Instant.now();
        List<ProviderOperationStep> steps = stepRepository
                .findByOperationIdOrderByStepOrderAsc(operationId);
        for (ProviderOperationStep step : steps) {
            step.setStartedAt(now);
            if ("CONTROL_RECORD".equals(step.getStepKey())) {
                step.setLifecycleState("SUCCEEDED");
                step.setExternalReference(tenant.getProviderTenantId().toString());
                step.setRedactedResult("{\"controlPlaneRecord\":\"created\"}");
                step.setCompletedAt(now);
            } else {
                step.setLifecycleState("PENDING_EXTERNAL");
                step.setRedactedResult("{\"gate\":\"downstream-provisioning-adapter\"}");
            }
        }
        stepRepository.saveAll(steps);
        operation.setLifecycleState("PARTIAL");
        operation = operationRepository.saveAndFlush(operation);
        auditService.success(
                "provider.tenant-onboarding.executed", "PROVIDER_TENANT",
                tenant.getProviderTenantId().toString(), correlationId,
                Map.of(
                        "tenantKey", tenantKey,
                        "onboardingState", "PENDING_EXTERNAL",
                        "pendingAdapters", List.of("auth", "platform", "people", "asset-storage")));
        return operationSummary(operation);
    }

    @Transactional(readOnly = true)
    public ProviderDtos.PageResult<ProviderDtos.OperationSummary> operations(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        Page<ProviderOperation> result = operationRepository.findAllByOrderByCreatedAtDesc(
                PageRequest.of(safePage, safeSize));
        return new ProviderDtos.PageResult<>(
                result.stream().map(this::operationSummary).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional
    public ProviderDtos.TenantSummary lifecycle(
            UUID tenantId,
            String correlationId,
            ProviderDtos.LifecycleRequest request) {
        ProviderTenant tenant = requireTenant(tenantId);
        requireVersion(tenant.getVersion(), request.version());
        if ("ACTIVE".equals(request.state()) && !"READY".equals(tenant.getOnboardingState())) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "A tenant cannot be activated until downstream onboarding is ready.");
        }
        tenant.setLifecycleState(request.state());
        tenant = tenantRepository.saveAndFlush(tenant);
        auditService.success(
                "provider.tenant.lifecycle-changed", "PROVIDER_TENANT", tenantId.toString(),
                correlationId,
                Map.of("state", request.state(), "justification", request.justification()));
        return tenantSummary(tenant);
    }

    @Transactional
    public ProviderDtos.TenantSummary replaceEntitlements(
            UUID tenantId,
            String correlationId,
            ProviderDtos.ReplaceEntitlementsRequest request) {
        ProviderTenant tenant = requireTenant(tenantId);
        requireVersion(tenant.getVersion(), request.version());
        List<Entitlement> entitlements = requireEntitlements(request.entitlementKeys());
        replaceTenantEntitlements(tenant, entitlements);
        tenant.setEntitlementRevision(valueOrZero(tenant.getEntitlementRevision()) + 1L);
        tenant = tenantRepository.saveAndFlush(tenant);
        auditService.success(
                "provider.tenant-entitlements.replaced", "PROVIDER_TENANT", tenantId.toString(),
                correlationId,
                Map.of(
                        "entitlements", entitlements.stream().map(Entitlement::getEntitlementKey).toList(),
                        "justification", request.justification()));
        return tenantSummary(tenant);
    }

    private Map<String, Object> onboardingPlan(
            ProviderDtos.OnboardingPlanRequest request,
            List<Entitlement> entitlements) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("contract", "dwp.provider.tenant-onboarding.v1");
        plan.put("tenantKey", request.tenantKey());
        plan.put("displayName", request.displayName().trim());
        plan.put("serviceTier", request.serviceTier());
        plan.put("dataRegion", request.dataRegion());
        plan.put("isolationModel", request.isolationModel());
        plan.put("entitlements", entitlements.stream()
                .map(Entitlement::getEntitlementKey).sorted().toList());
        plan.put("steps", List.of(
                "CONTROL_RECORD", "AUTH_TENANT", "PLATFORM_TENANT",
                "PEOPLE_TENANT", "ASSET_STORAGE"));
        plan.put("externalGates", List.of("downstream-provisioning-adapter", "KMS", "S3"));
        return plan;
    }

    private List<ProviderOperationStep> onboardingSteps(UUID operationId) {
        return List.of(
                step(operationId, 1, "CONTROL_RECORD", "dwp-provider-server"),
                step(operationId, 2, "AUTH_TENANT", "dwp-auth-server"),
                step(operationId, 3, "PLATFORM_TENANT", "dwp-platform-server"),
                step(operationId, 4, "PEOPLE_TENANT", "dwp-people-server"),
                step(operationId, 5, "ASSET_STORAGE", "object-storage"));
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
        Map<Long, Entitlement> catalog = entitlementRepository.findAll().stream()
                .collect(Collectors.toMap(Entitlement::getEntitlementId, Function.identity()));
        List<ProviderDtos.EntitlementSummary> entitlements = tenantEntitlementRepository
                .findByProviderTenantIdOrderByTenantEntitlementIdAsc(tenant.getProviderTenantId())
                .stream()
                .filter(assignment -> catalog.containsKey(assignment.getEntitlementId()))
                .map(assignment -> entitlementSummary(catalog.get(assignment.getEntitlementId()), assignment))
                .toList();
        return new ProviderDtos.TenantSummary(
                tenant.getProviderTenantId(), tenant.getTenantKey(), tenant.getDisplayName(),
                tenant.getServiceTier(), tenant.getDataRegion(), tenant.getIsolationModel(),
                tenant.getLifecycleState(), tenant.getOnboardingState(), tenant.getAuthTenantId(),
                valueOrZero(tenant.getVersion()), entitlements);
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
                        step.getStepOrder(), step.getStepKey(), step.getLifecycleState(),
                        step.getTargetService(), step.getExternalReference(), step.getRedactedResult(),
                        step.getStartedAt(), step.getCompletedAt()))
                .toList();
        return new ProviderDtos.OperationSummary(
                operation.getOperationId(), operation.getProviderTenantId(), operation.getOperationType(),
                operation.getLifecycleState(), operation.getRiskTier(), operation.getPlanHash(),
                operation.getPlan(), operation.getFailureCode(), operation.getFailureMessage(),
                operation.getStartedAt(), operation.getCompletedAt(),
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

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Provider plan serialization failed.", exception);
        }
    }

    private JsonNode read(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored provider plan is invalid.", exception);
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

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }
}
