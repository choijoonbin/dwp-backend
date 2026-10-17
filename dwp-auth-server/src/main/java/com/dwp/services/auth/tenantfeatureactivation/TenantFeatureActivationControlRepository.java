package com.dwp.services.auth.tenantfeatureactivation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/** CAS persistence boundary for global feature activation and tenant enrollment workflows. */
@Repository
public class TenantFeatureActivationControlRepository {

    private final JdbcTemplate jdbc;

    public TenantFeatureActivationControlRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long lockActivePolicy(String featureKey) {
        Long version = jdbc.queryForObject("""
                SELECT policy_version
                  FROM sys_product_feature_activation_policies
                 WHERE feature_key = ? AND lifecycle_state = 'PUBLISHED'
                 ORDER BY policy_version DESC
                 LIMIT 1
                 FOR UPDATE
                """, Long.class, featureKey);
        if (version == null) throw new IllegalStateException("Unknown active feature policy.");
        return version;
    }

    public TenantFeatureActivationDtos.GlobalChange insertGlobal(
            String featureKey,
            long policyVersion,
            long targetBundleVersion,
            String justification,
            String actorRef,
            Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO auth_product_feature_activation_changes (
                    activation_change_id, feature_key, policy_version,
                    target_bundle_version, justification, requested_by,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, featureKey, policyVersion, targetBundleVersion,
                justification, actorRef, timestamp(now), timestamp(now));
        return requireGlobal(id);
    }

    public TenantFeatureActivationDtos.GlobalChange requireGlobal(UUID id) {
        return jdbc.query("""
                SELECT * FROM auth_product_feature_activation_changes
                 WHERE activation_change_id = ?
                """, this::global, id).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Feature activation change not found."));
    }

    public TenantFeatureActivationDtos.GlobalChange submitGlobal(
            UUID id, long expectedVersion, String actorRef, Instant now) {
        requireUpdated(jdbc.update("""
                UPDATE auth_product_feature_activation_changes
                   SET lifecycle_state = 'IN_REVIEW', submitted_at = ?,
                       version = version + 1, updated_at = ?
                 WHERE activation_change_id = ? AND lifecycle_state = 'DRAFT'
                   AND version = ? AND requested_by = ?
                """, timestamp(now), timestamp(now), id, expectedVersion, actorRef));
        return requireGlobal(id);
    }

    public TenantFeatureActivationDtos.GlobalChange approveGlobal(
            UUID id, long expectedVersion, String reviewerRef, Instant now) {
        requireUpdated(jdbc.update("""
                UPDATE auth_product_feature_activation_changes
                   SET lifecycle_state = 'APPROVED', approved_by = ?, approved_at = ?,
                       version = version + 1, updated_at = ?
                 WHERE activation_change_id = ? AND lifecycle_state = 'IN_REVIEW'
                   AND version = ? AND requested_by <> ?
                """, reviewerRef, timestamp(now), timestamp(now), id,
                expectedVersion, reviewerRef));
        return requireGlobal(id);
    }

    public TenantFeatureActivationDtos.GlobalChange activateGlobal(
            UUID id,
            long expectedVersion,
            String activatorRef,
            UUID receiptId,
            Instant now) {
        TenantFeatureActivationDtos.GlobalChange candidate = requireGlobal(id);
        jdbc.update("""
                UPDATE auth_product_feature_activation_changes
                   SET lifecycle_state = 'SUPERSEDED', superseded_by = ?,
                       version = version + 1, updated_at = ?
                 WHERE feature_key = ? AND lifecycle_state = 'ACTIVE'
                   AND activation_change_id <> ?
                """, id, timestamp(now), candidate.featureKey(), id);
        requireUpdated(jdbc.update("""
                UPDATE auth_product_feature_activation_changes
                   SET lifecycle_state = 'ACTIVE', activated_by = ?, activated_at = ?,
                       activation_receipt_id = ?, version = version + 1, updated_at = ?
                 WHERE activation_change_id = ? AND lifecycle_state = 'APPROVED'
                   AND version = ? AND requested_by <> ? AND approved_by <> ?
                """, activatorRef, timestamp(now), receiptId, timestamp(now), id,
                expectedVersion, activatorRef, activatorRef));
        return requireGlobal(id);
    }

    public TenantFeatureActivationDtos.GlobalChange revokeGlobal(
            UUID id, long expectedVersion, String actorRef, String reason, Instant now) {
        requireUpdated(jdbc.update("""
                UPDATE auth_product_feature_activation_changes
                   SET lifecycle_state = 'REVOKED', revoked_by = ?, revoked_at = ?,
                       revocation_reason = ?, version = version + 1, updated_at = ?
                 WHERE activation_change_id = ? AND lifecycle_state = 'ACTIVE'
                   AND version = ?
                """, actorRef, timestamp(now), reason, timestamp(now), id, expectedVersion));
        return requireGlobal(id);
    }

    public TenantFeatureActivationDtos.TenantEnrollment insertTenant(
            Long tenantId,
            String featureKey,
            long policyVersion,
            String justification,
            Long actorId,
            Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_tenant_feature_enrollment_changes (
                    enrollment_change_id, tenant_id, feature_key, policy_version,
                    justification, requested_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, tenantId, featureKey, policyVersion, justification,
                actorId, timestamp(now), timestamp(now));
        return requireTenant(id);
    }

