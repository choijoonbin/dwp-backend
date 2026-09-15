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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

@Repository
public class ApprovalDraftMigrationRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ApprovalDraftMigrationRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Source source(ApprovalRequestContext.Actor actor, UUID requestId, Long expectedVersion, boolean lock) {
        var rows = jdbc.query("""
                SELECT request.request_id,request.version,request.title,request.summary,request.priority,
                       request.management_resource_set_key,payload.payload::text,
                       form.form_id,form.name_ko AS form_name_ko,form.name_en AS form_name_en,
                       form.lifecycle_state AS form_state,
                       pinned_form.form_version_id,pinned_form.version_number AS form_version,
                       pinned_form.schema_payload::text AS form_schema,
                       pinned_form.schema_sha256 AS form_schema_sha256,
                       pinned_form.lifecycle_state AS form_version_state,
                       current_form.form_version_id AS current_form_version_id,
                       workflow.workflow_id,workflow.name_ko AS workflow_name_ko,
                       workflow.name_en AS workflow_name_en,
                       workflow.lifecycle_state AS workflow_state,
                       pinned_workflow.workflow_version_id,
                       pinned_workflow.version_number AS workflow_version,
                       pinned_workflow.definition_sha256 AS workflow_definition_sha256,
                       pinned_workflow.lifecycle_state AS workflow_version_state,
                       current_workflow.workflow_version_id AS current_workflow_version_id
                  FROM apr_requests request
                  JOIN apr_tenants tenant ON tenant.tenant_id=request.tenant_id
                  JOIN apr_request_payloads payload ON payload.tenant_id=request.tenant_id
                   AND payload.request_id=request.request_id
                  JOIN apr_form_versions pinned_form ON pinned_form.tenant_id=request.tenant_id
                   AND pinned_form.form_version_id=request.form_version_id
                  JOIN apr_forms form ON form.tenant_id=pinned_form.tenant_id
                   AND form.form_id=pinned_form.form_id
                  JOIN apr_form_versions current_form ON current_form.tenant_id=form.tenant_id
                   AND current_form.form_id=form.form_id AND current_form.version_number=form.current_version
                  JOIN apr_workflow_versions pinned_workflow ON pinned_workflow.tenant_id=request.tenant_id
                   AND pinned_workflow.workflow_version_id=request.workflow_version_id
                  JOIN apr_workflow_definitions workflow ON workflow.tenant_id=pinned_workflow.tenant_id
                   AND workflow.workflow_id=pinned_workflow.workflow_id
                  JOIN apr_workflow_versions current_workflow ON current_workflow.tenant_id=workflow.tenant_id
                   AND current_workflow.workflow_id=workflow.workflow_id
                   AND current_workflow.version_number=workflow.current_version
                 WHERE request.tenant_id=:tenantId AND request.request_id=:requestId
                   AND request.requester_user_id=:userId AND request.status='DRAFT'
                   AND request.deleted_at IS NULL AND tenant.lifecycle_state='ACTIVE'
                   AND (CAST(:expectedVersion AS BIGINT) IS NULL OR request.version=:expectedVersion)
                """ + (lock
                ? " FOR UPDATE OF request FOR SHARE OF tenant,payload,pinned_form,form,current_form,pinned_workflow,workflow,current_workflow"
                : ""), params(actor, requestId).addValue("expectedVersion", expectedVersion), this::source);
        if (rows.size() != 1) throw unavailable("The owned source draft is unavailable or changed.");
        return rows.getFirst();
    }

    public Target target(ApprovalRequestContext.Actor actor, String resourceSet, UUID formId,
                         UUID workflowId, boolean lock) {
        var rows = jdbc.query("""
                SELECT form.form_id,form.name_ko AS form_name_ko,form.name_en AS form_name_en,
                       form_version.form_version_id,form_version.version_number AS form_version,
                       form_version.schema_payload::text AS form_schema,
                       form_version.schema_sha256 AS form_schema_sha256,
                       workflow.workflow_id,workflow.name_ko AS workflow_name_ko,
                       workflow.name_en AS workflow_name_en,
                       workflow_version.workflow_version_id,
                       workflow_version.version_number AS workflow_version,
                       workflow_version.definition_sha256 AS workflow_definition_sha256,
                       binding.binding_type,binding.condition_payload::text AS binding_condition
                  FROM apr_forms form
                  JOIN apr_form_versions form_version ON form_version.tenant_id=form.tenant_id
                   AND form_version.form_id=form.form_id AND form_version.version_number=form.current_version
                  JOIN apr_form_categories category ON category.tenant_id=form.tenant_id
                   AND category.category_id=form.category_id
                  JOIN apr_form_workflow_bindings binding ON binding.tenant_id=form.tenant_id
                   AND binding.form_id=form.form_id AND binding.workflow_id=:workflowId
                  JOIN apr_workflow_definitions workflow ON workflow.tenant_id=binding.tenant_id
                   AND workflow.workflow_id=binding.workflow_id
                  JOIN apr_workflow_versions workflow_version ON workflow_version.tenant_id=workflow.tenant_id
                   AND workflow_version.workflow_id=workflow.workflow_id
                   AND workflow_version.version_number=workflow.current_version
                 WHERE form.tenant_id=:tenantId AND form.form_id=:formId
                   AND form.management_resource_set_key=:resourceSet
                   AND workflow.management_resource_set_key=:resourceSet
                   AND category.management_resource_set_key=:resourceSet
                   AND form.lifecycle_state='PUBLISHED'
                   AND form_version.lifecycle_state='PUBLISHED'
                   AND category.lifecycle_state='ACTIVE'
                   AND binding.lifecycle_state='ACTIVE'
                   AND workflow.lifecycle_state='PUBLISHED'
                   AND workflow_version.lifecycle_state='PUBLISHED'
                   AND (binding.effective_from IS NULL OR binding.effective_from<=CURRENT_TIMESTAMP)
                   AND (binding.effective_to IS NULL OR binding.effective_to>CURRENT_TIMESTAMP)
                   AND (workflow_version.effective_from IS NULL OR workflow_version.effective_from<=CURRENT_TIMESTAMP)
                   AND (workflow_version.effective_to IS NULL OR workflow_version.effective_to>CURRENT_TIMESTAMP)
                """ + (lock ? " FOR SHARE OF form,form_version,category,binding,workflow,workflow_version" : ""),
                params(actor, null).addValue("resourceSet", resourceSet).addValue("formId", formId)
                        .addValue("workflowId", workflowId), this::target);
        if (rows.size() != 1) throw unavailable("The current published migration target is unavailable.");
        return rows.getFirst();
    }

    private Source source(ResultSet result, int row) throws SQLException {
        return new Source(
                result.getObject("request_id", UUID.class), result.getLong("version"),
                result.getString("title"), result.getString("summary"), result.getString("priority"),
                result.getString("management_resource_set_key"), object(result.getString("payload")),
                new ApprovalDraftMigrationDtos.Binding(
                        result.getObject("form_id", UUID.class),
                        result.getObject("form_version_id", UUID.class),
                        result.getInt("form_version"), result.getString("form_schema_sha256").strip(),
                        result.getString("form_name_ko"), result.getString("form_name_en"),
                        result.getObject("workflow_id", UUID.class),
                        result.getObject("workflow_version_id", UUID.class),
                        result.getInt("workflow_version"),
                        result.getString("workflow_definition_sha256").strip(),
                        result.getString("workflow_name_ko"), result.getString("workflow_name_en")),
                result.getString("form_schema"), result.getString("form_state"),
                result.getString("form_version_state"), result.getString("workflow_state"),
                result.getString("workflow_version_state"),
                result.getObject("current_form_version_id", UUID.class),
                result.getObject("current_workflow_version_id", UUID.class));
    }

    private Target target(ResultSet result, int row) throws SQLException {
        return new Target(new ApprovalDraftMigrationDtos.Binding(
                result.getObject("form_id", UUID.class),
                result.getObject("form_version_id", UUID.class),
                result.getInt("form_version"), result.getString("form_schema_sha256").strip(),
                result.getString("form_name_ko"), result.getString("form_name_en"),
                result.getObject("workflow_id", UUID.class),
                result.getObject("workflow_version_id", UUID.class),
                result.getInt("workflow_version"),
                result.getString("workflow_definition_sha256").strip(),
                result.getString("workflow_name_ko"), result.getString("workflow_name_en")),
                result.getString("form_schema"), result.getString("binding_type"),
                result.getString("binding_condition"));
    }

    private Map<String, Object> object(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw unavailable("Stored draft payload is unavailable.");
        }
    }

    private MapSqlParameterSource params(ApprovalRequestContext.Actor actor, UUID requestId) {
        return new MapSqlParameterSource("tenantId", actor.tenantId())
                .addValue("userId", actor.userId()).addValue("requestId", requestId);
    }

    private BaseException unavailable(String message) {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }

    public record Source(
            UUID requestId,
            long version,
            String title,
            String summary,
            String priority,
            String resourceSet,
            Map<String, Object> payload,
            ApprovalDraftMigrationDtos.Binding binding,
            String formSchema,
            String formState,
            String formVersionState,
            String workflowState,
            String workflowVersionState,
            UUID currentFormVersionId,
            UUID currentWorkflowVersionId) {
        public boolean requiresMigration() {
            return !"PUBLISHED".equals(formState) || !"PUBLISHED".equals(formVersionState)
                    || !"PUBLISHED".equals(workflowState) || !"PUBLISHED".equals(workflowVersionState)
                    || !binding.formVersionId().equals(currentFormVersionId)
                    || !binding.workflowVersionId().equals(currentWorkflowVersionId);
        }
    }

    public record Target(
            ApprovalDraftMigrationDtos.Binding binding,
            String formSchema,
            String bindingType,
            String bindingCondition) {
    }
}
