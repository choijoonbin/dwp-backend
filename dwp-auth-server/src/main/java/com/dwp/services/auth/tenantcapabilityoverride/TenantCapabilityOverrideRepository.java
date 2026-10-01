package com.dwp.services.auth.tenantcapabilityoverride;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TenantCapabilityOverrideRepository implements TenantCapabilityOverrideReader {

    private static final String ACTIVE_BUNDLE_KEY = "product-surfaces";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public TenantCapabilityOverrideRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<TenantCapabilityOverrideDtos.Policy> policies() {
        return jdbc.query("""
                SELECT capability.contract_key, capability.product_key,
                       mapping.app_resource_key, capability.surface_key,
                       capability.descriptor ->> 'resolvedCapabilityCode'
                           AS resolved_capability_code,
                       capability.descriptor ->> 'action' AS capability_action,
                       COALESCE(capability.descriptor ->> 'riskTier', 'UNCLASSIFIED')
                           AS risk_tier,
                       capability.descriptor ->> 'owner' AS contract_owner,
                       active_pointer.bundle_id AS active_bundle_id,
                       active_pointer.revision AS active_revision,
                       selected_rule.rule_key, selected_rule.rule_version,
                       selected_rule.override_mode, selected_rule.max_duration_days,
                       selected_rule.owner_key AS rule_owner,
                       selected_rule.reason_code
                  FROM auth_product_authorization_active active_pointer
                  JOIN auth_product_capability_contract capability
                    ON capability.bundle_id = active_pointer.bundle_id
                  JOIN sys_product_app_resource_mapping mapping
                    ON mapping.product_key = capability.product_key
                   AND mapping.lifecycle_state = 'ACTIVE'
                  JOIN LATERAL (
                      SELECT rule.*
                        FROM sys_tenant_capability_override_rules rule
                       WHERE rule.lifecycle_state = 'ACTIVE'
                         AND (rule.risk_tier IS NULL
                              OR rule.risk_tier = capability.descriptor ->> 'riskTier')
                       ORDER BY rule.priority DESC, rule.rule_version DESC
                       LIMIT 1
                  ) selected_rule ON TRUE
                 WHERE active_pointer.bundle_key = ?
                   AND capability.lifecycle_state = 'ACTIVE'
                 ORDER BY capability.product_key, capability.surface_key,
                          capability.contract_key
                """, this::mapPolicy, ACTIVE_BUNDLE_KEY);
    }

    public Optional<TenantCapabilityOverrideDtos.Policy> policy(String contractKey) {
        return policies().stream()
                .filter(value -> value.contractKey().equals(contractKey))
                .findFirst();
    }

    public List<TenantCapabilityOverrideDtos.Change> changes(Long tenantId) {
        return jdbc.query("""
                SELECT change.*
                  FROM com_tenant_capability_override_changes change
                 WHERE change.tenant_id = ?
                 ORDER BY change.updated_at DESC, change.override_change_id
                """, this::change, tenantId);
    }

    public TenantCapabilityOverrideDtos.Change requireChange(Long tenantId, UUID changeId) {
        return jdbc.query("""
                SELECT change.*
                  FROM com_tenant_capability_override_changes change
                 WHERE change.tenant_id = ? AND change.override_change_id = ?
                """, this::change, tenantId, changeId).stream().findFirst()
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    public TenantCapabilityOverrideDtos.Change lockChange(Long tenantId, UUID changeId) {
        jdbc.queryForObject("""
                SELECT override_change_id
                  FROM com_tenant_capability_override_changes
                 WHERE tenant_id = ? AND override_change_id = ? FOR UPDATE
                """, UUID.class, tenantId, changeId);
        return requireChange(tenantId, changeId);
    }

    public TenantCapabilityOverrideDtos.Change insert(
            Long tenantId,
            Long actorId,
            TenantCapabilityOverrideDtos.Policy policy,
            TenantCapabilityOverrideDtos.CreateRequest request,
            Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_tenant_capability_override_changes (
                    override_change_id, tenant_id, contract_key, product_key,
                    app_resource_key, policy_rule_key, policy_rule_version,
                    base_bundle_id, base_active_revision, desired_state, valid_to,
                    justification, requested_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, tenantId, policy.contractKey(), policy.productKey(),
                policy.appResourceKey(), policy.ruleKey(), policy.ruleVersion(),
                policy.activeBundleId(), policy.activeRevision(), request.desiredState(),
                timestamp(request.validTo()), request.justification().trim(), actorId,
                timestamp(now), timestamp(now));
        return requireChange(tenantId, id);
    }

    public TenantCapabilityOverrideDtos.Change submit(
            Long tenantId, UUID changeId, long version, Long actorId, Instant now) {
        int updated = jdbc.update("""
                UPDATE com_tenant_capability_override_changes
                   SET lifecycle_state = 'IN_REVIEW', submitted_at = ?,
                       version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND override_change_id = ?
                   AND lifecycle_state = 'DRAFT' AND version = ?
                   AND requested_by = ?
                """, timestamp(now), timestamp(now), tenantId, changeId, version, actorId);
        requireUpdated(updated);
        return requireChange(tenantId, changeId);
    }

    public TenantCapabilityOverrideDtos.Change decide(
            Long tenantId,
            UUID changeId,
            long version,
            String nextState,
            String reason,
            Long actorId,
            Instant now) {
        int updated = jdbc.update("""
                UPDATE com_tenant_capability_override_changes
                   SET lifecycle_state = ?, approved_by = ?, approved_at = ?,
                       decision_reason = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND override_change_id = ?
                   AND lifecycle_state = 'IN_REVIEW' AND version = ?
                   AND requested_by <> ?
                """, nextState, actorId, timestamp(now), reason.trim(), timestamp(now),
                tenantId, changeId, version, actorId);
        requireUpdated(updated);
        return requireChange(tenantId, changeId);
    }

    public TenantCapabilityOverrideDtos.Change activate(
            Long tenantId,
            UUID changeId,
            long version,
            Long actorId,
            String reason,
            UUID receiptId,
            Instant now) {
        jdbc.update("""
                UPDATE com_tenant_capability_override_changes
                   SET lifecycle_state = 'SUPERSEDED', version = version + 1,
                       updated_at = ?, revocation_reason = ?
                 WHERE tenant_id = ? AND contract_key = (
                     SELECT contract_key FROM com_tenant_capability_override_changes
                      WHERE tenant_id = ? AND override_change_id = ?)
                   AND lifecycle_state = 'ACTIVE' AND override_change_id <> ?
                """, timestamp(now), "SUPERSEDED_BY_APPROVED_CHANGE:" + reason.trim(),
                tenantId, tenantId, changeId, changeId);
        int updated = jdbc.update("""
                UPDATE com_tenant_capability_override_changes
                   SET lifecycle_state = 'ACTIVE', activated_by = ?, activated_at = ?,
                       activation_receipt_id = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND override_change_id = ?
                   AND lifecycle_state = 'APPROVED' AND version = ?
                   AND requested_by <> ? AND approved_by <> ?
                """, actorId, timestamp(now), receiptId, timestamp(now), tenantId,
                changeId, version, actorId, actorId);
        requireUpdated(updated);
        return requireChange(tenantId, changeId);
    }

    public TenantCapabilityOverrideDtos.Change revoke(
            Long tenantId,
            UUID changeId,
            long version,
            Long actorId,
            String reason,
            Instant now) {
        int updated = jdbc.update("""
                UPDATE com_tenant_capability_override_changes
                   SET lifecycle_state = 'REVOKED', revoked_by = ?, revoked_at = ?,
                       revocation_reason = ?, version = version + 1, updated_at = ?
                 WHERE tenant_id = ? AND override_change_id = ?
                   AND lifecycle_state = 'ACTIVE' AND version = ?
                """, actorId, timestamp(now), reason.trim(), timestamp(now),
                tenantId, changeId, version);
        requireUpdated(updated);
        return requireChange(tenantId, changeId);
    }

    public void appendEvent(
            Long tenantId,
            UUID changeId,
            String eventType,
            Long actorId,
            String correlationId,
            long resultingVersion,
            UUID receiptId,
            Map<String, Object> evidence) {
        jdbc.update("""
                INSERT INTO com_tenant_capability_override_events (
                    event_id, tenant_id, override_change_id, event_type, actor_id,
                    correlation_id, resulting_version, receipt_id, evidence)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, UUID.randomUUID(), tenantId, changeId, eventType, actorId,
                correlationId, resultingVersion, receiptId, json(evidence));
    }

    @Override
    public boolean isDisabled(Long tenantId, String contractKey, Instant evaluatedAt) {
        Boolean result = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM com_tenant_capability_override_changes change
                     WHERE change.tenant_id = ? AND change.contract_key = ?
                       AND change.lifecycle_state = 'ACTIVE'
                       AND change.desired_state = 'DISABLED'
                       AND change.valid_to > ?)
                """, Boolean.class, tenantId, contractKey, timestamp(evaluatedAt));
        return Boolean.TRUE.equals(result);
    }

    @Override
    public String effectiveRevision(Long tenantId, Instant evaluatedAt) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)::text || ':' ||
                       COALESCE(EXTRACT(EPOCH FROM MAX(event.occurred_at))::bigint, 0)::text
                  FROM com_tenant_capability_override_events event
                 WHERE event.tenant_id = ?
                """, String.class, tenantId);
    }

    public Optional<TenantCapabilityOverrideDtos.Change> activeChange(
            Long tenantId, String contractKey) {
        return jdbc.query("""
                SELECT change.* FROM com_tenant_capability_override_changes change
                 WHERE change.tenant_id = ? AND change.contract_key = ?
                   AND change.lifecycle_state = 'ACTIVE'
                 ORDER BY change.activated_at DESC LIMIT 1
                """, this::change, tenantId, contractKey).stream().findFirst();
    }

    private TenantCapabilityOverrideDtos.Policy mapPolicy(ResultSet result, int row)
            throws SQLException {
        return new TenantCapabilityOverrideDtos.Policy(
                result.getString("contract_key"), result.getString("product_key"),
                result.getString("app_resource_key"), result.getString("surface_key"),
                result.getString("resolved_capability_code"),
                result.getString("capability_action"), result.getString("risk_tier"),
                result.getString("contract_owner"),
                result.getObject("active_bundle_id", UUID.class),
                result.getLong("active_revision"), result.getString("rule_key"),
                result.getLong("rule_version"), result.getString("override_mode"),
                nullableInteger(result, "max_duration_days"),
                result.getString("rule_owner"), result.getString("reason_code"),
                "UNAVAILABLE", List.of());
    }

    private TenantCapabilityOverrideDtos.Change change(ResultSet result, int row)
            throws SQLException {
        return new TenantCapabilityOverrideDtos.Change(
                result.getObject("override_change_id", UUID.class),
                result.getString("contract_key"), result.getString("product_key"),
                result.getString("app_resource_key"), result.getString("policy_rule_key"),
                result.getLong("policy_rule_version"),
                result.getObject("base_bundle_id", UUID.class),
                result.getLong("base_active_revision"), result.getString("desired_state"),
                result.getString("lifecycle_state"), instant(result, "valid_to"),
                result.getString("justification"), result.getLong("requested_by"),
                instant(result, "submitted_at"), nullableLong(result, "approved_by"),
                instant(result, "approved_at"), result.getString("decision_reason"),
                nullableLong(result, "activated_by"), instant(result, "activated_at"),
                result.getObject("activation_receipt_id", UUID.class),
                nullableLong(result, "revoked_by"), instant(result, "revoked_at"),
                result.getString("revocation_reason"), result.getLong("version"),
                instant(result, "created_at"), instant(result, "updated_at"), List.of());
    }

    private static Integer nullableInteger(ResultSet result, String column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
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
        return value == null ? null : Timestamp.from(value);
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Tenant capability override evidence serialization failed.", exception);
        }
    }

    private static void requireUpdated(int updated) {
        if (updated != 1) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The capability override changed or is no longer in the required state.");
        }
    }
}
