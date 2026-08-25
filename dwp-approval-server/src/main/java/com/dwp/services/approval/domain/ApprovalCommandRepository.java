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

@Repository
public class ApprovalCommandRepository {

    private static final String ROOT_MANAGEMENT_SCOPE = "RS_APPROVALS";

    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalCommandPayloadSupport payloadSupport;
    private final ApprovalDelegationCommandSupport delegationCommands;

    public ApprovalCommandRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.payloadSupport = new ApprovalCommandPayloadSupport(objectMapper);
        this.delegationCommands = new ApprovalDelegationCommandSupport(
                jdbc, payloadSupport);
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
        List<RuntimeStep> steps = request.steps();
        RuntimeStep firstStep = steps.get(0);
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
            RuntimeStep step = steps.get(index);
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

    public DecisionResult decide(
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
        return new DecisionResult(normalized, requestStatus);
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

    public ApprovalDelegationCommandSupport.Created createDelegation(
            ApprovalRequestContext.Actor actor,
            ApprovalDtos.CreateDelegationRequest request,
            com.dwp.services.approval.integration.ApprovalIdentityDirectory.Subject delegate) {
        return delegationCommands.create(actor, request, delegate);
    }

    public void revokeDelegation(
            ApprovalRequestContext.Actor actor,
            UUID delegationId,
            long expectedVersion) {
        delegationCommands.revoke(actor, delegationId, expectedVersion);
    }

    public UUID createFormCategory(
            ApprovalRequestContext.Actor actor,
            ApprovalDtos.CreateFormCategoryRequest request) {
        String categoryKey = request.categoryKey().trim().toUpperCase(Locale.ROOT);
        validateCategoryParent(actor.tenantId(), null, request.parentCategoryId());
        UUID categoryId = UUID.randomUUID();
        try {
            jdbc.update(ApprovalCommandSql01.CREATE_FORM_CATEGORY_INSERT_APR_FORM_CATEGORIES, actorParams(actor)
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
        int updated = jdbc.update(ApprovalCommandSql01.UPDATE_FORM_CATEGORY_UPDATE_APR_FORM_CATEGORIES, actorParams(actor)
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
        payloadSupport.validateFormFields(request.fields());
        requireCategory(actor.tenantId(), request.categoryId());
        requireWorkflow(actor.tenantId(), request.defaultWorkflowId());
        String formKey = request.formKey().trim().toUpperCase(Locale.ROOT);
        UUID formId = UUID.randomUUID();
        UUID formVersionId = UUID.randomUUID();
        String schema = payloadSupport.json(payloadSupport.formSchema(request.fields()));
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
            jdbc.update(ApprovalCommandSql01.CREATE_FORM_DRAFT_INSERT_APR_FORMS, params);
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
        jdbc.update(ApprovalCommandSql01.APR_FORMS_INSERT_APR_FORM_VERSIONS, params);
        jdbc.update(ApprovalCommandSql01.APR_FORM_VERSIONS_INSERT_APR_FORM_WORKFLOW_BINDINGS, params);
        return formId;
    }

    public UUID createWorkflowDraft(
            ApprovalRequestContext.Actor actor,
            ApprovalDtos.CreateWorkflowDraftRequest request) {
        String workflowKey = request.workflowKey().trim().toUpperCase(Locale.ROOT);
        payloadSupport.validateWorkflowInput(
                request.category(), request.dataClassification(), request.slaMinutes(), request.steps());
        Integer existing = jdbc.queryForObject(ApprovalCommandSql01.CREATE_WORKFLOW_DRAFT_SELECT_APR_WORKFLOW_DEFINITIONS, actorParams(actor).addValue("workflowKey", workflowKey), Integer.class);
        if (existing != null && existing > 0) throw new BaseException(ErrorCode.RESOURCE_CONFLICT);

        jdbc.update(
                ApprovalCommandSql01.ENSURE_WORKFLOW_CATEGORY_INSERT_APR_FORM_CATEGORIES,
                actorParams(actor).addValue(
                        "category", request.category().trim().toUpperCase(Locale.ROOT)));

        UUID workflowId = UUID.randomUUID();
        UUID workflowVersionId = UUID.randomUUID();
        UUID formId = UUID.randomUUID();
        UUID formVersionId = UUID.randomUUID();
        String definition = payloadSupport.json(payloadSupport.workflowDefinition(request.steps()));
        String schema = payloadSupport.json(payloadSupport.defaultFormSchema());
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
        jdbc.update(ApprovalCommandSql01.COUNT_INSERT_APR_WORKFLOW_DEFINITIONS, params);
        jdbc.update(ApprovalCommandSql01.APR_WORKFLOW_DEFINITIONS_INSERT_APR_WORKFLOW_VERSIONS, params);
        jdbc.update(ApprovalCommandSql01.APR_WORKFLOW_VERSIONS_INSERT_APR_FORMS, params);
        jdbc.update(ApprovalCommandSql01.APR_FORMS_INSERT_APR_FORM_VERSIONS_2, params);
        jdbc.update(ApprovalCommandSql01.APR_FORM_VERSIONS_INSERT_APR_FORM_WORKFLOW_BINDINGS_2, params.addValue("bindingId", UUID.randomUUID()));
        return workflowId;
    }

    public void updateWorkflowDraft(
            ApprovalRequestContext.Actor actor,
            UUID workflowId,
            ApprovalDtos.UpdateWorkflowDraftRequest request) {
        payloadSupport.validateWorkflowInput(
                request.category(), request.dataClassification(), request.slaMinutes(), request.steps());
        String definition = payloadSupport.json(payloadSupport.workflowDefinition(request.steps()));
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
        int updated = jdbc.update(ApprovalCommandSql01.UPDATE_WORKFLOW_DRAFT_UPDATE_APR_WORKFLOW_DEFINITIONS, params);
        requireUpdated(updated);
        jdbc.update(ApprovalCommandSql01.UPDATE_WORKFLOW_DRAFT_UPDATE_APR_WORKFLOW_VERSIONS, params);
    }

    public void updateFormDraft(
            ApprovalRequestContext.Actor actor,
            UUID formId,
            ApprovalDtos.UpdateFormDraftRequest request) {
        payloadSupport.validateFormFields(request.fields());
        requireCategory(actor.tenantId(), request.categoryId());
        requireWorkflow(actor.tenantId(), request.defaultWorkflowId());
        String schema = payloadSupport.json(payloadSupport.formSchema(request.fields()));
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
        int updated = jdbc.update(ApprovalCommandSql01.UPDATE_FORM_DRAFT_UPDATE_APR_FORMS, params);
        requireUpdated(updated);
        jdbc.update(ApprovalCommandSql01.UPDATE_FORM_DRAFT_UPDATE_APR_FORM_VERSIONS, params);
        replaceDefaultFormRoute(actor, formId, request.defaultWorkflowId());
    }

    public void publishForm(
            ApprovalRequestContext.Actor actor,
            UUID formId,
            long expectedVersion) {
        MapSqlParameterSource params = actorParams(actor)
                .addValue("formId", formId)
                .addValue("expectedVersion", expectedVersion);
        int updated = jdbc.update(ApprovalCommandSql01.PUBLISH_FORM_UPDATE_APR_FORMS, params);
        requireUpdated(updated);
        jdbc.update(ApprovalCommandSql01.EXISTS_UPDATE_APR_FORM_VERSIONS, params);
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
        String policyKey = jdbc.query(ApprovalCommandSql01.UPDATE_POLICY_SELECT_APR_POLICY_RULES, actorParams(actor).addValue("policyId", policyId), result -> {
            if (!result.next()) throw new BaseException(ErrorCode.NOT_FOUND);
            return result.getString("policy_key");
        });
        validatePolicyRule(policyKey, request.rule());
        int updated = jdbc.update(ApprovalCommandSql01.UPDATE_POLICY_UPDATE_APR_POLICY_RULES, actorParams(actor)
                .addValue("policyId", policyId)
                .addValue("mode", mode)
                .addValue("severity", severity)
                .addValue("state", state)
                .addValue("rule", payloadSupport.json(request.rule()))
                .addValue("changeReason", request.changeReason().trim())
                .addValue("expectedVersion", request.expectedVersion()));
        requireUpdated(updated);
    }

    public void publishPolicy(
            ApprovalRequestContext.Actor actor,
            UUID policyId,
            ApprovalDtos.PublishPolicyRequest request) {
        int updated = jdbc.update(ApprovalCommandSql01.PUBLISH_POLICY_WITH_APR_POLICY_RULES, actorParams(actor)
                .addValue("policyId", policyId)
                .addValue("policyVersionId", UUID.randomUUID())
                .addValue("expectedVersion", request.expectedVersion())
                .addValue("reviewComment", request.reviewComment().trim()));
        requireUpdated(updated);
    }

    public void retryIntegrationDelivery(
            ApprovalRequestContext.Actor actor,
            UUID outboxId,
            long expectedVersion) {
        int updated = jdbc.update(
                ApprovalCommandSql01.RETRY_INTEGRATION_DELIVERY_UPDATE_APR_INTEGRATION_OUTBOX,
                actorParams(actor)
                        .addValue("outboxId", outboxId)
                        .addValue("expectedVersion", expectedVersion));
        requireUpdated(updated);
    }

    public void retryIntegrationDelivery(
            ApprovalRequestContext.Actor actor,
            UUID outboxId) {
        int updated = jdbc.update(
                ApprovalCommandSql01.LEGACY_RETRY_INTEGRATION_DELIVERY_UPDATE_APR_INTEGRATION_OUTBOX,
                actorParams(actor).addValue("outboxId", outboxId));
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

    private PolicyRuntime policy(
            long tenantId,
            String policyKey,
            String managementResourceSetKey) {
        return jdbc.query(ApprovalCommandSql01.POLICY_SELECT_APR_POLICY_RULES, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("policyKey", policyKey)
                .addValue("managementScope", managementResourceSetKey), result -> {
            if (!result.next()) return PolicyRuntime.disabled();
            return new PolicyRuntime(
                    result.getString("enforcement_mode"),
                    result.getString("lifecycle_state"),
                    payloadSupport.object(result.getString("rule_payload"),
                            "Stored approval policy data is invalid."));
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
        int updated = jdbc.update(ApprovalCommandSql02.PUBLISH_WORKFLOW_UPDATE_APR_WORKFLOW_DEFINITIONS, params);
        requireUpdated(updated);
        jdbc.update(ApprovalCommandSql02.COALESCE_UPDATE_APR_WORKFLOW_VERSIONS, params);
    }

    private WorkflowRuntime workflow(
            long tenantId,
            UUID workflowId,
            UUID formId,
            Map<String, Object> requestPayload,
            boolean requireRouteMatch) {
        return jdbc.query(ApprovalCommandSql02.WORKFLOW_SELECT_APR_WORKFLOW_DEFINITIONS, new MapSqlParameterSource()
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
                            result.getString("management_resource_set_key"),
                            result.getString("form_schema"));
                });
    }

    boolean matchesRouteCondition(
            String storedCondition,
            Map<String, Object> requestPayload) {
        try {
            Map<String, Object> condition = payloadSupport.object(
                    storedCondition, "The approval route condition is invalid.");
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
        } catch (BaseException exception) {
            throw exception;
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
        return jdbc.query(ApprovalCommandSql02.OWNED_REQUEST_SELECT_APR_REQUESTS, actorParams(actor).addValue("requestId", requestId), result -> {
            if (!result.next()) throw new BaseException(ErrorCode.NOT_FOUND);
            int slaMinutes = result.getInt("sla_minutes");
            return new RequestRuntime(
                    result.getString("status"),
                    result.getString("title"),
                    slaMinutes,
                    runtimeSteps(result.getString("workflow_definition"), slaMinutes),
                    result.getString("form_schema"),
                    payloadSupport.object(result.getString("request_payload"),
                            "Stored approval request data is invalid."),
                    result.getString("binding_type"),
                    result.getString("binding_condition"));
        });
    }

    private InformationRuntime informationRuntime(
            ApprovalRequestContext.Actor actor,
            UUID requestId) {
        return jdbc.query(ApprovalCommandSql02.INFORMATION_RUNTIME_SELECT_APR_REQUESTS, actorParams(actor).addValue("requestId", requestId), result -> {
            if (!result.next()) throw new BaseException(ErrorCode.INVALID_STATE);
            return new InformationRuntime(
                    result.getString("form_schema"),
                    payloadSupport.object(result.getString("request_payload"),
                            "Stored approval request data is invalid."));
        });
    }

    private void appendPayloadRevision(
            ApprovalRequestContext.Actor actor,
            UUID requestId,
            String changeType,
            String correlationId,
            String reason) {
        jdbc.update(ApprovalCommandSql02.APPEND_PAYLOAD_REVISION_INSERT_APR_REQUEST_PAYLOAD_VERSIONS, actorParams(actor)
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
        Map<String, Object> evidence = new LinkedHashMap<>(data);
        if (actor.displayName() != null && !actor.displayName().isBlank()) {
            evidence.putIfAbsent("actorDisplayName", actor.displayName().trim());
        }
        jdbc.update(ApprovalCommandSql02.APPEND_EVENT_INSERT_APR_REQUEST_EVENTS, new MapSqlParameterSource()
                .addValue("eventId", UUID.randomUUID())
                .addValue("tenantId", actor.tenantId())
                .addValue("userId", actor.userId())
                .addValue("requestId", requestId)
                .addValue("eventType", eventType)
                .addValue("actorId", actor.userId().toString())
                .addValue("message", message)
                .addValue("correlationId", correlationId)
                .addValue("eventData", payloadSupport.json(evidence)));
    }

    private void appendIntegration(
            ApprovalRequestContext.Actor actor,
            UUID requestId,
            String eventType,
            String correlationId,
            Map<String, Object> payload) {
        UUID eventId = UUID.randomUUID();
        String value = payloadSupport.json(Map.of(
                "specVersion", "1.0",
                "eventType", eventType,
                "tenantId", actor.tenantId(),
                "requestId", requestId.toString(),
                "correlationId", correlationId == null ? "" : correlationId,
                "payload", payload));
        int appended = jdbc.update(
                ApprovalCommandSql02.APPEND_INTEGRATION_INSERT_APR_INTEGRATION_OUTBOX,
                new MapSqlParameterSource()
                .addValue("outboxId", UUID.randomUUID())
                .addValue("eventId", eventId)
                .addValue("tenantId", actor.tenantId())
                .addValue("userId", actor.userId())
                .addValue("requestId", requestId)
                .addValue("eventType", eventType)
                .addValue("payload", value));
        requireUpdated(appended);
    }

    private MapSqlParameterSource actorParams(ApprovalRequestContext.Actor actor) {
        return new MapSqlParameterSource()
                .addValue("tenantId", actor.tenantId())
                .addValue("userId", actor.userId())
                .addValue("managementScope", managementResourceSetKey());
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
            Map<String, Object> definition = payloadSupport.object(
                    schema, "Stored approval form schema is invalid.");
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
        } catch (BaseException exception) {
            throw exception;
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
        Integer count = jdbc.queryForObject(ApprovalCommandSql02.REQUIRE_CATEGORY_SELECT_APR_FORM_CATEGORIES, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("managementScope", managementResourceSetKey())
                        .addValue("categoryId", categoryId), Integer.class);
        if (count == null || count == 0) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
    }

    private void requireWorkflow(long tenantId, UUID workflowId) {
        Integer count = jdbc.queryForObject(ApprovalCommandSql02.REQUIRE_WORKFLOW_SELECT_APR_WORKFLOW_DEFINITIONS, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("managementScope", managementResourceSetKey())
                        .addValue("workflowId", workflowId), Integer.class);
        if (count == null || count == 0) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
    }

    private void validateCategoryParent(long tenantId, UUID categoryId, UUID parentCategoryId) {
        if (parentCategoryId == null) return;
        if (parentCategoryId.equals(categoryId)) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        requireCategory(tenantId, parentCategoryId);
        if (categoryId == null) return;
        Integer descendants = jdbc.queryForObject(ApprovalCommandSql02.VALIDATE_CATEGORY_PARENT_WITH_APR_FORM_CATEGORIES, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("managementScope", managementResourceSetKey())
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
        jdbc.update(ApprovalCommandSql02.REPLACE_DEFAULT_FORM_ROUTE_UPDATE_APR_FORM_WORKFLOW_BINDINGS, params);
        int updated = jdbc.update(ApprovalCommandSql02.REPLACE_DEFAULT_FORM_ROUTE_UPDATE_APR_FORM_WORKFLOW_BINDINGS_2, params);
        if (updated == 0) {
            jdbc.update(ApprovalCommandSql02.REPLACE_DEFAULT_FORM_ROUTE_INSERT_APR_FORM_WORKFLOW_BINDINGS, params.addValue("bindingId", UUID.randomUUID()));
        }
    }

    List<RuntimeStep> runtimeSteps(String definition, int workflowSlaMinutes) {
        return payloadSupport.runtimeSteps(definition, workflowSlaMinutes);
    }

    private String requiredRuntimeString(Map<?, ?> value, String key) {
        Object raw = value.get(key);
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw new BaseException(ErrorCode.INVALID_STATE);
        }
        return String.valueOf(raw).trim();
    }

    private String normalizedOptional(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalized(String value, Set<String> allowed) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        return normalized;
    }

    private void requireUpdated(int updated) {
        if (updated == 0) throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
    }

    private record WorkflowRuntime(
            UUID workflowVersionId,
            UUID formVersionId,
            String dataClassification,
            String managementResourceSetKey,
            String formSchema) {
    }

    private String managementResourceSetKey() {
        return ApprovalManagementScopeContext.current()
                .map(ApprovalManagementScopeContext.Evidence::resourceSetKey)
                .orElseGet(() -> {
                    if (ApprovalDecisionRevisionContext.current().isPresent()) {
                        throw new BaseException(
                                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                                "Approval management scope evidence is unavailable.");
                    }
                    return ROOT_MANAGEMENT_SCOPE;
                });
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
