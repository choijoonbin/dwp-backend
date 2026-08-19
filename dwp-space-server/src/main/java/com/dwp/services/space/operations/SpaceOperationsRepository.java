package com.dwp.services.space.operations;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Repository
public class SpaceOperationsRepository {

    private static final Map<String, List<String>> ROLE_PERMISSIONS = Map.of(
            "GUEST", List.of("VIEW"),
            "VIEWER", List.of("VIEW"),
            "CONTRIBUTOR", List.of("VIEW", "CREATE"),
            "EDITOR", List.of("VIEW", "CREATE", "UPDATE"),
            "MODERATOR", List.of("VIEW", "CREATE", "UPDATE", "APPROVE"),
            "OWNER", List.of("VIEW", "CREATE", "UPDATE", "APPROVE", "MANAGE"));

    private final NamedParameterJdbcTemplate jdbc;

    public SpaceOperationsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Long> activeTenantIds() {
        return jdbc.queryForList("""
                SELECT tenant_id FROM spc_tenants
                 WHERE lifecycle_state = 'ACTIVE'
                 ORDER BY tenant_id
                """, new MapSqlParameterSource(), Long.class);
    }

    public PlanningResult plan(long tenantId) {
        int expired = expireMemberships(tenantId);
        generateLifecycleReviews(tenantId);
        List<MembershipProjection> memberships = memberships(tenantId);
        Map<SyncKey, SyncState> existing = new HashMap<>();
        for (SyncState state : syncStates(tenantId)) {
            existing.put(new SyncKey(state.membershipId(), state.permissionCode()), state);
        }

        Set<SyncKey> desired = new HashSet<>();
        int planned = 0;
        for (MembershipProjection membership : memberships) {
            if (!membership.effective()) continue;
            for (String permission : permissionsForRole(membership.memberRole())) {
                SyncKey key = new SyncKey(membership.membershipId(), permission);
                desired.add(key);
                planned += upsertGrant(membership, permission);
            }
        }
        for (Map.Entry<SyncKey, SyncState> entry : existing.entrySet()) {
            if (!desired.contains(entry.getKey())) {
                planned += markRevoked(entry.getValue());
            }
        }
        return new PlanningResult(planned, expired);
    }

    public List<SyncItem> claim(int limit, String workerId) {
        return jdbc.query("""
                WITH candidates AS (
                    SELECT sync_item_id
                      FROM spc_entitlement_sync_items
                     WHERE (delivery_state IN ('PENDING', 'RETRY')
                            AND next_attempt_at <= CURRENT_TIMESTAMP)
                        OR (delivery_state = 'IN_PROGRESS'
                            AND locked_until < CURRENT_TIMESTAMP)
                     ORDER BY next_attempt_at, created_at
                     FOR UPDATE SKIP LOCKED
                     LIMIT :limit
                )
                UPDATE spc_entitlement_sync_items item
                   SET delivery_state = 'IN_PROGRESS',
                       attempt_count = item.attempt_count + 1,
                       last_attempt_at = CURRENT_TIMESTAMP,
                       locked_by = :workerId,
                       locked_until = CURRENT_TIMESTAMP + INTERVAL '2 minutes',
                       version = item.version + 1,
                       updated_at = CURRENT_TIMESTAMP
                  FROM candidates
                 WHERE item.sync_item_id = candidates.sync_item_id
                RETURNING item.*
                """, new MapSqlParameterSource()
                .addValue("limit", Math.max(1, Math.min(limit, 100)))
                .addValue("workerId", workerId), this::syncItem);
    }

    public void markSucceeded(
            UUID syncItemId,
            String externalGrantId,
            String externalState) {
        jdbc.update("""
                UPDATE spc_entitlement_sync_items
                   SET delivery_state = 'SUCCEEDED',
                       external_grant_id = :grantId,
                       external_state = :externalState,
                       synchronized_at = CURRENT_TIMESTAMP,
                       locked_by = NULL, locked_until = NULL,
                       last_error = NULL,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE sync_item_id = :syncItemId
                   AND delivery_state = 'IN_PROGRESS'
                """, new MapSqlParameterSource()
                .addValue("syncItemId", syncItemId)
                .addValue("grantId", externalGrantId)
                .addValue("externalState", externalState));
    }

