package com.dwp.services.approval.document;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.documentretention.ApprovalRetentionLiveGuard;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class ApprovalDocumentOwnerRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public ApprovalDocumentOwnerRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Owner lock(ApprovalRequestContext.Actor actor, ApprovalDocumentDtos.OwnerType type, UUID id) {
        var p = params(actor).addValue("id", id);
        UUID requestId = type == ApprovalDocumentDtos.OwnerType.REQUEST ? jdbc.query("""
                SELECT request_id FROM apr_requests WHERE tenant_id=:tenant AND request_id=:id
                """, p, r -> r.next() ? r.getObject(1, UUID.class) : null) : jdbc.query("""
                SELECT request_id FROM apr_tasks WHERE tenant_id=:tenant AND task_id=:id
                """, p, r -> r.next() ? r.getObject(1, UUID.class) : null);
        if (requestId == null) throw hidden();
        new ApprovalRetentionLiveGuard(jdbc).writeRequest(actor.tenantId(),requestId);
        p.addValue("request", requestId);
        Owner request = jdbc.query("""
                SELECT r.*, p.schema_version AS payload_revision,p.payload_sha256,p.payload::text,
                       w.workflow_id,f.schema_payload::text AS form_schema
                  FROM apr_requests r JOIN apr_tenants tenant ON tenant.tenant_id=r.tenant_id
                  JOIN apr_request_payloads p ON p.tenant_id=r.tenant_id AND p.request_id=r.request_id
                  JOIN apr_workflow_versions w ON w.tenant_id=r.tenant_id AND w.workflow_version_id=r.workflow_version_id
                  JOIN apr_form_versions f ON f.tenant_id=r.tenant_id AND f.form_version_id=r.form_version_id
                 WHERE r.tenant_id=:tenant AND r.request_id=:request AND r.deleted_at IS NULL
                   AND tenant.lifecycle_state='ACTIVE' FOR UPDATE OF r,p FOR SHARE OF tenant
                """, p, r -> r.next() ? new Owner(requestId, null, r.getLong("version"), null,
                r.getLong("requester_user_id"), r.getString("request_number"), r.getString("title"),
                r.getString("summary"), r.getString("status"), r.getString("data_classification"),
                r.getString("management_resource_set_key"), r.getObject("workflow_id", UUID.class),
                null, null, null, null, null, r.getInt("payload_revision"), r.getString("payload_sha256"),
                r.getString("payload"), r.getString("form_schema")) : null);
        if (request == null) throw hidden();
        if (type == ApprovalDocumentDtos.OwnerType.REQUEST) return request;
        return jdbc.query("""
                SELECT * FROM apr_tasks WHERE tenant_id=:tenant AND request_id=:request AND task_id=:id FOR UPDATE
                """, p, r -> {
            if (!r.next()) throw hidden();
            boolean decision = r.getObject("decision_payload_revision") != null;
            Payload payload = decision ? jdbc.query("""
                    SELECT payload::text,payload_sha256 FROM apr_request_payload_versions
                     WHERE tenant_id=:tenant AND request_id=:request AND revision_number=:revision
                       AND payload_sha256=:sha
                    """, p.addValue("revision", r.getInt("decision_payload_revision"))
                    .addValue("sha", r.getString("decision_payload_sha256")), x -> x.next()
                    ? new Payload(x.getString(1), x.getString(2)) : null) : new Payload(request.payload(), request.payloadSha256());
            if (payload == null) throw ApprovalDocumentCanonical.unavailable("Immutable decision payload is unavailable.");
            return new Owner(request.requestId(), id, request.requestVersion(), r.getLong("version"),
                    request.requesterUserId(), request.requestNumber(), request.title(), request.summary(),
                    request.status(), request.classification(), request.resourceSetKey(), request.workflowId(),
                    r.getString("status"), (Long) r.getObject("assignee_user_id"), r.getString("candidate_role"),
                    (Long) r.getObject("delegated_from_user_id"), r.getString("delegated_authority_role_code"),
                    decision ? r.getInt("decision_payload_revision") : request.payloadRevision(), payload.sha256(), payload.json(), request.formSchema());
        });
    }

    public List<Delegation> lockDelegations(ApprovalRequestContext.Actor actor, Owner owner) {
        return jdbc.query("""
                SELECT delegation_id,delegator_user_id,delegated_role_codes::text
                  FROM apr_delegations
                 WHERE tenant_id=:tenant AND delegate_user_id=:actor AND lifecycle_state='ACTIVE'
                   AND starts_at<=clock_timestamp() AND ends_at>clock_timestamp()
                   AND (scope_type='ALL' OR (scope_type='WORKFLOW' AND workflow_id=:workflow))
                   AND (CAST(:source AS bigint) IS NULL OR delegator_user_id=:source)
                 ORDER BY delegation_id FOR UPDATE
                """, params(actor).addValue("workflow", owner.workflowId()).addValue("source", owner.delegatedFromUserId()),
                (r, n) -> new Delegation(r.getObject(1, UUID.class), r.getLong(2), r.getString(3)));
    }

    public void assertTaskState(ApprovalRequestContext.Actor actor, Owner owner) {
        Boolean unchanged = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM apr_tasks
                  WHERE tenant_id=:tenant AND task_id=:task AND version=:version
                    AND assignee_user_id IS NOT DISTINCT FROM CAST(:assigned AS bigint)
                    AND delegated_from_user_id IS NOT DISTINCT FROM CAST(:source AS bigint))
                """, params(actor).addValue("task", owner.taskId()).addValue("version", owner.taskVersion())
                .addValue("assigned", owner.assigneeUserId()).addValue("source", owner.delegatedFromUserId()), Boolean.class);
        if (!Boolean.TRUE.equals(unchanged)) throw ApprovalDocumentCanonical.conflict();
    }
    public void requireImmutablePayload(ApprovalRequestContext.Actor actor, Owner owner) {
        Boolean matches = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM apr_request_payload_versions
                  WHERE tenant_id=:tenant AND request_id=:request AND revision_number=:revision
                    AND payload_sha256=:sha AND payload=CAST(:payload AS jsonb))
                """, params(actor).addValue("request", owner.requestId()).addValue("revision", owner.payloadRevision())
                .addValue("sha", owner.payloadSha256()).addValue("payload", owner.payload()), Boolean.class);
        if (!Boolean.TRUE.equals(matches)) throw ApprovalDocumentCanonical.unavailable("Document payload is not bound to immutable revision evidence.");
    }

    public static MapSqlParameterSource params(ApprovalRequestContext.Actor actor) {
        return new MapSqlParameterSource().addValue("tenant", actor.tenantId()).addValue("actor", actor.userId());
    }
    public static BaseException hidden() { return new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE, "Approval document is unavailable."); }
    public record Owner(UUID requestId, UUID taskId, long requestVersion, Long taskVersion, long requesterUserId,
                        String requestNumber, String title, String summary, String status, String classification,
                        String resourceSetKey, UUID workflowId, String taskStatus, Long assigneeUserId,
                        String candidateRole, Long delegatedFromUserId, String authorityRoleCode,
                        int payloadRevision, String payloadSha256, String payload, String formSchema) {
        public long version() { return taskId == null ? requestVersion : taskVersion; }
        public String resource() { return taskId == null ? "ACTION.APPROVAL_REQUEST" : "ACTION.APPROVAL_TASK"; }
    }
    public record Delegation(UUID id, long sourceUserId, String roles) { }
    private record Payload(String json, String sha256) { }
}
