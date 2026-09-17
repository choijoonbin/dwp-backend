package com.dwp.services.approval.policyautomation;

import com.dwp.services.approval.document.ApprovalDocumentCanonical;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.approval.policyautomation.PolicyAutomationModels.*;

@Repository
public class DelegationGovernanceRepository {
    private static final Duration MAXIMUM_WINDOW = Duration.ofDays(90);

    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalDocumentCanonical canonical;

    public DelegationGovernanceRepository(
            NamedParameterJdbcTemplate jdbc,
            ApprovalDocumentCanonical canonical) {
        this.jdbc = jdbc;
        this.canonical = canonical;
    }

    List<DelegationView> delegations(Context context, Instant now) {
        requireActiveTenant(context);
        return query(context, null, false, now);
    }

    DelegationView requireDelegation(
            Context context, UUID delegationId, boolean lock, Instant now) {
        requireActiveTenant(context);
        List<DelegationView> rows = query(context, delegationId, lock, now);
        if (rows.size() != 1) throw PolicyAutomationRejected.unavailable(
                "Delegation is unavailable in this management scope.");
        return rows.getFirst();
    }

    List<DelegationReviewView> reviews(Context context, UUID delegationId, Instant now) {
        requireDelegation(context, delegationId, false, now);
        return jdbc.query("""
                SELECT review_id,delegation_id,delegation_version,disposition,
                       compliance_state,scope_binding_truth,time_window_truth,
                       no_sub_delegation_truth,identity_separation_truth,
                       role_snapshot_truth,role_sod_truth,findings::text,
                       review_evidence_sha256,reviewed_by,reviewed_at
                  FROM apr_delegation_governance_reviews
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND delegation_id=:id
                 ORDER BY reviewed_at DESC,review_id
                """, base(context).addValue("id", delegationId),
                (row, number) -> review(row));
    }

    List<DelegationAuditEvent> audit(Context context, UUID delegationId, int limit, Instant now) {
        requireActiveTenant(context);
        if (limit < 1 || limit > 200) {
            throw PolicyAutomationRejected.invalid("Delegation audit limit must be between 1 and 200.");
        }
        if (delegationId != null) requireDelegation(context, delegationId, false, now);
        return jdbc.query("""
                SELECT event_id,delegation_id,action,expected_version,resulting_version,
                       idempotency_key,command_sha256,before_state::text,after_state::text,
                       reason,actor_user_id,occurred_at
                  FROM apr_delegation_governance_events
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                """ + (delegationId == null ? "" : " AND delegation_id=:id")
                        + " ORDER BY occurred_at DESC,event_id LIMIT :limit",
                base(context).addValue("id", delegationId).addValue("limit", limit),
                (row, number) -> new DelegationAuditEvent(row.getObject(1, UUID.class),
                        row.getObject(2, UUID.class), row.getString(3), row.getLong(4), row.getLong(5),
                        row.getString(6), row.getString(7), object(row.getString(8)), object(row.getString(9)),
                        row.getString(10), row.getLong(11), row.getTimestamp(12).toInstant()));
    }

    DelegationView create(Context context, DelegationDraft input,
            DelegationIdentity delegator, DelegationIdentity delegate, Instant now) {
        requireActiveTenant(context);
        if (input.expectedVersion() != 0) {
            throw PolicyAutomationRejected.conflict("New delegations require expected version zero.");
        }
        WorkflowBinding workflow = workflow(context, input);
        lockPair(context, delegator.userId(), delegate.userId());
        requireNoConflict(context, input, null);
        MapSqlParameterSource parameters = command(context, input, delegator, delegate, workflow);
        try {
            int inserted = jdbc.update("""
                    INSERT INTO apr_delegations(delegation_id,tenant_id,delegator_user_id,delegate_user_id,
                        delegate_person_public_id,delegate_display_name,delegate_email,delegated_role_codes,
                        scope_type,workflow_id,workflow_key,starts_at,ends_at,lifecycle_state,reason,
                        management_resource_set_key,version,created_by,updated_by)
                    VALUES(:id,:tenant,:delegator,:delegate,:delegatePerson,:delegateName,:delegateEmail,
                        CAST(:roles AS jsonb),:scopeType,:workflowId,:workflowKey,:starts,:ends,'ACTIVE',
                        :reason,:scope,0,:actor,:actor)
                    """, parameters);
            if (inserted != 1) throw PolicyAutomationRejected.conflict("Delegation could not be created.");
        } catch (DuplicateKeyException exception) {
            throw PolicyAutomationRejected.conflict("Delegation identity or window conflicts in this scope.");
        }
        DelegationView result = requireDelegation(context, input.delegationId(), false, now);
        event(context, input.delegationId(), "CREATE", 0, result.version(), input,
                Map.of(), state(result), input.reason());
        return result;
    }

