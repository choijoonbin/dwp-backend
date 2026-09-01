package com.dwp.services.provider;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.operation.ProviderOperation;
import com.dwp.services.provider.operation.ProviderOperationRepository;
import com.dwp.services.provider.operation.ProviderOperationStep;
import com.dwp.services.provider.operation.ProviderOperationStepRepository;
import com.dwp.services.provider.security.ProviderRequestContext;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class ProviderReliabilityControl {

    private final ProviderOperationRepository operationRepository;
    private final ProviderOperationStepRepository stepRepository;
    private final ProviderEstateRepository estateRepository;
    private final ProviderOperationsRepository operationsRepository;
    private final ProviderAuditService auditService;
    private final ProviderControlPlaneContext context;

    ProviderReliabilityControl(
            ProviderOperationRepository operationRepository,
            ProviderOperationStepRepository stepRepository,
            ProviderEstateRepository estateRepository,
            ProviderOperationsRepository operationsRepository,
            ProviderAuditService auditService,
            ProviderControlPlaneContext context) {
        this.operationRepository = operationRepository;
        this.stepRepository = stepRepository;
        this.estateRepository = estateRepository;
        this.operationsRepository = operationsRepository;
        this.auditService = auditService;
        this.context = context;
    }

    ProviderDtos.OperationApprovalSummary decideOperationApproval(
            UUID approvalId,
            String correlationId,
            ProviderDtos.DecideOperationApprovalRequest request) {
        ProviderRequestContext.requirePermission("CHANGE_APPROVE");
        ProviderOperationsRepository.ApprovalRecord approval = operationsRepository.approval(approvalId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        context.requireVersion(approval.version(), request.version());
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
        ProviderOperation operation = context.requireOperation(approval.operationId());
        if ("REJECTED".equals(request.decision()) && "PREVIEWED".equals(operation.getLifecycleState())) {
            operation.setLifecycleState("CANCELLED");
            operation.setFailureCode("CHANGE_REJECTED");
            operation.setFailureMessage(request.reason().trim());
            operationRepository.saveAndFlush(operation);
            if ("MAINTENANCE_SCHEDULE".equals(operation.getOperationType())) {
                operationsRepository.cancelMaintenanceWindow(operation.getOperationId(), actor.operatorId());
            }
        }
        auditService.success(
                "provider.operation-approval.decided", "OPERATION_APPROVAL", approvalId.toString(),
                operation.getProviderTenantId(),
                operation.getProviderTenantId() == null
                        ? null
                        : context.requireTenant(operation.getProviderTenantId()).getOrganizationId(),
                correlationId,
                Map.of("decision", request.decision(), "reason", request.reason().trim()));
        return operationsRepository.operationApprovals(null).stream()
                .filter(item -> item.operationApprovalId().equals(approvalId))
                .findFirst().orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    ProviderDtos.ServiceIncidentSummary createIncident(
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
                request.tenantId() == null ? null : context.requireTenant(request.tenantId()).getOrganizationId(),
                correlationId,
                Map.of(
                        "severity", request.severity(),
                        "impactScope", request.impactScope(),
                        "title", request.title().trim()));
        return operationsRepository.incidents(200).stream()
                .filter(item -> item.incidentId().equals(incidentId))
                .findFirst().orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    ProviderDtos.ServiceIncidentSummary updateIncident(
            UUID incidentId,
            String correlationId,
            ProviderDtos.UpdateIncidentRequest request) {
        ProviderRequestContext.requirePermission("INCIDENT_WRITE");
        ProviderOperationsRepository.IncidentRecord incident = operationsRepository.incident(incidentId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        context.requireVersion(incident.version(), request.version());
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

    ProviderDtos.MaintenanceWindowSummary createMaintenanceWindow(
            String correlationId,
            ProviderDtos.CreateMaintenanceWindowRequest request) {
        ProviderRequestContext.requirePermission("MAINTENANCE_WRITE");
        validateMaintenanceWindow(request);
        ProviderRequestContext.Actor actor = ProviderRequestContext.require();
        Map<String, Object> plan = maintenancePlan(request);
        String planJson = context.json(plan);
        String planHash = context.sha256(planJson);
        ProviderOperation operation = ProviderOperation.builder()
                .providerTenantId(request.tenantId())
                .operationType("MAINTENANCE_SCHEDULE")
                .idempotencyKey("maintenance:schedule:"
                        + request.trackingKey().trim().toLowerCase(Locale.ROOT))
                .lifecycleState("PREVIEWED")
                .riskTier("L3")
                .requestedBy(actor.operatorId())
                .justification(request.summary().trim())
                .planHash(planHash)
                .plan(planJson)
                .build();
        UUID maintenanceId;
        try {
            operation = operationRepository.saveAndFlush(operation);
            stepRepository.saveAll(maintenanceSteps(operation.getOperationId()));
            operationsRepository.ensureOperationApproval(operation);
            maintenanceId = operationsRepository.createMaintenanceWindow(
                    request, actor.operatorId(), operation.getOperationId());
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "The maintenance window conflicts with the current provider catalog or tracking key.",
                    exception);
        }
        auditService.success(
                "provider.maintenance.review-requested", "MAINTENANCE_WINDOW", maintenanceId.toString(),
                request.tenantId(),
                request.tenantId() == null
                        ? null
                        : context.requireTenant(request.tenantId()).getOrganizationId(),
                correlationId,
                Map.of(
                        "trackingKey", request.trackingKey(),
                        "scopeType", request.scopeType(),
                        "impactType", request.impactType(),
                        "operationId", operation.getOperationId(),
                        "planHash", planHash,
                        "startsAt", request.startsAt().toString(),
                        "endsAt", request.endsAt().toString()));
        return operationsRepository.maintenanceWindows().stream()
                .filter(item -> item.maintenanceWindowId().equals(maintenanceId))
                .findFirst().orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private void validateIncidentScope(ProviderDtos.CreateIncidentRequest request) {
        int targetCount = 0;
        if (context.normalized(request.serviceKey()) != null) targetCount++;
        if (context.normalized(request.regionKey()) != null) targetCount++;
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
                context.requireTenant(request.tenantId());
            }
            default -> throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Unknown incident scope.");
        }
    }

    private void validateMaintenanceWindow(ProviderDtos.CreateMaintenanceWindowRequest request) {
        if ("NO_IMPACT".equals(request.impactType()) && request.expectedImpactSeconds() != 0) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "No-impact maintenance cannot declare customer interruption time.");
        }
        if (!request.endsAt().isAfter(request.startsAt())
                || !request.startsAt().isAfter(Instant.now())) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Planned maintenance must start in the future and end after it starts.");
        }
        if (request.customerNoticeAt().isAfter(Instant.now().plusSeconds(60))) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "A scheduled maintenance window requires a customer notice that has already been issued.");
        }
        long noticeHours = Duration.between(request.customerNoticeAt(), request.startsAt()).toHours();
        if (noticeHours < request.minimumNoticeHours()) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The customer notice does not meet the configured minimum notice period.");
        }
        int targetCount = 0;
        if (context.normalized(request.serviceKey()) != null) targetCount++;
        if (context.normalized(request.regionKey()) != null) targetCount++;
        if (request.deploymentCellId() != null) targetCount++;
        if (request.tenantId() != null) targetCount++;
        if (("GLOBAL".equals(request.scopeType()) && targetCount != 0)
                || (!"GLOBAL".equals(request.scopeType()) && targetCount != 1)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The maintenance scope must have exactly one matching target.");
        }
        switch (request.scopeType()) {
            case "GLOBAL" -> {
                // Global maintenance intentionally has no target identifier.
            }
            case "REGION" -> {
                if (request.regionKey() == null || estateRepository.regions().stream()
                        .noneMatch(region -> region.regionKey().equals(request.regionKey()))) {
                    throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "A valid region is required.");
                }
            }
            case "CELL" -> {
                if (request.deploymentCellId() == null || operationsRepository.cellPostures().stream()
                        .noneMatch(cell -> cell.deploymentCellId().equals(request.deploymentCellId()))) {
                    throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "A valid deployment cell is required.");
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
                context.requireTenant(request.tenantId());
            }
            default -> throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Unknown maintenance scope.");
        }
    }

    private Map<String, Object> maintenancePlan(
            ProviderDtos.CreateMaintenanceWindowRequest request) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("contract", "dwp.provider.maintenance-schedule.v1");
        plan.put("displayName", request.title().trim());
        plan.put("trackingKey", request.trackingKey().trim());
        plan.put("summary", request.summary().trim());
        plan.put("scopeType", request.scopeType());
        if (context.normalized(request.serviceKey()) != null) {
            plan.put("serviceKey", request.serviceKey().trim());
        }
        if (context.normalized(request.regionKey()) != null) {
            plan.put("regionKey", request.regionKey().trim());
        }
        if (request.deploymentCellId() != null) {
            plan.put("deploymentCellId", request.deploymentCellId());
        }
        if (request.tenantId() != null) {
            plan.put("tenantId", request.tenantId());
        }
        plan.put("impactType", request.impactType());
        plan.put("expectedImpactSeconds", request.expectedImpactSeconds());
        plan.put("startsAt", request.startsAt().toString());
        plan.put("endsAt", request.endsAt().toString());
        plan.put("customerNoticeAt", request.customerNoticeAt().toString());
        plan.put("minimumNoticeHours", request.minimumNoticeHours());
        plan.put("steps", List.of("SCHEDULE_MAINTENANCE"));
        plan.put("executionModel", "CONTROLLED_SINGLE_STEP");
        return plan;
    }

    private List<ProviderOperationStep> maintenanceSteps(UUID operationId) {
        return List.of(step(operationId, 1, "SCHEDULE_MAINTENANCE", "dwp-provider-server"));
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
