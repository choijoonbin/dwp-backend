package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalRequestContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Pinned HRIS draft writes; normalization ports keep repository dependencies one-way. */
final class ApprovalPinnedHrisDraftWriter {

    private final NamedParameterJdbcTemplate jdbc;
    private final Predicate<String> quorumDefinition;
    private final Predicate<String> typedForm;
    private final BiFunction<String, Map<String, Object>, Map<String, Object>> normalizePayload;
    private final Function<Map<String, Object>, String> encodePayload;

    ApprovalPinnedHrisDraftWriter(
            NamedParameterJdbcTemplate jdbc,
            Predicate<String> quorumDefinition,
            Predicate<String> typedForm,
            BiFunction<String, Map<String, Object>, Map<String, Object>> normalizePayload,
            Function<Map<String, Object>, String> encodePayload) {
        this.jdbc = jdbc;
        this.quorumDefinition = quorumDefinition;
        this.typedForm = typedForm;
        this.normalizePayload = normalizePayload;
        this.encodePayload = encodePayload;
    }

    void insert(
            ApprovalRequestContext.Actor actor,
            UUID requestId,
            UUID workflowVersionId,
            UUID formVersionId,
            String title,
            String summary,
            String normalizedPriority,
            Map<String, Object> nativePayload,
            String correlationId) {
        var rows = jdbc.queryForList("""
                SELECT workflow_version.definition::text AS workflow_definition,
                       workflow.data_classification,
                       workflow.management_resource_set_key,
                       form_version.schema_payload::text AS form_schema
                  FROM apr_tenants tenant
                  JOIN apr_workflow_versions workflow_version
                    ON workflow_version.tenant_id=tenant.tenant_id
                   AND workflow_version.workflow_version_id=:workflowVersionId
                   AND workflow_version.lifecycle_state='PUBLISHED'
                   AND (workflow_version.effective_from IS NULL OR workflow_version.effective_from<=CURRENT_TIMESTAMP)
                   AND (workflow_version.effective_to IS NULL OR workflow_version.effective_to>CURRENT_TIMESTAMP)
                  JOIN apr_workflow_definitions workflow
                    ON workflow.tenant_id=workflow_version.tenant_id
                   AND workflow.workflow_id=workflow_version.workflow_id
                   AND workflow.lifecycle_state='PUBLISHED'
                  JOIN apr_form_versions form_version
                    ON form_version.tenant_id=tenant.tenant_id
                   AND form_version.form_version_id=:formVersionId
                   AND form_version.lifecycle_state='PUBLISHED'
                  JOIN apr_forms form
                    ON form.tenant_id=form_version.tenant_id AND form.form_id=form_version.form_id
                   AND form.lifecycle_state='PUBLISHED'
                   AND form.management_resource_set_key=workflow.management_resource_set_key
                  JOIN apr_form_categories category
                    ON category.tenant_id=form.tenant_id AND category.category_id=form.category_id
                   AND category.lifecycle_state='ACTIVE'
                   AND category.management_resource_set_key=form.management_resource_set_key
                  JOIN apr_form_workflow_bindings binding
                    ON binding.tenant_id=tenant.tenant_id AND binding.form_id=form.form_id
                   AND binding.workflow_id=workflow.workflow_id AND binding.lifecycle_state='ACTIVE'
                   AND (binding.effective_from IS NULL OR binding.effective_from<=CURRENT_TIMESTAMP)
                   AND (binding.effective_to IS NULL OR binding.effective_to>CURRENT_TIMESTAMP)
                 WHERE tenant.tenant_id=:tenantId AND tenant.lifecycle_state='ACTIVE'
                """, new MapSqlParameterSource()
                .addValue("tenantId", actor.tenantId())
                .addValue("workflowVersionId", workflowVersionId)
                .addValue("formVersionId", formVersionId));
        if (rows.size() != 1) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The pinned HRIS workflow and form binding is no longer available.");
        }
        var runtime = rows.getFirst();
        if (!quorumDefinition.test((String) runtime.get("workflow_definition"))) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The pinned HRIS workflow is not a typed quorum workflow.");
        }
        String formSchema = (String) runtime.get("form_schema");
        if (typedForm.test(formSchema)) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The HRIS bridge requires its closed native payload form binding.");
        }
        Map<String, Object> payload = normalizePayload.apply(
                formSchema, new LinkedHashMap<>(nativePayload));
        if (!new LinkedHashMap<>(nativePayload).equals(payload)) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The HRIS native payload does not exactly match its published form.");
        }
        String payloadJson = encodePayload.apply(payload);
        String requestNumber = "APR-" + Instant.now().toEpochMilli() + "-"
                + requestId.toString().substring(0, 6).toUpperCase(Locale.ROOT);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("requestId", requestId)
                .addValue("tenantId", actor.tenantId())
                .addValue("requestNumber", requestNumber)
                .addValue("workflowVersionId", workflowVersionId)
                .addValue("formVersionId", formVersionId)
                .addValue("title", title)
                .addValue("summary", summary)
                .addValue("userId", actor.userId())
                .addValue("personPublicId", actor.personPublicId())
                .addValue("requesterName", actor.displayName())
                .addValue("priority", normalizedPriority)
                .addValue("classification", runtime.get("data_classification"))
                .addValue("managementScope", runtime.get("management_resource_set_key"))
                .addValue("payload", payloadJson)
                .addValue("correlationId", correlationId);
        jdbc.update(ApprovalCommandSql01.CREATE_DRAFT_INSERT_APR_REQUESTS, params);
        jdbc.update(ApprovalCommandSql01.APR_REQUESTS_INSERT_APR_REQUEST_PAYLOADS, params);
    }
}
