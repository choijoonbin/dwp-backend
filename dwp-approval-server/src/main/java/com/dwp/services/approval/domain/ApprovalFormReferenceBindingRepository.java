package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory.FormBinding;
import com.dwp.services.approval.security.ApprovalRequestContext.Actor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Existing requests keep their exact immutable published version even after the form advances. */
@Repository
public class ApprovalFormReferenceBindingRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public ApprovalFormReferenceBindingRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public PinnedForm requirePinned(Actor actor, UUID requestId, long expectedVersion, UUID expectedFormId, boolean readOnly) {
        return requirePinned(actor, requestId, expectedVersion, expectedFormId, readOnly, false);
    }

    private PinnedForm requirePinned(Actor actor, UUID requestId, long expectedVersion, UUID expectedFormId,
            boolean readOnly, boolean candidate) {
        var values = jdbc.query("""
                SELECT request.version, request.data_classification, request.workflow_version_id,
                       form.form_id, version.form_version_id, version.schema_payload::text, version.schema_sha256
                  FROM apr_requests request
                  JOIN apr_form_versions version ON version.tenant_id=request.tenant_id AND version.form_version_id=request.form_version_id
                  JOIN apr_forms form ON form.tenant_id=version.tenant_id AND form.form_id=version.form_id
                  JOIN apr_form_categories category ON category.tenant_id=form.tenant_id AND category.category_id=form.category_id
                  JOIN apr_workflow_versions workflow_version ON workflow_version.tenant_id=request.tenant_id
                   AND workflow_version.workflow_version_id=request.workflow_version_id
                  JOIN apr_workflow_definitions workflow ON workflow.tenant_id=workflow_version.tenant_id
                   AND workflow.workflow_id=workflow_version.workflow_id
                  JOIN apr_form_workflow_bindings binding ON binding.tenant_id=request.tenant_id
                   AND binding.form_id=form.form_id AND binding.workflow_id=workflow.workflow_id
                 WHERE request.tenant_id=:tenantId AND request.request_id=:requestId AND request.requester_user_id=:userId
                   AND request.deleted_at IS NULL AND request.version=:expectedVersion
                   AND version.lifecycle_state='PUBLISHED' AND form.lifecycle_state IN ('DRAFT','PUBLISHED') AND category.lifecycle_state='ACTIVE'
                   AND workflow.lifecycle_state='PUBLISHED' AND workflow_version.lifecycle_state='PUBLISHED'
                   AND category.management_resource_set_key=request.management_resource_set_key
                   AND form.management_resource_set_key=request.management_resource_set_key
                   AND workflow.management_resource_set_key=request.management_resource_set_key
                   AND binding.lifecycle_state='ACTIVE'
                   AND (binding.effective_from IS NULL OR binding.effective_from<=clock_timestamp())
                   AND (binding.effective_to IS NULL OR binding.effective_to>clock_timestamp())
                   AND (workflow_version.effective_from IS NULL OR workflow_version.effective_from<=clock_timestamp())
                   AND (workflow_version.effective_to IS NULL OR workflow_version.effective_to>clock_timestamp())
                """ + (candidate ? " AND request.status IN ('DRAFT','NEEDS_INFO')" : "")
                        + (readOnly ? "" : " FOR UPDATE OF request FOR SHARE OF version,form,category,workflow_version,workflow,binding"),
                new MapSqlParameterSource().addValue("tenantId", actor.tenantId()).addValue("userId", actor.userId())
                        .addValue("requestId", requestId).addValue("expectedVersion", expectedVersion), (row, index) -> {
                    UUID formId = row.getObject("form_id", UUID.class);
                    if (expectedFormId != null && !expectedFormId.equals(formId)) throw conflict();
                    var definition = parse(row.getString("schema_payload"));
                    ApprovalFormSchemaV2 compiled;
                    try { compiled = new ApprovalFormSchemaV2Compiler().compile(definition); }
                    catch (BaseException exception) { throw unavailable(); }
                    String hash = row.getString("schema_sha256");
                    if (!compiled.sha256().equals(hash)) throw unavailable();
                    return new PinnedForm(new FormBinding(actor.tenantId(), actor.userId(), formId,
                            row.getObject("form_version_id", UUID.class), hash), compiled,
                            row.getString("data_classification"), row.getObject("workflow_version_id", UUID.class), row.getLong("version"));
                });
        if (values.size() != 1) throw conflict();
        return values.getFirst();
    }

    private Map<String, Object> parse(String raw) {
        try { return mapper.readValue(raw, new TypeReference<>() { }); }
        catch (Exception exception) { throw unavailable(); }
    }

    public FormBinding requireNewBinding(Actor actor, UUID formVersionId, String schemaSha256) {
        var values = jdbc.query("""
                SELECT form.form_id FROM apr_forms form JOIN apr_form_versions version
                  ON version.tenant_id=form.tenant_id AND version.form_id=form.form_id AND version.version_number=form.current_version
                 WHERE form.tenant_id=:tenantId AND version.form_version_id=:versionId
                   AND version.schema_sha256=:schemaSha256 AND form.lifecycle_state='PUBLISHED' AND version.lifecycle_state='PUBLISHED'
                 FOR SHARE OF form,version
                """, new MapSqlParameterSource().addValue("tenantId", actor.tenantId()).addValue("versionId", formVersionId)
                        .addValue("schemaSha256", schemaSha256), (row, index) -> new FormBinding(actor.tenantId(), actor.userId(),
                        row.getObject("form_id", UUID.class), formVersionId, schemaSha256));
        if (values.size() != 1) throw conflict();
        return values.getFirst();
    }

    public PinnedForm requireCandidatePinned(Actor actor, UUID requestId, UUID formId, UUID formVersionId, String schemaSha256) {
        var versions = jdbc.query("""
                SELECT version FROM apr_requests WHERE tenant_id=:tenantId AND request_id=:requestId
                   AND requester_user_id=:userId AND deleted_at IS NULL AND status IN ('DRAFT','NEEDS_INFO')
                """, new MapSqlParameterSource().addValue("tenantId", actor.tenantId()).addValue("requestId", requestId)
                        .addValue("userId", actor.userId()), (row, number) -> row.getLong("version"));
        if (versions.size() != 1 || versions.getFirst() < 0 || versions.getFirst() > 9007199254740991L) throw conflict();
        var pin = requirePinned(actor, requestId, versions.getFirst(), formId, true, true);
        if (!formVersionId.equals(pin.binding().formVersionId()) || !schemaSha256.equals(pin.binding().schemaSha256())) throw conflict();
        return pin;
    }
    private BaseException conflict() { return new BaseException(ErrorCode.RESOURCE_CONFLICT, "The owned immutable published form binding changed."); }
    private BaseException unavailable() { return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "The pinned approval form cannot be verified."); }
    public record PinnedForm(FormBinding binding, ApprovalFormSchemaV2 schema, String dataClassification, UUID workflowVersionId, long requestVersion) { }
}
