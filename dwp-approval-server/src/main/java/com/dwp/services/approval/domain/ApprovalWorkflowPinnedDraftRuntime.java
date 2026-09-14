package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalDecisionRevisionContext;
import com.dwp.services.approval.security.ApprovalManagementScopeContext;
import com.dwp.services.approval.security.ApprovalRequestContext.Actor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Revalidates the request's immutable workflow, never the definition's current version. */
final class ApprovalWorkflowPinnedDraftRuntime {
    private ApprovalWorkflowPinnedDraftRuntime() { }

    record VerifiedRuntime(UUID workflowVersionId, UUID formVersionId, String dataClassification,
            String managementResourceSetKey, String formSchema) { }

    static <T> T require(NamedParameterJdbcTemplate jdbc, Actor actor,
            UUID requestId, long expectedVersion, UUID requestedWorkflowId,
            ApprovalCommandFormNormalization.PinnedDraft sealed,
            java.util.function.Function<VerifiedRuntime, T> assembler,
            java.util.function.BiConsumer<String, Integer> legacyValidator) {
        if (sealed == null || sealed.form() == null || requestedWorkflowId == null
                || !requestedWorkflowId.equals(sealed.workflowId())) throw conflict();
        var form = sealed.form();
        if (form.binding().tenantId() != actor.tenantId() || form.binding().actorId() != actor.userId()
                || form.requestVersion() != expectedVersion || !form.schema().sha256().equals(form.binding().schemaSha256())) throw conflict();
        ApprovalDecisionRevisionContext.current().ifPresent(revision -> {
            if (!java.util.Set.of("route.approvals.work.request-draft-update.action", "route.approvals.work.request-draft-recover.action")
                    .contains(revision.routeContractKey())) {
                throw new BaseException(ErrorCode.FORBIDDEN, "The draft route is not canonical.");
            }
            var scope = ApprovalManagementScopeContext.current().orElseThrow(ApprovalWorkflowPinnedDraftRuntime::unavailable);
            if (revision.validUntil() == null || !revision.validUntil().toInstant().isAfter(java.time.Instant.now())
                    || !scope.resourceSetKey().equals(sealed.managementResourceSetKey())) throw unavailable();
        });
        ApprovalManagementScopeContext.current().ifPresent(scope -> {
            if (!scope.resourceSetKey().equals(sealed.managementResourceSetKey())) throw unavailable();
        });
        var rows = jdbc.query("""
                SELECT request.workflow_version_id, request.form_version_id, request.data_classification,
                       request.management_resource_set_key, form_version.schema_payload::text AS form_schema,
                       form_version.schema_sha256, workflow_version.definition::text AS workflow_definition,
                       workflow_version.definition_sha256, workflow.sla_minutes
                  FROM apr_requests request
                  JOIN apr_tenants tenant ON tenant.tenant_id=request.tenant_id AND tenant.lifecycle_state='ACTIVE'
                  JOIN apr_workflow_versions workflow_version ON workflow_version.tenant_id=request.tenant_id
                   AND workflow_version.workflow_version_id=request.workflow_version_id
                  JOIN apr_workflow_definitions workflow ON workflow.tenant_id=workflow_version.tenant_id
                   AND workflow.workflow_id=workflow_version.workflow_id
                  JOIN apr_form_versions form_version ON form_version.tenant_id=request.tenant_id
                   AND form_version.form_version_id=request.form_version_id
                  JOIN apr_forms form ON form.tenant_id=form_version.tenant_id AND form.form_id=form_version.form_id
                  JOIN apr_form_categories category ON category.tenant_id=form.tenant_id AND category.category_id=form.category_id
                  JOIN apr_form_workflow_bindings binding ON binding.tenant_id=request.tenant_id
                   AND binding.form_id=form.form_id AND binding.workflow_id=workflow.workflow_id
                 WHERE request.tenant_id=:tenantId AND request.request_id=:requestId AND request.requester_user_id=:userId
                   AND request.requester_person_public_id IS NOT DISTINCT FROM CAST(:personId AS uuid)
                   AND request.status='DRAFT' AND request.deleted_at IS NULL
                   AND request.version=:expectedVersion AND workflow.workflow_id=:workflowId AND form.form_id=:formId
                   AND workflow.lifecycle_state='PUBLISHED' AND workflow_version.lifecycle_state='PUBLISHED'
                   AND (workflow_version.effective_from IS NULL OR workflow_version.effective_from<=clock_timestamp())
                   AND (workflow_version.effective_to IS NULL OR workflow_version.effective_to>clock_timestamp())
                   AND form.lifecycle_state IN ('DRAFT','PUBLISHED') AND form_version.lifecycle_state='PUBLISHED'
                   AND category.lifecycle_state='ACTIVE' AND binding.lifecycle_state='ACTIVE'
                   AND (binding.effective_from IS NULL OR binding.effective_from<=clock_timestamp())
                   AND (binding.effective_to IS NULL OR binding.effective_to>clock_timestamp())
                   AND workflow.management_resource_set_key=request.management_resource_set_key
                   AND form.management_resource_set_key=request.management_resource_set_key
                   AND category.management_resource_set_key=request.management_resource_set_key
                 FOR UPDATE OF request FOR SHARE OF tenant,workflow_version,workflow,form_version,form,category,binding
                """, new MapSqlParameterSource().addValue("tenantId", actor.tenantId()).addValue("userId", actor.userId())
                        .addValue("personId", actor.personPublicId()).addValue("requestId", requestId)
                        .addValue("expectedVersion", expectedVersion).addValue("workflowId", requestedWorkflowId)
                        .addValue("formId", form.binding().formId()), (row, index) -> {
                    var mapper = new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
                    String schema = row.getString("form_schema");
                    Map<String, Object> parsed;
                    Map<String, Object> workflow;
                    try {
                        parsed = mapper.readValue(schema, new com.fasterxml.jackson.core.type.TypeReference<>() { });
                        workflow = mapper.readValue(row.getString("workflow_definition"), new com.fasterxml.jackson.core.type.TypeReference<>() { });
                    } catch (java.io.IOException exception) { throw unavailable(); }
                    var compiled = new ApprovalFormSchemaV2Compiler().compile(parsed);
                    if (!compiled.sha256().equals(form.schema().sha256())
                            || !compiled.sha256().equals(row.getString("schema_sha256").trim())
                            || !form.workflowVersionId().equals(row.getObject("workflow_version_id", UUID.class))
                            || !form.binding().formVersionId().equals(row.getObject("form_version_id", UUID.class))
                            || !form.dataClassification().equals(row.getString("data_classification"))
                            || !sealed.managementResourceSetKey().equals(row.getString("management_resource_set_key"))) throw conflict();
                    var typed = workflow.containsKey("schemaContract") ? ApprovalWorkflowQuorumDefinition.compile(workflow) : null;
                    if (typed != null && !typed.sha256().equals(row.getString("definition_sha256").trim())) throw conflict();
                    if (typed == null) legacyValidator.accept(row.getString("workflow_definition"), row.getInt("sla_minutes"));
                    return new VerifiedRuntime(form.workflowVersionId(), form.binding().formVersionId(),
                            form.dataClassification(), sealed.managementResourceSetKey(), compiled.canonicalJson());
                });
        if (rows.size() != 1) throw conflict();
        new ApprovalWorkflowQuorumRuntimeStore(jdbc, new ObjectMapper()).policy(actor.tenantId(), requestId);
        return assembler.apply(rows.getFirst());
    }

    private static BaseException conflict() { return new BaseException(ErrorCode.RESOURCE_CONFLICT, "The pinned draft workflow changed."); }
    private static BaseException unavailable() { return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "Current workflow scope cannot be verified."); }
}
