package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.AccessReviewDtos;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Non-admin access-review boundary for assigned Work items. It resolves opaque
 * references server-side and rechecks assignment, campaign validity and object
 * version for every read and mutation.
 */
@Service
public class AccessReviewWorkService {

    private final JdbcTemplate jdbc;
    private final IdentityAuditService auditService;
    private final AccessReviewWorkItemOutboxPublisher events;
    private final Clock clock;

    @Autowired
    public AccessReviewWorkService(
            JdbcTemplate jdbc,
            IdentityAuditService auditService,
            AccessReviewWorkItemOutboxPublisher events) {
        this(jdbc, auditService, events, Clock.systemUTC());
    }

    AccessReviewWorkService(
            JdbcTemplate jdbc,
            IdentityAuditService auditService,
            AccessReviewWorkItemOutboxPublisher events,
            Clock clock) {
        this.jdbc = jdbc;
        this.auditService = auditService;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AccessReviewDtos.WorkItemDetail detail(
            Long tenantId,
            Long actorId,
            UUID workItemRef) {
        return workDetail(requireAccessible(tenantId, actorId, workItemRef, null, false));
    }

    @Transactional
    public AccessReviewDtos.WorkItemDetail decide(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID workItemRef,
            AccessReviewDtos.DecisionRequest request) {
        WorkEvidence before = requireAccessible(
                tenantId, actorId, workItemRef, request.version(), true);
        String remediationState = "APPROVE".equals(request.decision())
                ? "NOT_REQUIRED"
                : "DIRECT".equals(before.accessSourceType())
                        ? "PENDING"
                        : "MANUAL_REQUIRED";
        Instant now = clock.instant();
        int updated = jdbc.update("""
                UPDATE com_access_review_items item
                   SET decision = ?, decision_reason = ?, decided_by = ?,
                       decided_at = ?, remediation_state = ?,
                       version = item.version + 1, updated_at = ?
                  FROM com_access_review_campaigns campaign
                 WHERE item.tenant_id = ?
                   AND item.work_item_ref = ?
                   AND item.reviewer_user_id = ?
                   AND item.reviewer_assignment_state = 'ACTIVE'
                   AND item.decision = 'PENDING'
                   AND item.version = ?
                   AND campaign.tenant_id = item.tenant_id
                   AND campaign.access_review_campaign_id = item.access_review_campaign_id
                   AND campaign.reviewer_strategy = 'NAMED_REVIEWER'
                   AND campaign.lifecycle_state = 'ACTIVE'
                   AND campaign.due_at > ?
                """,
                request.decision(),
                request.reason().strip(),
                actorId,
                Timestamp.from(now),
                remediationState,
                Timestamp.from(now),
                tenantId,
                workItemRef,
                actorId,
                request.version(),
                Timestamp.from(now));
        if (updated != 1) throw conflict();

        long resultingVersion = request.version() + 1L;
        events.decided(
                tenantId,
                workItemRef,
                correlationId,
                request.decision(),
                resultingVersion);
        auditService.success(
                tenantId,
                actorId,
                "access-review.work-item.decided",
                "ACCESS_REVIEW_WORK_ITEM",
                workItemRef.toString(),
                correlationId,
                Map.of("decision", "PENDING", "version", request.version()),
                Map.of(
                        "decision", request.decision(),
                        "remediationState", remediationState,
                        "version", resultingVersion));
        return detail(tenantId, actorId, workItemRef);
    }

    public PredicateEvidence predicateEvidence(
            Long tenantId,
            Long actorId,
            UUID workItemRef,
            Long expectedVersion,
            boolean mutation) {
        Optional<WorkEvidence> value = find(tenantId, workItemRef);
        if (value.isEmpty()) return PredicateEvidence.notAvailable();
        return evaluateEvidence(value.get(), actorId, expectedVersion, mutation);
    }

    private PredicateEvidence evaluateEvidence(
            WorkEvidence evidence,
            Long actorId,
            Long expectedVersion,
            boolean mutation) {
        Instant now = clock.instant();
        if (!"NAMED_REVIEWER".equals(evidence.reviewerStrategy())
                || !actorId.equals(evidence.reviewerUserId())
                || !"ACTIVE".equals(evidence.assignmentState())
                || !"ACTIVE".equals(evidence.campaignState())
                || evidence.dueAt() == null
                || !evidence.dueAt().isAfter(now)) {
            return PredicateEvidence.notAvailable();
        }
        if (expectedVersion != null && evidence.version() != expectedVersion) {
            return new PredicateEvidence(
                    PredicateState.STALE_VERSION, evidence.dueAt(), evidence.version());
        }
        if (mutation && !"PENDING".equals(evidence.decision())) {
            return new PredicateEvidence(
                    PredicateState.ALREADY_DECIDED, evidence.dueAt(), evidence.version());
        }
        return new PredicateEvidence(
                PredicateState.ALLOWED, evidence.dueAt(), evidence.version());
    }

    private WorkEvidence requireAccessible(
            Long tenantId,
            Long actorId,
            UUID workItemRef,
            Long expectedVersion,
            boolean mutation) {
        Optional<WorkEvidence> value = find(tenantId, workItemRef);
        if (value.isEmpty()) throw unavailable();
        WorkEvidence evidence = value.get();
        PredicateEvidence result = evaluateEvidence(
                evidence, actorId, expectedVersion, mutation);
        if (result.state() == PredicateState.STALE_VERSION
                || result.state() == PredicateState.ALREADY_DECIDED) {
            throw conflict();
        }
        if (result.state() != PredicateState.ALLOWED) {
            throw unavailable();
        }
        return evidence;
    }

    private Optional<WorkEvidence> find(Long tenantId, UUID workItemRef) {
        return jdbc.query(SELECT_WORK_EVIDENCE + """
                 WHERE item.tenant_id = ? AND item.work_item_ref = ?
                """, this::evidence, tenantId, workItemRef).stream().findFirst();
    }

    private AccessReviewDtos.WorkItemDetail workDetail(WorkEvidence value) {
        return new AccessReviewDtos.WorkItemDetail(
                value.workItemRef(),
                value.campaignName(),
                value.dueAt(),
                value.subjectUserId(),
                value.subjectDisplayName(),
                value.subjectEmail(),
                value.roleId(),
                value.roleCode(),
                value.roleName(),
                value.accessSourceType(),
                value.sourceKey(),
                value.sourceDisplayName(),
                value.assignmentCreatedAt(),
                value.subjectLastSignInAt(),
                value.privileged(),
                value.recommendation(),
                value.recommendationReason(),
                value.decision(),
                value.decisionReason(),
                value.decidedAt(),
                value.remediationState(),
                value.version());
    }

    private WorkEvidence evidence(ResultSet result, int ignored) throws SQLException {
        return new WorkEvidence(
                result.getObject("work_item_ref", UUID.class),
                result.getString("campaign_name"),
                result.getString("reviewer_strategy"),
                nullableLong(result, "reviewer_user_id"),
                result.getString("reviewer_assignment_state"),
                result.getString("campaign_state"),
                instant(result, "due_at"),
                result.getLong("subject_user_id"),
                result.getString("subject_display_name"),
                result.getString("subject_email"),
                result.getLong("role_id"),
                result.getString("role_code"),
                result.getString("role_name"),
                result.getString("access_source_type"),
                result.getString("source_key"),
                result.getString("source_display_name"),
                instant(result, "assignment_created_at"),
                instant(result, "subject_last_sign_in_at"),
                result.getBoolean("privileged"),
                result.getString("recommendation"),
                result.getString("recommendation_reason"),
                result.getString("decision"),
                result.getString("decision_reason"),
                instant(result, "decided_at"),
                result.getString("remediation_state"),
                result.getLong("version"));
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private BaseException unavailable() {
        return new BaseException(
                ErrorCode.RESOURCE_NOT_AVAILABLE,
                "RESOURCE_NOT_AVAILABLE");
    }

    private BaseException conflict() {
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "The access review item changed after it was loaded. Refresh and try again.");
    }

    private static final String SELECT_WORK_EVIDENCE = """
            SELECT item.work_item_ref, campaign.name AS campaign_name,
                   campaign.reviewer_strategy, item.reviewer_user_id,
                   item.reviewer_assignment_state,
                   campaign.lifecycle_state AS campaign_state, campaign.due_at,
                   item.subject_user_id, subject.display_name AS subject_display_name,
                   subject.email AS subject_email, item.role_id,
                   role.code AS role_code, role.name AS role_name,
                   item.access_source_type, item.source_key, item.source_display_name,
                   item.assignment_created_at, item.subject_last_sign_in_at,
                   item.privileged, item.recommendation, item.recommendation_reason,
                   item.decision, item.decision_reason, item.decided_at,
                   item.remediation_state, item.version
              FROM com_access_review_items item
              JOIN com_access_review_campaigns campaign
                ON campaign.tenant_id = item.tenant_id
               AND campaign.access_review_campaign_id = item.access_review_campaign_id
              JOIN com_users subject
                ON subject.tenant_id = item.tenant_id
               AND subject.user_id = item.subject_user_id
              JOIN com_roles role
                ON role.tenant_id = item.tenant_id
               AND role.role_id = item.role_id
            """;

    public enum PredicateState {
        ALLOWED,
        NOT_AVAILABLE,
        STALE_VERSION,
        ALREADY_DECIDED
    }

    public record PredicateEvidence(PredicateState state, Instant dueAt, long version) {
        static PredicateEvidence notAvailable() {
            return new PredicateEvidence(PredicateState.NOT_AVAILABLE, null, -1L);
        }
    }

    private record WorkEvidence(
            UUID workItemRef,
            String campaignName,
            String reviewerStrategy,
            Long reviewerUserId,
            String assignmentState,
            String campaignState,
            Instant dueAt,
            Long subjectUserId,
            String subjectDisplayName,
            String subjectEmail,
            Long roleId,
            String roleCode,
            String roleName,
            String accessSourceType,
            String sourceKey,
            String sourceDisplayName,
            Instant assignmentCreatedAt,
            Instant subjectLastSignInAt,
            boolean privileged,
            String recommendation,
            String recommendationReason,
            String decision,
            String decisionReason,
            Instant decidedAt,
            String remediationState,
            long version) {
    }
}
