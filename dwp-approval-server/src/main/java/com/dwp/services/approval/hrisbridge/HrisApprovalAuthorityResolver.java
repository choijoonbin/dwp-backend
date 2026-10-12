package com.dwp.services.approval.hrisbridge;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.platform.contracts.hris.generated.ApprovalRequestV1;
import com.dwp.services.approval.domain.ApprovalWorkflowQuorumDefinition;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Service-native Auth and exact pinned Approval catalog resolution. */
@Component
@ConditionalOnProperty(prefix = "dwp.approval.hris-bridge", name = "enabled", havingValue = "true")
final class HrisApprovalAuthorityResolver {
    private static final String PERSON_PREFIX = "person:";
    private final ApprovalIdentityDirectory identities;
    private final NamedParameterJdbcTemplate jdbc;

    HrisApprovalAuthorityResolver(ApprovalIdentityDirectory identities,
            NamedParameterJdbcTemplate jdbc) {
        this.identities = identities;
        this.jdbc = jdbc;
    }

    Authority resolve(long tenantId, ApprovalRequestV1 request) {
        UUID personId = requesterPerson(request.requesterRef());
        List<ApprovalIdentityDirectory.Subject> people = identities
                .search(tenantId, personId.toString(), 2).stream()
                .filter(subject -> subject != null && subject.tenantId() != null
                        && subject.tenantId() == tenantId && subject.userId() != null
                        && subject.userId() > 0 && personId.equals(subject.personPublicId())
                        && subject.active())
                .toList();
        if (people.size() != 1) throw unavailable(
                "The HRIS requester does not resolve to exactly one active tenant identity.");
        var rows = jdbc.queryForList("""
                SELECT workflow.workflow_id,workflow_version.workflow_version_id,
                       workflow_version.version_number,workflow_version.definition::text AS workflow_definition,
                       form.form_id,form_version.form_version_id
                  FROM apr_tenants tenant
                  JOIN apr_workflow_definitions workflow
                    ON workflow.tenant_id=tenant.tenant_id
                   AND workflow.workflow_key=:workflowKey
                   AND workflow.lifecycle_state='PUBLISHED'
                  JOIN apr_workflow_versions workflow_version
                    ON workflow_version.tenant_id=workflow.tenant_id
                   AND workflow_version.workflow_id=workflow.workflow_id
                   AND workflow_version.version_number=:workflowVersion
                   AND workflow_version.lifecycle_state='PUBLISHED'
                   AND (workflow_version.effective_from IS NULL OR workflow_version.effective_from<=CURRENT_TIMESTAMP)
                   AND (workflow_version.effective_to IS NULL OR workflow_version.effective_to>CURRENT_TIMESTAMP)
                  JOIN apr_form_workflow_bindings binding
                    ON binding.tenant_id=workflow.tenant_id
                   AND binding.workflow_id=workflow.workflow_id
                   AND binding.lifecycle_state='ACTIVE'
                   AND (binding.effective_from IS NULL OR binding.effective_from<=CURRENT_TIMESTAMP)
                   AND (binding.effective_to IS NULL OR binding.effective_to>CURRENT_TIMESTAMP)
                  JOIN apr_forms form
                    ON form.tenant_id=binding.tenant_id AND form.form_id=binding.form_id
                   AND form.lifecycle_state='PUBLISHED'
                   AND form.management_resource_set_key=workflow.management_resource_set_key
                  JOIN apr_form_categories category
                    ON category.tenant_id=form.tenant_id AND category.category_id=form.category_id
                   AND category.lifecycle_state='ACTIVE'
                   AND category.management_resource_set_key=form.management_resource_set_key
                  JOIN apr_form_versions form_version
                    ON form_version.tenant_id=form.tenant_id AND form_version.form_id=form.form_id
                   AND form_version.lifecycle_state='PUBLISHED'
                 WHERE tenant.tenant_id=:tenant AND tenant.lifecycle_state='ACTIVE'
                """, new MapSqlParameterSource()
                .addValue("tenant", tenantId)
                .addValue("workflowKey", request.workflowKey())
                .addValue("workflowVersion", request.workflowVersion()));
        if (rows.size() != 1) throw unavailable(
                "The pinned workflow version must have exactly one active published form binding.");
        var row = rows.getFirst();
        try {
            ApprovalWorkflowQuorumDefinition.compile((String) row.get("workflow_definition"));
        } catch (RuntimeException exception) {
            throw unavailable("The pinned workflow version is not an exact typed-quorum definition.");
        }
        var person = people.getFirst();
        return new Authority(person.userId(), person.personPublicId(), person.displayName(),
                (UUID) row.get("workflow_id"),
                (UUID) row.get("workflow_version_id"),
                (UUID) row.get("form_id"),
                (UUID) row.get("form_version_id"));
    }

    private static UUID requesterPerson(String requesterRef) {
        if (requesterRef == null || !requesterRef.matches(
                "^person:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) {
            throw unavailable("A canonical person requester reference is required.");
        }
        return UUID.fromString(requesterRef.substring(PERSON_PREFIX.length()));
    }

    private static BaseException unavailable(String message) {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }

    record Authority(long userId, UUID personPublicId, String displayName,
            UUID workflowId, UUID workflowVersionId, UUID formId, UUID formVersionId) { }
}
