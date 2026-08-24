package com.dwp.services.approval.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalQueryRepository;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** Transactional owner-service rechecks for high-risk Approval objects. */
@Component
public class ApprovalOwnerPredicateEvaluator {

    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalIdentityDirectory identities;

    public ApprovalOwnerPredicateEvaluator(
            NamedParameterJdbcTemplate jdbc,
            ApprovalIdentityDirectory identities) {
        this.jdbc = jdbc;
        this.identities = identities;
    }

    public void lockAndValidate(
            ApprovalRequestContext.Actor actor,
            String targetType,
            UUID targetId,
            long expectedVersion) {
        switch (targetType) {
            case "WORKFLOW" -> validateMakerObject(actor, expectedVersion, queryMaker("""
                    SELECT workflow.version, version.created_by AS maker_user_id,
                           workflow.management_resource_set_key
                      FROM apr_workflow_definitions workflow
                      JOIN apr_workflow_versions version
                        ON version.tenant_id = workflow.tenant_id
                       AND version.workflow_id = workflow.workflow_id
                       AND version.version_number = workflow.current_version
                     WHERE workflow.tenant_id = :tenantId
                       AND workflow.workflow_id = :targetId
                       AND workflow.management_resource_set_key = :managementScope
                     FOR UPDATE OF workflow
                    """, actor, targetId));
            case "FORM" -> validateMakerObject(actor, expectedVersion, queryMaker("""
                    SELECT form.version, version.created_by AS maker_user_id,
                           form.management_resource_set_key
                      FROM apr_forms form
                      JOIN apr_form_versions version
                        ON version.tenant_id = form.tenant_id
                       AND version.form_id = form.form_id
                       AND version.version_number = form.current_version
                     WHERE form.tenant_id = :tenantId AND form.form_id = :targetId
                       AND form.management_resource_set_key = :managementScope
                     FOR UPDATE OF form
                    """, actor, targetId));
            case "POLICY" -> validateMakerObject(actor, expectedVersion, queryMaker("""
                    SELECT version, pending_by AS maker_user_id,
                           management_resource_set_key
                      FROM apr_policy_rules
                     WHERE tenant_id = :tenantId AND policy_id = :targetId
                       AND management_resource_set_key = :managementScope
                     FOR UPDATE
                    """, actor, targetId));
            case "OUTBOX_EVENT" -> validateRecovery(actor, expectedVersion, queryRecovery(actor, targetId));
            default -> throw unavailable();
        }
    }

    public void lockClaimableTask(
            ApprovalRequestContext.Actor actor,
            ApprovalQueryRepository.TaskAccess expected,
            long expectedVersion) {
        TaskEvidence task = lockTask(actor, expected.summary().taskId());
        if (task == null) throw unavailable();
        if (task.version() != expectedVersion || !"PENDING".equals(task.status())
                || task.assigneeUserId() != null) {
            throw conflict("Approval task claim state changed.");
        }
        boolean candidate = task.candidateRole() != null
                && actor.roles().contains(task.candidateRole());
        if (!candidate) requireCurrentDelegation(actor, task);
    }

    public void lockDecidableTask(
            ApprovalRequestContext.Actor actor,
            ApprovalQueryRepository.TaskAccess expected,
            long expectedVersion) {
        TaskEvidence task = lockTask(actor, expected.summary().taskId());
        if (task == null) throw unavailable();
        if (task.version() != expectedVersion
                || !("PENDING".equals(task.status()) || "CLAIMED".equals(task.status()))) {
            throw conflict("Approval task decision state changed.");
        }
        boolean assigned = actor.userId().equals(task.assigneeUserId());
        if (!assigned) requireCurrentDelegation(actor, task);
        if (actor.userId().equals(task.requesterUserId())) {
            throw new BaseException(
                    ErrorCode.SOD_CONFLICT,
                    "A requester cannot decide their own approval request.");
        }
    }

    public void lockOwnedRequest(
            ApprovalRequestContext.Actor actor,
            UUID requestId,
            long expectedVersion) {
        RequestEvidence request = jdbc.query("""
                SELECT version, requester_user_id
                  FROM apr_requests
                 WHERE tenant_id = :tenantId AND request_id = :targetId
                 FOR UPDATE
                """, params(actor, requestId), result -> result.next()
                ? new RequestEvidence(result.getLong("version"), result.getLong("requester_user_id"))
                : null);
        if (request == null || !actor.userId().equals(request.requesterUserId())) throw unavailable();
        if (request.version() != expectedVersion) throw conflict("Approval request version changed.");
    }

