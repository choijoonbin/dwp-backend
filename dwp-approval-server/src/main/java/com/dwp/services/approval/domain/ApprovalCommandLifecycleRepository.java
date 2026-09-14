package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.attachment.binding.ApprovalAttachmentLifecycleBinding;
import com.dwp.services.approval.documentretention.ApprovalRetentionLiveGuard;
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
        payload = payloadSupport.isTypedFormSchema(workflow.formSchema())
                ? formNormalization.create(actor, requestId, workflow.formVersionId(), workflow.formSchema(), payload)
                : normalizeRequestPayload(workflow.formSchema(), payload, false);
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
        attachmentLifecycleBinding().initializeCreated(requestId, 0);
        appendEvent(actor, requestId, "REQUEST_DRAFTED", "Draft created", correlationId, Map.of());
        return requestId;
    }

    public void updateDraft(
            ApprovalRequestContext.Actor actor,
            UUID requestId,
            ApprovalDtos.UpdateDraftRequest request,
            String correlationId) {
        updateDraft(actor, requestId, request, correlationId, null);
    }

    void updateDraft(ApprovalRequestContext.Actor actor, UUID requestId, ApprovalDtos.UpdateDraftRequest request,
            String correlationId, ApprovalAttachmentLifecycleBinding.Pin recoveryPin) {
        String priority = normalizedPriority(request.priority());
        Map<String, Object> payload = requestPayload(request.summary(), request.payload());
        var pin = formNormalization.pinTypedDraft(actor, requestId, request.expectedVersion(), request.formId());
        WorkflowRuntime workflow = pin == null ? workflow(actor.tenantId(), request.workflowId(), request.formId(), payload, false)
                : ApprovalWorkflowPinnedDraftRuntime.require(jdbc, actor, requestId, request.expectedVersion(), request.workflowId(), pin,
                        runtime -> new WorkflowRuntime(runtime.workflowVersionId(), runtime.formVersionId(), runtime.dataClassification(),
                                runtime.managementResourceSetKey(), runtime.formSchema()),
                        (definition, sla) -> payloadSupport.runtimeSteps(definition, sla));
        payload = normalizeRequestPayload(actor, requestId, workflow.formSchema(), payload, false, request.expectedVersion());
        String payloadJson = payloadSupport.json(payload);
        var attachments = attachmentLifecycleBinding();
        var attachmentPin = recoveryPin == null
                ? attachments.prepare(requestId, request.expectedVersion(), ApprovalAttachmentLifecycleBinding.Intent.UPDATE_DRAFT) : recoveryPin;
        MapSqlParameterSource params = workActorParams(
                actor, workflow.managementResourceSetKey())
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
        attachments.seal(attachmentPin, attachments.target(requestId));
        appendEvent(actor, requestId, "REQUEST_DRAFT_UPDATED", "Draft updated", correlationId,
                Map.of("workflowId", request.workflowId().toString()));
    }

    public void submit(
            ApprovalRequestContext.Actor actor,
            UUID requestId,
            long expectedVersion,
            String correlationId) {
        if (quorumWorkflow(actor.tenantId(), requestId) != null) throw new BaseException(
                ErrorCode.INVALID_STATE, "Tagged workflows require the durable quorum submit path.");
        RequestRuntime request = ownedRequest(actor, requestId);
        if (!"DRAFT".equals(request.status())) throw new BaseException(ErrorCode.INVALID_STATE);
        if (request.title() == null || request.title().isBlank()) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Approval title is required before submission.");
        }
        Map<String, Object> normalizedPayload = normalizeRequestPayload(actor, requestId, request.formSchema(), request.payload(), true, expectedVersion);
        if (payloadSupport.isTypedFormSchema(request.formSchema())
                && !ApprovalFormSchemaV2Canonical.freeze(request.payload()).equals(normalizedPayload)) {
            throw new BaseException(ErrorCode.INVALID_STATE, "Stored typed payload is not bound to its normalized revision.");
        }
        if ("CONDITIONAL".equals(request.bindingType())
                && !matchesRouteCondition(request.bindingCondition(), request.payload(), request.formSchema())) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "The selected approval route does not match this request.");
        }
        List<ApprovalCommandRepository.RuntimeStep> steps = request.steps();
        attachmentLifecycleBinding().requireSealedForSubmit(requestId, expectedVersion);
        ApprovalCommandRepository.RuntimeStep firstStep = steps.get(0);
        UUID firstStepId = UUID.randomUUID();
        UUID firstTaskId = UUID.randomUUID();
        MapSqlParameterSource params = workActorParams(
                actor, request.managementResourceSetKey())
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
            jdbc.update(ApprovalCommandSql01.SUBMIT_INSERT_APR_STEPS, workActorParams(
                    actor, request.managementResourceSetKey())
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
        jdbc.update(ApprovalCommandSql01.APR_STEPS_INSERT_APR_TASKS, workActorParams(
                actor, request.managementResourceSetKey())
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

    public ApprovalWorkflowQuorumDefinition prepareQuorumSubmit(ApprovalRequestContext.Actor actor, UUID requestId) {
        Long version = jdbc.queryForObject("SELECT version FROM apr_requests WHERE tenant_id=:tenantId "
                + "AND request_id=:requestId AND requester_user_id=:userId", identityParams(actor).addValue("requestId", requestId), Long.class);
        if (version == null) throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        return prepareQuorumSubmit(actor, requestId, version);
    }

    public ApprovalWorkflowQuorumDefinition prepareQuorumSubmit(ApprovalRequestContext.Actor actor, UUID requestId, long expectedVersion) {
        var definition = quorumWorkflow(actor.tenantId(), requestId);
        if (definition == null) throw new BaseException(ErrorCode.INVALID_STATE);
        RequestRuntime request = ownedRequest(actor, requestId);
        if (!"DRAFT".equals(request.status())) throw new BaseException(ErrorCode.INVALID_STATE);
        if (request.title() == null || request.title().isBlank()) throw new BaseException(
                ErrorCode.INVALID_INPUT_VALUE, "Approval title is required before submission.");
        var normalized = normalizeRequestPayload(actor, requestId, request.formSchema(), request.payload(), true, expectedVersion);
        if (payloadSupport.isTypedFormSchema(request.formSchema())
                && !ApprovalFormSchemaV2Canonical.freeze(request.payload()).equals(normalized)) {
            throw new BaseException(ErrorCode.INVALID_STATE, "Stored typed payload is not bound to its normalized revision.");
        }
        if ("CONDITIONAL".equals(request.bindingType())
                && !matchesRouteCondition(request.bindingCondition(), request.payload(), request.formSchema())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The selected approval route does not match this request.");
        }
        if (request.slaMinutes() != definition.slaMinutes()) throw new BaseException(ErrorCode.INVALID_STATE);
        attachmentLifecycleBinding().requireSealedForSubmit(requestId, expectedVersion);
        return definition;
    }

    public void submitQuorum(ApprovalRequestContext.Actor actor, UUID requestId, long expectedVersion,
            String correlationId, ApprovalWorkflowQuorumRuntime runtime) {
        var definition = prepareQuorumSubmit(actor, requestId, expectedVersion);
        var request = ownedRequest(actor, requestId);
        var params = workActorParams(actor, request.managementResourceSetKey()).addValue("requestId", requestId)
                .addValue("expectedVersion", expectedVersion).addValue("slaMinutes", definition.slaMinutes())
                .addValue("correlationId", correlationId);
        requireUpdated(jdbc.update(ApprovalCommandSql01.SUBMIT_UPDATE_APR_REQUESTS, params));
        var pins = runtime.canonicalPins(actor.tenantId(), requestId, definition);
        runtime.start(actor.tenantId(), requestId, pins, definition);
        appendEvent(actor, requestId, "REQUEST_SUBMITTED", "Request submitted", correlationId,
                Map.of("workflowContract", ApprovalWorkflowQuorum.CONTRACT, "definitionSha256", definition.sha256()));
    }

    public void withdraw(
            ApprovalRequestContext.Actor actor,
            UUID requestId,
            long expectedVersion,
            String correlationId) {
        String managementScope = ownedRequestManagementScope(actor, requestId);
        MapSqlParameterSource params = workActorParams(actor, managementScope)
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
        boolean typedForm = payloadSupport.isTypedFormSchema(current.formSchema());
        if (typedForm) validateTypedInformationPatch(request.payload());
        else validateInformationPatch(request.payload());
        Map<String, Object> amendedPayload = informationPayloadBase(current.formSchema(), current.payload());
        if (request.payload() != null) amendedPayload.putAll(request.payload());
        if (request.payload() != null && request.payload().containsKey("summary")) {
            Object summaryValue = amendedPayload.get("summary");
            if (!(summaryValue instanceof String summary)
                    || summary.strip().isEmpty()
                    || summary.strip().length() > 2000) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "The amended approval summary must contain at most 2000 characters.");
            }
            amendedPayload.put("summary", summary.strip());
        }
        amendedPayload = normalizeRequestPayload(actor, requestId, current.formSchema(), amendedPayload, true, request.expectedVersion());
        if ("CONDITIONAL".equals(current.bindingType())
                && !matchesRouteCondition(current.bindingCondition(), amendedPayload, current.formSchema())) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "The amended request no longer matches its governed approval route.");
        }
        List<String> amendedFields = new java.util.ArrayList<>();
        if (request.payload() != null || typedForm) {
            Map<String, Object> previousValues = typedForm
                    ? ApprovalFormSchemaV2Canonical.freeze(current.payload()) : current.payload();
            Map<String, Object> resultingValues = typedForm
                    ? ApprovalFormSchemaV2Canonical.freeze(amendedPayload) : amendedPayload;
            Set<String> changedKeys = new HashSet<>();
            if (typedForm) {
                changedKeys.addAll(current.payload().keySet());
                changedKeys.addAll(amendedPayload.keySet());
            } else changedKeys.addAll(request.payload().keySet());
            for (String key : changedKeys) {
                if (!java.util.Objects.equals(
                        previousValues.get(key), resultingValues.get(key))) {
                    amendedFields.add(key);
                }
            }
        }
        amendedFields.sort(String::compareTo);
        var attachments = attachmentLifecycleBinding();
        var attachmentPin = attachments.prepare(requestId, request.expectedVersion(), ApprovalAttachmentLifecycleBinding.Intent.INFO_RESPONSE);
        boolean materialChange = !amendedFields.isEmpty() || attachmentPin.materialChange();
        String amendedPayloadJson = payloadSupport.json(amendedPayload);
        MapSqlParameterSource params = workActorParams(
                actor, current.managementResourceSetKey())
                .addValue("requestId", requestId)
                .addValue("expectedVersion", request.expectedVersion())
                .addValue("message", request.message().trim())
                .addValue("summaryChanged", amendedFields.contains("summary"))
                .addValue("requestSummary", amendedFields.contains("summary")
                        ? amendedPayload.get("summary") : null)
                .addValue("payload", amendedPayloadJson);
        int updated = jdbc.update(
                ApprovalCommandSql01.RESPOND_TO_INFORMATION_REQUEST_UPDATE_APR_REQUESTS,
                params);
        requireUpdated(updated);

        PayloadEvidence previousEvidence = new PayloadEvidence(
                current.payloadRevision(), current.payloadSha256());
        PayloadEvidence resultingEvidence = previousEvidence;
        UUID restartedTaskId = null;
        int supersededDecisions = 0;
        if (materialChange) {
            int payloadUpdated = jdbc.update(
                    ApprovalCommandSql01.RESPOND_TO_INFORMATION_REQUEST_UPDATE_APR_REQUEST_PAYLOADS,
                    params);
            requireUpdated(payloadUpdated);
            appendPayloadRevision(
                    actor,
                    requestId,
                    "INFORMATION_RESPONDED",
                    correlationId,
                    request.message().trim());
            resultingEvidence = currentPayloadEvidence(actor.tenantId(), requestId);
            supersededDecisions = jdbc.update(
                    ApprovalCommandInformationSql.SUPERSEDE_DECISIONS,
                    params);
            if (supersededDecisions == 0) {
                throw new BaseException(
                        ErrorCode.INVALID_STATE,
                        "No information-request decision exists to supersede.");
            }
            jdbc.update(ApprovalCommandInformationSql.CANCEL_OPEN_TASKS, params);
            restartedTaskId = restartAfterMaterialAmendment(
                    actor, requestId, current, correlationId);
        } else {
            int resumed = jdbc.update(
                    ApprovalCommandSql01.RESPOND_TO_INFORMATION_REQUEST_UPDATE_APR_TASKS,
                    params);
            if (resumed != 1) throw new BaseException(ErrorCode.INVALID_STATE);
        }

        attachments.seal(attachmentPin, attachments.target(requestId));
        Map<String, Object> eventEvidence = new LinkedHashMap<>();
        eventEvidence.put("responseLength", request.message().trim().length());
        eventEvidence.put("amendedFields", amendedFields);
        eventEvidence.put("materialChange", materialChange);
        eventEvidence.put("previousPayloadRevision", previousEvidence.revision());
        eventEvidence.put("previousPayloadSha256", previousEvidence.sha256());
        eventEvidence.put("payloadRevision", resultingEvidence.revision());
        eventEvidence.put("payloadSha256", resultingEvidence.sha256());
        eventEvidence.put("supersededDecisionCount", supersededDecisions);
        if (restartedTaskId != null) {
            eventEvidence.put("restartedTaskId", restartedTaskId.toString());
        }
        appendEvent(actor, requestId, "INFORMATION_RESPONDED",
                request.message().trim(), correlationId, eventEvidence);
        appendIntegration(actor, requestId, "approval.request.information.responded", correlationId,
                Map.of(
                        "requestId", requestId.toString(),
                        "materialChange", materialChange,
                        "payloadRevision", resultingEvidence.revision(),
                        "payloadSha256", resultingEvidence.sha256()));
    }

    private UUID restartAfterMaterialAmendment(
            ApprovalRequestContext.Actor actor,
            UUID requestId,
            InformationRuntime current,
            String correlationId) {
        int cumulativeMinutes = 0;
        for (int index = 0; index < current.steps().size(); index++) {
            ApprovalCommandRepository.RuntimeStep step = current.steps().get(index);
            cumulativeMinutes = Math.addExact(cumulativeMinutes, step.slaMinutes());
            int stepUpdated = jdbc.update(
                    ApprovalCommandInformationSql.RESTART_STEPS,
                    workActorParams(actor, current.managementResourceSetKey())
                            .addValue("requestId", requestId)
                            .addValue("sequenceNumber", index + 1)
                            .addValue("candidateRole", step.candidateRole())
                            .addValue("cumulativeMinutes", cumulativeMinutes));
            requireUpdated(stepUpdated);
        }
        NextStep firstStep = jdbc.query(
                ApprovalCommandInformationSql.RESTART_FIRST_STEP,
                new MapSqlParameterSource()
                        .addValue("tenantId", actor.tenantId())
                        .addValue("requestId", requestId),
                result -> result.next()
                        ? new NextStep(
                                result.getObject("step_id", UUID.class),
                                result.getString("step_key"),
                                result.getString("step_name"),
                                result.getString("candidate_role"),
                                result.getTimestamp("due_at").toInstant())
                        : null);
        if (firstStep == null) throw new BaseException(ErrorCode.INVALID_STATE);
        UUID taskId = UUID.randomUUID();
        int inserted = jdbc.update(
                ApprovalCommandSql01.ACTIVATE_NEXT_STEP_INSERT_APR_TASKS,
                workActorParams(actor, current.managementResourceSetKey())
                        .addValue("requestId", requestId)
                        .addValue("stepId", firstStep.stepId())
                        .addValue("taskId", taskId)
                        .addValue("candidateRole", firstStep.candidateRole())
                        .addValue("dueAt", Timestamp.from(firstStep.dueAt())));
        requireUpdated(inserted);
        appendEvent(actor, requestId, "APPROVAL_FLOW_RESTARTED",
                firstStep.stepName(), correlationId,
                Map.of(
                        "stepKey", firstStep.stepKey(),
                        "taskId", taskId.toString(),
                        "candidateRole", firstStep.candidateRole(),
                        "reason", "MATERIAL_INFORMATION_RESPONSE"));
        return taskId;
    }

    public void claim(
            ApprovalRequestContext.Actor actor,
            ApprovalQueryRepository.TaskAccess task,
            long expectedVersion,
            String correlationId) {
        new ApprovalRetentionLiveGuard(jdbc).writeRequest(actor.tenantId(), task.summary().requestId());
        if (task.assigneeUserId() != null && !task.assigneeUserId().equals(actor.userId())
                && !task.delegatedAccess()) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        if (task.candidateRole() != null && !actor.roles().contains(task.candidateRole())
                && task.assigneeUserId() == null && !task.delegatedAccess()) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        int updated = jdbc.update(ApprovalCommandSql01.CLAIM_UPDATE_APR_TASKS, workActorParams(
                actor, task.managementResourceSetKey())
                .addValue("personPublicId", actor.personPublicId())
                .addValue("delegatedFromUserId", task.delegatedFromUserId())
                .addValue("delegatedAuthorityRoleCode", task.delegatedAuthorityRoleCode())
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
        new ApprovalRetentionLiveGuard(jdbc).writeRequest(actor.tenantId(), task.summary().requestId());
        if (quorumWorkflow(actor.tenantId(), task.summary().requestId()) != null) throw new BaseException(
                ErrorCode.INVALID_STATE, "Tagged workflows require a versioned durable quorum vote.");
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

        PayloadEvidence payloadEvidence = currentPayloadEvidence(
                actor.tenantId(), task.summary().requestId());

        MapSqlParameterSource params = workActorParams(
                actor, task.managementResourceSetKey())
                .addValue("taskId", task.summary().taskId())
                .addValue("requestId", task.summary().requestId())
                .addValue("expectedVersion", decision.expectedVersion())
                .addValue("taskStatus", taskStatus)
                .addValue("requestStatus", requestStatus)
                .addValue("personPublicId", actor.personPublicId())
                .addValue("delegatedFromUserId", task.delegatedFromUserId())
                .addValue("delegatedAuthorityRoleCode", task.delegatedAuthorityRoleCode())
                .addValue("payloadRevision", payloadEvidence.revision())
                .addValue("payloadSha256", payloadEvidence.sha256())
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
                activateNextStep(
                        actor,
                        task.summary().requestId(),
                        task.managementResourceSetKey(),
                        nextStep,
                        correlationId);
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
        decisionEvidence.put("payloadRevision", payloadEvidence.revision());
        decisionEvidence.put("payloadSha256", payloadEvidence.sha256());
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
                        "decision", normalized,
                        "payloadRevision", payloadEvidence.revision(),
                        "payloadSha256", payloadEvidence.sha256()));
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
            String managementResourceSetKey,
            NextStep nextStep,
            String correlationId) {
        UUID taskId = UUID.randomUUID();
        MapSqlParameterSource params = workActorParams(actor, managementResourceSetKey)
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
