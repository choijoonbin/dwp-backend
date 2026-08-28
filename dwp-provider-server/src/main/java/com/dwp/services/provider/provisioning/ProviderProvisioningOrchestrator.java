package com.dwp.services.provider.provisioning;

import com.dwp.core.autoconfig.DwpHttpClientProperties;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.ProviderEstateRepository;
import com.dwp.services.provider.ProviderOperationsRepository;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
public class ProviderProvisioningOrchestrator {

    private static final Set<String> ONBOARDING_SERVICES =
            Set.of("auth", "platform", "people", "asset-storage");

    private final ProviderOperationRepository operationRepository;
    private final ProviderOperationStepRepository stepRepository;
    private final ProviderOperationLeaseRepository leaseRepository;
    private final ProviderOperationEvidenceRepository evidenceRepository;
    private final ProviderOperationProjectionCoordinator projectionCoordinator;
    private final ProviderProvisioningFailureSanitizer failureSanitizer;
    private final ProviderTenantRepository tenantRepository;
    private final EntitlementRepository entitlementRepository;
    private final TenantEntitlementRepository tenantEntitlementRepository;
    private final ProviderEstateRepository estateRepository;
    private final ProviderTenantPlacementRepository placementRepository;
    private final ProviderOnboardingFoundationVerifier foundationVerifier;
    private final ProviderOperationsRepository operationsRepository;
    private final DownstreamProvisioningClient client;
    private final TenantMutationOrchestrator tenantMutationOrchestrator;
    private final ProviderProvisioningAuditRecorder auditRecorder;
    private final ObjectMapper objectMapper;
    private final String workerId;
    private final Duration leaseDuration;

    public ProviderProvisioningOrchestrator(
            ProviderOperationRepository operationRepository,
            ProviderOperationStepRepository stepRepository,
            ProviderOperationLeaseRepository leaseRepository,
            ProviderOperationEvidenceRepository evidenceRepository,
            ProviderOperationProjectionCoordinator projectionCoordinator,
            ProviderProvisioningFailureSanitizer failureSanitizer,
            ProviderTenantRepository tenantRepository,
            EntitlementRepository entitlementRepository,
            TenantEntitlementRepository tenantEntitlementRepository,
            ProviderEstateRepository estateRepository,
            ProviderTenantPlacementRepository placementRepository,
            ProviderOnboardingFoundationVerifier foundationVerifier,
            ProviderOperationsRepository operationsRepository,
            DownstreamProvisioningClient client,
            TenantMutationOrchestrator tenantMutationOrchestrator,
            ProviderProvisioningAuditRecorder auditRecorder,
            ObjectMapper objectMapper,
            DwpHttpClientProperties httpClientProperties,
            @Value("${dwp.provider.onboarding.worker-id:provider-onboarding}") String workerId,
            @Value("${dwp.provider.onboarding.lease-duration:5m}") Duration leaseDuration) {
        if (workerId == null || workerId.isBlank() || workerId.length() > 120) {
            throw new IllegalArgumentException("A bounded provider onboarding worker id is required.");
        }
        Duration maximumDownstreamStep = httpClientProperties.connectTimeout()
                .plus(httpClientProperties.readTimeout())
                .multipliedBy(3)
                .plusSeconds(5);
        if (leaseDuration == null
                || leaseDuration.compareTo(Duration.ofMinutes(1)) < 0
                || leaseDuration.compareTo(Duration.ofMinutes(30)) > 0
                || leaseDuration.compareTo(maximumDownstreamStep) <= 0) {
            throw new IllegalArgumentException(
                    "Provider onboarding lease duration must be 1-30 minutes and exceed the downstream timeout budget.");
        }
        this.operationRepository = operationRepository;
        this.stepRepository = stepRepository;
        this.leaseRepository = leaseRepository;
        this.evidenceRepository = evidenceRepository;
        this.projectionCoordinator = projectionCoordinator;
        this.failureSanitizer = failureSanitizer;
        this.tenantRepository = tenantRepository;
        this.entitlementRepository = entitlementRepository;
        this.tenantEntitlementRepository = tenantEntitlementRepository;
        this.estateRepository = estateRepository;
        this.placementRepository = placementRepository;
        this.foundationVerifier = foundationVerifier;
        this.operationsRepository = operationsRepository;
        this.client = client;
        this.tenantMutationOrchestrator = tenantMutationOrchestrator;
        this.auditRecorder = auditRecorder;
        this.objectMapper = objectMapper;
        this.workerId = workerId.trim();
        this.leaseDuration = leaseDuration;
    }