    public TenantFeatureActivationDtos.TenantEnrollment requireTenant(UUID id) {
        return jdbc.query("""
                SELECT * FROM com_tenant_feature_enrollment_changes
                 WHERE enrollment_change_id = ?
                """, this::tenant, id).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Tenant enrollment change not found."));
    }

    public TenantFeatureActivationDtos.TenantEnrollment submitTenant(
            UUID id, long expectedVersion, Long actorId, Instant now) {
        requireUpdated(jdbc.update("""
                UPDATE com_tenant_feature_enrollment_changes
                   SET lifecycle_state = 'IN_REVIEW', submitted_at = ?,
                       version = version + 1, updated_at = ?
                 WHERE enrollment_change_id = ? AND lifecycle_state = 'DRAFT'
                   AND version = ? AND requested_by = ?
                """, timestamp(now), timestamp(now), id, expectedVersion, actorId));
        return requireTenant(id);
    }

    public TenantFeatureActivationDtos.TenantEnrollment approveTenant(
            UUID id, long expectedVersion, Long reviewerId, Instant now) {
        requireUpdated(jdbc.update("""
                UPDATE com_tenant_feature_enrollment_changes
                   SET lifecycle_state = 'APPROVED', approved_by = ?, approved_at = ?,
                       version = version + 1, updated_at = ?
                 WHERE enrollment_change_id = ? AND lifecycle_state = 'IN_REVIEW'
                   AND version = ? AND requested_by <> ?
                """, reviewerId, timestamp(now), timestamp(now), id,
                expectedVersion, reviewerId));
        return requireTenant(id);
    }

    public TenantFeatureActivationDtos.TenantEnrollment activateTenant(
            UUID id,
            long expectedVersion,
            Long activatorId,
            UUID receiptId,
            Instant now) {
        TenantFeatureActivationDtos.TenantEnrollment candidate = requireTenant(id);
        jdbc.update("""
                UPDATE com_tenant_feature_enrollment_changes
                   SET lifecycle_state = 'SUPERSEDED', superseded_by = ?,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND feature_key = ? AND lifecycle_state = 'ACTIVE'
                   AND enrollment_change_id <> ?
                """, id, timestamp(now), candidate.tenantId(), candidate.featureKey(), id);
        requireUpdated(jdbc.update("""
                UPDATE com_tenant_feature_enrollment_changes
                   SET lifecycle_state = 'ACTIVE', activated_by = ?, activated_at = ?,
                       activation_receipt_id = ?, version = version + 1, updated_at = ?
                 WHERE enrollment_change_id = ? AND lifecycle_state = 'APPROVED'
                   AND version = ? AND requested_by <> ? AND approved_by <> ?
                """, activatorId, timestamp(now), receiptId, timestamp(now), id,
                expectedVersion, activatorId, activatorId));
        return requireTenant(id);
    }

    public TenantFeatureActivationDtos.TenantEnrollment revokeTenant(
            UUID id, long expectedVersion, Long actorId, String reason, Instant now) {
        requireUpdated(jdbc.update("""
                UPDATE com_tenant_feature_enrollment_changes
                   SET lifecycle_state = 'REVOKED', revoked_by = ?, revoked_at = ?,
                       revocation_reason = ?, version = version + 1, updated_at = ?
                 WHERE enrollment_change_id = ? AND lifecycle_state = 'ACTIVE'
                   AND version = ?
                """, actorId, timestamp(now), reason, timestamp(now), id, expectedVersion));
        return requireTenant(id);
    }

    public void appendEvent(
            String aggregateType,
            UUID aggregateId,
            Long tenantId,
            String featureKey,
            String eventType,
            String actorRef,
            String correlationId,
            long resultingVersion,
            UUID receiptId) {
        jdbc.update("""
                INSERT INTO auth_product_feature_control_events (
                    event_id, aggregate_type, aggregate_id, tenant_id, feature_key,
                    event_type, actor_ref, correlation_id, resulting_version,
                    receipt_id, evidence)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '{}'::jsonb)
                """, UUID.randomUUID(), aggregateType, aggregateId, tenantId, featureKey,
                eventType, actorRef, correlationId, resultingVersion, receiptId);
    }

    private TenantFeatureActivationDtos.GlobalChange global(ResultSet result, int ignored)
            throws SQLException {
        return new TenantFeatureActivationDtos.GlobalChange(
                result.getObject("activation_change_id", UUID.class),
                result.getString("feature_key"), result.getLong("policy_version"),
                result.getLong("target_bundle_version"), result.getString("lifecycle_state"),
                result.getString("requested_by"), result.getString("approved_by"),
                result.getString("activated_by"),
                result.getObject("activation_receipt_id", UUID.class),
                result.getLong("version"), instant(result, "updated_at"));
    }

    private TenantFeatureActivationDtos.TenantEnrollment tenant(ResultSet result, int ignored)
            throws SQLException {
        return new TenantFeatureActivationDtos.TenantEnrollment(
                result.getObject("enrollment_change_id", UUID.class),
                result.getLong("tenant_id"), result.getString("feature_key"),
                result.getLong("policy_version"), result.getString("lifecycle_state"),
                result.getLong("requested_by"), nullableLong(result, "approved_by"),
                nullableLong(result, "activated_by"),
                result.getObject("activation_receipt_id", UUID.class),
                result.getLong("version"), instant(result, "updated_at"));
    }

    private static Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private static void requireUpdated(int updated) {
        if (updated != 1) {
            throw new IllegalStateException(
                    "Feature control version changed or the transition is no longer valid.");
        }
    }
}
