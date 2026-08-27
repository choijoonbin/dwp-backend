package com.dwp.services.auth.service;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.AccessReviewDtos;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class AccessReviewService {

    private static final String CAMPAIGN_SELECT = """
            SELECT campaign.access_review_campaign_id, campaign.name, campaign.description,
                   campaign.scope_type, campaign.scope_ref, campaign.reviewer_strategy,
                   campaign.reviewer_user_id, campaign.lifecycle_state, campaign.due_at,
                   campaign.activated_at, campaign.completed_at, campaign.version,
                   COUNT(item.access_review_item_id) AS total_items,
                   COUNT(*) FILTER (WHERE item.decision = 'PENDING') AS pending_items,
                   COUNT(*) FILTER (WHERE item.decision = 'APPROVE') AS approved_items,
                   COUNT(*) FILTER (WHERE item.decision = 'REVOKE') AS revoked_items,
                   COUNT(*) FILTER (WHERE item.remediation_state = 'MANUAL_REQUIRED')
                       AS manual_remediation_items
              FROM com_access_review_campaigns campaign
              LEFT JOIN com_access_review_items item
                ON item.access_review_campaign_id = campaign.access_review_campaign_id
            """;

    private final JdbcTemplate jdbc;
    private final IdentityAuditService auditService;
    private final AccessReviewWorkItemOutboxPublisher workItemEvents;

    public AccessReviewService(JdbcTemplate jdbc, IdentityAuditService auditService) {
        this(jdbc, auditService, null);
    }

    @Autowired
    public AccessReviewService(
            JdbcTemplate jdbc,
            IdentityAuditService auditService,
            AccessReviewWorkItemOutboxPublisher workItemEvents) {
        this.jdbc = jdbc;
        this.auditService = auditService;
        this.workItemEvents = workItemEvents;
    }

    @Transactional(readOnly = true)
    public List<AccessReviewDtos.CampaignSummary> campaigns(
            Long tenantId, Long actorId, boolean tenantAdmin) {
        String visibility = tenantAdmin
                ? "campaign.tenant_id = ?"
                : "campaign.tenant_id = ? AND campaign.reviewer_user_id = ?";
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (!tenantAdmin) args.add(actorId);
        return jdbc.query(
                CAMPAIGN_SELECT + " WHERE " + visibility + "\n" + """
                        GROUP BY campaign.access_review_campaign_id
                        ORDER BY CASE campaign.lifecycle_state
                                   WHEN 'ACTIVE' THEN 0 WHEN 'DRAFT' THEN 1 ELSE 2 END,
                                 campaign.due_at, campaign.created_at DESC
                        """,
                this::campaignSummary,
                args.toArray());
    }

    @Transactional(readOnly = true)
    public AccessReviewDtos.CampaignItems campaignItems(
            Long tenantId,
            Long actorId,
            boolean tenantAdmin,
            UUID campaignId) {
        AccessReviewDtos.CampaignSummary campaign = requireCampaign(tenantId, campaignId);
        requireReviewer(campaign, actorId, tenantAdmin);
        List<AccessReviewDtos.ItemSummary> items = jdbc.query("""
                SELECT item.access_review_item_id, item.subject_user_id,
                       subject.display_name, subject.email, item.role_id,
                       role.code, role.name, item.access_source_type,
                       item.access_source_id, item.source_key, item.source_display_name,
                       item.assignment_created_at, item.subject_last_sign_in_at,
                       item.privileged, item.recommendation, item.recommendation_reason,
                       item.reviewer_user_id, item.decision,
                       item.decision_reason, item.decided_by, item.decided_at,
                       item.remediation_state, item.version
                  FROM com_access_review_items item
                  JOIN com_users subject
                    ON subject.tenant_id = item.tenant_id
                   AND subject.user_id = item.subject_user_id
                  JOIN com_roles role
                    ON role.tenant_id = item.tenant_id
                   AND role.role_id = item.role_id
                 WHERE item.tenant_id = ?
                   AND item.access_review_campaign_id = ?
                 ORDER BY CASE item.decision WHEN 'PENDING' THEN 0 ELSE 1 END,
                          subject.display_name, role.code, item.access_source_type
                """, this::itemSummary, tenantId, campaignId);
        return new AccessReviewDtos.CampaignItems(campaign, items);
    }

    @Transactional
    public AccessReviewDtos.CampaignSummary createCampaign(
            Long tenantId,
            Long actorId,
            String correlationId,
            AccessReviewDtos.CreateCampaignRequest request) {
        validateScope(tenantId, request.scopeType(), request.scopeRef());
        validateReviewer(
                tenantId, request.reviewerStrategy(), request.reviewerUserId());
        UUID campaignId = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO com_access_review_campaigns (
                        access_review_campaign_id, tenant_id, name, description,
                        scope_type, scope_ref, reviewer_strategy, reviewer_user_id,
                        lifecycle_state, due_at, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?)
                    """,
                    campaignId,
                    tenantId,
                    request.name().strip(),
                    trimToNull(request.description()),
                    request.scopeType(),
                    request.scopeRef(),
                    request.reviewerStrategy(),
                    request.reviewerUserId(),
                    Timestamp.from(request.dueAt()),
                    actorId,
                    actorId);
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The access review campaign configuration is invalid.",
                    exception);
        }
        auditService.success(
                tenantId, actorId, "access-review.campaign.created", "ACCESS_REVIEW_CAMPAIGN",
                campaignId.toString(), correlationId, null,
                campaignSnapshot(request.scopeType(), request.scopeRef(), "DRAFT", 0L));
        return requireCampaign(tenantId, campaignId);
    }

    @Transactional
    public AccessReviewDtos.CampaignSummary activate(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID campaignId,
            long expectedVersion) {
        AccessReviewDtos.CampaignSummary campaign = requireCampaign(tenantId, campaignId);
        requireState(campaign, "DRAFT", expectedVersion);
        int direct = insertDirectItems(tenantId, campaign);
        int inherited = insertGroupItems(tenantId, campaign);
        if (direct + inherited == 0) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "No active access assignments match this campaign scope.");
        }
        int updated = jdbc.update("""
                UPDATE com_access_review_campaigns
                   SET lifecycle_state = 'ACTIVE', activated_at = CURRENT_TIMESTAMP,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND access_review_campaign_id = ?
                   AND lifecycle_state = 'DRAFT' AND version = ?
                """, actorId, tenantId, campaignId, expectedVersion);
        if (updated != 1) throw conflict();
        if (workItemEvents != null && "NAMED_REVIEWER".equals(campaign.reviewerStrategy())) {
            workItemEvents.assignedForCampaign(tenantId, campaignId, correlationId);
        }
        auditService.success(
                tenantId, actorId, "access-review.campaign.activated", "ACCESS_REVIEW_CAMPAIGN",
                campaignId.toString(), correlationId,
                campaignSnapshot(campaign.scopeType(), campaign.scopeRef(), "DRAFT", expectedVersion),
                Map.of("state", "ACTIVE", "directItems", direct, "inheritedItems", inherited));
        return requireCampaign(tenantId, campaignId);
    }

    @Transactional
    public AccessReviewDtos.ItemSummary decide(
            Long tenantId,
            Long actorId,
            boolean tenantAdmin,
            String correlationId,
            UUID campaignId,
            UUID itemId,
            AccessReviewDtos.DecisionRequest request) {
        AccessReviewDtos.CampaignSummary campaign = requireCampaign(tenantId, campaignId);
        requireReviewer(campaign, actorId, tenantAdmin);
        if (!"ACTIVE".equals(campaign.lifecycleState())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The review campaign is not active.");
        }
        String remediationState = "APPROVE".equals(request.decision())
                ? "NOT_REQUIRED"
                : sourceType(tenantId, campaignId, itemId).equals("DIRECT")
                        ? "PENDING" : "MANUAL_REQUIRED";
        int updated = jdbc.update("""
                UPDATE com_access_review_items
                   SET decision = ?, decision_reason = ?, decided_by = ?,
                       decided_at = CURRENT_TIMESTAMP, remediation_state = ?,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND access_review_campaign_id = ?
                   AND access_review_item_id = ? AND decision = 'PENDING' AND version = ?
                """,
                request.decision(), request.reason().strip(), actorId, remediationState,
                tenantId, campaignId, itemId, request.version());
        if (updated != 1) throw conflict();
        if (workItemEvents != null && "NAMED_REVIEWER".equals(campaign.reviewerStrategy())) {
            workItemEvents.decidedByInternalId(
                    tenantId, campaignId, itemId, correlationId, request.decision(),
                    request.version() + 1L);
        }
        auditService.success(
                tenantId, actorId, "access-review.item.decided", "ACCESS_REVIEW_ITEM",
                itemId.toString(), correlationId,
                Map.of("decision", "PENDING", "version", request.version()),
                Map.of("decision", request.decision(), "remediationState", remediationState));
        return requireItem(tenantId, campaignId, itemId);
    }

    /**
     * Revokes only the named-reviewer relationship. It does not infer or remove any
     * tenant-admin role and is intended for campaign lifecycle orchestration.
     */
    @Transactional
    public void revokeNamedReviewerAssignment(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID campaignId,
            UUID itemId,
            long expectedVersion) {
        int updated = jdbc.update("""
                UPDATE com_access_review_items item
                   SET reviewer_assignment_state = 'REVOKED',
                       version = item.version + 1,
                       updated_at = CURRENT_TIMESTAMP
                  FROM com_access_review_campaigns campaign
                 WHERE item.tenant_id = ?
                   AND item.access_review_campaign_id = ?
                   AND item.access_review_item_id = ?
                   AND item.version = ?
                   AND item.reviewer_assignment_state = 'ACTIVE'
                   AND item.reviewer_user_id IS NOT NULL
                   AND campaign.tenant_id = item.tenant_id
                   AND campaign.access_review_campaign_id = item.access_review_campaign_id
                   AND campaign.reviewer_strategy = 'NAMED_REVIEWER'
                """, tenantId, campaignId, itemId, expectedVersion);
        if (updated != 1) throw conflict();
        AccessReviewWorkRef ref = workRef(tenantId, campaignId, itemId);
        if (workItemEvents != null) {
            workItemEvents.revoked(
                    tenantId, ref.workItemRef(), correlationId, ref.version());
        }
        auditService.success(
                tenantId, actorId, "access-review.item.assignment-revoked",
                "ACCESS_REVIEW_ITEM", itemId.toString(), correlationId,
                Map.of("assignmentState", "ACTIVE", "version", expectedVersion),
                Map.of("assignmentState", "REVOKED", "version", ref.version()));
    }

    @Transactional
    public AccessReviewDtos.CampaignSummary complete(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID campaignId,
            long expectedVersion) {
        AccessReviewDtos.CampaignSummary campaign = requireCampaign(tenantId, campaignId);
        requireState(campaign, "ACTIVE", expectedVersion);
        Long pending = jdbc.queryForObject("""
                SELECT COUNT(*) FROM com_access_review_items
                 WHERE tenant_id = ? AND access_review_campaign_id = ?
                   AND decision = 'PENDING'
                """, Long.class, tenantId, campaignId);
        if (pending != null && pending > 0) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Every access item must be decided before completion.");
        }

        Set<Long> affectedUsers = new LinkedHashSet<>();
        List<DirectRevoke> directRevokes = jdbc.query("""
                SELECT access_review_item_id, subject_user_id, role_id, access_source_id
                  FROM com_access_review_items
                 WHERE tenant_id = ? AND access_review_campaign_id = ?
                   AND decision = 'REVOKE' AND access_source_type = 'DIRECT'
                   AND remediation_state = 'PENDING'
                """, (rs, rowNum) -> new DirectRevoke(
                        rs.getObject("access_review_item_id", UUID.class),
                        rs.getLong("subject_user_id"), rs.getLong("role_id"),
                        rs.getLong("access_source_id")), tenantId, campaignId);
        for (DirectRevoke revoke : directRevokes) {
            jdbc.update("""
                    DELETE FROM com_role_members
                     WHERE role_member_id = ? AND tenant_id = ?
                       AND user_id = ? AND role_id = ?
                    """, revoke.sourceId(), tenantId, revoke.userId(), revoke.roleId());
            jdbc.update("""
                    UPDATE com_access_review_items
                       SET remediation_state = 'APPLIED', version = version + 1,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE access_review_item_id = ?
                    """, revoke.itemId());
            jdbc.update("""
                    INSERT INTO sys_access_remediation_tasks (
                        access_review_item_id, tenant_id, action_type, lifecycle_state,
                        reason, completed_at, completed_by)
                    VALUES (?, ?, 'REMOVE_DIRECT_ROLE', 'COMPLETED',
                            'Direct role removed by completed access review.',
                            CURRENT_TIMESTAMP, ?)
                    ON CONFLICT (access_review_item_id) DO NOTHING
                    """, revoke.itemId(), tenantId, actorId);
            affectedUsers.add(revoke.userId());
        }

        jdbc.update("""
                INSERT INTO sys_access_remediation_tasks (
                    access_review_item_id, tenant_id, action_type, lifecycle_state, reason)
                SELECT access_review_item_id, tenant_id, 'REVIEW_GROUP_MEMBERSHIP', 'OPEN',
                       'Inherited access requires group-owner review to avoid collateral revocation.'
                  FROM com_access_review_items
                 WHERE tenant_id = ? AND access_review_campaign_id = ?
                   AND decision = 'REVOKE' AND access_source_type = 'GROUP'
                ON CONFLICT (access_review_item_id) DO NOTHING
                """, tenantId, campaignId);

        for (Long userId : affectedUsers) invalidateIdentity(tenantId, userId, actorId);
        int updated = jdbc.update("""
                UPDATE com_access_review_campaigns
                   SET lifecycle_state = 'COMPLETED', completed_at = CURRENT_TIMESTAMP,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND access_review_campaign_id = ?
                   AND lifecycle_state = 'ACTIVE' AND version = ?
                """, actorId, tenantId, campaignId, expectedVersion);
        if (updated != 1) throw conflict();
        auditService.success(
                tenantId, actorId, "access-review.campaign.completed", "ACCESS_REVIEW_CAMPAIGN",
                campaignId.toString(), correlationId,
                Map.of("state", "ACTIVE", "version", expectedVersion),
                Map.of("state", "COMPLETED", "directRevocations", directRevokes.size(),
                        "affectedUsers", affectedUsers.size()));
        return requireCampaign(tenantId, campaignId);
    }

    private int insertDirectItems(Long tenantId, AccessReviewDtos.CampaignSummary campaign) {
        if ("GROUP".equals(campaign.scopeType())) return 0;
        return jdbc.update("""
                INSERT INTO com_access_review_items (
                    access_review_campaign_id, tenant_id, subject_user_id, role_id,
                    access_source_type, access_source_id, source_key, source_display_name,
                    assignment_created_at, subject_last_sign_in_at, privileged,
                    recommendation, recommendation_reason, reviewer_user_id)
                SELECT ?, member.tenant_id, member.user_id, member.role_id,
                       'DIRECT', member.role_member_id, NULL, NULL, member.created_at,
                       session_evidence.last_sign_in_at, role.privileged,
                       CASE
                           WHEN role.privileged THEN 'REVIEW'
                           WHEN session_evidence.last_sign_in_at IS NULL THEN 'REVIEW'
                           WHEN session_evidence.last_sign_in_at
                                < CURRENT_TIMESTAMP - INTERVAL '90 days' THEN 'REVIEW'
                           ELSE 'KEEP'
                       END,
                       CASE
                           WHEN role.privileged THEN 'PRIVILEGED_ROLE'
                           WHEN session_evidence.last_sign_in_at IS NULL THEN 'NEVER_SIGNED_IN'
                           WHEN session_evidence.last_sign_in_at
                                < CURRENT_TIMESTAMP - INTERVAL '90 days' THEN 'INACTIVE_90_DAYS'
                           ELSE 'RECENT_ACTIVITY'
                       END,
                       ?
                  FROM com_role_members member
                  JOIN com_users subject
                    ON subject.tenant_id = member.tenant_id AND subject.user_id = member.user_id
                  JOIN com_roles role
                    ON role.tenant_id = member.tenant_id AND role.role_id = member.role_id
                  LEFT JOIN LATERAL (
                       SELECT MAX(session.session_started_at) AS last_sign_in_at
                         FROM sys_auth_sessions session
                        WHERE session.tenant_id = member.tenant_id
                          AND session.user_id = member.user_id
                  ) session_evidence ON TRUE
                 WHERE member.tenant_id = ? AND subject.status IN ('ACTIVE', 'INVITED')
                   AND role.status = 'ACTIVE'
                   AND (? = 'TENANT' OR member.role_id = ?)
                ON CONFLICT (access_review_campaign_id, subject_user_id, role_id,
                             access_source_type, access_source_id) DO NOTHING
                """, campaign.campaignId(), campaign.reviewerUserId(), tenantId,
                campaign.scopeType(), campaign.scopeRef());
    }

    private int insertGroupItems(Long tenantId, AccessReviewDtos.CampaignSummary campaign) {
        return jdbc.update("""
                INSERT INTO com_access_review_items (
                    access_review_campaign_id, tenant_id, subject_user_id, role_id,
                    access_source_type, access_source_id, source_key, source_display_name,
                    assignment_created_at, subject_last_sign_in_at, privileged,
                    recommendation, recommendation_reason, reviewer_user_id)
                SELECT ?, assignment.tenant_id, membership.user_id, assignment.role_id,
                       'GROUP', assignment.group_role_assignment_id,
                       access_group.group_key, access_group.display_name,
                       assignment.created_at, session_evidence.last_sign_in_at, role.privileged,
                       CASE
                           WHEN role.privileged THEN 'REVIEW'
                           WHEN session_evidence.last_sign_in_at IS NULL THEN 'REVIEW'
                           WHEN session_evidence.last_sign_in_at
                                < CURRENT_TIMESTAMP - INTERVAL '90 days' THEN 'REVIEW'
                           ELSE 'KEEP'
                       END,
                       CASE
                           WHEN role.privileged THEN 'PRIVILEGED_ROLE'
                           WHEN session_evidence.last_sign_in_at IS NULL THEN 'NEVER_SIGNED_IN'
                           WHEN session_evidence.last_sign_in_at
                                < CURRENT_TIMESTAMP - INTERVAL '90 days' THEN 'INACTIVE_90_DAYS'
                           ELSE 'RECENT_ACTIVITY'
                       END,
                       ?
                  FROM com_group_role_assignments assignment
                  JOIN com_group_members membership
                    ON membership.tenant_id = assignment.tenant_id
                   AND membership.group_id = assignment.group_id
                  JOIN com_groups access_group
                    ON access_group.tenant_id = assignment.tenant_id
                   AND access_group.group_id = assignment.group_id
                  JOIN com_users subject
                    ON subject.tenant_id = membership.tenant_id
                   AND subject.user_id = membership.user_id
                  JOIN com_roles role
                    ON role.tenant_id = assignment.tenant_id
                   AND role.role_id = assignment.role_id
                  LEFT JOIN LATERAL (
                       SELECT MAX(session.session_started_at) AS last_sign_in_at
                         FROM sys_auth_sessions session
                        WHERE session.tenant_id = membership.tenant_id
                          AND session.user_id = membership.user_id
                  ) session_evidence ON TRUE
                 WHERE assignment.tenant_id = ?
                   AND assignment.lifecycle_state = 'ACTIVE'
                   AND assignment.assignment_type = 'ACTIVE'
                   AND access_group.status = 'ACTIVE' AND role.status = 'ACTIVE'
                   AND subject.status IN ('ACTIVE', 'INVITED')
                   AND (assignment.valid_from IS NULL OR assignment.valid_from <= CURRENT_TIMESTAMP)
                   AND (assignment.valid_to IS NULL OR assignment.valid_to > CURRENT_TIMESTAMP)
                   AND (? = 'TENANT'
                        OR (? = 'ROLE' AND assignment.role_id = ?)
                        OR (? = 'GROUP' AND assignment.group_id = ?))
                ON CONFLICT (access_review_campaign_id, subject_user_id, role_id,
                             access_source_type, access_source_id) DO NOTHING
                """, campaign.campaignId(), campaign.reviewerUserId(), tenantId,
                campaign.scopeType(), campaign.scopeType(), campaign.scopeRef(),
                campaign.scopeType(), campaign.scopeRef());
    }

    private void validateScope(Long tenantId, String scopeType, Long scopeRef) {
        if (("TENANT".equals(scopeType) && scopeRef != null)
                || (!"TENANT".equals(scopeType) && scopeRef == null)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "The review scope is invalid.");
        }
        if ("ROLE".equals(scopeType)) requireExists(
                "SELECT COUNT(*) FROM com_roles WHERE tenant_id = ? AND role_id = ? AND status = 'ACTIVE'",
                tenantId, scopeRef, "The selected role is unavailable.");
        if ("GROUP".equals(scopeType)) requireExists(
                "SELECT COUNT(*) FROM com_groups WHERE tenant_id = ? AND group_id = ? AND status = 'ACTIVE'",
                tenantId, scopeRef, "The selected group is unavailable.");
    }

    private void validateReviewer(Long tenantId, String strategy, Long reviewerUserId) {
        if (("TENANT_ADMIN".equals(strategy) && reviewerUserId != null)
                || ("NAMED_REVIEWER".equals(strategy) && reviewerUserId == null)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "The reviewer strategy is invalid.");
        }
        if (reviewerUserId != null) requireExists(
                "SELECT COUNT(*) FROM com_users WHERE tenant_id = ? AND user_id = ? "
                        + "AND status = 'ACTIVE' AND identity_plane = 'TENANT'",
                tenantId, reviewerUserId, "The named reviewer is unavailable.");
    }

    private void requireExists(String sql, Long tenantId, Long id, String message) {
        Long count = jdbc.queryForObject(sql, Long.class, tenantId, id);
        if (count == null || count == 0) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private AccessReviewDtos.CampaignSummary requireCampaign(Long tenantId, UUID campaignId) {
        List<AccessReviewDtos.CampaignSummary> values = jdbc.query(
                CAMPAIGN_SELECT + """
                         WHERE campaign.tenant_id = ?
                           AND campaign.access_review_campaign_id = ?
                         GROUP BY campaign.access_review_campaign_id
                        """, this::campaignSummary, tenantId, campaignId);
        if (values.isEmpty()) throw new BaseException(ErrorCode.NOT_FOUND);
        return values.get(0);
    }

    private AccessReviewDtos.ItemSummary requireItem(Long tenantId, UUID campaignId, UUID itemId) {
        List<AccessReviewDtos.ItemSummary> values = jdbc.query("""
                SELECT item.access_review_item_id, item.subject_user_id,
                       subject.display_name, subject.email, item.role_id,
                       role.code, role.name, item.access_source_type,
                       item.access_source_id, item.source_key, item.source_display_name,
                       item.assignment_created_at, item.subject_last_sign_in_at,
                       item.privileged, item.recommendation, item.recommendation_reason,
                       item.reviewer_user_id, item.decision,
                       item.decision_reason, item.decided_by, item.decided_at,
                       item.remediation_state, item.version
                  FROM com_access_review_items item
                  JOIN com_users subject
                    ON subject.tenant_id = item.tenant_id AND subject.user_id = item.subject_user_id
                  JOIN com_roles role
                    ON role.tenant_id = item.tenant_id AND role.role_id = item.role_id
                 WHERE item.tenant_id = ? AND item.access_review_campaign_id = ?
                   AND item.access_review_item_id = ?
                """, this::itemSummary, tenantId, campaignId, itemId);
        if (values.isEmpty()) throw new BaseException(ErrorCode.NOT_FOUND);
        return values.get(0);
    }

    private AccessReviewWorkRef workRef(Long tenantId, UUID campaignId, UUID itemId) {
        return jdbc.query("""
                SELECT work_item_ref, version
                  FROM com_access_review_items
                 WHERE tenant_id = ? AND access_review_campaign_id = ?
                   AND access_review_item_id = ?
                """, (result, ignored) -> new AccessReviewWorkRef(
                        result.getObject("work_item_ref", UUID.class),
                        result.getLong("version")), tenantId, campaignId, itemId)
                .stream().findFirst()
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private String sourceType(Long tenantId, UUID campaignId, UUID itemId) {
        List<String> values = jdbc.query(
                "SELECT access_source_type FROM com_access_review_items "
                        + "WHERE tenant_id = ? AND access_review_campaign_id = ? "
                        + "AND access_review_item_id = ?",
                (rs, rowNum) -> rs.getString(1), tenantId, campaignId, itemId);
        if (values.isEmpty()) throw new BaseException(ErrorCode.NOT_FOUND);
        return values.get(0);
    }

    private void requireReviewer(
            AccessReviewDtos.CampaignSummary campaign, Long actorId, boolean tenantAdmin) {
        if (tenantAdmin) return;
        if (!"NAMED_REVIEWER".equals(campaign.reviewerStrategy())
                || !Objects.equals(actorId, campaign.reviewerUserId())) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
    }

    private void requireState(
            AccessReviewDtos.CampaignSummary campaign, String state, long expectedVersion) {
        if (!state.equals(campaign.lifecycleState())) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The review campaign state is invalid.");
        }
        if (campaign.version() != expectedVersion) throw conflict();
    }

    private void invalidateIdentity(Long tenantId, Long userId, Long actorId) {
        jdbc.update("""
                UPDATE sys_auth_sessions
                   SET revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP),
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND user_id = ? AND revoked_at IS NULL
                """, actorId, tenantId, userId);
        jdbc.update("""
                UPDATE com_users
                   SET access_revision = access_revision + 1,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND user_id = ?
                """, actorId, tenantId, userId);
    }

    private AccessReviewDtos.CampaignSummary campaignSummary(ResultSet rs, int rowNum)
            throws SQLException {
        return new AccessReviewDtos.CampaignSummary(
                rs.getObject("access_review_campaign_id", UUID.class),
                rs.getString("name"), rs.getString("description"),
                rs.getString("scope_type"), nullableLong(rs, "scope_ref"),
                rs.getString("reviewer_strategy"), nullableLong(rs, "reviewer_user_id"),
                rs.getString("lifecycle_state"), instant(rs, "due_at"),
                instant(rs, "activated_at"), instant(rs, "completed_at"),
                rs.getLong("total_items"), rs.getLong("pending_items"),
                rs.getLong("approved_items"), rs.getLong("revoked_items"),
                rs.getLong("manual_remediation_items"), rs.getLong("version"));
    }

    private AccessReviewDtos.ItemSummary itemSummary(ResultSet rs, int rowNum) throws SQLException {
        return new AccessReviewDtos.ItemSummary(
                rs.getObject("access_review_item_id", UUID.class),
                rs.getLong("subject_user_id"), rs.getString("display_name"), rs.getString("email"),
                rs.getLong("role_id"), rs.getString("code"), rs.getString("name"),
                rs.getString("access_source_type"), rs.getLong("access_source_id"),
                rs.getString("source_key"), rs.getString("source_display_name"),
                instant(rs, "assignment_created_at"), instant(rs, "subject_last_sign_in_at"),
                rs.getBoolean("privileged"), rs.getString("recommendation"),
                rs.getString("recommendation_reason"),
                nullableLong(rs, "reviewer_user_id"), rs.getString("decision"),
                rs.getString("decision_reason"), nullableLong(rs, "decided_by"),
                instant(rs, "decided_at"), rs.getString("remediation_state"),
                rs.getLong("version"));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Map<String, Object> campaignSnapshot(
            String scopeType, Long scopeRef, String state, long version) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("scopeType", scopeType);
        if (scopeRef != null) snapshot.put("scopeRef", scopeRef);
        snapshot.put("state", state);
        snapshot.put("version", version);
        return snapshot;
    }

    private static BaseException conflict() {
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "The access review changed after it was loaded. Refresh and try again.");
    }

    private record DirectRevoke(UUID itemId, Long userId, Long roleId, Long sourceId) {
    }

    private record AccessReviewWorkRef(UUID workItemRef, long version) {
    }
}