    public void requirePublishedForm(
            ApprovalRequestContext.Actor actor,
            UUID formId,
            UUID workflowId) {
        Boolean available = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM apr_forms form
                      JOIN apr_form_workflow_bindings binding
                        ON binding.tenant_id = form.tenant_id
                       AND binding.form_id = form.form_id
                       AND binding.workflow_id = :workflowId
                       AND binding.lifecycle_state = 'ACTIVE'
                      JOIN apr_workflow_definitions workflow
                        ON workflow.tenant_id = binding.tenant_id
                       AND workflow.workflow_id = binding.workflow_id
                     WHERE form.tenant_id = :tenantId AND form.form_id = :targetId
                       AND form.lifecycle_state = 'PUBLISHED'
                       AND workflow.lifecycle_state = 'PUBLISHED')
                """, params(actor, formId).addValue("workflowId", workflowId), Boolean.class);
        if (!Boolean.TRUE.equals(available)) throw unavailable();
    }

    public void lockOwnedDelegation(
            ApprovalRequestContext.Actor actor,
            UUID delegationId,
            long expectedVersion) {
        RequestEvidence delegation = jdbc.query("""
                SELECT version, delegator_user_id AS requester_user_id
                  FROM apr_delegations
                 WHERE tenant_id = :tenantId AND delegation_id = :targetId
                 FOR UPDATE
                """, params(actor, delegationId), result -> result.next()
                ? new RequestEvidence(result.getLong("version"), result.getLong("requester_user_id"))
                : null);
        if (delegation == null || !actor.userId().equals(delegation.requesterUserId())) {
            throw unavailable();
        }
        if (delegation.version() != expectedVersion) throw conflict("Delegation version changed.");
    }

    private TaskEvidence lockTask(ApprovalRequestContext.Actor actor, UUID taskId) {
        return jdbc.query("""
                SELECT task.version, task.status, task.assignee_user_id, task.candidate_role,
                       request.requester_user_id, workflow.workflow_id
                  FROM apr_tasks task
                  JOIN apr_requests request
                    ON request.tenant_id = task.tenant_id
                   AND request.request_id = task.request_id
                  JOIN apr_workflow_versions workflow_version
                    ON workflow_version.tenant_id = request.tenant_id
                   AND workflow_version.workflow_version_id = request.workflow_version_id
                  JOIN apr_workflow_definitions workflow
                    ON workflow.tenant_id = workflow_version.tenant_id
                   AND workflow.workflow_id = workflow_version.workflow_id
                 WHERE task.tenant_id = :tenantId AND task.task_id = :targetId
                 FOR UPDATE OF task
                """, params(actor, taskId), result -> result.next()
                ? new TaskEvidence(
                        result.getLong("version"), result.getString("status"),
                        (Long) result.getObject("assignee_user_id"),
                        result.getString("candidate_role"),
                        result.getLong("requester_user_id"),
                        result.getObject("workflow_id", UUID.class))
                : null);
    }

    private void requireCurrentDelegation(
            ApprovalRequestContext.Actor actor,
            TaskEvidence task) {
        DelegationEvidence delegation = jdbc.query("""
                SELECT delegation.delegation_id, delegation.delegator_user_id
                  FROM apr_delegations delegation
                 WHERE delegation.tenant_id = :tenantId
                   AND delegation.delegate_user_id = :actorUserId
                   AND delegation.lifecycle_state = 'ACTIVE'
                   AND delegation.starts_at <= clock_timestamp()
                   AND delegation.ends_at > clock_timestamp()
                   AND (delegation.scope_type = 'ALL'
                        OR (delegation.scope_type = 'WORKFLOW'
                            AND delegation.workflow_id = :workflowId))
                   AND ((:assignedAuthority = TRUE
                         AND delegation.delegator_user_id = :assigneeUserId)
                        OR (:roleAuthority = TRUE
                            AND jsonb_exists(
                                delegation.delegated_role_codes, :candidateRole)))
                 ORDER BY delegation.starts_at DESC, delegation.created_at DESC,
                          delegation.delegation_id
                 LIMIT 1
                 FOR UPDATE
                """, new MapSqlParameterSource()
                .addValue("tenantId", actor.tenantId())
                .addValue("actorUserId", actor.userId())
                .addValue("workflowId", task.workflowId())
                .addValue("assignedAuthority", task.assigneeUserId() != null)
                .addValue("roleAuthority", task.assigneeUserId() == null
                        && task.candidateRole() != null)
                .addValue("assigneeUserId", task.assigneeUserId() == null
                        ? -1L : task.assigneeUserId())
                .addValue("candidateRole", task.candidateRole() == null
                        ? "" : task.candidateRole()), result -> result.next()
                ? new DelegationEvidence(
                        result.getObject("delegation_id", UUID.class),
                        result.getLong("delegator_user_id"))
                : null);
        if (delegation == null) throw unavailable();
        ApprovalIdentityDirectory.Subject delegator = identities.require(
                actor.tenantId(), delegation.delegatorUserId());
        boolean roleBased = task.assigneeUserId() == null;
        if (!delegator.active()
                || (roleBased && !delegator.hasRole(task.candidateRole()))) {
            throw unavailable();
        }
    }

    private MakerEvidence queryMaker(
            String sql, ApprovalRequestContext.Actor actor, UUID targetId) {
        return jdbc.query(sql, managementParams(actor, targetId), result -> result.next()
                ? new MakerEvidence(
                        result.getLong("version"),
                        (Long) result.getObject("maker_user_id"),
                        result.getString("management_resource_set_key"))
                : null);
    }

    private RecoveryEvidence queryRecovery(
            ApprovalRequestContext.Actor actor, UUID targetId) {
        return jdbc.query("""
                SELECT version, event_originator_user_id, assigned_auditor_user_id, status,
                       management_resource_set_key,
                       recovery_auditor_assignment_state,
                       recovery_auditor_resource_set_key,
                       recovery_auditor_assignment_revision,
                       recovery_auditor_assigned_at
                  FROM apr_integration_outbox
                 WHERE tenant_id = :tenantId AND outbox_id = :targetId
                   AND management_resource_set_key = :managementScope
                 FOR UPDATE
                """, managementParams(actor, targetId), result -> result.next()
                ? new RecoveryEvidence(
                        result.getLong("version"),
                        (Long) result.getObject("event_originator_user_id"),
                        (Long) result.getObject("assigned_auditor_user_id"),
                        result.getString("status"),
                        result.getString("management_resource_set_key"),
                        result.getString("recovery_auditor_assignment_state"),
                        result.getString("recovery_auditor_resource_set_key"),
                        result.getString("recovery_auditor_assignment_revision"),
                        result.getTimestamp("recovery_auditor_assigned_at") == null
                                ? null
                                : result.getTimestamp("recovery_auditor_assigned_at").toInstant())
                : null);
    }

    private void validateMakerObject(
            ApprovalRequestContext.Actor actor, long expectedVersion, MakerEvidence evidence) {
        if (evidence == null) throw unavailable();
        if (!selectedManagementScope()
                .equals(evidence.managementResourceSetKey())) throw unavailable();
        if (evidence.version() != expectedVersion) throw conflict("Approval object version changed.");
        if (evidence.makerUserId() == null) throw authorityUnavailable(
                "Approval maker evidence is incomplete.");
        if (evidence.makerUserId().equals(actor.userId())) {
            throw new BaseException(
                    ErrorCode.SOD_CONFLICT,
                    "The draft maker cannot publish the same approval object version.");
        }
    }

    private void validateRecovery(
            ApprovalRequestContext.Actor actor,
            long expectedVersion,
            RecoveryEvidence evidence) {
        if (evidence == null) throw unavailable();
        String selectedScope = selectedManagementScope();
        if (evidence.originatorUserId() == null
                || evidence.auditorUserId() == null
                || !selectedScope.equals(evidence.managementResourceSetKey())
                || !"ASSIGNED".equals(evidence.assignmentState())
                || !selectedScope.equals(evidence.resourceSetKey())
                || evidence.assignmentRevision() == null
                || evidence.assignmentRevision().isBlank()
                || evidence.assignedAt() == null
                || evidence.originatorUserId().equals(evidence.auditorUserId())) {
            throw authorityUnavailable("Approval recovery-party evidence is incomplete.");
        }
        if (evidence.version() != expectedVersion) throw conflict("Delivery version changed.");
        if (!"FAILED".equals(evidence.status()) && !"DEAD".equals(evidence.status())) {
            throw conflict("Only a failed delivery can be recovered.");
        }
        if (actor.userId().equals(evidence.originatorUserId())
                || actor.userId().equals(evidence.auditorUserId())) {
            throw new BaseException(
                    ErrorCode.SOD_CONFLICT,
                    "Delivery originators and assigned auditors cannot execute recovery.");
        }
    }

    private MapSqlParameterSource params(ApprovalRequestContext.Actor actor, UUID targetId) {
        return new MapSqlParameterSource()
                .addValue("tenantId", actor.tenantId())
                .addValue("targetId", targetId);
    }

    private MapSqlParameterSource managementParams(
            ApprovalRequestContext.Actor actor, UUID targetId) {
        return params(actor, targetId).addValue(
                "managementScope", selectedManagementScope());
    }

    private String selectedManagementScope() {
        return ApprovalManagementScopeContext.current()
                .map(ApprovalManagementScopeContext.Evidence::resourceSetKey)
                .orElseGet(() -> {
                    if (ApprovalDecisionRevisionContext.current().isEmpty()) {
                        return "RS_APPROVALS";
                    }
                    throw authorityUnavailable(
                            "Approval management scope evidence is unavailable.");
                });
    }

    private BaseException unavailable() {
        return new BaseException(
                ErrorCode.RESOURCE_NOT_AVAILABLE,
                "The governed approval object is not available.");
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT, message);
    }

    private BaseException authorityUnavailable(String message) {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message);
    }

    private record MakerEvidence(
            long version, Long makerUserId, String managementResourceSetKey) {
    }

    private record RecoveryEvidence(
            long version,
            Long originatorUserId,
            Long auditorUserId,
            String status,
            String managementResourceSetKey,
            String assignmentState,
            String resourceSetKey,
            String assignmentRevision,
            Instant assignedAt) {
    }

    private record TaskEvidence(
            long version,
            String status,
            Long assigneeUserId,
            String candidateRole,
            long requesterUserId,
            UUID workflowId) {
    }

    private record DelegationEvidence(UUID delegationId, long delegatorUserId) {
    }

    private record RequestEvidence(long version, Long requesterUserId) {
    }
}
