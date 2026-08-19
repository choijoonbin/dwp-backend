package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Repository
public class ApprovalCommandRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ApprovalCommandRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
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
        String payloadJson = json(payload);
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
                .addValue("payload", payloadJson)
                .addValue("correlationId", correlationId);
        jdbc.update("""
                INSERT INTO apr_requests (
                    request_id, tenant_id, request_number,
                    workflow_version_id, form_version_id, title, summary,
                    requester_user_id, requester_person_public_id,
                    requester_name,
                    status, priority, data_classification, created_by, updated_by)
                VALUES (
                    :requestId, :tenantId, :requestNumber,
                    :workflowVersionId, :formVersionId, :title, :summary,
                    :userId, :personPublicId,
                    :requesterName,
                    'DRAFT', :priority, :classification, :userId, :userId)
                """, params);
        jdbc.update("""
                INSERT INTO apr_request_payloads (
                    tenant_id, request_id, payload, payload_sha256, schema_version)
                VALUES (
                    :tenantId, :requestId, CAST(:payload AS jsonb),
                    encode(sha256(convert_to(:payload, 'UTF8')), 'hex'), 1)
                """, params);
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
        String payloadJson = json(payload);
        MapSqlParameterSource params = actorParams(actor)
                .addValue("requestId", requestId)
                .addValue("workflowVersionId", workflow.workflowVersionId())
                .addValue("formVersionId", workflow.formVersionId())
                .addValue("title", request.title().trim())
                .addValue("summary", request.summary().trim())
                .addValue("priority", priority)
                .addValue("classification", workflow.dataClassification())
                .addValue("payload", payloadJson)
                .addValue("expectedVersion", request.expectedVersion());
        int updated = jdbc.update("""
                UPDATE apr_requests
                   SET workflow_version_id = :workflowVersionId,
                       form_version_id = :formVersionId,
                       title = :title,
                       summary = :summary,
                       priority = :priority,
                       data_classification = :classification,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = :userId
                 WHERE tenant_id = :tenantId
                   AND request_id = :requestId
                   AND requester_user_id = :userId
                   AND status = 'DRAFT'
                   AND version = :expectedVersion
                """, params);
        requireUpdated(updated);
        jdbc.update("""
                UPDATE apr_request_payloads
                   SET payload = CAST(:payload AS jsonb),
                       payload_sha256 = encode(sha256(convert_to(:payload, 'UTF8')), 'hex'),
                       schema_version = schema_version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId AND request_id = :requestId
                """, params);
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
        List<RuntimeStep> steps = request.steps();
        RuntimeStep firstStep = steps.get(0);
        UUID firstStepId = UUID.randomUUID();
        UUID firstTaskId = UUID.randomUUID();
        MapSqlParameterSource params = actorParams(actor)
                .addValue("requestId", requestId)
                .addValue("expectedVersion", expectedVersion)
                .addValue("slaMinutes", request.slaMinutes())
                .addValue("correlationId", correlationId);
        int updated = jdbc.update("""
                UPDATE apr_requests
                   SET status = 'IN_REVIEW', submitted_at = CURRENT_TIMESTAMP,
                       due_at = CURRENT_TIMESTAMP + make_interval(mins => :slaMinutes),
                       version = version + 1, updated_at = CURRENT_TIMESTAMP,
                       updated_by = :userId
                 WHERE tenant_id = :tenantId AND request_id = :requestId
                   AND requester_user_id = :userId AND status = 'DRAFT'
                   AND version = :expectedVersion
                """, params);
        requireUpdated(updated);

        int cumulativeMinutes = 0;
        for (int index = 0; index < steps.size(); index++) {
            RuntimeStep step = steps.get(index);
            cumulativeMinutes = Math.addExact(cumulativeMinutes, step.slaMinutes());
            UUID stepId = index == 0 ? firstStepId : UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO apr_steps (
                        step_id, tenant_id, request_id, step_key, step_name,
                        sequence_number, approval_mode, candidate_role,
                        status, started_at, due_at)
                    VALUES (
                        :stepId, :tenantId, :requestId, :stepKey, :stepName,
                        :sequenceNumber, :approvalMode, :candidateRole,
                        :status,
                        CASE WHEN :status = 'IN_PROGRESS' THEN CURRENT_TIMESTAMP ELSE NULL END,
                        CURRENT_TIMESTAMP + make_interval(mins => :cumulativeMinutes))
                    """, actorParams(actor)
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
        jdbc.update("""
                INSERT INTO apr_tasks (
                    task_id, tenant_id, request_id, step_id, candidate_role,
                    status, risk_score, due_at)
                VALUES (
                    :taskId, :tenantId, :requestId, :stepId, :candidateRole,
                    'PENDING', CASE WHEN :stepSlaMinutes <= 240 THEN 75 ELSE 45 END,
                    CURRENT_TIMESTAMP + make_interval(mins => :stepSlaMinutes))
                """, actorParams(actor)
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
        int updated = jdbc.update("""
                UPDATE apr_requests
                   SET status = 'WITHDRAWN', completed_at = CURRENT_TIMESTAMP,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP,
                       updated_by = :userId
                 WHERE tenant_id = :tenantId AND request_id = :requestId
                   AND requester_user_id = :userId
                   AND status IN ('SUBMITTED', 'IN_REVIEW', 'NEEDS_INFO')
                   AND version = :expectedVersion
                """, params);
        requireUpdated(updated);
        jdbc.update("""
                UPDATE apr_tasks
                   SET status = 'CANCELLED', completed_at = CURRENT_TIMESTAMP,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId AND request_id = :requestId
                   AND status IN ('PENDING', 'CLAIMED', 'INFO_REQUESTED')
                """, params);
        jdbc.update("""
                UPDATE apr_steps
                   SET status = 'CANCELLED', completed_at = CURRENT_TIMESTAMP,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId AND request_id = :requestId
                   AND status IN ('WAITING', 'PENDING', 'IN_PROGRESS')
                """, params);
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
        String amendedPayloadJson = json(amendedPayload);
        MapSqlParameterSource params = actorParams(actor)
                .addValue("requestId", requestId)
                .addValue("expectedVersion", request.expectedVersion())
                .addValue("message", request.message().trim())
                .addValue("payload", amendedPayloadJson);
        int updated = jdbc.update("""
                UPDATE apr_requests
                   SET status = 'IN_REVIEW', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = :userId
                 WHERE tenant_id = :tenantId AND request_id = :requestId
                   AND requester_user_id = :userId
                   AND status = 'NEEDS_INFO' AND version = :expectedVersion
                """, params);
        requireUpdated(updated);
        if (!amendedFields.isEmpty()) {
            jdbc.update("""
                    UPDATE apr_request_payloads
                       SET payload = CAST(:payload AS jsonb),
                           payload_sha256 = encode(sha256(convert_to(:payload, 'UTF8')), 'hex'),
                           schema_version = schema_version + 1,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE tenant_id = :tenantId AND request_id = :requestId
                    """, params);
            appendPayloadRevision(
                    actor,
                    requestId,
                    "INFORMATION_RESPONDED",
                    correlationId,
                    request.message().trim());
        }
        int resumed = jdbc.update("""
                UPDATE apr_tasks
                   SET status = CASE WHEN assignee_user_id IS NULL THEN 'PENDING' ELSE 'CLAIMED' END,
                       decision_reason = NULL, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId AND request_id = :requestId
                   AND status = 'INFO_REQUESTED'
                """, params);
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
        int updated = jdbc.update("""
                UPDATE apr_tasks
                   SET assignee_user_id = :userId,
                       assignee_person_public_id = :personPublicId,
                       delegated_from_user_id = :delegatedFromUserId,
                       status = 'CLAIMED', claimed_at = CURRENT_TIMESTAMP,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId AND task_id = :taskId
                   AND assignee_user_id IS NULL AND status = 'PENDING'
                   AND version = :expectedVersion
                """, actorParams(actor)
                .addValue("personPublicId", actor.personPublicId())
                .addValue("delegatedFromUserId", task.delegatedFromUserId())
                .addValue("taskId", task.summary().taskId())
                .addValue("expectedVersion", expectedVersion));
        requireUpdated(updated);
        appendEvent(actor, task.summary().requestId(), "TASK_CLAIMED", "Task claimed",
                correlationId, Map.of("taskId", task.summary().taskId().toString()));
    }

    public DecisionResult decide(
            ApprovalRequestContext.Actor actor,
            ApprovalQueryRepository.TaskAccess task,
            ApprovalDtos.DecisionRequest decision,
            String correlationId) {
        PolicyRuntime selfApprovalPolicy = policy(
                actor.tenantId(), "BLOCK_SELF_APPROVAL");
        if (task.requesterUserId() == actor.userId()
                && selfApprovalPolicy.blocks()) {
            throw new BaseException(ErrorCode.FORBIDDEN, "A requester cannot decide their own request.");
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
                        actor.tenantId(), "REQUIRE_REJECT_REASON");
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
        int updated = jdbc.update("""
                UPDATE apr_tasks
                   SET assignee_user_id = COALESCE(assignee_user_id, :userId),
                       decision_actor_user_id = :userId,
                       decision_actor_person_public_id = :personPublicId,
                       delegated_from_user_id = COALESCE(
                           delegated_from_user_id, :delegatedFromUserId),
                       status = :taskStatus, decision_reason = :reason,
                       completed_at = CASE WHEN :taskStatus = 'INFO_REQUESTED'
                                           THEN NULL ELSE CURRENT_TIMESTAMP END,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId AND task_id = :taskId
                   AND status IN ('PENDING', 'CLAIMED')
                   AND version = :expectedVersion
                """, params);
        requireUpdated(updated);
        jdbc.update("""
                UPDATE apr_steps
                   SET status = CASE WHEN :taskStatus = 'APPROVED' THEN 'APPROVED'
                                     WHEN :taskStatus = 'REJECTED' THEN 'REJECTED'
                                     ELSE 'IN_PROGRESS' END,
                       completed_at = CASE WHEN :taskStatus IN ('APPROVED', 'REJECTED')
                                           THEN CURRENT_TIMESTAMP ELSE NULL END,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId
                   AND step_id = (SELECT step_id FROM apr_tasks
                                   WHERE tenant_id = :tenantId AND task_id = :taskId)
                """, params);
        NextStep nextStep = null;
        if ("APPROVE".equals(normalized)) {
            nextStep = nextWaitingStep(actor.tenantId(), task.summary().requestId());
            if (nextStep == null) {
                requestStatus = "APPROVED";
            } else {
                activateNextStep(actor, task.summary().requestId(), nextStep, correlationId);
            }
        } else if ("REJECT".equals(normalized)) {
            jdbc.update("""
                    UPDATE apr_steps
                       SET status = 'CANCELLED', completed_at = CURRENT_TIMESTAMP,
                           version = version + 1, updated_at = CURRENT_TIMESTAMP
                     WHERE tenant_id = :tenantId AND request_id = :requestId
                       AND status = 'WAITING'
                    """, params);
        }
        params.addValue("requestStatus", requestStatus);
        jdbc.update("""
                UPDATE apr_requests
                   SET status = :requestStatus,
                       completed_at = CASE WHEN :requestStatus IN ('APPROVED', 'REJECTED')
                                           THEN CURRENT_TIMESTAMP ELSE NULL END,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP,
                       updated_by = :userId
                 WHERE tenant_id = :tenantId AND request_id = :requestId
                """, params);
        Map<String, Object> decisionEvidence = new LinkedHashMap<>();
        decisionEvidence.put("taskId", task.summary().taskId().toString());
        decisionEvidence.put("decision", normalized);
        decisionEvidence.put("delegated", task.delegatedAccess());
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
        return new DecisionResult(normalized, requestStatus);
    }

    private NextStep nextWaitingStep(long tenantId, UUID requestId) {
        return jdbc.query("""
                SELECT step_id, step_key, step_name, candidate_role, due_at
                  FROM apr_steps
                 WHERE tenant_id = :tenantId AND request_id = :requestId
                   AND status = 'WAITING'
                 ORDER BY sequence_number
                 LIMIT 1
                 FOR UPDATE
                """, new MapSqlParameterSource()
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
        int updated = jdbc.update("""
                UPDATE apr_steps
                   SET status = 'IN_PROGRESS', started_at = CURRENT_TIMESTAMP,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId AND request_id = :requestId
                   AND step_id = :stepId AND status = 'WAITING'
                """, params);
        requireUpdated(updated);
        jdbc.update("""
                INSERT INTO apr_tasks (
                    task_id, tenant_id, request_id, step_id, candidate_role,
                    status, risk_score, due_at)
                VALUES (
                    :taskId, :tenantId, :requestId, :stepId, :candidateRole,
                    'PENDING', CASE WHEN :dueAt <= CURRENT_TIMESTAMP + INTERVAL '4 hours'
                                    THEN 75 ELSE 45 END, :dueAt)
                """, params);
        appendEvent(actor, requestId, "APPROVAL_STEP_STARTED", nextStep.stepName(), correlationId,
                Map.of(
                        "stepKey", nextStep.stepKey(),
                        "taskId", taskId.toString(),
                        "candidateRole", nextStep.candidateRole()));
    }

    public UUID createDelegation(
            ApprovalRequestContext.Actor actor,
            ApprovalDtos.CreateDelegationRequest request,
            ApprovalIdentityDirectory.Subject delegate) {
        if (request.delegateUserId().equals(actor.userId()) || !request.endsAt().isAfter(request.startsAt())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
        Instant now = Instant.now();
        if (request.startsAt().isBefore(now.minus(Duration.ofMinutes(5)))
                || Duration.between(request.startsAt(), request.endsAt()).compareTo(Duration.ofDays(90)) > 0
                || !delegate.active()
                || !request.delegateUserId().equals(delegate.userId())
                || !actor.tenantId().equals(delegate.tenantId())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
        String scope = request.scopeType().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ALL", "WORKFLOW").contains(scope)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
        if ("WORKFLOW".equals(scope)
                && (request.workflowKey() == null || request.workflowKey().isBlank())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
        String workflowKey = "WORKFLOW".equals(scope)
                ? request.workflowKey().trim().toUpperCase(Locale.ROOT)
                : null;
        if (workflowKey != null) {
            Integer workflowCount = jdbc.queryForObject("""
                    SELECT COUNT(*)::INTEGER
                      FROM apr_workflow_definitions
                     WHERE tenant_id = :tenantId AND workflow_key = :workflowKey
                       AND lifecycle_state = 'PUBLISHED'
                    """, actorParams(actor).addValue("workflowKey", workflowKey), Integer.class);
            if (workflowCount == null || workflowCount == 0) {
                throw new BaseException(ErrorCode.NOT_FOUND);
            }
        }
        MapSqlParameterSource lockParams = actorParams(actor)
                .addValue("delegateUserId", request.delegateUserId())
                .addValue("scopeType", scope)
                .addValue("workflowKey", workflowKey)
                .addValue("startsAt", request.startsAt())
                .addValue("endsAt", request.endsAt());
        jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))",
                lockParams.addValue(
                        "lockKey",
                        actor.tenantId() + ":approval-delegation:" + actor.userId()),
                Object.class);
        Integer overlaps = jdbc.queryForObject("""
                SELECT COUNT(*)::INTEGER
                  FROM apr_delegations
                 WHERE tenant_id = :tenantId AND delegator_user_id = :userId
                   AND delegate_user_id = :delegateUserId
                   AND lifecycle_state = 'ACTIVE'
                   AND scope_type = :scopeType
                   AND COALESCE(workflow_key, '') = COALESCE(:workflowKey, '')
                   AND starts_at < :endsAt AND ends_at > :startsAt
                """, lockParams, Integer.class);
        if (overlaps != null && overlaps > 0) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO apr_delegations (
                    delegation_id, tenant_id, delegator_user_id, delegate_user_id,
                    delegate_person_public_id, delegate_display_name, delegate_email,
                    delegated_role_codes,
                    scope_type, workflow_key, starts_at, ends_at,
                    lifecycle_state, reason, created_by, updated_by)
                VALUES (
                    :id, :tenantId, :userId, :delegateUserId,
                    :delegatePersonPublicId, :delegateDisplayName, :delegateEmail,
                    CAST(:delegatedRoles AS jsonb),
                    :scopeType, :workflowKey, :startsAt, :endsAt,
                    'ACTIVE', :reason, :userId, :userId)
                """, actorParams(actor)
                .addValue("id", id)
                .addValue("delegateUserId", request.delegateUserId())
                .addValue("delegatePersonPublicId", delegate.personPublicId())
                .addValue("delegateDisplayName", delegate.displayName())
                .addValue("delegateEmail", delegate.email())
                .addValue("delegatedRoles", json(actor.roles().stream().sorted().toList()))
                .addValue("scopeType", scope)
                .addValue("workflowKey", workflowKey)
                .addValue("startsAt", request.startsAt())
                .addValue("endsAt", request.endsAt())
                .addValue("reason", request.reason().trim()));
        return id;
    }

    public void revokeDelegation(
            ApprovalRequestContext.Actor actor,
            UUID delegationId,
            long expectedVersion) {
        int updated = jdbc.update("""
                UPDATE apr_delegations
                   SET lifecycle_state = 'REVOKED', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = :userId
                 WHERE tenant_id = :tenantId AND delegation_id = :delegationId
                   AND delegator_user_id = :userId AND lifecycle_state = 'ACTIVE'
                   AND version = :expectedVersion
                """, actorParams(actor)
                .addValue("delegationId", delegationId)
                .addValue("expectedVersion", expectedVersion));
        requireUpdated(updated);
    }

    public UUID createFormCategory(
            ApprovalRequestContext.Actor actor,
            ApprovalDtos.CreateFormCategoryRequest request) {
        String categoryKey = request.categoryKey().trim().toUpperCase(Locale.ROOT);
        validateCategoryParent(actor.tenantId(), null, request.parentCategoryId());
        UUID categoryId = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO apr_form_categories (
                        category_id, tenant_id, category_key, parent_category_id,
                        name_ko, name_en, description_ko, description_en,
                        icon_key, sort_order, lifecycle_state, created_by, updated_by)
                    VALUES (
                        :categoryId, :tenantId, :categoryKey, :parentCategoryId,
                        :nameKo, :nameEn, :descriptionKo, :descriptionEn,
                        :iconKey, :sortOrder, 'ACTIVE', :userId, :userId)
                    """, actorParams(actor)
                    .addValue("categoryId", categoryId)
                    .addValue("categoryKey", categoryKey)
                    .addValue("parentCategoryId", request.parentCategoryId())
                    .addValue("nameKo", request.nameKo().trim())
                    .addValue("nameEn", request.nameEn().trim())
                    .addValue("descriptionKo", normalizedOptional(request.descriptionKo()))
                    .addValue("descriptionEn", normalizedOptional(request.descriptionEn()))
                    .addValue("iconKey", request.iconKey().trim())
                    .addValue("sortOrder", request.sortOrder()));
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
        return categoryId;
    }

    public void updateFormCategory(
            ApprovalRequestContext.Actor actor,
            UUID categoryId,
            ApprovalDtos.UpdateFormCategoryRequest request) {
        validateCategoryParent(actor.tenantId(), categoryId, request.parentCategoryId());
        int updated = jdbc.update("""
                UPDATE apr_form_categories
                   SET parent_category_id = :parentCategoryId,
                       name_ko = :nameKo, name_en = :nameEn,
                       description_ko = :descriptionKo, description_en = :descriptionEn,
                       icon_key = :iconKey, sort_order = :sortOrder,
                       lifecycle_state = :lifecycleState,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP,
                       updated_by = :userId
                 WHERE tenant_id = :tenantId AND category_id = :categoryId
                   AND version = :expectedVersion
                """, actorParams(actor)
                .addValue("categoryId", categoryId)
                .addValue("parentCategoryId", request.parentCategoryId())
                .addValue("nameKo", request.nameKo().trim())
                .addValue("nameEn", request.nameEn().trim())
                .addValue("descriptionKo", normalizedOptional(request.descriptionKo()))
                .addValue("descriptionEn", normalizedOptional(request.descriptionEn()))
                .addValue("iconKey", request.iconKey().trim())
                .addValue("sortOrder", request.sortOrder())
                .addValue("lifecycleState", request.lifecycleState().trim().toUpperCase(Locale.ROOT))
                .addValue("expectedVersion", request.expectedVersion()));
        requireUpdated(updated);
    }

    public UUID createFormDraft(
            ApprovalRequestContext.Actor actor,
            ApprovalDtos.CreateFormDraftRequest request) {
        validateFormFields(request.fields());
        requireCategory(actor.tenantId(), request.categoryId());
        requireWorkflow(actor.tenantId(), request.defaultWorkflowId());
        String formKey = request.formKey().trim().toUpperCase(Locale.ROOT);
        UUID formId = UUID.randomUUID();
        UUID formVersionId = UUID.randomUUID();
        String schema = json(formSchema(request.fields()));
        MapSqlParameterSource params = actorParams(actor)
                .addValue("formId", formId)
                .addValue("formVersionId", formVersionId)
                .addValue("formKey", formKey)
                .addValue("categoryId", request.categoryId())
                .addValue("nameKo", request.nameKo().trim())
                .addValue("nameEn", request.nameEn().trim())
                .addValue("descriptionKo", request.descriptionKo().trim())
                .addValue("descriptionEn", request.descriptionEn().trim())
                .addValue("ownerGroupRef", request.ownerGroupRef().trim())
                .addValue("workflowId", request.defaultWorkflowId())
                .addValue("schema", schema)
                .addValue("bindingId", UUID.randomUUID());
        try {
            jdbc.update("""
                    INSERT INTO apr_forms (
                        form_id, tenant_id, form_key, category_id,
                        name_ko, name_en, description_ko, description_en,
                        owner_group_ref, form_kind, lifecycle_state,
                        current_version, created_by, updated_by)
                    VALUES (
                        :formId, :tenantId, :formKey, :categoryId,
                        :nameKo, :nameEn, :descriptionKo, :descriptionEn,
                        :ownerGroupRef, 'REQUEST', 'DRAFT', 1, :userId, :userId)
                    """, params);
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
        jdbc.update("""
                INSERT INTO apr_form_versions (
                    form_version_id, tenant_id, form_id, version_number,
                    schema_payload, schema_sha256, lifecycle_state, created_by)
                VALUES (
                    :formVersionId, :tenantId, :formId, 1,
                    CAST(:schema AS jsonb),
                    encode(sha256(convert_to(:schema, 'UTF8')), 'hex'),
                    'DRAFT', :userId)
                """, params);
        jdbc.update("""
                INSERT INTO apr_form_workflow_bindings (
                    binding_id, tenant_id, form_id, workflow_id,
                    binding_type, priority, lifecycle_state, created_by, updated_by)
                VALUES (
                    :bindingId, :tenantId, :formId, :workflowId,
                    'DEFAULT', 100, 'ACTIVE', :userId, :userId)
                """, params);
        return formId;
    }

    public UUID createWorkflowDraft(
            ApprovalRequestContext.Actor actor,
            ApprovalDtos.CreateWorkflowDraftRequest request) {
        String workflowKey = request.workflowKey().trim().toUpperCase(Locale.ROOT);
        validateWorkflowInput(
                request.category(), request.dataClassification(), request.slaMinutes(), request.steps());
        Integer existing = jdbc.queryForObject("""
                SELECT COUNT(*)::INTEGER
                  FROM apr_workflow_definitions
                 WHERE tenant_id = :tenantId AND workflow_key = :workflowKey
                """, actorParams(actor).addValue("workflowKey", workflowKey), Integer.class);
        if (existing != null && existing > 0) throw new BaseException(ErrorCode.RESOURCE_CONFLICT);

        UUID workflowId = UUID.randomUUID();
        UUID workflowVersionId = UUID.randomUUID();
        UUID formId = UUID.randomUUID();
        UUID formVersionId = UUID.randomUUID();
        String definition = json(workflowDefinition(request.steps()));
        String schema = json(defaultFormSchema());
        MapSqlParameterSource params = actorParams(actor)
                .addValue("workflowId", workflowId)
                .addValue("workflowVersionId", workflowVersionId)
                .addValue("workflowKey", workflowKey)
                .addValue("nameKo", request.nameKo().trim())
                .addValue("nameEn", request.nameEn().trim())
                .addValue("descriptionKo", request.descriptionKo().trim())
                .addValue("descriptionEn", request.descriptionEn().trim())
                .addValue("category", request.category().trim().toUpperCase(Locale.ROOT))
                .addValue("classification", request.dataClassification().trim().toUpperCase(Locale.ROOT))
                .addValue("slaMinutes", request.slaMinutes())
                .addValue("ownerGroupRef", request.ownerGroupRef().trim())
                .addValue("definition", definition)
                .addValue("formId", formId)
                .addValue("formVersionId", formVersionId)
                .addValue("formKey", workflowKey + "_FORM")
                .addValue("formNameKo", request.nameKo().trim() + " 양식")
                .addValue("formNameEn", request.nameEn().trim() + " form")
                .addValue("schema", schema);
        jdbc.update("""
                INSERT INTO apr_workflow_definitions (
                    workflow_id, tenant_id, workflow_key, name_ko, name_en,
                    description_ko, description_en, category, data_classification,
                    lifecycle_state, current_version, sla_minutes, allow_self_approval,
                    owner_group_ref, created_by, updated_by)
                VALUES (
                    :workflowId, :tenantId, :workflowKey, :nameKo, :nameEn,
                    :descriptionKo, :descriptionEn, :category, :classification,
                    'DRAFT', 1, :slaMinutes, FALSE,
                    :ownerGroupRef, :userId, :userId)
                """, params);
        jdbc.update("""
                INSERT INTO apr_workflow_versions (
                    workflow_version_id, tenant_id, workflow_id, version_number,
                    definition, definition_sha256, lifecycle_state, created_by)
                VALUES (
                    :workflowVersionId, :tenantId, :workflowId, 1,
                    CAST(:definition AS jsonb),
                    encode(sha256(convert_to(:definition, 'UTF8')), 'hex'),
                    'DRAFT', :userId)
                """, params);
        jdbc.update("""
                INSERT INTO apr_forms (
                    form_id, tenant_id, form_key, category_id, name_ko, name_en,
                    description_ko, description_en, owner_group_ref,
                    lifecycle_state, current_version, created_by, updated_by)
                VALUES (
                    :formId, :tenantId, :formKey,
                    (SELECT category_id FROM apr_form_categories
                      WHERE tenant_id = :tenantId AND category_key = :category),
                    :formNameKo, :formNameEn,
                    :descriptionKo, :descriptionEn, :ownerGroupRef,
                    'DRAFT', 1, :userId, :userId)
                """, params);
        jdbc.update("""
                INSERT INTO apr_form_versions (
                    form_version_id, tenant_id, form_id, version_number,
                    schema_payload, schema_sha256, lifecycle_state, created_by)
                VALUES (
                    :formVersionId, :tenantId, :formId, 1,
                    CAST(:schema AS jsonb),
                    encode(sha256(convert_to(:schema, 'UTF8')), 'hex'),
                    'DRAFT', :userId)
                """, params);
        jdbc.update("""
                INSERT INTO apr_form_workflow_bindings (
                    binding_id, tenant_id, form_id, workflow_id, binding_type,
                    lifecycle_state, priority, created_by, updated_by)
                VALUES (
                    :bindingId, :tenantId, :formId, :workflowId, 'DEFAULT',
                    'ACTIVE', 100, :userId, :userId)
                """, params.addValue("bindingId", UUID.randomUUID()));
        return workflowId;
    }

    public void updateWorkflowDraft(
            ApprovalRequestContext.Actor actor,
            UUID workflowId,
            ApprovalDtos.UpdateWorkflowDraftRequest request) {
        validateWorkflowInput(
                request.category(), request.dataClassification(), request.slaMinutes(), request.steps());
        String definition = json(workflowDefinition(request.steps()));
        MapSqlParameterSource params = actorParams(actor)
                .addValue("workflowId", workflowId)
                .addValue("nameKo", request.nameKo().trim())
                .addValue("nameEn", request.nameEn().trim())
                .addValue("descriptionKo", request.descriptionKo().trim())
                .addValue("descriptionEn", request.descriptionEn().trim())
                .addValue("category", request.category().trim().toUpperCase(Locale.ROOT))
                .addValue("classification", request.dataClassification().trim().toUpperCase(Locale.ROOT))
                .addValue("slaMinutes", request.slaMinutes())
                .addValue("ownerGroupRef", request.ownerGroupRef().trim())
                .addValue("definition", definition)
                .addValue("expectedVersion", request.expectedVersion());
        int updated = jdbc.update("""
                UPDATE apr_workflow_definitions
                   SET name_ko = :nameKo, name_en = :nameEn,
                       description_ko = :descriptionKo, description_en = :descriptionEn,
                       category = :category, data_classification = :classification,
                       sla_minutes = :slaMinutes, owner_group_ref = :ownerGroupRef,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP,
                       updated_by = :userId
                 WHERE tenant_id = :tenantId AND workflow_id = :workflowId
                   AND lifecycle_state = 'DRAFT' AND version = :expectedVersion
                """, params);
        requireUpdated(updated);
        jdbc.update("""
                UPDATE apr_workflow_versions
                   SET definition = CAST(:definition AS jsonb),
                       definition_sha256 = encode(
                           sha256(convert_to(:definition, 'UTF8')), 'hex')
                 WHERE tenant_id = :tenantId AND workflow_id = :workflowId
                   AND lifecycle_state = 'DRAFT'
                   AND version_number = (
                       SELECT current_version FROM apr_workflow_definitions
                        WHERE tenant_id = :tenantId AND workflow_id = :workflowId)
                """, params);
    }

    public void updateFormDraft(
            ApprovalRequestContext.Actor actor,
            UUID formId,
            ApprovalDtos.UpdateFormDraftRequest request) {
        validateFormFields(request.fields());
        requireCategory(actor.tenantId(), request.categoryId());
        requireWorkflow(actor.tenantId(), request.defaultWorkflowId());
        String schema = json(formSchema(request.fields()));
        MapSqlParameterSource params = actorParams(actor)
                .addValue("formId", formId)
                .addValue("categoryId", request.categoryId())
                .addValue("nameKo", request.nameKo().trim())
                .addValue("nameEn", request.nameEn().trim())
                .addValue("descriptionKo", request.descriptionKo().trim())
                .addValue("descriptionEn", request.descriptionEn().trim())
                .addValue("ownerGroupRef", request.ownerGroupRef().trim())
                .addValue("workflowId", request.defaultWorkflowId())
                .addValue("schema", schema)
                .addValue("expectedVersion", request.expectedVersion());
        int updated = jdbc.update("""
                UPDATE apr_forms
                   SET category_id = :categoryId,
                       name_ko = :nameKo, name_en = :nameEn,
                       description_ko = :descriptionKo, description_en = :descriptionEn,
                       owner_group_ref = :ownerGroupRef,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP,
                       updated_by = :userId
                 WHERE tenant_id = :tenantId AND form_id = :formId
                   AND lifecycle_state = 'DRAFT' AND version = :expectedVersion
                """, params);
        requireUpdated(updated);
        jdbc.update("""
                UPDATE apr_form_versions
                   SET schema_payload = CAST(:schema AS jsonb),
                       schema_sha256 = encode(sha256(convert_to(:schema, 'UTF8')), 'hex')
                 WHERE tenant_id = :tenantId AND form_id = :formId
                   AND lifecycle_state = 'DRAFT'
                   AND version_number = (
                       SELECT current_version FROM apr_forms
                        WHERE tenant_id = :tenantId AND form_id = :formId)
                """, params);
        replaceDefaultFormRoute(actor, formId, request.defaultWorkflowId());
    }

    public void publishForm(
            ApprovalRequestContext.Actor actor,
            UUID formId,
            long expectedVersion) {
        MapSqlParameterSource params = actorParams(actor)
                .addValue("formId", formId)
                .addValue("expectedVersion", expectedVersion);
        int updated = jdbc.update("""
                UPDATE apr_forms form
                   SET lifecycle_state = 'PUBLISHED', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = :userId
                 WHERE form.tenant_id = :tenantId AND form.form_id = :formId
                   AND form.lifecycle_state = 'DRAFT'
                   AND form.version = :expectedVersion
                   AND COALESCE(form.updated_by, form.created_by, -1) <> :userId
                   AND EXISTS (
                       SELECT 1
                         FROM apr_form_workflow_bindings binding
                         JOIN apr_workflow_definitions workflow
                           ON workflow.tenant_id = binding.tenant_id
                          AND workflow.workflow_id = binding.workflow_id
                        WHERE binding.tenant_id = form.tenant_id
                          AND binding.form_id = form.form_id
                          AND binding.binding_type = 'DEFAULT'
                          AND binding.lifecycle_state = 'ACTIVE'
                          AND workflow.lifecycle_state = 'PUBLISHED')
                """, params);
        requireUpdated(updated);
        jdbc.update("""
                UPDATE apr_form_versions
                   SET lifecycle_state = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
                       published_by = :userId
                 WHERE tenant_id = :tenantId AND form_id = :formId
                   AND lifecycle_state = 'DRAFT'
                   AND version_number = (
                       SELECT current_version FROM apr_forms
                        WHERE tenant_id = :tenantId AND form_id = :formId)
                """, params);
    }

    public void updatePolicy(
            ApprovalRequestContext.Actor actor,
            UUID policyId,
            ApprovalDtos.UpdatePolicyRequest request) {
        String mode = normalized(request.enforcementMode(),
                Set.of("BLOCK", "WARN", "MONITOR"));
        String severity = normalized(request.severity(),
                Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL"));
        String state = normalized(request.lifecycleState(),
                Set.of("ACTIVE", "DISABLED", "RETIRED"));
        String policyKey = jdbc.query("""
                SELECT policy_key
                  FROM apr_policy_rules
                 WHERE tenant_id = :tenantId AND policy_id = :policyId
                """, actorParams(actor).addValue("policyId", policyId), result -> {
            if (!result.next()) throw new BaseException(ErrorCode.NOT_FOUND);
            return result.getString("policy_key");
        });
        validatePolicyRule(policyKey, request.rule());
        int updated = jdbc.update("""
                UPDATE apr_policy_rules
                   SET pending_enforcement_mode = :mode,
                       pending_severity = :severity,
                       pending_lifecycle_state = :state,
                       pending_rule_payload = CAST(:rule AS jsonb),
                       pending_change_reason = :changeReason,
                       pending_by = :userId,
                       pending_at = CURRENT_TIMESTAMP,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP,
                       updated_by = :userId
                 WHERE tenant_id = :tenantId AND policy_id = :policyId
                   AND version = :expectedVersion
                """, actorParams(actor)
                .addValue("policyId", policyId)
                .addValue("mode", mode)
                .addValue("severity", severity)
                .addValue("state", state)
                .addValue("rule", json(request.rule()))
                .addValue("changeReason", request.changeReason().trim())
                .addValue("expectedVersion", request.expectedVersion()));
        requireUpdated(updated);
    }

    public void publishPolicy(
            ApprovalRequestContext.Actor actor,
            UUID policyId,
            ApprovalDtos.PublishPolicyRequest request) {
        int updated = jdbc.update("""
                WITH candidate AS (
                    SELECT tenant_id, policy_id,
                           pending_enforcement_mode, pending_severity,
                           pending_lifecycle_state, pending_rule_payload,
                           pending_change_reason, pending_by, pending_at
                      FROM apr_policy_rules
                     WHERE tenant_id = :tenantId AND policy_id = :policyId
                       AND version = :expectedVersion
                       AND pending_by IS NOT NULL
                       AND pending_by <> :userId
                     FOR UPDATE
                ), published AS (
                    UPDATE apr_policy_rules policy
                       SET enforcement_mode = candidate.pending_enforcement_mode,
                           severity = candidate.pending_severity,
                           lifecycle_state = candidate.pending_lifecycle_state,
                           rule_payload = candidate.pending_rule_payload,
                           pending_enforcement_mode = NULL,
                           pending_severity = NULL,
                           pending_lifecycle_state = NULL,
                           pending_rule_payload = NULL,
                           pending_change_reason = NULL,
                           pending_by = NULL,
                           pending_at = NULL,
                           version = policy.version + 1,
                           updated_at = CURRENT_TIMESTAMP,
                           updated_by = :userId
                      FROM candidate
                     WHERE policy.tenant_id = candidate.tenant_id
                       AND policy.policy_id = candidate.policy_id
                    RETURNING policy.tenant_id, policy.policy_id,
                              policy.enforcement_mode, policy.severity,
                              policy.lifecycle_state, policy.rule_payload
                )
                INSERT INTO apr_policy_rule_versions (
                    policy_version_id, tenant_id, policy_id, version_number,
                    enforcement_mode, severity, lifecycle_state, rule_payload,
                    change_reason, submitted_by, submitted_at,
                    published_by, published_at, review_comment)
                SELECT :policyVersionId, published.tenant_id, published.policy_id,
                       COALESCE((
                           SELECT MAX(version_number) + 1
                             FROM apr_policy_rule_versions history
                            WHERE history.tenant_id = published.tenant_id
                              AND history.policy_id = published.policy_id
                       ), 1),
                       published.enforcement_mode, published.severity,
                       published.lifecycle_state, published.rule_payload,
                       candidate.pending_change_reason, candidate.pending_by,
                       candidate.pending_at, :userId, CURRENT_TIMESTAMP,
                       :reviewComment
                  FROM published
                  JOIN candidate
                    ON candidate.tenant_id = published.tenant_id
                   AND candidate.policy_id = published.policy_id
                """, actorParams(actor)
                .addValue("policyId", policyId)
                .addValue("policyVersionId", UUID.randomUUID())
                .addValue("expectedVersion", request.expectedVersion())
                .addValue("reviewComment", request.reviewComment().trim()));
        requireUpdated(updated);
    }

    public void retryIntegrationDelivery(
            ApprovalRequestContext.Actor actor,
            UUID outboxId) {
        int updated = jdbc.update("""
                UPDATE apr_integration_outbox
                   SET status = 'PENDING', attempt_count = 0,
                       available_at = CURRENT_TIMESTAMP,
                       locked_by = NULL, locked_until = NULL,
                       manual_retry_count = manual_retry_count + 1,
                       last_retried_at = CURRENT_TIMESTAMP,
                       last_retried_by = :userId,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId AND outbox_id = :outboxId
                   AND status IN ('FAILED', 'DEAD')
                """, actorParams(actor).addValue("outboxId", outboxId));
        requireUpdated(updated);
    }

    void validatePolicyRule(String policyKey, Map<String, Object> rule) {
        Set<String> expectedKeys;
        switch (policyKey) {
            case "BLOCK_SELF_APPROVAL" -> {
                expectedKeys = Set.of("requesterCannotDecide");
                if (!Boolean.TRUE.equals(rule.get("requesterCannotDecide"))) {
                    throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
                }
            }
            case "REQUIRE_REJECT_REASON" -> {
                expectedKeys = Set.of("minimumLength");
                boundedInteger(rule.get("minimumLength"), 4, 1000);
            }
            case "CAPTURE_DECISION_EVIDENCE" -> {
                expectedKeys = Set.of("retentionClass");
                if (!Set.of("STANDARD", "EXTENDED").contains(
                        String.valueOf(rule.get("retentionClass")))) {
                    throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
                }
            }
            case "SLA_ESCALATION" -> {
                expectedKeys = Set.of("warningPercent", "breachPercent");
                int warning = boundedInteger(rule.get("warningPercent"), 1, 99);
                int breach = boundedInteger(rule.get("breachPercent"), warning, 100);
                if (breach < warning) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
            }
            default -> throw new BaseException(ErrorCode.INVALID_STATE);
        }
        if (!rule.keySet().equals(expectedKeys)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private int boundedInteger(Object value, int minimum, int maximum) {
        if (!(value instanceof Number number)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
        int parsed = number.intValue();
        if (parsed < minimum || parsed > maximum) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
        return parsed;
    }

    private PolicyRuntime policy(long tenantId, String policyKey) {
        return jdbc.query("""
                SELECT enforcement_mode, lifecycle_state, rule_payload::text
                  FROM apr_policy_rules
                 WHERE tenant_id = :tenantId AND policy_key = :policyKey
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("policyKey", policyKey), result -> {
            if (!result.next()) return PolicyRuntime.disabled();
            return new PolicyRuntime(
                    result.getString("enforcement_mode"),
                    result.getString("lifecycle_state"),
                    object(result.getString("rule_payload")));
        });
    }

    public void publishWorkflow(
            ApprovalRequestContext.Actor actor,
            UUID workflowId,
            long expectedVersion,
            String correlationId) {
        MapSqlParameterSource params = actorParams(actor)
                .addValue("workflowId", workflowId)
                .addValue("expectedVersion", expectedVersion);
        int updated = jdbc.update("""
                UPDATE apr_workflow_definitions
                   SET lifecycle_state = 'PUBLISHED', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = :userId
                 WHERE tenant_id = :tenantId AND workflow_id = :workflowId
                   AND lifecycle_state = 'DRAFT' AND version = :expectedVersion
                   AND COALESCE(updated_by, created_by, -1) <> :userId
                """, params);
        requireUpdated(updated);
        jdbc.update("""
                UPDATE apr_workflow_versions
                   SET lifecycle_state = 'PUBLISHED', effective_from = CURRENT_TIMESTAMP,
                       published_at = CURRENT_TIMESTAMP, published_by = :userId
                 WHERE tenant_id = :tenantId AND workflow_id = :workflowId
                   AND lifecycle_state = 'DRAFT'
                """, params);
    }

    private WorkflowRuntime workflow(
            long tenantId,
            UUID workflowId,
            UUID formId,
            Map<String, Object> requestPayload,
            boolean requireRouteMatch) {
        return jdbc.query("""
                SELECT version.workflow_version_id, form_version.form_version_id,
                       definition.data_classification,
                       form_version.schema_payload::text AS form_schema,
                       binding.binding_type,
                       binding.condition_payload::text AS binding_condition
                  FROM apr_workflow_definitions definition
                  JOIN apr_workflow_versions version
                    ON version.tenant_id = definition.tenant_id
                   AND version.workflow_id = definition.workflow_id
                   AND version.version_number = definition.current_version
                  JOIN apr_form_workflow_bindings binding
                    ON binding.tenant_id = definition.tenant_id
                   AND binding.workflow_id = definition.workflow_id
                   AND binding.form_id = :formId
                   AND binding.lifecycle_state = 'ACTIVE'
                   AND (binding.effective_from IS NULL
                        OR binding.effective_from <= CURRENT_TIMESTAMP)
                   AND (binding.effective_to IS NULL
                        OR binding.effective_to > CURRENT_TIMESTAMP)
                  JOIN apr_forms form
                    ON form.tenant_id = binding.tenant_id
                   AND form.form_id = binding.form_id
                  JOIN apr_form_versions form_version
                    ON form_version.tenant_id = form.tenant_id
                   AND form_version.form_id = form.form_id
                   AND form_version.version_number = form.current_version
                 WHERE definition.tenant_id = :tenantId
                   AND definition.workflow_id = :workflowId
                   AND definition.lifecycle_state = 'PUBLISHED'
                   AND version.lifecycle_state = 'PUBLISHED'
                   AND (version.effective_from IS NULL
                        OR version.effective_from <= CURRENT_TIMESTAMP)
                   AND (version.effective_to IS NULL
                        OR version.effective_to > CURRENT_TIMESTAMP)
                   AND form.lifecycle_state = 'PUBLISHED'
                   AND form_version.lifecycle_state = 'PUBLISHED'
                """, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("workflowId", workflowId)
                        .addValue("formId", formId),
                result -> {
                    if (!result.next()) throw new BaseException(ErrorCode.NOT_FOUND);
                    if (requireRouteMatch
                            && "CONDITIONAL".equals(result.getString("binding_type"))
                            && !matchesRouteCondition(
                                    result.getString("binding_condition"), requestPayload)) {
                        throw new BaseException(
                                ErrorCode.INVALID_STATE,
                                "The selected approval route does not match this request.");
                    }
                    return new WorkflowRuntime(
                            result.getObject("workflow_version_id", UUID.class),
                            result.getObject("form_version_id", UUID.class),
                            result.getString("data_classification"),
                            result.getString("form_schema"));
                });
    }

    boolean matchesRouteCondition(
            String storedCondition,
            Map<String, Object> requestPayload) {
        try {
            Map<String, Object> condition = objectMapper.readValue(
                    storedCondition, new TypeReference<Map<String, Object>>() { });
            Object rawClauses = condition.get("all");
            if (!(rawClauses instanceof List<?> clauses) || clauses.isEmpty()) return false;
            for (Object rawClause : clauses) {
                if (!(rawClause instanceof Map<?, ?> clause)) return false;
                Object fieldValue = clause.get("field");
                String field = fieldValue == null ? "" : String.valueOf(fieldValue).trim();
                Object operatorValue = clause.get("operator");
                String operator = (operatorValue == null ? "EQ" : String.valueOf(operatorValue))
                        .trim().toUpperCase(Locale.ROOT);
                if (field.isEmpty() || !requestPayload.containsKey(field)) return false;
                Object actual = requestPayload.get(field);
                Object expected = clause.get("value");
                boolean matched = switch (operator) {
                    case "EQ" -> java.util.Objects.equals(actual, expected);
                    case "IN" -> expected instanceof List<?> values && values.contains(actual);
                    case "GTE", "GT", "LTE", "LT" -> compareNumbers(actual, expected, operator);
                    default -> false;
                };
                if (!matched) return false;
            }
            return true;
        } catch (JsonProcessingException exception) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "The approval route condition is invalid.");
        }
    }

    private boolean compareNumbers(Object actual, Object expected, String operator) {
        if (!(actual instanceof Number actualNumber) || !(expected instanceof Number expectedNumber)) {
            return false;
        }
        int comparison = new BigDecimal(actualNumber.toString())
                .compareTo(new BigDecimal(expectedNumber.toString()));
        return switch (operator) {
            case "GTE" -> comparison >= 0;
            case "GT" -> comparison > 0;
            case "LTE" -> comparison <= 0;
            case "LT" -> comparison < 0;
            default -> false;
        };
    }

    private RequestRuntime ownedRequest(ApprovalRequestContext.Actor actor, UUID requestId) {
        return jdbc.query("""
                SELECT request.status, request.title, workflow.sla_minutes,
                       workflow_version.definition::text AS workflow_definition,
                       form_version.schema_payload::text AS form_schema,
                       payload.payload::text AS request_payload,
                       binding.binding_type,
                       binding.condition_payload::text AS binding_condition
                  FROM apr_requests request
                  JOIN apr_workflow_versions workflow_version
                    ON workflow_version.tenant_id = request.tenant_id
                   AND workflow_version.workflow_version_id = request.workflow_version_id
                  JOIN apr_workflow_definitions workflow
                    ON workflow.tenant_id = workflow_version.tenant_id
                   AND workflow.workflow_id = workflow_version.workflow_id
                  JOIN apr_form_versions form_version
                    ON form_version.tenant_id = request.tenant_id
                   AND form_version.form_version_id = request.form_version_id
                  JOIN apr_request_payloads payload
                    ON payload.tenant_id = request.tenant_id
                   AND payload.request_id = request.request_id
                  JOIN apr_form_workflow_bindings binding
                    ON binding.tenant_id = request.tenant_id
                   AND binding.workflow_id = workflow.workflow_id
                   AND binding.form_id = form_version.form_id
                   AND binding.lifecycle_state = 'ACTIVE'
                   AND (binding.effective_from IS NULL
                        OR binding.effective_from <= CURRENT_TIMESTAMP)
                   AND (binding.effective_to IS NULL
                        OR binding.effective_to > CURRENT_TIMESTAMP)
                 WHERE request.tenant_id = :tenantId
                   AND request.request_id = :requestId
                   AND request.requester_user_id = :userId
                """, actorParams(actor).addValue("requestId", requestId), result -> {
            if (!result.next()) throw new BaseException(ErrorCode.NOT_FOUND);
            int slaMinutes = result.getInt("sla_minutes");
            return new RequestRuntime(
                    result.getString("status"),
                    result.getString("title"),
                    slaMinutes,
                    runtimeSteps(result.getString("workflow_definition"), slaMinutes),
                    result.getString("form_schema"),
                    object(result.getString("request_payload")),
                    result.getString("binding_type"),
                    result.getString("binding_condition"));
        });
    }

    private InformationRuntime informationRuntime(
            ApprovalRequestContext.Actor actor,
            UUID requestId) {
        return jdbc.query("""
                SELECT form_version.schema_payload::text AS form_schema,
                       payload.payload::text AS request_payload
                  FROM apr_requests request
                  JOIN apr_form_versions form_version
                    ON form_version.tenant_id = request.tenant_id
                   AND form_version.form_version_id = request.form_version_id
                  JOIN apr_request_payloads payload
                    ON payload.tenant_id = request.tenant_id
                   AND payload.request_id = request.request_id
                 WHERE request.tenant_id = :tenantId
                   AND request.request_id = :requestId
                   AND request.requester_user_id = :userId
                   AND request.status = 'NEEDS_INFO'
                """, actorParams(actor).addValue("requestId", requestId), result -> {
            if (!result.next()) throw new BaseException(ErrorCode.INVALID_STATE);
            return new InformationRuntime(
                    result.getString("form_schema"),
                    object(result.getString("request_payload")));
        });
    }

    private void appendPayloadRevision(
            ApprovalRequestContext.Actor actor,
            UUID requestId,
            String changeType,
            String correlationId,
            String reason) {
        jdbc.update("""
                INSERT INTO apr_request_payload_versions (
                    payload_version_id, tenant_id, request_id, revision_number,
                    payload, payload_sha256, change_type,
                    changed_by, change_reason, correlation_id)
                SELECT gen_random_uuid(), payload.tenant_id, payload.request_id,
                       payload.schema_version, payload.payload, payload.payload_sha256,
                       :changeType, :userId, :reason, :correlationId
                  FROM apr_request_payloads payload
                 WHERE payload.tenant_id = :tenantId AND payload.request_id = :requestId
                ON CONFLICT (tenant_id, request_id, revision_number) DO NOTHING
                """, actorParams(actor)
                .addValue("requestId", requestId)
                .addValue("changeType", changeType)
                .addValue("reason", reason)
                .addValue("correlationId", correlationId));
    }

    private void appendEvent(
            ApprovalRequestContext.Actor actor,
            UUID requestId,
            String eventType,
            String message,
            String correlationId,
            Map<String, Object> data) {
        jdbc.update("""
                INSERT INTO apr_request_events (
                    event_id, tenant_id, request_id, event_type,
                    actor_type, actor_id, outcome, message,
                    correlation_id, event_data)
                VALUES (
                    :eventId, :tenantId, :requestId, :eventType,
                    'USER', :actorId, 'SUCCESS', :message,
                    :correlationId, CAST(:eventData AS jsonb))
                """, new MapSqlParameterSource()
                .addValue("eventId", UUID.randomUUID())
                .addValue("tenantId", actor.tenantId())
                .addValue("requestId", requestId)
                .addValue("eventType", eventType)
                .addValue("actorId", actor.userId().toString())
                .addValue("message", message)
                .addValue("correlationId", correlationId)
                .addValue("eventData", json(data)));
    }

    private void appendIntegration(
            ApprovalRequestContext.Actor actor,
            UUID requestId,
            String eventType,
            String correlationId,
            Map<String, Object> payload) {
        UUID eventId = UUID.randomUUID();
        String value = json(Map.of(
                "specVersion", "1.0",
                "eventType", eventType,
                "tenantId", actor.tenantId(),
                "requestId", requestId.toString(),
                "correlationId", correlationId == null ? "" : correlationId,
                "payload", payload));
        jdbc.update("""
                INSERT INTO apr_integration_outbox (
                    outbox_id, event_id, tenant_id, request_id,
                    event_type, payload, payload_sha256, status)
                VALUES (
                    :outboxId, :eventId, :tenantId, :requestId,
                    :eventType, CAST(:payload AS jsonb),
                    encode(sha256(convert_to(:payload, 'UTF8')), 'hex'), 'PENDING')
                """, new MapSqlParameterSource()
                .addValue("outboxId", UUID.randomUUID())
                .addValue("eventId", eventId)
                .addValue("tenantId", actor.tenantId())
                .addValue("requestId", requestId)
                .addValue("eventType", eventType)
                .addValue("payload", value));
    }

    private MapSqlParameterSource actorParams(ApprovalRequestContext.Actor actor) {
        return new MapSqlParameterSource()
                .addValue("tenantId", actor.tenantId())
                .addValue("userId", actor.userId());
    }

    private String normalizedPriority(String value) {
        String normalized = value == null ? "NORMAL" : value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("LOW", "NORMAL", "HIGH", "URGENT").contains(normalized)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
        return normalized;
    }

    private Map<String, Object> requestPayload(
            String summary,
            Map<String, Object> rawPayload) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (rawPayload != null) payload.putAll(rawPayload);
        payload.put("summary", summary == null ? "" : summary.trim());
        return payload;
    }

    void validateRequestPayload(String schema, Map<String, Object> payload) {
        validateRequestPayload(schema, payload, true);
    }

    void validateRequestPayload(
            String schema,
            Map<String, Object> payload,
            boolean requireRequiredFields) {
        try {
            Map<String, Object> definition = objectMapper.readValue(
                    schema, new TypeReference<Map<String, Object>>() { });
            Object rawFields = definition.get("fields");
            if (!(rawFields instanceof List<?> fields) || fields.isEmpty()) {
                throw new BaseException(ErrorCode.INVALID_STATE);
            }
            Set<String> knownKeys = new HashSet<>();
            for (Object rawField : fields) {
                if (!(rawField instanceof Map<?, ?> field)) {
                    throw new BaseException(ErrorCode.INVALID_STATE);
                }
                String key = requiredRuntimeString(field, "key");
                String type = normalized(requiredRuntimeString(field, "type"),
                        Set.of("TEXT", "TEXTAREA", "NUMBER", "DATE", "SELECT", "USER"));
                boolean required = Boolean.parseBoolean(String.valueOf(field.get("required")));
                knownKeys.add(key);
                Object value = payload.get(key);
                boolean empty = value == null || (value instanceof String text && text.isBlank());
                if (requireRequiredFields && required && empty) {
                    throw new BaseException(
                            ErrorCode.INVALID_INPUT_VALUE,
                            "Required approval field is missing: " + key);
                }
                if (empty) continue;
                validateRequestField(key, type, value, field.get("options"));
            }
            for (String key : payload.keySet()) {
                if (!knownKeys.contains(key) && !"createdFrom".equals(key)) {
                    throw new BaseException(
                            ErrorCode.INVALID_INPUT_VALUE,
                            "Unknown approval field: " + key);
                }
            }
        } catch (JsonProcessingException exception) {
            throw new BaseException(ErrorCode.INVALID_STATE);
        }
    }

    private void validateRequestField(
            String key,
            String type,
            Object value,
            Object rawOptions) {
        String text = String.valueOf(value).trim();
        try {
            switch (type) {
                case "NUMBER" -> new BigDecimal(text);
                case "DATE" -> LocalDate.parse(text);
                case "SELECT" -> {
                    if (!(rawOptions instanceof List<?> options)
                            || options.stream().map(String::valueOf).noneMatch(text::equals)) {
                        throw new BaseException(
                                ErrorCode.INVALID_INPUT_VALUE,
                                "Invalid option for approval field: " + key);
                    }
                }
                case "TEXT", "TEXTAREA", "USER" -> {
                    if (!(value instanceof String)) {
                        throw new BaseException(
                                ErrorCode.INVALID_INPUT_VALUE,
                                "Approval field must be text: " + key);
                    }
                }
                default -> throw new BaseException(ErrorCode.INVALID_STATE);
            }
        } catch (NumberFormatException | DateTimeParseException exception) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Invalid value for approval field: " + key);
        }
    }

    private String normalizeComment(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void requireCategory(long tenantId, UUID categoryId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)::INTEGER
                  FROM apr_form_categories
                 WHERE tenant_id = :tenantId AND category_id = :categoryId
                   AND lifecycle_state = 'ACTIVE'
                """, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("categoryId", categoryId), Integer.class);
        if (count == null || count == 0) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
    }

    private void requireWorkflow(long tenantId, UUID workflowId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)::INTEGER
                  FROM apr_workflow_definitions
                 WHERE tenant_id = :tenantId AND workflow_id = :workflowId
                   AND lifecycle_state <> 'RETIRED'
                """, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("workflowId", workflowId), Integer.class);
        if (count == null || count == 0) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
    }

    private void validateCategoryParent(long tenantId, UUID categoryId, UUID parentCategoryId) {
        if (parentCategoryId == null) return;
        if (parentCategoryId.equals(categoryId)) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        requireCategory(tenantId, parentCategoryId);
        if (categoryId == null) return;
        Integer descendants = jdbc.queryForObject("""
                WITH RECURSIVE descendants AS (
                    SELECT category_id
                      FROM apr_form_categories
                     WHERE tenant_id = :tenantId AND parent_category_id = :categoryId
                    UNION ALL
                    SELECT child.category_id
                      FROM apr_form_categories child
                      JOIN descendants parent ON child.parent_category_id = parent.category_id
                     WHERE child.tenant_id = :tenantId
                )
                SELECT COUNT(*)::INTEGER FROM descendants WHERE category_id = :parentCategoryId
                """, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("categoryId", categoryId)
                        .addValue("parentCategoryId", parentCategoryId), Integer.class);
        if (descendants != null && descendants > 0) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private void replaceDefaultFormRoute(
            ApprovalRequestContext.Actor actor,
            UUID formId,
            UUID workflowId) {
        MapSqlParameterSource params = actorParams(actor)
                .addValue("formId", formId)
                .addValue("workflowId", workflowId);
        jdbc.update("""
                UPDATE apr_form_workflow_bindings
                   SET lifecycle_state = 'INACTIVE', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = :userId
                 WHERE tenant_id = :tenantId AND form_id = :formId
                   AND binding_type = 'DEFAULT' AND lifecycle_state = 'ACTIVE'
                   AND workflow_id <> :workflowId
                """, params);
        int updated = jdbc.update("""
                UPDATE apr_form_workflow_bindings
                   SET binding_type = 'DEFAULT', lifecycle_state = 'ACTIVE',
                       priority = 100, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = :userId
                 WHERE tenant_id = :tenantId AND form_id = :formId
                   AND workflow_id = :workflowId
                """, params);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO apr_form_workflow_bindings (
                        binding_id, tenant_id, form_id, workflow_id,
                        binding_type, priority, lifecycle_state, created_by, updated_by)
                    VALUES (
                        :bindingId, :tenantId, :formId, :workflowId,
                        'DEFAULT', 100, 'ACTIVE', :userId, :userId)
                    """, params.addValue("bindingId", UUID.randomUUID()));
        }
    }

    private void validateWorkflowInput(
            String category,
            String classification,
            int workflowSlaMinutes,
            List<ApprovalDtos.WorkflowStepInput> steps) {
        normalized(category, Set.of("FINANCE", "PEOPLE", "PROCUREMENT", "ACCESS", "GENERAL"));
        normalized(classification, Set.of("INTERNAL", "CONFIDENTIAL", "RESTRICTED"));
        Set<String> keys = new HashSet<>();
        long totalStepSla = 0;
        for (ApprovalDtos.WorkflowStepInput step : steps) {
            normalized(step.mode(), Set.of("ANY"));
            if (!keys.add(step.key().trim().toUpperCase(Locale.ROOT))) {
                throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
            }
            if (!step.candidateRole().trim().matches("[A-Z][A-Z0-9_]{1,79}")) {
                throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
            }
            totalStepSla += step.slaMinutes();
        }
        if (totalStepSla > workflowSlaMinutes) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The sum of step SLAs cannot exceed the workflow SLA.");
        }
    }

    List<RuntimeStep> runtimeSteps(String definition, int workflowSlaMinutes) {
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    definition, new TypeReference<Map<String, Object>>() { });
            Object rawSteps = payload.get("steps");
            if (!(rawSteps instanceof List<?> values) || values.isEmpty()) {
                return List.of(defaultRuntimeStep(workflowSlaMinutes));
            }
            List<RuntimeStep> steps = new java.util.ArrayList<>();
            for (Object rawStep : values) {
                if (!(rawStep instanceof Map<?, ?> value)) {
                    throw new BaseException(ErrorCode.INVALID_STATE);
                }
                String key = requiredRuntimeString(value, "key").toUpperCase(Locale.ROOT);
                String name = runtimeString(value, "name", key);
                String mode = normalized(runtimeString(value, "mode", "ANY"), Set.of("ANY"));
                String candidateRole = requiredRuntimeString(value, "candidateRole").toUpperCase(Locale.ROOT);
                Object rawSlaMinutes = value.get("slaMinutes");
                int slaMinutes = rawSlaMinutes instanceof Number number
                        ? number.intValue()
                        : Integer.parseInt(String.valueOf(rawSlaMinutes));
                if (slaMinutes < 15 || slaMinutes > 525600
                        || !key.matches("[A-Z][A-Z0-9_]{1,79}")
                        || !candidateRole.matches("[A-Z][A-Z0-9_]{1,79}")) {
                    throw new BaseException(ErrorCode.INVALID_STATE);
                }
                steps.add(new RuntimeStep(key, name, mode, candidateRole, slaMinutes));
            }
            return List.copyOf(steps);
        } catch (JsonProcessingException | NumberFormatException exception) {
            throw new BaseException(ErrorCode.INVALID_STATE);
        }
    }

    private RuntimeStep defaultRuntimeStep(int slaMinutes) {
        return new RuntimeStep(
                "PRIMARY_REVIEW", "Primary review", "ANY", "APPROVAL_OPERATOR", slaMinutes);
    }

    private String requiredRuntimeString(Map<?, ?> value, String key) {
        Object raw = value.get(key);
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw new BaseException(ErrorCode.INVALID_STATE);
        }
        return String.valueOf(raw).trim();
    }

    private String runtimeString(Map<?, ?> value, String key, String fallback) {
        Object raw = value.get(key);
        return raw == null || String.valueOf(raw).isBlank() ? fallback : String.valueOf(raw).trim();
    }

    private void validateFormFields(List<ApprovalDtos.FormFieldInput> fields) {
        Set<String> keys = new HashSet<>();
        for (ApprovalDtos.FormFieldInput field : fields) {
            String type = normalized(
                    field.type(), Set.of("TEXT", "TEXTAREA", "NUMBER", "DATE", "SELECT", "USER"));
            if (!keys.add(field.key().trim())) {
                throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
            }
            List<String> options = normalizedOptions(field.options());
            if ("SELECT".equals(type) && options.size() < 2) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "Select fields require at least two unique options.");
            }
            if (!"SELECT".equals(type) && !options.isEmpty()) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "Only select fields can define options.");
            }
        }
    }

    private Map<String, Object> workflowDefinition(List<ApprovalDtos.WorkflowStepInput> steps) {
        return Map.of(
                "schemaVersion", 1,
                "steps", steps.stream().map(step -> Map.<String, Object>of(
                        "key", step.key().trim().toUpperCase(Locale.ROOT),
                        "name", step.name().trim(),
                        "mode", normalized(step.mode(), Set.of("ANY")),
                        "candidateRole", step.candidateRole().trim(),
                        "slaMinutes", step.slaMinutes())).toList(),
                "guardrails", Map.of(
                        "selfApproval", false,
                        "requireReasonOnReject", true,
                        "optimisticConcurrency", true));
    }

    private Map<String, Object> defaultFormSchema() {
        return Map.of(
                "schemaVersion", 1,
                "fields", List.of(
                        Map.of("key", "summary", "labelKo", "요청 내용",
                                "labelEn", "Request summary", "type", "TEXTAREA", "required", true),
                        Map.of("key", "amount", "labelKo", "금액",
                                "labelEn", "Amount", "type", "NUMBER", "required", false),
                        Map.of("key", "neededBy", "labelKo", "필요 일자",
                                "labelEn", "Needed by", "type", "DATE", "required", false)));
    }

    private Map<String, Object> formSchema(List<ApprovalDtos.FormFieldInput> fields) {
        return Map.of(
                "schemaVersion", 1,
                "fields", fields.stream().map(this::formFieldSchema).toList());
    }

    private Map<String, Object> formFieldSchema(ApprovalDtos.FormFieldInput field) {
        String type = normalized(
                field.type(), Set.of("TEXT", "TEXTAREA", "NUMBER", "DATE", "SELECT", "USER"));
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("key", field.key().trim());
        value.put("labelKo", field.labelKo().trim());
        value.put("labelEn", field.labelEn().trim());
        value.put("helpKo", normalizedOptional(field.helpKo()));
        value.put("helpEn", normalizedOptional(field.helpEn()));
        value.put("type", type);
        value.put("required", field.required());
        value.put("options", "SELECT".equals(type) ? normalizedOptions(field.options()) : List.of());
        return value;
    }

    private List<String> normalizedOptions(List<String> options) {
        if (options == null) return List.of();
        return options.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private String normalizedOptional(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalized(String value, Set<String> allowed) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        return normalized;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Approval payload could not be serialized.", exception);
        }
    }

    private Map<String, Object> object(String value) {
        try {
            return objectMapper.readValue(
                    value, new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException exception) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Stored approval policy data is invalid.");
        }
    }

    private void requireUpdated(int updated) {
        if (updated == 0) throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
    }

    private record WorkflowRuntime(
            UUID workflowVersionId,
            UUID formVersionId,
            String dataClassification,
            String formSchema) {
    }

    private record RequestRuntime(
            String status,
            String title,
            int slaMinutes,
            List<RuntimeStep> steps,
            String formSchema,
            Map<String, Object> payload,
            String bindingType,
            String bindingCondition) {
    }

    private record InformationRuntime(
            String formSchema,
            Map<String, Object> payload) {
    }

    record RuntimeStep(
            String key,
            String name,
            String mode,
            String candidateRole,
            int slaMinutes) {
    }

    private record NextStep(
            UUID stepId,
            String stepKey,
            String stepName,
            String candidateRole,
            Instant dueAt) {
    }

    private record PolicyRuntime(
            String enforcementMode,
            String lifecycleState,
            Map<String, Object> rule) {

        private static PolicyRuntime disabled() {
            return new PolicyRuntime("MONITOR", "DISABLED", Map.of());
        }

        private boolean blocks() {
            return "ACTIVE".equals(lifecycleState) && "BLOCK".equals(enforcementMode);
        }

        private int integer(String key, int fallback, int minimum, int maximum) {
            Object value = rule.get(key);
            if (!(value instanceof Number number)) return fallback;
            return Math.max(minimum, Math.min(maximum, number.intValue()));
        }
    }

    public record DecisionResult(String decision, String requestStatus) {
    }
}