    public void markFailed(SyncItem item, String error) {
        boolean dead = item.attemptCount() >= 8;
        long delaySeconds = Math.min(900L, 5L * (1L << Math.min(item.attemptCount(), 7)));
        jdbc.update("""
                UPDATE spc_entitlement_sync_items
                   SET delivery_state = :state,
                       next_attempt_at = :nextAttempt,
                       last_error = :error,
                       locked_by = NULL, locked_until = NULL,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE sync_item_id = :syncItemId
                   AND delivery_state = 'IN_PROGRESS'
                """, new MapSqlParameterSource()
                .addValue("syncItemId", item.syncItemId())
                .addValue("state", dead ? "DEAD" : "RETRY")
                .addValue("nextAttempt", Instant.now().plusSeconds(delaySeconds))
                .addValue("error", truncate(error, 1000)));
    }

    public boolean retry(UUID syncItemId, long tenantId) {
        return jdbc.update("""
                UPDATE spc_entitlement_sync_items
                   SET delivery_state = 'PENDING', attempt_count = 0,
                       next_attempt_at = CURRENT_TIMESTAMP,
                       locked_by = NULL, locked_until = NULL,
                       last_error = NULL, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId AND sync_item_id = :syncItemId
                   AND delivery_state IN ('RETRY', 'DEAD')
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("syncItemId", syncItemId)) == 1;
    }

    public List<SyncItem> syncItems(long tenantId, int limit) {
        return jdbc.query("""
                SELECT * FROM spc_entitlement_sync_items
                 WHERE tenant_id = :tenantId
                 ORDER BY CASE delivery_state WHEN 'DEAD' THEN 0 WHEN 'RETRY' THEN 1
                                WHEN 'PENDING' THEN 2 WHEN 'IN_PROGRESS' THEN 3 ELSE 4 END,
                          updated_at DESC
                 LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("limit", Math.max(1, Math.min(limit, 100))), this::syncItem);
    }

    private int expireMemberships(long tenantId) {
        return jdbc.update("""
                UPDATE spc_memberships
                   SET lifecycle_state = 'EXPIRED', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId AND lifecycle_state = 'ACTIVE'
                   AND valid_until IS NOT NULL AND valid_until <= CURRENT_TIMESTAMP
                """, new MapSqlParameterSource("tenantId", tenantId));
    }

    private void generateLifecycleReviews(long tenantId) {
        jdbc.update("""
                UPDATE spc_lifecycle_reviews
                   SET status = 'OVERDUE', updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId AND status = 'OPEN'
                   AND due_at < CURRENT_TIMESTAMP
                """, new MapSqlParameterSource("tenantId", tenantId));
        jdbc.update("""
                INSERT INTO spc_lifecycle_reviews (
                    lifecycle_review_id, tenant_id, space_id, review_type,
                    due_at, status, evidence)
                SELECT gen_random_uuid(), space.tenant_id, space.space_id, 'ACTIVITY',
                       CURRENT_TIMESTAMP + INTERVAL '14 days', 'OPEN',
                       jsonb_build_object('lastActivityAt', space.last_activity_at,
                                          'thresholdDays', 90)
                  FROM spc_spaces space
                 WHERE space.tenant_id = :tenantId
                   AND space.lifecycle_state = 'ACTIVE'
                   AND space.last_activity_at < CURRENT_TIMESTAMP - INTERVAL '90 days'
                ON CONFLICT (tenant_id, space_id, review_type)
                    WHERE status IN ('OPEN', 'OVERDUE') DO NOTHING
                """, new MapSqlParameterSource("tenantId", tenantId));
        jdbc.update("""
                INSERT INTO spc_lifecycle_reviews (
                    lifecycle_review_id, tenant_id, space_id, review_type,
                    due_at, status, evidence)
                SELECT gen_random_uuid(), space.tenant_id, space.space_id, 'ACCESS',
                       CURRENT_TIMESTAMP + INTERVAL '30 days', 'OPEN',
                       jsonb_build_object('classification', space.data_classification,
                                          'reason', 'PERIODIC_CERTIFICATION')
                  FROM spc_spaces space
                 WHERE space.tenant_id = :tenantId
                   AND space.lifecycle_state = 'ACTIVE'
                   AND space.data_classification IN ('CONFIDENTIAL', 'RESTRICTED')
                   AND NOT EXISTS (
                       SELECT 1 FROM spc_lifecycle_reviews previous
                        WHERE previous.tenant_id = space.tenant_id
                          AND previous.space_id = space.space_id
                          AND previous.review_type = 'ACCESS'
                          AND previous.status = 'COMPLETED'
                          AND previous.decided_at > CURRENT_TIMESTAMP - INTERVAL '90 days')
                ON CONFLICT (tenant_id, space_id, review_type)
                    WHERE status IN ('OPEN', 'OVERDUE') DO NOTHING
                """, new MapSqlParameterSource("tenantId", tenantId));
    }

