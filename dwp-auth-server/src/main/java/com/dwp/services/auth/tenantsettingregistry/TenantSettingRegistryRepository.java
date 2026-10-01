package com.dwp.services.auth.tenantsettingregistry;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TenantSettingRegistryRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public TenantSettingRegistryRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<OwnerRow> owners() {
        return jdbc.query("""
                SELECT owner_key, owner_version, setting_key, owner_service, value_type,
                       resolution_strategy, override_policy, activation_mode,
                       default_value::text, localized_label_key, lifecycle_state, created_at
                  FROM sys_tenant_setting_owner_registry
                 WHERE lifecycle_state = 'ACTIVE'
                 ORDER BY owner_service, setting_key
                """, this::owner);
    }

    public OwnerRow requireOwner(String settingKey) {
        return jdbc.query("""
                SELECT owner_key, owner_version, setting_key, owner_service, value_type,
                       resolution_strategy, override_policy, activation_mode,
                       default_value::text, localized_label_key, lifecycle_state, created_at
                  FROM sys_tenant_setting_owner_registry
                 WHERE setting_key = ? AND lifecycle_state = 'ACTIVE'
                """, this::owner, settingKey).stream().findFirst()
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND,
                        "The active tenant setting owner was not found."));
    }

    public long activePrincipalCount(Long tenantId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM com_users WHERE tenant_id = ? AND status = 'ACTIVE'",
                Long.class, tenantId);
        return count == null ? 0 : count;
    }

    public TenantSettingRegistryDtos.Change insert(
            Long tenantId,
            OwnerRow owner,
            String desiredState,
            JsonNode beforeValue,
            JsonNode proposedValue,
            String beforeHash,
            String proposedHash,
            long impactCount,
            Instant observedAt,
            String justification,
            Long actorId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_tenant_setting_override_changes (
                    change_id, tenant_id, setting_key, owner_key, owner_version,
                    desired_state, before_value, proposed_value, before_hash, proposed_hash,
                    impact_count, impact_coverage, impact_observed_at, justification,
                    requested_by, created_at, created_by, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?,
                        'INTERNAL_AUTH_ACTIVE_IDENTITIES', ?, ?, ?, CURRENT_TIMESTAMP, ?,
                        CURRENT_TIMESTAMP, ?)
                """, id, tenantId, owner.settingKey(), owner.ownerKey(), owner.ownerVersion(),
                desiredState, json(beforeValue), proposedValue == null ? null : json(proposedValue),
                beforeHash, proposedHash, impactCount, timestamp(observedAt),
                justification.trim(), actorId, actorId, actorId);
        return requireChange(tenantId, id);
    }

    public List<TenantSettingRegistryDtos.Change> changes(Long tenantId, int fetchLimit) {
        return jdbc.query("""
                SELECT * FROM com_tenant_setting_override_changes
                 WHERE tenant_id = ?
                 ORDER BY CASE WHEN lifecycle_state IN ('DRAFT', 'IN_REVIEW', 'APPROVED')
                                    THEN 0 ELSE 1 END,
                          updated_at DESC, created_at DESC, change_id DESC
                 LIMIT ?
                """, this::change, tenantId, fetchLimit);
    }

    public TenantSettingRegistryDtos.Change requireChange(Long tenantId, UUID id) {
        return jdbc.query("""
                SELECT * FROM com_tenant_setting_override_changes
                 WHERE tenant_id = ? AND change_id = ?
                """, this::change, tenantId, id).stream().findFirst()
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    public TenantSettingRegistryDtos.Change submit(
            Long tenantId, UUID id, long version, Long actorId, Instant now) {
        requireUpdated(jdbc.update("""
                UPDATE com_tenant_setting_override_changes
                   SET lifecycle_state = 'IN_REVIEW', submitted_at = ?, version = version + 1,
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND change_id = ?
                   AND lifecycle_state = 'DRAFT' AND version = ?
                """, timestamp(now), timestamp(now), actorId, tenantId, id, version));
        return requireChange(tenantId, id);
    }

    public TenantSettingRegistryDtos.Change decide(
            Long tenantId,
            UUID id,
            long version,
            String nextState,
            String reason,
            Long actorId,
            Instant now) {
        requireUpdated(jdbc.update("""
                UPDATE com_tenant_setting_override_changes
                   SET lifecycle_state = ?, approved_by = ?, approved_at = ?,
                       decision_reason = ?, version = version + 1,
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND change_id = ?
                   AND lifecycle_state = 'IN_REVIEW' AND version = ?
                   AND requested_by <> ?
                """, nextState, actorId, timestamp(now), reason.trim(), timestamp(now), actorId,
                tenantId, id, version, actorId));
        return requireChange(tenantId, id);
    }

    public TenantSettingRegistryDtos.Change publish(
            Long tenantId,
            UUID id,
            long version,
            Long actorId,
            Instant now,
            UUID receiptId) {
        requireUpdated(jdbc.update("""
                UPDATE com_tenant_setting_override_changes
                   SET lifecycle_state = 'PUBLISHED', published_by = ?, published_at = ?,
                       publish_receipt_id = ?, version = version + 1,
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND change_id = ?
                   AND lifecycle_state = 'APPROVED' AND version = ?
                   AND requested_by <> ? AND approved_by <> ?
                """, actorId, timestamp(now), receiptId, timestamp(now), actorId,
                tenantId, id, version, actorId, actorId));
        jdbc.update("""
                UPDATE com_tenant_setting_override_changes
                   SET lifecycle_state = 'SUPERSEDED', version = version + 1,
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ?
                   AND setting_key = (SELECT setting_key
                                        FROM com_tenant_setting_override_changes
                                       WHERE tenant_id = ? AND change_id = ?)
                   AND change_id <> ? AND lifecycle_state = 'PUBLISHED'
                """, timestamp(now), actorId, tenantId, tenantId, id, id);
        return requireChange(tenantId, id);
    }

    public Optional<TenantSettingRegistryDtos.Change> latestPublished(
            Long tenantId, String settingKey) {
        return jdbc.query("""
                SELECT * FROM com_tenant_setting_override_changes
                 WHERE tenant_id = ? AND setting_key = ? AND lifecycle_state = 'PUBLISHED'
                 ORDER BY published_at DESC LIMIT 1
                """, this::change, tenantId, settingKey).stream().findFirst();
    }

    public Optional<CanonicalAuthPolicyPublication> latestCanonicalAuthPolicyPublication(
            Long tenantId) {
        return jdbc.query("""
                SELECT proposed_state::text, published_at, publish_receipt_id
                  FROM com_tenant_setting_change_sets
                 WHERE tenant_id = ? AND owner_type = 'AUTH_POLICY'
                   AND owner_ref = 'tenant-authentication'
                   AND lifecycle_state = 'PUBLISHED'
                 ORDER BY published_at DESC, updated_at DESC LIMIT 1
                """, (result, ignored) -> new CanonicalAuthPolicyPublication(
                        tree(result.getString("proposed_state")),
                        instant(result, "published_at"),
                        result.getObject("publish_receipt_id", UUID.class)), tenantId)
                .stream().findFirst();
    }

    public void event(
            Long tenantId,
            UUID id,
            String type,
            Long actorId,
            String correlationId,
            long resultingVersion,
            UUID receiptId,
            JsonNode evidence,
            Instant now) {
        jdbc.update("""
                INSERT INTO com_tenant_setting_override_events (
                    event_id, tenant_id, change_id, event_type, actor_id, correlation_id,
                    resulting_version, receipt_id, evidence, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, UUID.randomUUID(), tenantId, id, type, actorId, correlationId,
                resultingVersion, receiptId, json(evidence), timestamp(now));
    }

    private OwnerRow owner(ResultSet result, int ignored) throws SQLException {
        return new OwnerRow(
                result.getString("owner_key"), result.getLong("owner_version"),
                result.getString("setting_key"), result.getString("owner_service"),
                result.getString("value_type"), result.getString("resolution_strategy"),
                result.getString("override_policy"), result.getString("activation_mode"),
                tree(result.getString("default_value")),
                result.getString("localized_label_key"), result.getString("lifecycle_state"),
                instant(result, "created_at"));
    }

    private TenantSettingRegistryDtos.Change change(ResultSet result, int ignored)
            throws SQLException {
        return new TenantSettingRegistryDtos.Change(
                result.getObject("change_id", UUID.class), result.getString("setting_key"),
                result.getString("owner_key"), result.getLong("owner_version"),
                result.getString("desired_state"), tree(result.getString("before_value")),
                nullableTree(result.getString("proposed_value")),
                result.getString("lifecycle_state"), result.getLong("impact_count"),
                result.getString("impact_coverage"), instant(result, "impact_observed_at"),
                result.getString("justification"), result.getLong("requested_by"),
                instant(result, "submitted_at"), (Long) result.getObject("approved_by"),
                instant(result, "approved_at"), result.getString("decision_reason"),
                (Long) result.getObject("published_by"), instant(result, "published_at"),
                result.getObject("publish_receipt_id", UUID.class), result.getLong("version"),
                instant(result, "created_at"), instant(result, "updated_at"), null, List.of());
    }

    private void requireUpdated(int count) {
        if (count != 1) throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                "The tenant setting change changed or is no longer in the required state.");
    }

    private String json(JsonNode value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Tenant setting JSON serialization failed.", exception);
        }
    }

    private JsonNode nullableTree(String value) {
        return value == null ? null : tree(value);
    }

    private JsonNode tree(String value) {
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Tenant setting JSON is invalid.", exception);
        }
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        Object value = result.getObject(column);
        if (value == null) return null;
        if (value instanceof OffsetDateTime offset) return offset.toInstant();
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        return ((java.time.LocalDateTime) value).toInstant(ZoneOffset.UTC);
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    public record OwnerRow(
            String ownerKey,
            long ownerVersion,
            String settingKey,
            String ownerService,
            String valueType,
            String resolutionStrategy,
            String overridePolicy,
            String activationMode,
            JsonNode defaultValue,
            String localizedLabelKey,
            String lifecycleState,
            Instant createdAt) {
    }

    public record CanonicalAuthPolicyPublication(
            JsonNode proposedState,
            Instant publishedAt,
            UUID receiptId) {

        public CanonicalAuthPolicyPublication {
            proposedState = proposedState.deepCopy();
        }
    }
}
