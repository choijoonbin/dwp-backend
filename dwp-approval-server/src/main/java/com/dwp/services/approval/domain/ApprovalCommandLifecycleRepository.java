package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalDecisionRevisionContext;
import com.dwp.services.approval.security.ApprovalManagementScopeContext;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;


class ApprovalCommandLifecycleRepository extends ApprovalCommandJdbcRepository {
    ApprovalCommandLifecycleRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        super(jdbc, objectMapper);
    }

    public UUID createDraft(
            ApprovalRequestContext.Actor actor,
            ApprovalDtos.CreateRequest request,
            String correlationId) {
        String priority = normalizedPriority(request.priority());
        UUID requestId = UUID.randomUUID();
        String requestNumber = "APR-" + Instant.now().toEpochMilli() + "-"
                + requestId.toString().substring(0, 6).toUpperCase(Locale.ROOT);
        Map<String, Object> payload = requestPayload(request.summary(), request.payload());
        WorkflowRuntime workflow = workflow(
                actor.tenantId(), request.workflowId(), request.formId(), payload, false);
        validateRequestPayload(workflow.formSchema(), payload, false);
        String payloadJson = payloadSupport.json(payload);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("requestId", requestId)
                .addValue("tenantId", actor.tenantId())
                .addValue("requestNumber", requestNumber)
                .addValue("workflowVersionId", workflow.workflowVersionId())
                .addValue("formVersionId", workflow.formVersionId())
                .addValue("title", request.title().trim())
                .addValue("summary", request.summary().trim())
                .addValue("userId", actor.userId())
                .addValue("personPublicId", actor.personPublicId())
                .addValue("requesterName", actor.displayName())
                .addValue("priority", priority)
                .addValue("classification", workflow.dataClassification())
                .addValue("managementScope", workflow.managementResourceSetKey())
                .addValue("payload", payloadJson)
                .addValue("correlationId", correlationId);
        jdbc.update(ApprovalCommandSql01.CREATE_DRAFT_INSERT_APR_REQUESTS, params);
        jdbc.update(ApprovalCommandSql01.APR_REQUESTS_INSERT_APR_REQUEST_PAYLOADS, params);
        appendPayloadRevision(actor, requestId, "DRAFT_CREATED", correlationId, "Draft created");
        appendEvent(actor, requestId, "REQUEST_DRAFTED", "Draft created", correlationId, Map.of());
        return requestId;
    }

    public void updateDraft(
            ApprovalRequestContext.Actor actor,
            UUID requestId,
            ApprovalDtos.UpdateDraftRequest request,
            String correlationId) {
        String priority = normalizedPriority(request.priority());
        Map<String, Object> payload = requestPayload(request.summary(), request.payload());
        WorkflowRuntime workflow = workflow(
                actor.tenantId(), request.workflowId(), request.formId(), payload, false);
        validateRequestPayload(workflow.formSchema(), payload, false);
        String payloadJson = payloadSupport.json(payload);
        MapSqlParameterSource params = actorParams(actor)
                .addValue("requestId", requestId)
                .addValue("workflowVersionId", workflow.workflowVersionId())
                .addValue("formVersionId", workflow.formVersionId())
                .addValue("title", request.title().trim())
                .addValue("summary", request.summary().trim())
                .addValue("priority", priority)
                .addValue("classification", workflow.dataClassification())
                .addValue("managementScope", workflow.managementResourceSetKey())
                .addValue("payload", payloadJson)
                .addValue("expectedVersion", request.expectedVersion());
        int updated = jdbc.update(ApprovalCommandSql01.UPDATE_DRAFT_UPDATE_APR_REQUESTS, params);
        requireUpdated(updated);
        jdbc.update(ApprovalCommandSql01.UPDATE_DRAFT_UPDATE_APR_REQUEST_PAYLOADS, params);
        appendPayloadRevision(actor, requestId, "DRAFT_UPDATED", correlationId, "Draft updated");
        appendEvent(actor, requestId, "REQUEST_DRAFT_UPDATED", "Draft updated", correlationId,
                Map.of("workflowId", request.workflowId().toString()));
    }

    public void submit(
            ApprovalRequestContext.Actor actor,
            UUID requestId,
            long expectedVersion,
            String correlationId) {
        RequestRuntime request = ownedRequest(actor, requestId);
        if (!"DRAFT".equals(request.status())) throw new BaseException(ErrorCode.INVALID_STATE);
        if (request.title() == null || request.title().isBlank()) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Approval title is required before submission.");
        }
        validateRequestPayload(request.formSchema(), request.payload(), true);
        if ("CONDITIONAL".equals(request.bindingType())
                && !matchesRouteCondition(request.bindingCondition(), request.payload())) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "The selected approval route does not match this request.");
        }
        List<ApprovalCommandRepository.RuntimeStep> steps = request.steps();
        ApprovalCommandRepository.RuntimeStep firstStep = steps.get(0);
        UUID firstStepId = UUID.randomUUID();
        UUID firstTaskId = UUID.randomUUID();
        MapSqlParameterSource params = actorParams(actor)
                .addValue("requestId", requestId)
                .addValue("expectedVersion", expectedVersion)
                .addValue("slaMinutes", request.slaMinutes())
                .addValue("correlationId", correlationId);
        int updated = jdbc.update(ApprovalCommandSql01.SUBMIT_UPDATE_APR_REQUESTS, params);
        requireUpdated(updated);

        int cumulativeMinutes = 0;
        for (int index = 0; index < steps.size(); index++) {
            ApprovalCommandRepository.RuntimeStep step = steps.get(index);
            cumulativeMinutes = Math.addExact(cumulativeMinutes, step.slaMinutes());
            UUID stepId = index == 0 ? firstStepId : UUID.randomUUID();
            jdbc.update(ApprovalCommandSql01.SUBMIT_INSERT_APR_STEPS, actorParams(actor)
                    .addValue("requestId", requestId)
                    .addValue("stepId", stepId)
                    .addValue("stepKey", step.key())
                    .addValue("stepName", step.name())
                    .addValue("sequenceNumber", index + 1)
                    .addValue("approvalMode", step.mode())
                    .addValue("candidateRole", step.candidateRole())
                    .addValue("status", index == 0 ? "IN_PROGRESS" : "WAITING")
                    .addValue("cumulativeMinutes", cumulativeMinutes));
        }
        jdbc.update(ApprovalCommandSql01.APR_STEPS_INSERT_APR_TASKS, actorParams(actor)
                .addValue("requestId", requestId)
                .addValue("taskId", firstTaskId)
                .addValue("stepId", firstStepId)
                .addValue("candidateRole", firstStep.candidateRole())
                .addValue("stepSlaMinutes", firstStep.slaMinutes()));
        appendEvent(actor, requestId, "REQUEST_SUBMITTED", "Request submitted", correlationId,
                Map.of(
                        "taskId", firstTaskId.toString(),
                        "stepKey", firstStep.key(),
                        "stepCount", steps.size()));
        appendIntegration(actor, requestId, "approval.request.submitted", correlationId,
                Map.of(
                        "requestId", requestId.toString(),
                        "taskId", firstTaskId.toString(),
                        "recipientUserId", actor.userId(),
                        "requestTitle", request.title(),
                        "stepKey", firstStep.key(),
                        "stepCount", steps.size()));
    }

    public void withdraw(
            ApprovalRequestContext.Actor actor,
            UUID requestId,
            long expectedVersion,
            String correlationId) {
        MapSqlParameterSource params = actorParams(actor)
                .addValue("requestId", requestId)
                .addValue("expectedVersion", expectedVersion);
        int updated = jdbc.update(ApprovalCommandSql01.WITHDRAW_UPDATE_APR_REQUESTS, params);
        requireUpdated(updated);
        jdbc.update(ApprovalCommandSql01.IN_UPDATE_APR_TASKS, params);
        jdbc.update(ApprovalCommandSql01.IN_UPDATE_APR_STEPS, params);
        appendEvent(actor, requestId, "REQUEST_WITHDRAWN", "Request withdrawn", correlationId, Map.of());
        appendIntegration(actor, requestId, "approval.request.withdrawn", correlationId,
                Map.of("requestId", requestId.toString()));
    }

    public void respondToInformationRequest(
            ApprovalRequestContext.Actor actor,
            UUID requestId,
            ApprovalDtos.InformationResponseRequest request,
            String correlationId) {
        InformationRuntime current = informationRuntime(actor, requestId);
        Map<String, Object> amendedPayload = new LinkedHashMap<>(current.payload());
        if (request.payload() != null) amendedPayload.putAll(request.payload());
        validateRequestPayload(current.formSchema(), amendedPayload, true);
        List<String> amendedFields = new java.util.ArrayList<>();
        for (String key : amendedPayload.keySet()) {
            if (!java.util.Objects.equals(current.payload().get(key), amendedPayload.get(key))) {
                amendedFields.add(key);
            }
        }
        amendedFields.sort(String::compareTo);
        String amendedPayloadJson = payloadSupport.json(amendedPayload);
        MapSqlParameterSource params = actorParams(actor)
                .addValue("requestId", requestId)
                .addValue("expectedVersion", request.expectedVersion())
                .addValue("message", request.message().trim())
                .addValue("payload", amendedPayloadJson);
        int updated = jdbc.update(ApprovalCommandSql01.RESPOND_TO_INFORMATION_REQUEST_UPDATE_APR_REQUESTS, params);
        requireUpdated(updated);
        if (!amendedFields.isEmpty()) {
            jdbc.update(ApprovalCommandSql01.RESPOND_TO_INFORMATION_REQUEST_UPDATE_APR_REQUEST_PAYLOADS, params);
            appendPayloadRevision(
                    actor,
                    requestId,
                    "INFORMATION_RESPONDED",
                    correlationId,
                    request.message().trim());
        }
        int resumed = jdbc.update(ApprovalCommandSql01.RESPOND_TO_INFORMATION_REQUEST_UPDATE_APR_TASKS, params);
        if (resumed == 0) throw new BaseException(ErrorCode.INVALID_STATE);
        appendEvent(actor, requestId, "INFORMATION_RESPONDED", request.message().trim(), correlationId,
                Map.of(
                        "responseLength", request.message().trim().length(),
                        "amendedFields", amendedFields));
        appendIntegration(actor, requestId, "approval.request.information.responded", correlationId,
                Map.of("requestId", requestId.toString()));
    }

    public void claim(
            ApprovalRequestContext.Actor actor,
            ApprovalQueryRepository.TaskAccess task,
            long expectedVersion,
            String correlationId) {
        if (task.assigneeUserId() != null && !task.assigneeUserId().equals(actor.userId())
                && !task.delegatedAccess()) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        if (task.candidateRole() != null && !actor.roles().contains(task.candidateRole())
                && task.assigneeUserId() == null && !task.delegatedAccess()) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        int updated = jdbc.update(ApprovalCommandSql01.CLAIM_UPDATE_APR_TASKS, actorParams(actor)
                .addValue("personPublicId", actor.personPublicId())
                .addValue("delegatedFromUserId", task.delegatedFromUserId())
                .addValue("taskId", task.summary().taskId())
                .addValue("expectedVersion", expectedVersion));
        requireUpdated(updated);
        appendEvent(actor, task.summary().requestId(), "TASK_CLAIMED", "Task claimed",
                correlationId, Map.of(
                        "taskId", task.summary().taskId().toString(),
                        "stepName", task.summary().stepName(),
                        "stepSequence", task.summary().stepSequence()));
    }

    public ApprovalCommandRepository.DecisionResult decide(
            ApprovalRequestContext.Actor actor,
            ApprovalQueryRepository.TaskAccess task,
            ApprovalDtos.DecisionRequest decision,
            String correlationId) {
        PolicyRuntime selfApprovalPolicy = policy(
                actor.tenantId(), "BLOCK_SELF_APPROVAL",
                task.managementResourceSetKey());
        if (task.requesterUserId() == actor.userId()
                && (selfApprovalPolicy.blocks()
                || com.dwp.services.approval.security.ApprovalPilotAuthorizationContext
                .requiresPredicate("predicate.approval-task-decision.v1"))) {
            throw new BaseException(
                    ErrorCode.SOD_CONFLICT,
                    "A requester cannot decide their own request.");
        }
        if (com.dwp.services.approval.security.ApprovalPilotAuthorizationContext
                .requiresPredicate("predicate.approval-task-decision.v1")
                && task.assigneeUserId() == null && !task.delegatedAccess()) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "An approval task must be claimed before it can be decided.");
        }
        if (task.assigneeUserId() != null && !task.assigneeUserId().equals(actor.userId())
                && !task.delegatedAccess()) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        String normalized = decision.decision().trim().toUpperCase(Locale.ROOT);
        String taskStatus;
        String requestStatus;
        String eventType;
        switch (normalized) {
            case "APPROVE" -> {
                taskStatus = "APPROVED";
                requestStatus = "IN_REVIEW";
                eventType = "TASK_APPROVED";
            }
            case "REJECT" -> {
                PolicyRuntime rejectionPolicy = policy(
                        actor.tenantId(), "REQUIRE_REJECT_REASON",
                        task.managementResourceSetKey());
                int minimumLength = rejectionPolicy.integer("minimumLength", 8, 4, 1000);
                if (rejectionPolicy.blocks()
                        && (decision.comment() == null
                            || decision.comment().trim().length() < minimumLength)) {
                    throw new BaseException(
                            ErrorCode.INVALID_INPUT_VALUE,
                            "A rejection reason of at least " + minimumLength
                                    + " characters is required.");
                }
                taskStatus = "REJECTED";
                requestStatus = "REJECTED";
                eventType = "TASK_REJECTED";
            }
            case "REQUEST_INFO" -> {
                if (decision.comment() == null || decision.comment().trim().length() < 4) {
                    throw new BaseException(
                            ErrorCode.INVALID_INPUT_VALUE,
                            "An information request message is required.");
                }
                taskStatus = "INFO_REQUESTED";
                requestStatus = "NEEDS_INFO";
                eventType = "INFORMATION_REQUESTED";
            }
            default -> throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The approval decision is not supported.");
        }

        MapSqlParameterSource params = actorParams(actor)
                .addValue("taskId", task.summary().taskId())
                .addValue("requestId", task.summary().requestId())
                .addValue("expectedVersion", decision.expectedVersion())
                .addValue("taskStatus", taskStatus)
                .addValue("requestStatus", requestStatus)
                .addValue("personPublicId", actor.personPublicId())
                .addValue("delegatedFromUserId", task.delegatedFromUserId())
                .addValue("reason", normalizeComment(decision.comment()));
        int updated = jdbc.update(ApprovalCommandSql01.DECIDE_UPDATE_APR_TASKS, params);
        requireUpdated(updated);
        jdbc.update(ApprovalCommandSql01.IN_UPDATE_APR_STEPS_2, params);
        NextStep nextStep = null;
        if ("APPROVE".equals(normalized)) {
            nextStep = nextWaitingStep(actor.tenantId(), task.summary().requestId());
            if (nextStep == null) {
                requestStatus = "APPROVED";
            } else {
                activateNextStep(actor, task.summary().requestId(), nextStep, correlationId);
            }
        } else if ("REJECT".equals(normalized)) {
            jdbc.update(ApprovalCommandSql01.IN_UPDATE_APR_STEPS_3, params);
        }
        params.addValue("requestStatus", requestStatus);
        jdbc.update(ApprovalCommandSql01.IN_UPDATE_APR_REQUESTS, params);
        Map<String, Object> decisionEvidence = new LinkedHashMap<>();
        decisionEvidence.put("taskId", task.summary().taskId().toString());
        decisionEvidence.put("decision", normalized);
        decisionEvidence.put("delegated", task.delegatedAccess());
        decisionEvidence.put("stepName", task.summary().stepName());
        decisionEvidence.put("stepSequence", task.summary().stepSequence());
        if (task.delegatedFromUserId() != null) {
            decisionEvidence.put("delegatedFromUserId", task.delegatedFromUserId());
        }
        appendEvent(actor, task.summary().requestId(), eventType,
                normalizeComment(decision.comment()), correlationId, decisionEvidence);
        String integrationEventType = switch (requestStatus) {
            case "APPROVED" -> "approval.request.approved";
            case "REJECTED" -> "approval.request.rejected";
            default -> "approval.task." + normalized.toLowerCase(Locale.ROOT);
        };
        appendIntegration(actor, task.summary().requestId(),
                integrationEventType, correlationId,
                Map.of("requestId", task.summary().requestId().toString(),
                        "taskId", task.summary().taskId().toString(),
                        "recipientUserId", task.requesterUserId(),
                        "requestTitle", task.summary().title(),
                        "decision", normalized));
        return new ApprovalCommandRepository.DecisionResult(normalized, requestStatus);
    }

    private NextStep nextWaitingStep(long tenantId, UUID requestId) {
        return jdbc.query(ApprovalCommandSql01.NEXT_WAITING_STEP_SELECT_APR_STEPS, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("requestId", requestId), result -> result.next()
                        ? new NextStep(
                                result.getObject("step_id", UUID.class),
                                result.getString("step_key"),
                                result.getString("step_name"),
                                result.getString("candidate_role"),
                                result.getTimestamp("due_at").toInstant())
                        : null);
    }

    private void activateNextStep(
            ApprovalRequestContext.Actor actor,
            UUID requestId,
            NextStep nextStep,
            String correlationId) {
        UUID taskId = UUID.randomUUID();
        MapSqlParameterSource params = actorParams(actor)
                .addValue("requestId", requestId)
                .addValue("stepId", nextStep.stepId())
                .addValue("taskId", taskId)
                .addValue("candidateRole", nextStep.candidateRole())
                .addValue("dueAt", Timestamp.from(nextStep.dueAt()));
        int updated = jdbc.update(ApprovalCommandSql01.ACTIVATE_NEXT_STEP_UPDATE_APR_STEPS, params);
        requireUpdated(updated);
        jdbc.update(ApprovalCommandSql01.ACTIVATE_NEXT_STEP_INSERT_APR_TASKS, params);
        appendEvent(actor, requestId, "APPROVAL_STEP_STARTED", nextStep.stepName(), correlationId,
                Map.of(
                        "stepKey", nextStep.stepKey(),
                        "taskId", taskId.toString(),
                        "candidateRole", nextStep.candidateRole()));
    }


    private record NextStep(
            UUID stepId,
            String stepKey,
            String stepName,
            String candidateRole,
            Instant dueAt) {
    }

}