    private List<MembershipProjection> memberships(long tenantId) {
        return jdbc.query("""
                SELECT membership.membership_id, membership.tenant_id,
                       membership.space_id, membership.principal_type,
                       membership.principal_ref, membership.member_role,
                       membership.lifecycle_state, membership.valid_from,
                       membership.valid_until,
                       COALESCE(membership.approved_by, space.updated_by,
                                space.created_by, 1) AS actor_user_id,
                       space.space_key, space.name_en, space.lifecycle_state AS space_state
                  FROM spc_memberships membership
                  JOIN spc_spaces space
                    ON space.tenant_id = membership.tenant_id
                   AND space.space_id = membership.space_id
                 WHERE membership.tenant_id = :tenantId
                """, new MapSqlParameterSource("tenantId", tenantId), (rs, row) ->
                new MembershipProjection(
                        rs.getLong("tenant_id"), rs.getObject("space_id", UUID.class),
                        rs.getString("space_key"), rs.getString("name_en"),
                        rs.getObject("membership_id", UUID.class),
                        rs.getString("principal_type"), rs.getString("principal_ref"),
                        rs.getString("member_role"), rs.getString("lifecycle_state"),
                        rs.getString("space_state"), instant(rs, "valid_from"),
                        instant(rs, "valid_until"), rs.getLong("actor_user_id")));
    }

    private List<SyncState> syncStates(long tenantId) {
        return jdbc.query("""
                SELECT sync_item_id, membership_id, permission_code,
                       desired_state, delivery_state
                  FROM spc_entitlement_sync_items
                 WHERE tenant_id = :tenantId
                """, new MapSqlParameterSource("tenantId", tenantId), (rs, row) ->
                new SyncState(
                        rs.getObject("sync_item_id", UUID.class),
                        rs.getObject("membership_id", UUID.class),
                        rs.getString("permission_code"),
                        rs.getString("desired_state"),
                        rs.getString("delivery_state")));
    }

    private int upsertGrant(MembershipProjection membership, String permission) {
        String resourceKey = "SPACE." + membership.spaceKey().toUpperCase(Locale.ROOT);
        String sourceRef = "space:" + membership.membershipId() + ":" + permission.toLowerCase(Locale.ROOT);
        return jdbc.update("""
                INSERT INTO spc_entitlement_sync_items (
                    sync_item_id, tenant_id, space_id, membership_id,
                    principal_type, principal_ref, resource_key, resource_name,
                    permission_code, actor_user_id, valid_until,
                    desired_state, delivery_state, source_ref)
                VALUES (:syncItemId, :tenantId, :spaceId, :membershipId,
                    :principalType, :principalRef, :resourceKey, :resourceName,
                    :permissionCode, :actorUserId, :validUntil,
                    'GRANTED', 'PENDING', :sourceRef)
                ON CONFLICT (tenant_id, membership_id, permission_code)
                DO UPDATE SET
                    principal_type = EXCLUDED.principal_type,
                    principal_ref = EXCLUDED.principal_ref,
                    resource_key = EXCLUDED.resource_key,
                    resource_name = EXCLUDED.resource_name,
                    actor_user_id = EXCLUDED.actor_user_id,
                    valid_until = EXCLUDED.valid_until,
                    desired_state = 'GRANTED',
                    delivery_state = 'PENDING',
                    attempt_count = 0,
                    next_attempt_at = CURRENT_TIMESTAMP,
                    locked_by = NULL, locked_until = NULL,
                    last_error = NULL,
                    version = spc_entitlement_sync_items.version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE spc_entitlement_sync_items.desired_state <> 'GRANTED'
                   OR spc_entitlement_sync_items.principal_type IS DISTINCT FROM EXCLUDED.principal_type
                   OR spc_entitlement_sync_items.principal_ref IS DISTINCT FROM EXCLUDED.principal_ref
                   OR spc_entitlement_sync_items.resource_key IS DISTINCT FROM EXCLUDED.resource_key
                   OR spc_entitlement_sync_items.actor_user_id IS DISTINCT FROM EXCLUDED.actor_user_id
                   OR spc_entitlement_sync_items.valid_until IS DISTINCT FROM EXCLUDED.valid_until
                """, new MapSqlParameterSource()
                .addValue("syncItemId", UUID.randomUUID())
                .addValue("tenantId", membership.tenantId())
                .addValue("spaceId", membership.spaceId())
                .addValue("membershipId", membership.membershipId())
                .addValue("principalType", membership.principalType())
                .addValue("principalRef", membership.principalRef())
                .addValue("resourceKey", resourceKey)
                .addValue("resourceName", membership.spaceName())
                .addValue("permissionCode", permission)
                .addValue("actorUserId", membership.actorUserId())
                .addValue("validUntil", membership.validUntil())
                .addValue("sourceRef", sourceRef));
    }

