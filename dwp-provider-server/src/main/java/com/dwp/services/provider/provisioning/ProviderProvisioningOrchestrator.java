package com.dwp.services.provider.provisioning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.ProviderOperationsRepository;
import com.dwp.services.provider.ProviderEstateRepository;
import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.entitlement.Entitlement;
import com.dwp.services.provider.entitlement.EntitlementRepository;
import com.dwp.services.provider.entitlement.TenantEntitlement;
import com.dwp.services.provider.entitlement.TenantEntitlementRepository;
import com.dwp.services.provider.operation.ProviderOperation;
import com.dwp.services.provider.operation.ProviderOperationRepository;
import com.dwp.services.provider.operation.ProviderOperationStep;
import com.dwp.services.provider.operation.ProviderOperationStepAttempt;
import com.dwp.services.provider.operation.ProviderOperationStepAttemptRepository;
import com.dwp.services.provider.operation.ProviderOperationStepRepository;
import com.dwp.services.provider.security.ProviderRequestContext;
import com.dwp.services.provider.tenant.ProviderTenant;
import com.dwp.services.provider.tenant.ProviderTenantRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ProviderProvisioningOrchestrator {

    private final ProviderOperationRepository operationRepository;
    private final ProviderOperationStepRepository stepRepository;
    private final ProviderOperationStepAttemptRepository attemptRepository;
    private final ProviderTenantRepository tenantRepository;
    private final EntitlementRepository entitlementRepository;
    private final TenantEntitlementRepository tenantEntitlementRepository;
    private final ProviderEstateRepository estateRepository;
    private final ProviderOperationsRepository operationsRepository;
    private final DownstreamProvisioningClient client;
    private final ProviderAuditService auditService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public ProviderProvisioningOrchestrator(
            ProviderOperationRepository operationRepository,
            ProviderOperationStepRepository stepRepository,
            ProviderOperationStepAttemptRepository attemptRepository,
            ProviderTenantRepository tenantRepository,
            EntitlementRepository entitlementRepository,
            TenantEntitlementRepository tenantEntitlementRepository,
            ProviderEstateRepository estateRepository,
            ProviderOperationsRepository operationsRepository,
            DownstreamProvisioningClient client,
            ProviderAuditService auditService,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate) {
        this.operationRepository = operationRepository;
        this.stepRepository = stepRepository;
        this.attemptRepository = attemptRepository;
        this.tenantRepository = tenantRepository;
        this.entitlementRepository = entitlementRepository;
        this.tenantEntitlementRepository = tenantEntitlementRepository;
        this.estateRepository = estateRepository;
        this.operationsRepository = operationsRepository;
        this.client = client;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
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
            if (!List.of("PARTIAL", "FAILED").contains(operation.getLifecycleState())) {
                throw new BaseException(ErrorCode.INVALID_STATE, "Only a failed or partial operation can retry.");
            }
        } else if (!"PREVIEWED".equals(operation.getLifecycleState())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "Only a previewed operation can execute.");
        }
        operation.setLifecycleState("EXECUTING");
        operation.setFailureCode(null);
        operation.setFailureMessage(null);
        if (operation.getStartedAt() == null) operation.setStartedAt(Instant.now());
        operation = operationRepository.saveAndFlush(operation);

        JsonNode plan = read(operation.getPlan());
        List<ProviderOperationStep> steps = stepRepository
                .findByOperationIdOrderByStepOrderAsc(operationId);
        for (ProviderOperationStep step : steps) {
            if ("SUCCEEDED".equals(step.getLifecycleState())) continue;
            StepResult result = runStep(operation, step, plan);
            if (!result.succeeded()) {
                return failOperation(operation, step, result, correlationId);
            }
            operation = requireOperation(operationId);
        }
        operation.setLifecycleState("SUCCEEDED");
        operation.setCompletedAt(Instant.now());
        operation.setFailureCode(null);
        operation.setFailureMessage(null);
        operation = operationRepository.saveAndFlush(operation);
        if ("MAINTENANCE_SCHEDULE".equals(operation.getOperationType())) {
            UUID maintenanceId = operationsRepository.maintenanceWindowId(operation.getOperationId())
                    .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
            auditService.success(
                    "provider.maintenance.scheduled",
                    "MAINTENANCE_WINDOW",
                    maintenanceId.toString(),
                    operation.getProviderTenantId(),
                    organizationId(operation.getProviderTenantId()),
                    correlationId,
                    Map.of("operationId", operation.getOperationId(), "planHash", operation.getPlanHash()));
        } else {
            ProviderTenant tenant = requireTenant(operation.getProviderTenantId());
            auditService.success(
                    "provider.tenant-onboarding.succeeded",
                    "PROVIDER_TENANT",
                    tenant.getProviderTenantId().toString(),
                    tenant.getProviderTenantId(),
                    tenant.getOrganizationId(),
                    correlationId,
                    Map.of("tenantKey", tenant.getTenantKey(), "authTenantId", tenant.getAuthTenantId()));
        }
        return operation;
    }

    private StepResult runStep(ProviderOperation operation, ProviderOperationStep step, JsonNode plan) {
        int attemptNumber = step.getAttemptCount() + 1;
        step.setLifecycleState("RUNNING");
        step.setAttemptCount(attemptNumber);
        step.setStartedAt(Instant.now());
        step.setCompletedAt(null);
        step.setLastErrorCode(null);
        step.setLastErrorMessage(null);
        step.setNextRetryAt(null);
        step = stepRepository.saveAndFlush(step);
        ProviderOperationStepAttempt attempt = ProviderOperationStepAttempt.builder()
                .operationStepId(step.getOperationStepId())
                .attemptNumber(attemptNumber)
                .lifecycleState("RUNNING")
                .requestFingerprint(operation.getPlanHash())
                .build();
        attempt = attemptRepository.saveAndFlush(attempt);
        try {
            StepResult result = executeStep(operation, step.getStepKey(), plan);
            step.setLifecycleState("SUCCEEDED");
            step.setExternalReference(result.externalReference());
            step.setRedactedResult(json(result.redactedResult()));
            step.setCompletedAt(Instant.now());
            stepRepository.saveAndFlush(step);
            attempt.setLifecycleState("SUCCEEDED");
            attempt.setRedactedResult(json(result.redactedResult()));
            attempt.setCompletedAt(Instant.now());
            attemptRepository.saveAndFlush(attempt);
            return result;
        } catch (RuntimeException exception) {
            String code = exception instanceof RestClientResponseException response
                    ? "HTTP_" + response.getStatusCode().value()
                    : "PROVISIONING_FAILED";
            String message = safeMessage(exception);
            step.setLifecycleState("FAILED");
            step.setLastErrorCode(code);
            step.setLastErrorMessage(message);
            step.setCompletedAt(Instant.now());
            stepRepository.saveAndFlush(step);
            attempt.setLifecycleState("FAILED");
            attempt.setErrorCode(code);
            attempt.setErrorMessage(message);
            attempt.setCompletedAt(Instant.now());
            attemptRepository.saveAndFlush(attempt);
            return StepResult.failed(code, message);
        }
    }

    private StepResult executeStep(ProviderOperation operation, String stepKey, JsonNode plan) {
        return switch (stepKey) {
            case "CONTROL_RECORD" -> createControlRecord(operation, plan);
            case "AUTH_TENANT" -> provisionAuth(operation, plan);
            case "PLATFORM_TENANT" -> provisionPlatform(operation, plan);
            case "PEOPLE_TENANT" -> provisionPeople(operation, plan);
            case "ASSET_STORAGE" -> provisionAssetStorage(operation);
            case "ACTIVATE_TENANT" -> activateTenant(operation);
            case "SCHEDULE_MAINTENANCE" -> scheduleMaintenance(operation);
            default -> throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Unsupported provider operation step: " + stepKey);
        };
    }

    private StepResult createControlRecord(ProviderOperation operation, JsonNode plan) {
        StepResult result = transactionTemplate.execute(status -> {
            ProviderRequestContext.Actor actor = ProviderRequestContext.require();
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
            estateRepository.initializeServiceInstances(
                    tenant.getProviderTenantId(), tenant.getDataRegion(), actor.operatorId());
            String primaryDomain = textOrNull(plan.path("primaryDomain"));
            estateRepository.createInternalDomain(
                    tenant.getProviderTenantId(), tenant.getTenantKey(), actor.operatorId());
            if (primaryDomain != null) {
                String challenge = "dwp-verification=" + tenant.getProviderTenantId();
                estateRepository.createDomain(
                        tenant.getProviderTenantId(), primaryDomain.toLowerCase(), "LOGIN", true,
                        challenge, sha256(challenge), actor.operatorId());
            }
            JsonNode administrator = plan.path("initialAdministrator");
            estateRepository.createTenantAdministrator(
                    tenant.getProviderTenantId(),
                    administrator.path("email").asText(),
                    administrator.path("displayName").asText(),
                    actor.operatorId());
            return StepResult.succeeded(
                    tenant.getProviderTenantId().toString(),
                    Map.of(
                            "providerTenantId", tenant.getProviderTenantId(),
                            "organizationId", organizationId,
                            "environmentKey", tenant.getEnvironmentKey()));
        });
        if (result == null) throw new IllegalStateException("Control record transaction returned no result.");
        return result;
    }

    private StepResult provisionAuth(ProviderOperation operation, JsonNode plan) {
        ProviderTenant tenant = requireTenant(operation.getProviderTenantId());
        DownstreamProvisioningClient.AuthProvisioningResult result =
                client.provisionAuth(tenant.getProviderTenantId(), plan);
        tenant.setAuthTenantId(result.tenantId());
        tenant.setOnboardingState("PENDING_EXTERNAL");
        tenantRepository.saveAndFlush(tenant);
        estateRepository.linkTenantAdministrator(
                tenant.getProviderTenantId(),
                result.administratorEmail(),
                result.administratorUserId(),
                ProviderRequestContext.require().operatorId());
        estateRepository.updateServiceInstance(
                tenant.getProviderTenantId(), "auth", "READY", String.valueOf(result.tenantId()),
                json(Map.of("schemaVersion", result.schemaVersion(), "status", "ready")),
                ProviderRequestContext.require().operatorId());
        return StepResult.succeeded(
                String.valueOf(result.tenantId()),
                Map.of(
                        "authTenantId", result.tenantId(),
                        "administratorUserId", result.administratorUserId(),
                        "schemaVersion", result.schemaVersion()));
    }

    private StepResult provisionPlatform(ProviderOperation operation, JsonNode plan) {
        ProviderTenant tenant = requireTenant(operation.getProviderTenantId());
        requireAuthTenant(tenant);
        DownstreamProvisioningClient.ServiceProvisioningResult result =
                client.provisionPlatform(tenant.getProviderTenantId(), tenant.getAuthTenantId(), plan);
        String externalReference = updateService(tenant, "platform", result);
        return serviceResult(result, externalReference);
    }

    private StepResult provisionPeople(ProviderOperation operation, JsonNode plan) {
        ProviderTenant tenant = requireTenant(operation.getProviderTenantId());
        requireAuthTenant(tenant);
        DownstreamProvisioningClient.ServiceProvisioningResult result =
                client.provisionPeople(tenant.getProviderTenantId(), tenant.getAuthTenantId(), plan);
        String externalReference = updateService(tenant, "people", result);
        return serviceResult(result, externalReference);
    }

    private StepResult provisionAssetStorage(ProviderOperation operation) {
        ProviderTenant tenant = requireTenant(operation.getProviderTenantId());
        DownstreamProvisioningClient.ServiceProvisioningResult result =
                client.provisionAssetStorage(tenant.getProviderTenantId());
        String externalReference = updateService(tenant, "asset-storage", result);
        return serviceResult(result, externalReference);
    }

    private StepResult activateTenant(ProviderOperation operation) {
        ProviderTenant tenant = requireTenant(operation.getProviderTenantId());
        client.updateLifecycle(tenant.getProviderTenantId(), "ACTIVE");
        tenant.setLifecycleState("ACTIVE");
        tenant.setOnboardingState("READY");
        tenantRepository.saveAndFlush(tenant);
        return StepResult.succeeded(
                tenant.getProviderTenantId().toString(),
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
            String correlationId) {
        operation = requireOperation(operation.getOperationId());
        operation.setLifecycleState("PARTIAL");
        operation.setFailureCode(result.errorCode());
        operation.setFailureMessage(result.errorMessage());
        operation = operationRepository.saveAndFlush(operation);
        boolean tenantOnboarding = "TENANT_ONBOARD".equals(operation.getOperationType());
        UUID tenantId = operation.getProviderTenantId();
        UUID organizationId = null;
        if (tenantOnboarding && tenantId != null) {
            ProviderTenant tenant = requireTenant(tenantId);
            tenant.setOnboardingState("FAILED");
            tenantRepository.saveAndFlush(tenant);
            organizationId = tenant.getOrganizationId();
            String serviceKey = serviceKey(step.getStepKey());
            if (serviceKey != null) {
                estateRepository.updateServiceInstance(
                        tenantId, serviceKey, "FAILED", null,
                        json(Map.of("status", "failed", "errorCode", result.errorCode())),
                        ProviderRequestContext.require().operatorId());
            }
        }
        if (!tenantOnboarding) {
            organizationId = organizationId(tenantId);
        }
        auditService.failed(
                tenantOnboarding
                        ? "provider.tenant-onboarding.step-failed"
                        : "provider.maintenance.schedule-failed",
                "PROVIDER_OPERATION",
                operation.getOperationId().toString(),
                tenantId,
                organizationId,
                correlationId,
                Map.of(
                        "step", step.getStepKey(),
                        "errorCode", result.errorCode(),
                        "attempt", step.getAttemptCount()));
        return operation;
    }

    private UUID organizationId(UUID tenantId) {
        if (tenantId == null) return null;
        return tenantRepository.findById(tenantId).map(ProviderTenant::getOrganizationId).orElse(null);
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
        tenantEntitlementRepository.saveAll(assignments);
    }

    private String updateService(
            ProviderTenant tenant,
            String serviceKey,
            DownstreamProvisioningClient.ServiceProvisioningResult result) {
        String externalReference = safeExternalReference(
                serviceKey, tenant.getProviderTenantId(), result.externalReference());
        estateRepository.updateServiceInstance(
                tenant.getProviderTenantId(), serviceKey, "READY", externalReference,
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

    private String safeMessage(RuntimeException exception) {
        String message;
        if (exception instanceof RestClientResponseException response
                && response.getResponseBodyAsString() != null
                && !response.getResponseBodyAsString().isBlank()) {
            message = "Downstream service rejected the provisioning contract (HTTP "
                    + response.getStatusCode().value() + ").";
        } else if (exception instanceof RestClientResponseException response) {
            message = "Downstream provisioning failed (HTTP "
                    + response.getStatusCode().value() + ").";
        } else if (exception instanceof DataAccessException) {
            message = "Provider state persistence failed. Review the correlated service trace.";
        } else if (exception instanceof BaseException) {
            message = exception.getMessage();
        } else {
            message = "Provider step failed. Review the correlated service trace.";
        }
        if (message == null || message.isBlank()) message = exception.getClass().getSimpleName();
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
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
