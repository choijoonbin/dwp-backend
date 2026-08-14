package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
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
        WorkflowRuntime workflow = workflow(actor.tenantId(), request.workflowId());
        String priority = normalizedPriority(request.priority());
        UUID requestId = UUID.randomUUID();
        String requestNumber = "APR-" + Instant.now().toEpochMilli() + "-"
                + requestId.toString().substring(0, 6).toUpperCase(Locale.ROOT);
        Map<String, Object> payload = requestPayload(request.summary(), request.payload());
        validateRequestPayload(workflow.formSchema(), payload);
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
        appendEvent(actor, requestId, "REQUEST_DRAFTED", "Draft created", correlationId, Map.of());
        return requestId;
    }

    public void updateDraft(
            ApprovalRequestContext.Actor actor,
            UUID requestId,
            ApprovalDtos.UpdateDraftRequest request,
            String correlationId) {
        WorkflowRuntime workflow = workflow(actor.tenantId(), request.workflowId());
        String priority = normalizedPriority(request.priority());
        Map<String, Object> payload = requestPayload(request.summary(), request.payload());
        validateRequestPayload(workflow.formSchema(), payload);
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
        MapSqlParameterSource params = actorParams(actor)
                .addValue("requestId", requestId)
                .addValue("expectedVersion", request.expectedVersion())
                .addValue("message", request.message().trim());
        int updated = jdbc.update("""
                UPDATE apr_requests
                   SET status = 'IN_REVIEW', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = :userId
                 WHERE tenant_id = :tenantId AND request_id = :requestId
                   AND requester_user_id = :userId
                   AND status = 'NEEDS_INFO' AND version = :expectedVersion
                """, params);
        requireUpdated(updated);
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
                Map.of("responseLength", request.message().trim().length()));
        appendIntegration(actor, requestId, "approval.request.information.responded", correlationId,
                Map.of("requestId", requestId.toString()));
    }

    public void claim(
            ApprovalRequestContext.Actor actor,
            ApprovalQueryRepository.TaskAccess task,
            long expectedVersion,
            String correlationId) {
        if (task.assigneeUserId() != null && !task.assigneeUserId().equals(actor.userId())) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        if (task.candidateRole() != null && !actor.roles().contains(task.candidateRole())
                && task.assigneeUserId() == null) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        int updated = jdbc.update("""
                UPDATE apr_tasks
                   SET assignee_user_id = :userId,
                       assignee_person_public_id = :personPublicId,
                       status = 'CLAIMED', claimed_at = CURRENT_TIMESTAMP,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId AND task_id = :taskId
                   AND assignee_user_id IS NULL AND status = 'PENDING'
                   AND version = :expectedVersion
                """, actorParams(actor)
                .addValue("personPublicId", actor.personPublicId())
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
        if (task.requesterUserId() == actor.userId()) {
            throw new BaseException(ErrorCode.FORBIDDEN, "A requester cannot decide their own request.");
        }
        if (task.assigneeUserId() != null && !task.assigneeUserId().equals(actor.userId())) {
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
                if (decision.comment() == null || decision.comment().trim().length() < 8) {
                    throw new BaseException(
                            ErrorCode.INVALID_INPUT_VALUE,
                            "A rejection reason of at least 8 characters is required.");
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
                .addValue("reason", normalizeComment(decision.comment()));
        int updated = jdbc.update("""
                UPDATE apr_tasks
                   SET assignee_user_id = COALESCE(assignee_user_id, :userId),
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
        appendEvent(actor, task.summary().requestId(), eventType,
                normalizeComment(decision.comment()), correlationId,
                Map.of("taskId", task.summary().taskId().toString(), "decision", normalized));
        appendIntegration(actor, task.summary().requestId(),
                "approval.task." + normalized.toLowerCase(Locale.ROOT), correlationId,
                Map.of("requestId", task.summary().requestId().toString(),
                        "taskId", task.summary().taskId().toString(),
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
            ApprovalDtos.CreateDelegationRequest request) {
        if (request.delegateUserId().equals(actor.userId()) || !request.endsAt().isAfter(request.startsAt())) {
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
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO apr_delegations (
                    delegation_id, tenant_id, delegator_user_id, delegate_user_id,
                    scope_type, workflow_key, starts_at, ends_at,
                    lifecycle_state, reason, created_by, updated_by)
                VALUES (
                    :id, :tenantId, :userId, :delegateUserId,
                    :scopeType, :workflowKey, :startsAt, :endsAt,
                    'ACTIVE', :reason, :userId, :userId)
                """, actorParams(actor)
                .addValue("id", id)
                .addValue("delegateUserId", request.delegateUserId())
                .addValue("scopeType", scope)
                .addValue("workflowKey", request.workflowKey())
                .addValue("startsAt", request.startsAt())
                .addValue("endsAt", request.endsAt())
                .addValue("reason", request.reason().trim()));
        return id;
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
                    form_id, tenant_id, form_key, name_ko, name_en,
                    lifecycle_state, current_version, created_by, updated_by)
                VALUES (
                    :formId, :tenantId, :formKey, :formNameKo, :formNameEn,
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
        String schema = json(formSchema(request.fields()));
        MapSqlParameterSource params = actorParams(actor)
                .addValue("formId", formId)
                .addValue("nameKo", request.nameKo().trim())
                .addValue("nameEn", request.nameEn().trim())
                .addValue("schema", schema)
                .addValue("expectedVersion", request.expectedVersion());
        int updated = jdbc.update("""
                UPDATE apr_forms
                   SET name_ko = :nameKo, name_en = :nameEn,
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
        int updated = jdbc.update("""
                UPDATE apr_policy_rules
                   SET enforcement_mode = :mode, severity = :severity,
                       lifecycle_state = :state, rule_payload = CAST(:rule AS jsonb),
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
                .addValue("expectedVersion", request.expectedVersion()));
        requireUpdated(updated);
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
                   AND COALESCE(created_by, -1) <> :userId
                """, params);
        requireUpdated(updated);
        jdbc.update("""
                UPDATE apr_workflow_versions
                   SET lifecycle_state = 'PUBLISHED', effective_from = CURRENT_TIMESTAMP,
                       published_at = CURRENT_TIMESTAMP, published_by = :userId
                 WHERE tenant_id = :tenantId AND workflow_id = :workflowId
                   AND lifecycle_state = 'DRAFT'
                """, params);
        jdbc.update("""
                UPDATE apr_forms form
                   SET lifecycle_state = 'PUBLISHED', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = :userId
                 WHERE form.tenant_id = :tenantId
                   AND form.form_key = (
                       SELECT workflow_key || '_FORM' FROM apr_workflow_definitions
                        WHERE tenant_id = :tenantId AND workflow_id = :workflowId)
                """, params);
        jdbc.update("""
                UPDATE apr_form_versions form_version
                   SET lifecycle_state = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
                       published_by = :userId
                 WHERE form_version.tenant_id = :tenantId
                   AND form_version.lifecycle_state = 'DRAFT'
                   AND form_version.form_id = (
                       SELECT form.form_id FROM apr_forms form
                        WHERE form.tenant_id = :tenantId
                          AND form.form_key = (
                              SELECT workflow_key || '_FORM'
                                FROM apr_workflow_definitions
                               WHERE tenant_id = :tenantId
                                 AND workflow_id = :workflowId))
                """, params);
        UUID evidenceRequest = jdbc.query("""
                SELECT request_id FROM apr_requests
                 WHERE tenant_id = :tenantId ORDER BY created_at LIMIT 1
                """, params, result -> result.next() ? result.getObject(1, UUID.class) : null);
        if (evidenceRequest != null) {
            appendEvent(actor, evidenceRequest, "WORKFLOW_PUBLISHED",
                    "Workflow published", correlationId,
                    Map.of("workflowId", workflowId.toString()));
        }
    }

    private WorkflowRuntime workflow(long tenantId, UUID workflowId) {
        return jdbc.query("""
                SELECT version.workflow_version_id, form_version.form_version_id,
                       definition.data_classification,
                       form_version.schema_payload::text AS form_schema
                  FROM apr_workflow_definitions definition
                  JOIN apr_workflow_versions version
                    ON version.tenant_id = definition.tenant_id
                   AND version.workflow_id = definition.workflow_id
                   AND version.version_number = definition.current_version
                  JOIN apr_forms form
                    ON form.tenant_id = definition.tenant_id
                   AND form.form_key = definition.workflow_key || '_FORM'
                  JOIN apr_form_versions form_version
                    ON form_version.tenant_id = form.tenant_id
                   AND form_version.form_id = form.form_id
                   AND form_version.version_number = form.current_version
                 WHERE definition.tenant_id = :tenantId
                   AND definition.workflow_id = :workflowId
                   AND definition.lifecycle_state = 'PUBLISHED'
                """, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("workflowId", workflowId),
                result -> {
                    if (!result.next()) throw new BaseException(ErrorCode.NOT_FOUND);
                    return new WorkflowRuntime(
                            result.getObject("workflow_version_id", UUID.class),
                            result.getObject("form_version_id", UUID.class),
                            result.getString("data_classification"),
                            result.getString("form_schema"));
                });
    }

    private RequestRuntime ownedRequest(ApprovalRequestContext.Actor actor, UUID requestId) {
        return jdbc.query("""
                SELECT request.status, workflow.sla_minutes,
                       workflow_version.definition::text AS workflow_definition
                  FROM apr_requests request
                  JOIN apr_workflow_versions workflow_version
                    ON workflow_version.tenant_id = request.tenant_id
                   AND workflow_version.workflow_version_id = request.workflow_version_id
                  JOIN apr_workflow_definitions workflow
                    ON workflow.tenant_id = workflow_version.tenant_id
                   AND workflow.workflow_id = workflow_version.workflow_id
                 WHERE request.tenant_id = :tenantId
                   AND request.request_id = :requestId
                   AND request.requester_user_id = :userId
                """, actorParams(actor).addValue("requestId", requestId), result -> {
            if (!result.next()) throw new BaseException(ErrorCode.NOT_FOUND);
            int slaMinutes = result.getInt("sla_minutes");
            return new RequestRuntime(
                    result.getString("status"),
                    slaMinutes,
                    runtimeSteps(result.getString("workflow_definition"), slaMinutes));
        });
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
        payload.put("summary", summary.trim());
        return payload;
    }

    void validateRequestPayload(String schema, Map<String, Object> payload) {
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
                if (required && empty) {
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

    private void requireUpdated(int updated) {
        if (updated == 0) throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
    }

    private record WorkflowRuntime(
            UUID workflowVersionId,
            UUID formVersionId,
            String dataClassification,
            String formSchema) {
    }

    private record RequestRuntime(String status, int slaMinutes, List<RuntimeStep> steps) {
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

    public record DecisionResult(String decision, String requestStatus) {
    }
}