    private int markRevoked(SyncState state) {
        if ("REVOKED".equals(state.desiredState())) return 0;
        return jdbc.update("""
                UPDATE spc_entitlement_sync_items
                   SET desired_state = 'REVOKED', delivery_state = 'PENDING',
                       attempt_count = 0, next_attempt_at = CURRENT_TIMESTAMP,
                       locked_by = NULL, locked_until = NULL,
                       last_error = NULL, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE sync_item_id = :syncItemId
                """, new MapSqlParameterSource("syncItemId", state.syncItemId()));
    }

    private SyncItem syncItem(ResultSet rs, int row) throws SQLException {
        return new SyncItem(
                rs.getObject("sync_item_id", UUID.class), rs.getLong("tenant_id"),
                rs.getObject("space_id", UUID.class),
                rs.getObject("membership_id", UUID.class),
                rs.getString("principal_type"), rs.getString("principal_ref"),
                rs.getString("resource_key"), rs.getString("resource_name"),
                rs.getString("permission_code"), rs.getLong("actor_user_id"),
                instant(rs, "valid_until"), rs.getString("desired_state"),
                rs.getString("delivery_state"), rs.getString("source_ref"),
                rs.getInt("attempt_count"), instant(rs, "next_attempt_at"),
                rs.getString("external_grant_id"), rs.getString("external_state"),
                rs.getString("last_error"), instant(rs, "last_attempt_at"),
                instant(rs, "synchronized_at"));
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private String truncate(String value, int max) {
        String safe = value == null || value.isBlank() ? "Unknown delivery failure." : value;
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    static List<String> permissionsForRole(String role) {
        if (role == null) return List.of();
        return ROLE_PERMISSIONS.getOrDefault(role.toUpperCase(Locale.ROOT), List.of());
    }

    public record PlanningResult(int plannedCount, int expiredCount) {
    }

    public record SyncItem(
            UUID syncItemId,
            long tenantId,
            UUID spaceId,
            UUID membershipId,
            String principalType,
            String principalRef,
            String resourceKey,
            String resourceName,
            String permissionCode,
            long actorUserId,
            Instant validUntil,
            String desiredState,
            String deliveryState,
            String sourceRef,
            int attemptCount,
            Instant nextAttemptAt,
            String externalGrantId,
            String externalState,
            String lastError,
            Instant lastAttemptAt,
            Instant synchronizedAt) {
    }

    private record MembershipProjection(
            long tenantId,
            UUID spaceId,
            String spaceKey,
            String spaceName,
            UUID membershipId,
            String principalType,
            String principalRef,
            String memberRole,
            String lifecycleState,
            String spaceState,
            Instant validFrom,
            Instant validUntil,
            long actorUserId) {

        boolean effective() {
            Instant now = Instant.now();
            return "ACTIVE".equals(lifecycleState)
                    && "ACTIVE".equals(spaceState)
                    && (validFrom == null || !validFrom.isAfter(now))
                    && (validUntil == null || validUntil.isAfter(now));
        }
    }

    private record SyncKey(UUID membershipId, String permissionCode) {
    }

    private record SyncState(
            UUID syncItemId,
            UUID membershipId,
            String permissionCode,
            String desiredState,
            String deliveryState) {
    }

}