    DelegationView update(Context context, DelegationDraft input,
            DelegationIdentity delegator, DelegationIdentity delegate, Instant now) {
        DelegationView before = requireDelegation(context, input.delegationId(), true, now);
        if (before.version() != input.expectedVersion() || !"ACTIVE".equals(before.lifecycleState())) {
            throw PolicyAutomationRejected.conflict("Delegation changed or is no longer editable.");
        }
        WorkflowBinding workflow = workflow(context, input);
        lockPair(context, delegator.userId(), delegate.userId());
        requireNoConflict(context, input, input.delegationId());
        MapSqlParameterSource parameters = command(context, input, delegator, delegate, workflow);
        int updated = jdbc.update("""
                UPDATE apr_delegations
                   SET delegator_user_id=:delegator,delegate_user_id=:delegate,
                       delegate_person_public_id=:delegatePerson,delegate_display_name=:delegateName,
                       delegate_email=:delegateEmail,delegated_role_codes=CAST(:roles AS jsonb),
                       scope_type=:scopeType,workflow_id=:workflowId,workflow_key=:workflowKey,
                       starts_at=:starts,ends_at=:ends,reason=:reason,version=version+1,
                       updated_at=clock_timestamp(),updated_by=:actor
                 WHERE tenant_id=:tenant AND management_resource_set_key=:scope
                   AND delegation_id=:id AND lifecycle_state='ACTIVE' AND version=:expected
                """, parameters);
        if (updated != 1) throw PolicyAutomationRejected.conflict("Delegation update lost its object-version fence.");
        DelegationView result = requireDelegation(context, input.delegationId(), false, now);
        event(context, input.delegationId(), "UPDATE", before.version(), result.version(), input,
                state(before), state(result), input.reason());
        return result;
    }

    DelegationView revoke(Context context, UUID delegationId, DelegationStateCommand input,
            boolean scheduledOnly, Instant now) {
        DelegationView before = requireDelegation(context, delegationId, true, now);
        if (before.version() != input.expectedVersion() || !"ACTIVE".equals(before.lifecycleState())
                || scheduledOnly && !now.isBefore(before.startsAt())) {
            throw PolicyAutomationRejected.conflict("Delegation changed or is not eligible for this revocation.");
        }
        int updated = jdbc.update("""
                UPDATE apr_delegations
                   SET lifecycle_state='REVOKED',reason=:reason,version=version+1,
                       updated_at=clock_timestamp(),updated_by=:actor
                 WHERE tenant_id=:tenant AND management_resource_set_key=:scope
                   AND delegation_id=:id AND lifecycle_state='ACTIVE' AND version=:expected
                """, base(context).addValue("id", delegationId).addValue("expected", input.expectedVersion())
                .addValue("reason", input.reason().trim()));
        if (updated != 1) throw PolicyAutomationRejected.conflict("Delegation revocation lost its object-version fence.");
        DelegationView result = requireDelegation(context, delegationId, false, now);
        String action = scheduledOnly ? "CANCEL_SCHEDULED" : "REVOKE";
        event(context, delegationId, action, before.version(), result.version(), input,
                state(before), state(result), input.reason());
        return result;
    }

