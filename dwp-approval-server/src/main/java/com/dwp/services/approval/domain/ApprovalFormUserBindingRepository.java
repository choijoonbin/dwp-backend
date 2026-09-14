package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory.FormBinding;
import com.dwp.services.approval.security.ApprovalManagementScopeContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Types;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads one current published form version with the same effective route prerequisites as the work catalog. */
@Repository
public class ApprovalFormUserBindingRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ApprovalFormUserBindingRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public ApprovalFormSchemaV2 requirePublished(FormBinding form, boolean admin) {
        String managementScope = admin ? ApprovalManagementScopeContext.requireResourceSetKey() : null;
        var matches = jdbc.query("""
                SELECT version.schema_payload::text, version.schema_sha256
                  FROM apr_forms form
                  JOIN apr_form_versions version ON version.tenant_id = form.tenant_id
                   AND version.form_id = form.form_id AND version.version_number = form.current_version
                  JOIN apr_form_categories category ON category.tenant_id = form.tenant_id
                   AND category.category_id = form.category_id
                   AND category.management_resource_set_key = form.management_resource_set_key
                 WHERE form.tenant_id = :tenantId AND form.form_id = :formId
                   AND version.form_version_id = :formVersionId
                   AND form.lifecycle_state = 'PUBLISHED' AND version.lifecycle_state = 'PUBLISHED'
                   AND category.lifecycle_state = 'ACTIVE'
                   AND (:admin = FALSE OR form.management_resource_set_key = :managementScope)
                   AND EXISTS (
                       SELECT 1 FROM apr_form_workflow_bindings binding
                       JOIN apr_workflow_definitions workflow ON workflow.tenant_id = binding.tenant_id
                        AND workflow.workflow_id = binding.workflow_id
                       JOIN apr_workflow_versions workflow_version ON workflow_version.tenant_id = workflow.tenant_id
                        AND workflow_version.workflow_id = workflow.workflow_id
                        AND workflow_version.version_number = workflow.current_version
                       WHERE binding.tenant_id = form.tenant_id AND binding.form_id = form.form_id
                         AND binding.lifecycle_state = 'ACTIVE' AND workflow.lifecycle_state = 'PUBLISHED'
                         AND workflow_version.lifecycle_state = 'PUBLISHED'
                         AND workflow.management_resource_set_key = form.management_resource_set_key
                         AND (binding.effective_from IS NULL OR binding.effective_from <= clock_timestamp())
                         AND (binding.effective_to IS NULL OR binding.effective_to > clock_timestamp())
                         AND (workflow_version.effective_from IS NULL OR workflow_version.effective_from <= clock_timestamp())
                         AND (workflow_version.effective_to IS NULL OR workflow_version.effective_to > clock_timestamp()))
                """, new MapSqlParameterSource().addValue("tenantId", form.tenantId()).addValue("formId", form.formId())
                .addValue("formVersionId", form.formVersionId()).addValue("admin", admin)
                .addValue("managementScope", managementScope, Types.VARCHAR),
                (row, number) -> new Stored(row.getString("schema_payload"), row.getString("schema_sha256")));
        if (matches.size() != 1 || !form.schemaSha256().equals(matches.getFirst().sha256())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT, "The current published form snapshot changed.");
        }
        try {
            Map<String, Object> definition = mapper.readValue(matches.getFirst().json(), new TypeReference<>() { });
            var compiled = new ApprovalFormSchemaV2Compiler().compile(definition);
            var summary = compiled.scope.fields().get("summary");
            if (!compiled.sha256().equals(form.schemaSha256()) || summary == null
                    || !java.util.Set.of("TEXT", "TEXTAREA").contains(summary.type()) || summary.visibleWhen() != null) {
                throw unavailable();
            }
            return compiled;
        } catch (Exception exception) {
            throw unavailable();
        }
    }

    private BaseException unavailable() {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                "The published typed form schema cannot be verified.");
    }

    private record Stored(String json, String sha256) { }
}
