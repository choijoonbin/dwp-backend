package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Repository
public class ApprovalCommandRepository extends ApprovalCommandManagementRepository {
    public ApprovalCommandRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        super(jdbc, objectMapper);
    }

    record RuntimeStep(
            String key,
            String name,
            String mode,
            String candidateRole,
            int slaMinutes) {
    }

    public UUID createResubmitDraft(
            ApprovalRequestContext.Actor actor,
            ApprovalDraftRepository.ResubmitSource source,
            String correlationId) {
        UUID requestId = UUID.randomUUID();
        String requestNumber = "APR-" + Instant.now().toEpochMilli() + "-"
                + requestId.toString().substring(0, 6).toUpperCase(Locale.ROOT);
        String priority = normalizedPriority(source.priority());
        Map<String, Object> payload = requestPayload(source.summary(), source.payload());
        WorkflowRuntime workflow = currentResubmitWorkflow(actor, source, payload);
        boolean typed = requireCurrentResubmitSchema(workflow.formSchema());
        Map<String, Object> normalized = typed
                ? formNormalization.create(actor, requestId, workflow.formVersionId(), workflow.formSchema(), payload)
                : normalizeRequestPayload(workflow.formSchema(), payload, true);
        if (typed) normalized = formNormalization.pure(workflow.formSchema(), normalized, true);
        if (!ApprovalFormSchemaV2Canonical.freeze(payload)
                .equals(ApprovalFormSchemaV2Canonical.freeze(normalized))) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The source payload is not an exact valid value for the current published form schema.");
        }
        String payloadJson = payloadSupport.json(normalized);
        var params = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("requestId", requestId)
                .addValue("sourceRequestId", source.requestId().toString())
                .addValue("tenantId", actor.tenantId())
                .addValue("requestNumber", requestNumber)
                .addValue("workflowVersionId", workflow.workflowVersionId())
                .addValue("formVersionId", workflow.formVersionId())
                .addValue("title", source.title().trim())
                .addValue("summary", source.summary().trim())
                .addValue("userId", actor.userId())
                .addValue("personPublicId", actor.personPublicId())
                .addValue("requesterName", actor.displayName())
                .addValue("priority", priority)
                .addValue("classification", workflow.dataClassification())
                .addValue("managementScope", workflow.managementResourceSetKey())
                .addValue("payload", payloadJson);
        jdbc.update(ApprovalResubmitDraftSql.INSERT_DRAFT, params);
        jdbc.update(ApprovalCommandSql01.APR_REQUESTS_INSERT_APR_REQUEST_PAYLOADS, params);
        appendPayloadRevision(actor, requestId, "DRAFT_CREATED", correlationId, "Resubmission draft created");
        attachmentLifecycleBinding().initializeCreated(requestId, 0);
        appendEvent(actor, requestId, "REQUEST_RESUBMIT_DRAFTED", "Resubmission draft created", correlationId,
                Map.of("sourceRequestId", source.requestId().toString(),
                        "sourceVersion", source.version(), "sourceStatus", source.status()));
        return requestId;
    }

    private WorkflowRuntime currentResubmitWorkflow(
            ApprovalRequestContext.Actor actor,
            ApprovalDraftRepository.ResubmitSource source,
            Map<String, Object> payload) {
        try {
            return workflow(actor.tenantId(), source.workflowId(), source.formId(), payload, true);
        } catch (BaseException exception) {
            if (exception.getErrorCode() == ErrorCode.INVALID_STATE) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "The source payload no longer matches the current published workflow route.",
                        exception);
            }
            if (exception.getErrorCode() == ErrorCode.INVALID_INPUT_VALUE) {
                throw new BaseException(
                        ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                        "The current published workflow route cannot be verified.",
                        exception);
            }
            throw exception;
        }
    }

    private boolean requireCurrentResubmitSchema(String schema) {
        try {
            payloadSupport.validateStoredFormSchema(schema);
            boolean typed = payloadSupport.isTypedFormSchema(schema);
            if (!typed) validateRequestPayload(schema, Map.of(), false);
            return typed;
        } catch (BaseException exception) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "The current published form schema cannot be verified.",
                    exception);
        }
    }


    public record DecisionResult(String decision, String requestStatus) {
    }
}