    DelegationKillSwitchView killSwitch(Context context, DelegationKillSwitchCommand input, Instant now) {
        requireActiveTenant(context);
        jdbc.update("""
                INSERT INTO apr_delegation_control_heads(tenant_id,resource_set_key,version)
                VALUES(:tenant,:scope,0) ON CONFLICT DO NOTHING
                """, base(context));
        Long current = jdbc.queryForObject("""
                SELECT version FROM apr_delegation_control_heads
                 WHERE tenant_id=:tenant AND resource_set_key=:scope FOR UPDATE
                """, base(context), Long.class);
        if (current == null || current != input.expectedControlVersion()) {
            throw PolicyAutomationRejected.conflict("Delegation kill-switch control version changed.");
        }
        Long active = jdbc.queryForObject("""
                SELECT count(*) FROM apr_delegations
                 WHERE tenant_id=:tenant AND management_resource_set_key=:scope
                   AND lifecycle_state='ACTIVE'
                """, base(context), Long.class);
        int revoked = jdbc.update("""
                UPDATE apr_delegations
                   SET lifecycle_state='REVOKED',version=version+1,
                       reason=:reason,updated_at=clock_timestamp(),updated_by=:actor
                 WHERE tenant_id=:tenant AND management_resource_set_key=:scope
                   AND lifecycle_state='ACTIVE'
                """, base(context).addValue("reason", input.reason().trim()));
        if (revoked != (active == null ? 0 : active.intValue())) {
            throw PolicyAutomationRejected.conflict("Delegation kill-switch cardinality changed.");
        }
        int updated = jdbc.update("""
                UPDATE apr_delegation_control_heads
                   SET version=version+1,last_kill_switch_id=:id,last_reason=:reason,
                       last_invoked_by=:actor,last_invoked_at=:now
                 WHERE tenant_id=:tenant AND resource_set_key=:scope AND version=:expected
                """, base(context).addValue("id", input.killSwitchId()).addValue("reason", input.reason().trim())
                .addValue("now", Timestamp.from(now)).addValue("expected", input.expectedControlVersion()));
        if (updated != 1) throw PolicyAutomationRejected.conflict("Delegation kill-switch lost its control-version fence.");
        long resulting = Math.addExact(input.expectedControlVersion(), 1);
        event(context, null, "KILL_SWITCH", input.expectedControlVersion(), resulting, input,
                Map.of("activeDelegations", active == null ? 0 : active),
                Map.of("activeDelegations", 0, "revokedDelegations", revoked), input.reason());
        return new DelegationKillSwitchView(input.killSwitchId(), context.resourceSetKey(), resulting,
                revoked, input.reason().trim(), context.actorUserId(), now);
    }

    DelegationReviewView review(
            Context context,
            UUID delegationId,
            DelegationReviewCommand input,
            Instant now) {
        validate(input);
        DelegationView delegation = requireDelegation(context, delegationId, true, now);
        if (delegation.version() != input.expectedDelegationVersion()) {
            throw PolicyAutomationRejected.conflict(
                    "Delegation version changed before governance review.");
        }
        DelegationComplianceState compliance = compliance(delegation);
        MapSqlParameterSource parameters = base(context)
                .addValue("id", delegationId)
                .addValue("review", input.reviewId())
                .addValue("version", delegation.version())
                .addValue("disposition", input.disposition().name())
                .addValue("compliance", compliance.name())
                .addValue("scopeTruth", delegation.scopeBindingTruth().name())
                .addValue("timeTruth", delegation.timeWindowTruth().name())
                .addValue("subTruth", delegation.noSubDelegationTruth().name())
                .addValue("identityTruth", delegation.identitySeparationTruth().name())
                .addValue("roleSnapshotTruth", delegation.roleSnapshotTruth().name())
                .addValue("roleSodTruth", delegation.roleSeparationOfDutiesTruth().name())
                .addValue("findings", canonical.json(delegation.findings()))
                .addValue("evidence", input.reviewEvidenceSha256())
                .addValue("now", Timestamp.from(now));
        try {
            jdbc.update("""
                    INSERT INTO apr_delegation_governance_reviews(
                        tenant_id,resource_set_key,delegation_id,review_id,
                        delegation_version,disposition,compliance_state,
                        scope_binding_truth,time_window_truth,no_sub_delegation_truth,
                        identity_separation_truth,role_snapshot_truth,role_sod_truth,
                        findings,review_evidence_sha256,reviewed_by,reviewed_at)
                    VALUES(:tenant,:scope,:id,:review,:version,:disposition,:compliance,
                        :scopeTruth,:timeTruth,:subTruth,:identityTruth,:roleSnapshotTruth,
                        :roleSodTruth,CAST(:findings AS jsonb),:evidence,:actor,:now)
                    """, parameters);
        } catch (DuplicateKeyException exception) {
            throw PolicyAutomationRejected.conflict(
                    "Delegation governance review identity already exists.");
        }
        DelegationReviewView result = new DelegationReviewView(input.reviewId(), delegationId, delegation.version(),
                input.disposition(), compliance, delegation.scopeBindingTruth(),
                delegation.timeWindowTruth(), delegation.noSubDelegationTruth(),
                delegation.identitySeparationTruth(), delegation.roleSnapshotTruth(),
                delegation.roleSeparationOfDutiesTruth(), delegation.findings(),
                input.reviewEvidenceSha256(), context.actorUserId(), now);
        event(context, delegationId, "REVIEW", delegation.version(), delegation.version(), input,
                state(delegation), Map.of("reviewId", input.reviewId().toString(),
                        "disposition", input.disposition().name(), "complianceState", compliance.name()),
                input.disposition().name());
        return result;
    }