    public ProviderOperation execute(
            UUID operationId,
            String expectedPlanHash,
            Long expectedVersion,
            boolean retry,
            String correlationId) {
        ProviderRequestContext.requirePermission("OPERATION_EXECUTE");
        ProviderOperation operation = requireOperation(operationId);
        if ("L3".equals(operation.getRiskTier())
                && !operationsRepository.operationApproved(operationId)) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "All required approvals must be completed before this high-risk operation can run.");
        }
        requireVersion(operation.getVersion(), expectedVersion);
        if (expectedPlanHash != null && !constantTimeEquals(operation.getPlanHash(), expectedPlanHash)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "The provider plan hash does not match.");
        }
        if ("SUCCEEDED".equals(operation.getLifecycleState())) return operation;
        if (retry) {
            if (!List.of("PARTIAL", "FAILED", "EXECUTING").contains(operation.getLifecycleState())) {
                throw new BaseException(
                        ErrorCode.INVALID_STATE,
                        "Only a failed, partial, or expired executing operation can retry.");
            }
        } else if (!"PREVIEWED".equals(operation.getLifecycleState())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "Only a previewed operation can execute.");
        }
        UUID leaseToken = leaseRepository.claim(
                operationId, operation.getVersion(), retry, workerId, leaseDuration);
        if (retry) evidenceRepository.abandonRunning(operationId, leaseToken, leaseDuration);
        operation = requireOperation(operationId);

        JsonNode plan = read(operation.getPlan());
        List<ProviderOperationStep> steps = stepRepository
                .findByOperationIdOrderByStepOrderAsc(operationId);
        for (ProviderOperationStep step : steps) {
            if ("SUCCEEDED".equals(step.getLifecycleState())) continue;
            StepResult result = runStep(
                    operation, step, plan, leaseToken, correlationId);
            if (!result.succeeded()) {
                return failOperation(operation, step, result, correlationId, leaseToken);
            }
            operation = requireOperation(operationId);
        }
        return completeOperation(operationId, correlationId, leaseToken);
    }

    private ProviderOperation completeOperation(
            UUID operationId,
            String correlationId,
            UUID leaseToken) {
        try {
            projectionCoordinator.complete(
                    operationId, leaseToken, leaseDuration,
                    () -> auditRecorder.success(operationId, correlationId));
        } catch (ProviderOperationLeaseRepository.OperationLeaseLostException exception) {
            throw leaseConflict("before the terminal result could be recorded", exception);
        }
        return requireOperation(operationId);
    }

    private StepResult runStep(
            ProviderOperation operation,
            ProviderOperationStep step,
            JsonNode plan,
            UUID leaseToken,
            String correlationId) {
        int attemptNumber;
        try {
            attemptNumber = evidenceRepository.startAttempt(
                    operation.getOperationId(), leaseToken, leaseDuration,
                    step.getOperationStepId(), operation.getPlanHash());
            step.setAttemptCount(attemptNumber);
            step.setLifecycleState("RUNNING");
        } catch (ProviderOperationLeaseRepository.OperationLeaseLostException exception) {
            throw leaseConflict("before the next step could start", exception);
        }
        try {
            return executeStep(
                    operation, step, plan, leaseToken, attemptNumber, correlationId);
        } catch (ProviderOperationLeaseRepository.OperationLeaseLostException exception) {
            throw leaseConflict("while a step was executing", exception);
        } catch (RuntimeException exception) {
            ProviderProvisioningFailureSanitizer.Failure failure = failureSanitizer.sanitize(exception);
            return StepResult.failed(failure.code(), failure.message());
        }
    }

    private BaseException leaseConflict(
            String phase,
            ProviderOperationLeaseRepository.OperationLeaseLostException exception) {
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "The provider operation lease expired " + phase + ".",
                exception);
    }

    private StepResult executeStep(
            ProviderOperation operation,
            ProviderOperationStep step,
            JsonNode plan,
            UUID leaseToken,
            int attemptNumber,
            String correlationId) {
        Supplier<StepResult> projection = switch (step.getStepKey()) {
            case "CONTROL_RECORD" -> () -> createControlRecord(operation, plan);
            case "AUTH_TENANT" -> prepareAuth(operation, plan);
            case "PLATFORM_TENANT" -> prepareService(operation, "platform", tenant ->
                    client.provisionPlatform(tenant.getProviderTenantId(), tenant.getAuthTenantId(), plan));
            case "PEOPLE_TENANT" -> prepareService(operation, "people", tenant ->
                    client.provisionPeople(tenant.getProviderTenantId(), tenant.getAuthTenantId(), plan));
            case "ASSET_STORAGE" -> prepareService(operation, "asset-storage", tenant ->
                    client.provisionAssetStorage(tenant.getProviderTenantId(), tenant.getAuthTenantId()));
            case "ACTIVATE_TENANT" -> prepareActivation(
                    operation, leaseToken, correlationId);
            case "SCHEDULE_MAINTENANCE" -> () -> scheduleMaintenance(operation);
            default -> throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Unsupported provider operation step: " + step.getStepKey());
        };
        ProviderOperationProjectionCoordinator.ProjectionResult committed = projectionCoordinator.succeed(
                operation.getOperationId(), leaseToken, leaseDuration,
                step.getOperationStepId(), attemptNumber,
                () -> projectionResult(projection.get()));
        step.setLifecycleState("SUCCEEDED");
        step.setExternalReference(committed.externalReference());
        step.setRedactedResult(committed.redactedResult());
        return StepResult.succeeded(committed.externalReference(), Map.of());
    }

    private StepResult createControlRecord(ProviderOperation operation, JsonNode plan) {
        ProviderRequestContext.Actor actor = ProviderRequestContext.require();
        if (operation.getProviderTenantId() != null) {
            ProviderTenant tenant = requireTenant(operation.getProviderTenantId());
            ProviderTenantPlacementRepository.TenantPlacement placement =
                    placementRepository.initializeOrValidate(
                            tenant.getProviderTenantId(), tenant.getDataRegion(),
                            tenant.getIsolationModel(), ONBOARDING_SERVICES,
                            actor.operatorId(), false);
            foundationVerifier.requireExact(tenant, plan);
            return controlRecordResult(tenant, placement);
        }

        String organizationKey = plan.path("organizationKey").asText();
        UUID organizationId = estateRepository.organizationIdByKey(organizationKey)
                .orElseGet(() -> estateRepository.createOrganization(
                        organizationKey,
                        plan.path("organizationName").asText(),
                        textOrNull(plan.path("legalName")),
                        textOrNull(plan.path("customerReference")),
                        actor.operatorId()));
        estateRepository.ensureOrganizationSubscription(
                organizationId,
                plan.path("serviceTier").asText(),
                textOrNull(plan.path("customerReference")),
                actor.operatorId());
        if (estateRepository.environmentExists(
                organizationId, plan.path("environmentKey").asText())) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The organization environment already exists.");
        }
        ProviderTenant tenant = ProviderTenant.builder()
                .organizationId(organizationId)
                .tenantKey(plan.path("tenantKey").asText())
                .displayName(plan.path("displayName").asText())
                .environmentKey(plan.path("environmentKey").asText())
                .serviceTier(plan.path("serviceTier").asText())
                .dataRegion(plan.path("dataRegion").asText())
                .isolationModel(plan.path("isolationModel").asText())
                .defaultLocale(plan.path("defaultLocale").asText())
                .timeZone(plan.path("timeZone").asText())
                .lifecycleState("PROVISIONING")
                .onboardingState("CONTROL_PLANE_READY")
                .configuration("{}")
                .build();
        tenant = tenantRepository.saveAndFlush(tenant);
        operation.setProviderTenantId(tenant.getProviderTenantId());
        operationRepository.saveAndFlush(operation);
        estateRepository.initializeTenantExtension(
                tenant.getProviderTenantId(), tenant.getConfiguration(), actor.operatorId());
        assignEntitlements(tenant, plan);
        ProviderTenantPlacementRepository.TenantPlacement placement =
                placementRepository.initializeOrValidate(
                        tenant.getProviderTenantId(), tenant.getDataRegion(),
                        tenant.getIsolationModel(), ONBOARDING_SERVICES,
                        actor.operatorId(), true);
        String primaryDomain = textOrNull(plan.path("primaryDomain"));
        estateRepository.createInternalDomain(
                tenant.getProviderTenantId(), tenant.getTenantKey(), actor.operatorId());
        if (primaryDomain != null) {
            String challenge = "dwp-verification=" + tenant.getProviderTenantId();
            estateRepository.createDomain(
                    tenant.getProviderTenantId(), primaryDomain.toLowerCase(Locale.ROOT), "LOGIN", true,
                    challenge, foundationVerifier.verificationTokenHash(challenge), actor.operatorId());
        }
        JsonNode administrator = plan.path("initialAdministrator");
        estateRepository.createTenantAdministrator(
                tenant.getProviderTenantId(),
                administrator.path("email").asText(),
                administrator.path("displayName").asText(),
                actor.operatorId());
        foundationVerifier.requireExact(tenant, plan);
        return controlRecordResult(tenant, placement);
    }

    private StepResult controlRecordResult(
            ProviderTenant tenant,
            ProviderTenantPlacementRepository.TenantPlacement placement) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("providerTenantId", tenant.getProviderTenantId());
        evidence.put("organizationId", tenant.getOrganizationId());
        evidence.put("environmentKey", tenant.getEnvironmentKey());
        evidence.put("deploymentCellId", placement.cellId());
        return StepResult.succeeded(
                tenant.getProviderTenantId().toString(),
                evidence);
    }

    private Supplier<StepResult> prepareAuth(ProviderOperation operation, JsonNode plan) {
        ProviderTenant tenant = requireTenant(operation.getProviderTenantId());
        DownstreamProvisioningClient.AuthProvisioningResult result =
                client.provisionAuth(tenant.getProviderTenantId(), plan);
        return () -> persistAuth(operation.getProviderTenantId(), result);
    }

    private StepResult persistAuth(
            UUID tenantId,
            DownstreamProvisioningClient.AuthProvisioningResult result) {
        ProviderTenant tenant = requireTenant(tenantId);
        if (tenant.getAuthTenantId() != null
                && !Objects.equals(tenant.getAuthTenantId(), result.tenantId())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The auth tenant binding changed before commit.");
        }
        tenant.setAuthTenantId(result.tenantId());
        tenant.setOnboardingState("PENDING_EXTERNAL");
        tenantRepository.saveAndFlush(tenant);
        estateRepository.linkTenantAdministrator(
                tenant.getProviderTenantId(),
                result.administratorEmail(),
                result.administratorUserId(),
                ProviderRequestContext.require().operatorId());
        placementRepository.updateServiceInstance(
                tenant.getProviderTenantId(), "auth", "READY", String.valueOf(result.tenantId()),
                result.schemaVersion(),
                json(Map.of("schemaVersion", result.schemaVersion(), "status", "ready")),
                ProviderRequestContext.require().operatorId());
        return StepResult.succeeded(
                String.valueOf(result.tenantId()),
                Map.of(
                        "authTenantId", result.tenantId(),
                        "administratorUserId", result.administratorUserId(),
                        "schemaVersion", result.schemaVersion()));
    }

    private Supplier<StepResult> prepareService(
            ProviderOperation operation,
            String serviceKey,
            Function<ProviderTenant, DownstreamProvisioningClient.ServiceProvisioningResult> downstreamCall) {
        ProviderTenant tenant = requireTenant(operation.getProviderTenantId());
        requireAuthTenant(tenant);
        Long expectedAuthTenantId = tenant.getAuthTenantId();
        DownstreamProvisioningClient.ServiceProvisioningResult result = downstreamCall.apply(tenant);
        return () -> persistService(operation.getProviderTenantId(), expectedAuthTenantId, serviceKey, result);
    }

    private StepResult persistService(
            UUID tenantId,
            Long expectedAuthTenantId,
            String serviceKey,
            DownstreamProvisioningClient.ServiceProvisioningResult result) {
        ProviderTenant tenant = requireTenant(tenantId);
        if (!Objects.equals(tenant.getAuthTenantId(), expectedAuthTenantId)) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The auth tenant binding changed before commit.");
        }
        String externalReference = updateService(tenant, serviceKey, result);
        return serviceResult(result, externalReference);
    }

    private Supplier<StepResult> prepareActivation(
            ProviderOperation operation,
            UUID leaseToken,
            String correlationId) {
        ProviderTenant tenant = requireTenant(operation.getProviderTenantId());
        TenantMutationOrchestrator.ActivationFence activation =
                tenantMutationOrchestrator.activateForOnboarding(
                        tenant, operation.getOperationId(), leaseToken, correlationId);
        return () -> persistActivation(tenant.getProviderTenantId(), activation);
    }

    private StepResult persistActivation(
            UUID tenantId,
            TenantMutationOrchestrator.ActivationFence activation) {
        tenantMutationOrchestrator.completeOnboardingProjection(
                activation, ProviderRequestContext.require().operatorId());
        return StepResult.succeeded(
                tenantId.toString(),
                Map.of("lifecycle", "ACTIVE", "onboarding", "READY"));
    }

    private StepResult scheduleMaintenance(ProviderOperation operation) {
        UUID maintenanceId = operationsRepository.scheduleMaintenanceWindow(
                        operation.getOperationId(), ProviderRequestContext.require().operatorId())
                .orElseThrow(() -> new BaseException(
                        ErrorCode.INVALID_STATE,
                        "The maintenance window is no longer a schedulable draft."));
        return StepResult.succeeded(
                maintenanceId.toString(),
                Map.of("maintenanceWindowId", maintenanceId, "lifecycle", "SCHEDULED"));
    }

    private ProviderOperation failOperation(
            ProviderOperation operation,
            ProviderOperationStep step,
            StepResult result,
            String correlationId,
            UUID leaseToken) {
        try {
            projectionCoordinator.fail(
                    operation.getOperationId(), leaseToken, leaseDuration,
                    step.getOperationStepId(), step.getAttemptCount(),
                    result.errorCode(), result.errorMessage(),
                    () -> applyFailureProjection(operation.getOperationId(), step, result),
                    () -> auditRecorder.failure(
                            operation.getOperationId(), step.getStepKey(), result.errorCode(),
                            step.getAttemptCount(), correlationId));
        } catch (ProviderOperationLeaseRepository.OperationLeaseLostException exception) {
            throw leaseConflict("before the failure result could be recorded", exception);
        }
        step.setLifecycleState("FAILED");
        step.setLastErrorCode(result.errorCode());
        step.setLastErrorMessage(result.errorMessage());
        return requireOperation(operation.getOperationId());
    }

    private void applyFailureProjection(
            UUID operationId,
            ProviderOperationStep step,
            StepResult result) {
        ProviderOperation operation = requireOperation(operationId);
        boolean tenantOnboarding = "TENANT_ONBOARD".equals(operation.getOperationType());
        UUID tenantId = operation.getProviderTenantId();
        if (tenantOnboarding && tenantId != null) {
            if ("ACTIVATE_TENANT".equals(step.getStepKey())) return;
            ProviderTenant tenant = requireTenant(tenantId);
            if (!"CONTROL_RECORD".equals(step.getStepKey())) {
                tenant.setOnboardingState("FAILED");
                tenantRepository.saveAndFlush(tenant);
            }
            String serviceKey = serviceKey(step.getStepKey());
            if (serviceKey != null) {
                placementRepository.updateServiceInstance(
                        tenantId, serviceKey, "FAILED", null,
                        null,
                        json(Map.of("status", "failed", "errorCode", result.errorCode())),
                        ProviderRequestContext.require().operatorId());
            }
        }
    }

    private void assignEntitlements(ProviderTenant tenant, JsonNode plan) {
        List<String> keys = new ArrayList<>();
        plan.path("entitlements").forEach(value -> keys.add(value.asText()));
        Map<String, Entitlement> catalog = entitlementRepository
                .findByLifecycleStateOrderByEntitlementKeyAsc("ACTIVE")
                .stream().collect(Collectors.toMap(Entitlement::getEntitlementKey, Function.identity()));
        List<TenantEntitlement> assignments = keys.stream()
                .sorted(Comparator.naturalOrder())
                .map(catalog::get)
                .filter(java.util.Objects::nonNull)
                .map(entitlement -> TenantEntitlement.builder()
                        .providerTenantId(tenant.getProviderTenantId())
                        .entitlementId(entitlement.getEntitlementId())
                        .lifecycleState("ACTIVE")
                        .configuration("{}")
                        .build())
                .toList();
        tenantEntitlementRepository.saveAllAndFlush(assignments);
    }

    private String updateService(
            ProviderTenant tenant,
            String serviceKey,
            DownstreamProvisioningClient.ServiceProvisioningResult result) {
        String externalReference = safeExternalReference(
                serviceKey, tenant.getProviderTenantId(), result.externalReference());
        placementRepository.updateServiceInstance(
                tenant.getProviderTenantId(), serviceKey, "READY", externalReference,
                result.schemaVersion(),
                json(Map.of("schemaVersion", result.schemaVersion(), "status", "ready")),
                ProviderRequestContext.require().operatorId());
        return externalReference;
    }

    private StepResult serviceResult(
            DownstreamProvisioningClient.ServiceProvisioningResult result,
            String externalReference) {
        return StepResult.succeeded(
                externalReference,
                Map.of(
                        "tenantId", result.tenantId(),
                        "schemaVersion", result.schemaVersion(),
                        "status", "ready"));
    }

    private ProviderOperationProjectionCoordinator.ProjectionResult projectionResult(StepResult result) {
        return new ProviderOperationProjectionCoordinator.ProjectionResult(
                result.externalReference(), json(result.redactedResult()));
    }

    private String safeExternalReference(String serviceKey, UUID tenantId, String reference) {
        if (reference == null || reference.isBlank()) return null;
        if (reference.startsWith("/")
                || reference.startsWith("file:")
                || reference.matches("^[A-Za-z]:[\\\\/].*")) {
            return serviceKey + ":tenant:" + tenantId;
        }
        return reference;
    }

    private void requireAuthTenant(ProviderTenant tenant) {
        if (tenant.getAuthTenantId() == null) {
            throw new BaseException(ErrorCode.INVALID_STATE, "Auth tenant must be provisioned first.");
        }
    }

    private String serviceKey(String stepKey) {
        return switch (stepKey) {
            case "AUTH_TENANT" -> "auth";
            case "PLATFORM_TENANT" -> "platform";
            case "PEOPLE_TENANT" -> "people";
            case "ASSET_STORAGE" -> "asset-storage";
            default -> null;
        };
    }

    private ProviderOperation requireOperation(UUID operationId) {
        return operationRepository.findById(operationId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private ProviderTenant requireTenant(UUID tenantId) {
        if (tenantId == null) throw new BaseException(ErrorCode.INVALID_STATE);
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private void requireVersion(Long actual, Long expected) {
        long actualValue = actual == null ? 0 : actual;
        if (expected == null || actualValue != expected) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Provider state changed after it was loaded. Refresh and try again.");
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        return right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private JsonNode read(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored provider plan is invalid.", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Provider provisioning serialization failed.", exception);
        }
    }

    private String textOrNull(JsonNode value) {
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText().trim();
    }

    private record StepResult(
            boolean succeeded,
            String externalReference,
            Map<String, Object> redactedResult,
            String errorCode,
            String errorMessage) {

        private static StepResult succeeded(String reference, Map<String, Object> result) {
            return new StepResult(true, reference, new LinkedHashMap<>(result), null, null);
        }

        private static StepResult failed(String code, String message) {
            return new StepResult(false, null, Map.of(), code, message);
        }
    }
}
