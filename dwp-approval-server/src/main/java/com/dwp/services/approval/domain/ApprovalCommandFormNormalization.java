package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalRequestContext.Actor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Keeps immutable form ownership checks separate from the command's workflow and write logic. */
final class ApprovalCommandFormNormalization {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ApprovalFormReferenceBindingRepository bindings;
    private ApprovalFormReferenceNormalizer normalizer;

    ApprovalCommandFormNormalization(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        bindings = new ApprovalFormReferenceBindingRepository(jdbc, mapper);
        // Standalone repositories can normalize financial forms, but cannot resolve any USER source.
        normalizer = new ApprovalFormReferenceNormalizer(bindings, new ApprovalFormUserBindingRepository(jdbc, mapper),
                null, null, null, mapper);
    }

    void bind(ApprovalFormReferenceNormalizer value) { normalizer = Objects.requireNonNull(value); }

    Map<String, Object> pure(String schema, Map<String, Object> payload, boolean submitting) {
        return new ApprovalFormSchemaV2Evaluator().evaluate(new ApprovalFormSchemaV2Compiler().compile(
                definition(schema)), payload, submitting).payload();
    }

    Map<String, Object> create(Actor actor, UUID requestId, UUID formVersionId, String schema, Map<String, Object> payload) {
        return normalizer.normalize(actor, requestId, formVersionId, schema, payload, false, 0, true, false);
    }

    Map<String, Object> existing(Actor actor, UUID requestId, String schema, Map<String, Object> payload,
            boolean submitting, long expectedVersion, boolean readOnly) {
        var pin = bindings.requirePinned(actor, requestId, expectedVersion, null, readOnly);
        return normalizer.normalize(actor, requestId, pin.binding().formVersionId(), schema, payload,
                submitting, expectedVersion, false, readOnly);
    }

    PinnedDraft pinTypedDraft(Actor actor, UUID requestId, long expectedVersion, UUID requestedFormId) {
        var values = jdbc.query("""
                SELECT version.schema_payload::text, workflow.workflow_id, request.management_resource_set_key
                  FROM apr_requests request JOIN apr_form_versions version ON version.tenant_id=request.tenant_id
                   AND version.form_version_id=request.form_version_id
                  JOIN apr_workflow_versions workflow ON workflow.tenant_id=request.tenant_id
                   AND workflow.workflow_version_id=request.workflow_version_id
                 WHERE request.tenant_id=:tenantId AND request.request_id=:requestId AND request.requester_user_id=:userId
                   AND request.deleted_at IS NULL AND request.status='DRAFT' AND request.version=:expectedVersion
                 FOR UPDATE OF request FOR SHARE OF version,workflow
                """, new MapSqlParameterSource().addValue("tenantId", actor.tenantId()).addValue("userId", actor.userId())
                        .addValue("requestId", requestId).addValue("expectedVersion", expectedVersion), (row, number) -> {
                    var definition = definition(row.getString(1));
                    if (!definition.containsKey("schemaContract")) return null;
                    if (!ApprovalFormSchemaV2.CONTRACT.equals(definition.get("schemaContract"))) throw new BaseException(
                            ErrorCode.INVALID_STATE, "Unknown approval form schema contract.");
                    var pin = bindings.requirePinned(actor, requestId, expectedVersion, requestedFormId, false);
                    return new PinnedDraft(pin, row.getObject("workflow_id", UUID.class), row.getString("management_resource_set_key"));
                });
        if (values.size() != 1) throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "The owned draft snapshot changed.");
        return values.getFirst();
    }

    record PinnedDraft(ApprovalFormReferenceBindingRepository.PinnedForm form, UUID workflowId, String managementResourceSetKey) { }

    private Map<String, Object> definition(String schema) {
        try { return mapper.readValue(schema, new TypeReference<>() { }); }
        catch (Exception exception) { throw new BaseException(ErrorCode.INVALID_STATE, "Stored approval form schema is invalid."); }
    }
}