    private WorkflowBinding workflow(Context context, DelegationDraft input) {
        if ("ALL".equals(input.scopeType())) {
            if (!"RS_APPROVALS".equals(context.resourceSetKey())
                    || input.workflowId() != null
                    || input.workflowKey() != null && !input.workflowKey().isBlank()) {
                throw PolicyAutomationRejected.invalid("ALL delegation must use the root Approval scope.");
            }
            return new WorkflowBinding(null, null);
        }
        if (!"WORKFLOW".equals(input.scopeType()) || input.workflowId() == null) {
            throw PolicyAutomationRejected.invalid("WORKFLOW delegation requires an immutable workflow identity.");
        }
        List<WorkflowBinding> values = jdbc.query("""
                SELECT workflow_id,workflow_key FROM apr_workflow_definitions
                 WHERE tenant_id=:tenant AND management_resource_set_key=:scope
                   AND workflow_id=:workflowId AND lifecycle_state='PUBLISHED'
                """, base(context).addValue("workflowId", input.workflowId()),
                (row, number) -> new WorkflowBinding(row.getObject(1, UUID.class), row.getString(2)));
        if (values.size() != 1 || input.workflowKey() != null
                && !values.getFirst().key().equals(input.workflowKey())) {
            throw PolicyAutomationRejected.conflict("Delegation workflow binding is unavailable or changed.");
        }
        return values.getFirst();
    }

    private MapSqlParameterSource command(Context context, DelegationDraft input,
            DelegationIdentity delegator, DelegationIdentity delegate, WorkflowBinding workflow) {
        return base(context).addValue("id", input.delegationId())
                .addValue("delegator", delegator.userId()).addValue("delegate", delegate.userId())
                .addValue("delegatePerson", delegate.personPublicId()).addValue("delegateName", delegate.displayName())
                .addValue("delegateEmail", delegate.email()).addValue("roles", canonical.json(input.delegatedRoleCodes()))
                .addValue("scopeType", input.scopeType()).addValue("workflowId", workflow.id())
                .addValue("workflowKey", workflow.key()).addValue("starts", Timestamp.from(input.startsAt()))
                .addValue("ends", Timestamp.from(input.endsAt())).addValue("reason", input.reason().trim())
                .addValue("expected", input.expectedVersion());
    }

