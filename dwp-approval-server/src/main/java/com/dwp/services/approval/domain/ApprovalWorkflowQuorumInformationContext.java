package com.dwp.services.approval.domain;

import static com.dwp.services.approval.domain.ApprovalWorkflowQuorumRuntimeStore.*;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalManagementScopeContext;
import com.dwp.services.approval.security.ApprovalRequestContext.Actor;
import java.util.Map;
import java.util.UUID;

/** Reads the exact request-owned versions under current effective resource-set and binding guards. */
final class ApprovalWorkflowQuorumInformationContext {
    record Bound(Context context, ApprovalWorkflowQuorumDefinition definition, long requestVersion,
            String schema, Map<String, Object> payload, String resourceSet, String bindingType, String bindingCondition,
            UUID workflowId, UUID formId) {
        boolean same(Bound other) {
            return context.equals(other.context) && definition.sha256().equals(other.definition.sha256())
                    && requestVersion == other.requestVersion && schema.equals(other.schema)
                    && payload.equals(other.payload) && resourceSet.equals(other.resourceSet)
                    && bindingType.equals(other.bindingType) && bindingCondition.equals(other.bindingCondition)
                    && workflowId.equals(other.workflowId) && formId.equals(other.formId);
        }
    }

    static Bound require(ApprovalWorkflowQuorumRuntimeStore store, Actor actor, UUID request, String status) {
        var rows = store.jdbc.queryForList("""
                SELECT request.version,request.management_resource_set_key,form_version.schema_payload::text AS schema,
                       payload.payload::text AS payload,workflow_version.definition::text AS definition,
                       binding.binding_type,binding.condition_payload::text AS condition,workflow.workflow_id,form.form_id
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
                  JOIN apr_request_payloads payload ON payload.tenant_id=request.tenant_id AND payload.request_id=request.request_id
                 WHERE request.tenant_id=:tenant AND request.request_id=:request AND request.status=:status
                   AND request.deleted_at IS NULL AND workflow.lifecycle_state='PUBLISHED'
                   AND workflow_version.lifecycle_state='PUBLISHED'
                   AND (workflow_version.effective_from IS NULL OR workflow_version.effective_from<=clock_timestamp())
                   AND (workflow_version.effective_to IS NULL OR workflow_version.effective_to>clock_timestamp())
                   AND form.lifecycle_state IN ('DRAFT','PUBLISHED') AND form_version.lifecycle_state='PUBLISHED'
                   AND category.lifecycle_state='ACTIVE' AND binding.lifecycle_state='ACTIVE'
                   AND (binding.effective_from IS NULL OR binding.effective_from<=clock_timestamp())
                   AND (binding.effective_to IS NULL OR binding.effective_to>clock_timestamp())
                   AND workflow.management_resource_set_key=request.management_resource_set_key
                   AND form.management_resource_set_key=request.management_resource_set_key
                   AND category.management_resource_set_key=request.management_resource_set_key
                 FOR SHARE OF tenant,workflow_version,workflow,form_version,form,category,binding,payload
                """, store.scope(actor.tenantId(), request).addValue("status", status));
        if (rows.size() != 1) throw conflict();
        var row = rows.getFirst();
        String scope = (String) row.get("management_resource_set_key");
        ApprovalManagementScopeContext.current().ifPresent(current -> {
            if (!scope.equals(current.resourceSetKey())) throw new BaseException(ErrorCode.FORBIDDEN);
        });
        var definition = ApprovalWorkflowQuorumDefinition.compile((String) row.get("definition"));
        var context = store.context(actor.tenantId(), request, definition, store.policy(actor.tenantId(), request));
        String schema = (String) row.get("schema");
        var rawSchema = store.object(schema);
        var payload = ApprovalFormSchemaV2Canonical.freeze(store.object((String) row.get("payload")));
        if (rawSchema.containsKey("schemaContract")) {
            var compiled = new ApprovalFormSchemaV2Compiler().compile(rawSchema);
            if (!compiled.sha256().equals(context.pins().formSchemaSha256())
                    || !store.hash(ApprovalFormSchemaV2Canonical.json(payload)).equals(context.payloadSha256())) throw conflict();
            schema = compiled.canonicalJson();
        }
        return new Bound(context, definition, ((Number) row.get("version")).longValue(), schema,
                payload, scope,
                (String) row.get("binding_type"), (String) row.get("condition"), (UUID) row.get("workflow_id"), (UUID) row.get("form_id"));
    }

    private ApprovalWorkflowQuorumInformationContext() { }
}