    private void lockPair(Context context, long first, long second) {
        String key = context.tenantId() + ":delegation-pair:" + Math.min(first, second) + ':' + Math.max(first, second);
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey,0))",
                base(context).addValue("lockKey", key), Object.class);
    }

    private void requireNoConflict(Context context, DelegationDraft input, UUID excluded) {
        String excludedPredicate = excluded == null
                ? ""
                : " AND existing.delegation_id<>:excluded";
        MapSqlParameterSource parameters = base(context)
                .addValue("starts", Timestamp.from(input.startsAt()))
                .addValue("ends", Timestamp.from(input.endsAt()))
                .addValue("delegator", input.delegatorUserId())
                .addValue("delegate", input.delegateUserId())
                .addValue("scopeType", input.scopeType())
                .addValue("workflowId", input.workflowId());
        if (excluded != null) {
            parameters.addValue("excluded", excluded);
        }
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM apr_delegations existing
                 WHERE existing.tenant_id=:tenant AND existing.lifecycle_state='ACTIVE'
                """ + excludedPredicate + """
                   AND existing.starts_at<:ends AND existing.ends_at>:starts
                   AND (
                       (existing.delegator_user_id=:delegator
                        AND (existing.scope_type='ALL' OR :scopeType='ALL'
                             OR existing.workflow_id=:workflowId))
                       OR (existing.delegator_user_id=:delegate
                           AND existing.delegate_user_id=:delegator)
                       OR existing.delegate_user_id=:delegator
                       OR existing.delegator_user_id=:delegate)
                """, parameters, Long.class);
        if (count != null && count > 0) {
            throw PolicyAutomationRejected.conflict("Delegation overlaps or creates a reverse/sub-delegation chain.");
        }
    }

    private void event(Context context, UUID delegationId, String action,
            long expected, long resulting, Object command, Map<String, Object> before,
            Map<String, Object> after, String reason) {
        int inserted = jdbc.update("""
                INSERT INTO apr_delegation_governance_events(event_id,tenant_id,resource_set_key,
                    delegation_id,action,expected_version,resulting_version,idempotency_key,
                    command_sha256,before_state,after_state,reason,actor_user_id)
                VALUES(:event,:tenant,:scope,:delegation,:action,:expected,:resulting,:key,
                    :sha,CAST(:before AS jsonb),CAST(:after AS jsonb),:reason,:actor)
                """, base(context).addValue("event", UUID.randomUUID()).addValue("delegation", delegationId)
                .addValue("action", action).addValue("expected", expected).addValue("resulting", resulting)
                .addValue("sha", canonical.fingerprint(command)).addValue("before", canonical.json(before))
                .addValue("after", canonical.json(after)).addValue("reason", reason.trim()));
        if (inserted != 1) throw PolicyAutomationRejected.unavailable("Delegation audit evidence could not be recorded.");
    }

    private Map<String, Object> state(DelegationView value) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("delegationId", value.delegationId().toString());
        result.put("delegatorUserId", value.delegatorUserId());
        result.put("delegateUserId", value.delegateUserId());
        result.put("delegatedRoleCodes", value.delegatedRoleCodes());
        result.put("scopeType", value.scopeType());
        if (value.workflowId() != null) result.put("workflowId", value.workflowId().toString());
        if (value.workflowKey() != null) result.put("workflowKey", value.workflowKey());
        result.put("startsAt", value.startsAt().toString());
        result.put("endsAt", value.endsAt().toString());
        result.put("lifecycleState", value.lifecycleState());
        result.put("version", value.version());
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(String value) {
        Object parsed = canonical.read(value, Object.class);
        if (!(parsed instanceof Map<?, ?> map)) {
            throw PolicyAutomationRejected.unavailable("Delegation audit evidence is malformed.");
        }
        return (Map<String, Object>) map;
    }

    private List<DelegationView> query(
            Context context, UUID delegationId, boolean lock, Instant now) {
        String target = delegationId == null ? "" : " AND delegation.delegation_id=:id";
        String order = lock ? "" : " ORDER BY delegation.starts_at DESC,delegation.delegation_id";
        String locking = lock ? " FOR UPDATE OF delegation" : "";
        return jdbc.query("""
                SELECT delegation.delegation_id,delegation.delegator_user_id,
                       delegation.delegate_user_id,delegation.delegate_person_public_id,
                       delegation.delegate_display_name,delegation.delegate_email,
                       delegation.delegated_role_codes::text,delegation.scope_type,
                       delegation.workflow_id,delegation.workflow_key,
                       delegation.starts_at,delegation.ends_at,delegation.lifecycle_state,
                       delegation.reason,delegation.version,
                       workflow.management_resource_set_key AS workflow_scope,
                       EXISTS (
                           SELECT 1 FROM apr_delegations chain
                            WHERE chain.tenant_id=delegation.tenant_id
                              AND chain.delegation_id<>delegation.delegation_id
                              AND chain.lifecycle_state='ACTIVE'
                              AND chain.starts_at<delegation.ends_at
                              AND chain.ends_at>delegation.starts_at
                              AND (chain.delegator_user_id=delegation.delegate_user_id
                                   OR chain.delegate_user_id=delegation.delegator_user_id)
                       ) AS has_delegation_chain,
                       latest.review_id,latest.delegation_version,latest.disposition,
                       latest.compliance_state,latest.reviewed_at
                  FROM apr_delegations delegation
                  LEFT JOIN apr_workflow_definitions workflow
                    ON workflow.tenant_id=delegation.tenant_id
                   AND workflow.workflow_id=delegation.workflow_id
                  LEFT JOIN LATERAL (
                       SELECT review.review_id,review.delegation_version,
                              review.disposition,review.compliance_state,review.reviewed_at
                         FROM apr_delegation_governance_reviews review
                        WHERE review.tenant_id=delegation.tenant_id
                          AND review.resource_set_key=:scope
                          AND review.delegation_id=delegation.delegation_id
                        ORDER BY review.reviewed_at DESC,review.review_id LIMIT 1
                 ) latest ON TRUE
                 WHERE delegation.tenant_id=:tenant
                   AND delegation.management_resource_set_key=:scope
                   AND ((delegation.scope_type='ALL' AND :scope='RS_APPROVALS')
                     OR (delegation.scope_type='WORKFLOW'
                         AND workflow.management_resource_set_key=:scope
                         AND workflow.workflow_key=delegation.workflow_key))
                """ + target + order + locking,
                base(context).addValue("id", delegationId),
                (row, number) -> delegation(row, context.resourceSetKey(), now));
    }

    private DelegationView delegation(
            ResultSet row, String resourceSetKey, Instant now) throws SQLException {
        UUID delegationId = row.getObject("delegation_id", UUID.class);
        long delegator = row.getLong("delegator_user_id");
        long delegate = row.getLong("delegate_user_id");
        String scopeType = row.getString("scope_type");
        UUID workflowId = row.getObject("workflow_id", UUID.class);
        String workflowKey = row.getString("workflow_key");
        String workflowScope = row.getString("workflow_scope");
        Instant startsAt = row.getTimestamp("starts_at").toInstant();
        Instant endsAt = row.getTimestamp("ends_at").toInstant();
        String lifecycle = row.getString("lifecycle_state");
        List<?> rawRoles = canonical.read(row.getString("delegated_role_codes"), List.class);
        List<String> roles = rawRoles.stream().filter(String.class::isInstance)
                .map(String.class::cast).toList();

        DelegationTruth identity = delegator == delegate
                ? DelegationTruth.VIOLATED : DelegationTruth.VERIFIED;
        boolean exactScope = "ALL".equals(scopeType)
                ? "RS_APPROVALS".equals(resourceSetKey)
                    && workflowId == null && workflowKey == null
                : "WORKFLOW".equals(scopeType) && workflowId != null
                    && workflowKey != null && resourceSetKey.equals(workflowScope);
        DelegationTruth scope = exactScope
                ? DelegationTruth.VERIFIED : DelegationTruth.VIOLATED;
        boolean exactWindow = endsAt.isAfter(startsAt)
                && Duration.between(startsAt, endsAt).compareTo(MAXIMUM_WINDOW) <= 0
                && !("ACTIVE".equals(lifecycle) && !now.isBefore(endsAt));
        DelegationTruth window = exactWindow
                ? DelegationTruth.VERIFIED : DelegationTruth.VIOLATED;
        DelegationTruth noSubDelegation = row.getBoolean("has_delegation_chain")
                ? DelegationTruth.VIOLATED : DelegationTruth.VERIFIED;
        boolean roleSnapshotValid = !rawRoles.isEmpty() && roles.size() == rawRoles.size()
                && roles.stream().allMatch(role -> role.matches("[A-Z][A-Z0-9_]{1,79}"));
        DelegationTruth roleSnapshot = roleSnapshotValid
                ? DelegationTruth.VERIFIED : DelegationTruth.VIOLATED;
        DelegationTruth roleSod = DelegationTruth.NOT_VERIFIED;
        List<String> findings = findings(identity, scope, window, noSubDelegation,
                roleSnapshot, roleSod);
        return new DelegationView(delegationId, delegator, delegate,
                row.getObject("delegate_person_public_id", UUID.class),
                row.getString("delegate_display_name"), row.getString("delegate_email"),
                List.copyOf(roles), scopeType, workflowId, workflowKey, startsAt, endsAt,
                lifecycle, row.getString("reason"), row.getLong("version"),
                effective(lifecycle, startsAt, endsAt, now), scope, window,
                noSubDelegation, identity, roleSnapshot, roleSod, findings,
                latestReview(row));
    }

    private List<String> findings(
            DelegationTruth identity,
            DelegationTruth scope,
            DelegationTruth window,
            DelegationTruth noSubDelegation,
            DelegationTruth roleSnapshot,
            DelegationTruth roleSod) {
        List<String> findings = new ArrayList<>();
        if (identity == DelegationTruth.VIOLATED) findings.add("IDENTITY_NOT_SEPARATED");
        if (scope == DelegationTruth.VIOLATED) findings.add("SCOPE_BINDING_INVALID");
        if (window == DelegationTruth.VIOLATED) findings.add("TIME_WINDOW_INVALID_OR_STALE");
        if (noSubDelegation == DelegationTruth.VIOLATED) findings.add("SUB_DELEGATION_PRESENT");
        if (roleSnapshot == DelegationTruth.VIOLATED) findings.add("ROLE_SNAPSHOT_INVALID");
        if (roleSod == DelegationTruth.NOT_VERIFIED) {
            findings.add("ROLE_SOD_EVIDENCE_UNAVAILABLE");
        }
        return List.copyOf(findings);
    }

    private DelegationReviewSummary latestReview(ResultSet row) throws SQLException {
        UUID reviewId = row.getObject("review_id", UUID.class);
        return reviewId == null ? null : new DelegationReviewSummary(reviewId,
                row.getLong("delegation_version"),
                DelegationReviewDisposition.valueOf(row.getString("disposition")),
                DelegationComplianceState.valueOf(row.getString("compliance_state")),
                row.getTimestamp("reviewed_at").toInstant());
    }

    private DelegationReviewView review(ResultSet row) throws SQLException {
        @SuppressWarnings("unchecked")
        List<String> findings = canonical.read(row.getString("findings"), List.class);
        return new DelegationReviewView(row.getObject("review_id", UUID.class),
                row.getObject("delegation_id", UUID.class), row.getLong("delegation_version"),
                DelegationReviewDisposition.valueOf(row.getString("disposition")),
                DelegationComplianceState.valueOf(row.getString("compliance_state")),
                DelegationTruth.valueOf(row.getString("scope_binding_truth")),
                DelegationTruth.valueOf(row.getString("time_window_truth")),
                DelegationTruth.valueOf(row.getString("no_sub_delegation_truth")),
                DelegationTruth.valueOf(row.getString("identity_separation_truth")),
                DelegationTruth.valueOf(row.getString("role_snapshot_truth")),
                DelegationTruth.valueOf(row.getString("role_sod_truth")),
                List.copyOf(findings), row.getString("review_evidence_sha256"),
                row.getLong("reviewed_by"), row.getTimestamp("reviewed_at").toInstant());
    }

    private DelegationComplianceState compliance(DelegationView value) {
        boolean violation = value.scopeBindingTruth() == DelegationTruth.VIOLATED
                || value.timeWindowTruth() == DelegationTruth.VIOLATED
                || value.noSubDelegationTruth() == DelegationTruth.VIOLATED
                || value.identitySeparationTruth() == DelegationTruth.VIOLATED
                || value.roleSnapshotTruth() == DelegationTruth.VIOLATED;
        return violation ? DelegationComplianceState.BLOCKED
                : DelegationComplianceState.EVIDENCE_REQUIRED;
    }

    private DelegationEffectiveState effective(
            String lifecycle, Instant startsAt, Instant endsAt, Instant now) {
        if ("REVOKED".equals(lifecycle)) return DelegationEffectiveState.REVOKED;
        if (now.isBefore(startsAt)) return DelegationEffectiveState.SCHEDULED;
        if ("EXPIRED".equals(lifecycle) || !now.isBefore(endsAt)) {
            return DelegationEffectiveState.EXPIRED;
        }
        return DelegationEffectiveState.IN_EFFECT;
    }

    private void validate(DelegationReviewCommand input) {
        if (input == null || input.reviewId() == null || input.disposition() == null
                || input.expectedDelegationVersion() < 0
                || input.reviewEvidenceSha256() == null
                || !input.reviewEvidenceSha256().matches("[0-9a-f]{64}")) {
            throw PolicyAutomationRejected.invalid(
                    "Delegation governance review command is invalid.");
        }
    }

    private void requireActiveTenant(Context context) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM apr_tenants
                 WHERE tenant_id=:tenant AND lifecycle_state='ACTIVE'
                """, base(context), Integer.class);
        if (count == null || count != 1) throw PolicyAutomationRejected.forbidden(
                "Approval tenant is not active.");
    }

    private MapSqlParameterSource base(Context context) {
        return new MapSqlParameterSource().addValue("tenant", context.tenantId())
                .addValue("scope", context.resourceSetKey())
                .addValue("actor", context.actorUserId())
                .addValue("key", context.idempotencyKey());
    }

    private record WorkflowBinding(UUID id, String key) { }
}
