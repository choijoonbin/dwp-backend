package com.dwp.services.auth.tenantsettings;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TenantSettingsRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final ObjectMapper objectMapper;

    public TenantSettingsRepository(
            JdbcTemplate jdbc,
            NamedParameterJdbcTemplate namedJdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.objectMapper = objectMapper;
    }

    public PolicyState currentPolicy(Long tenantId) {
        return jdbc.query("""
                SELECT policy.default_login_type, policy.local_login_enabled,
                       policy.sso_login_enabled, policy.sso_provider_key,
                       policy.require_mfa, policy.token_ttl_sec,
                       COALESCE((
                           SELECT jsonb_agg(login_type ORDER BY sort_order)
                             FROM sys_auth_policy_login_types allowed
                            WHERE allowed.tenant_id = policy.tenant_id
                       ), '[]'::jsonb)::text AS allowed_login_types
                  FROM sys_auth_policies policy
                 WHERE policy.tenant_id = ?
                """, (result, ignored) -> new PolicyState(
                        result.getString("default_login_type"),
                        strings(result.getString("allowed_login_types")),
                        result.getBoolean("local_login_enabled"),
                        result.getBoolean("sso_login_enabled"),
                        result.getString("sso_provider_key"),
                        result.getBoolean("require_mfa"),
                        (Integer) result.getObject("token_ttl_sec")), tenantId).stream()
                .findFirst()
                .orElseThrow(() -> new BaseException(
                        ErrorCode.NOT_FOUND, "The tenant authentication policy was not found."));
    }

    public boolean enabledIdentityProviderExists(Long tenantId, String providerKey) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sys_identity_providers
                 WHERE tenant_id = ? AND provider_key = ? AND enabled = TRUE
                """, Integer.class, tenantId, providerKey);
        return count != null && count > 0;
    }

    public SsoReceiptWrite recordSsoTestLoginReceipt(
            Long tenantId,
            UUID testLoginJobId,
            String providerKey,
            Long actorId,
            UUID idempotencyKey,
            String justification,
            String lifecycleState,
            String internalPrerequisiteState,
            String externalProbeState,
            List<String> blockingReasons,
            String executionBoundary,
            Instant requestedAt,
            Instant completedAt,
            String correlationId) {
        int inserted = jdbc.update("""
                INSERT INTO com_sso_test_login_receipts (
                    test_login_job_id, tenant_id, provider_key, requested_by,
                    idempotency_key, justification, lifecycle_state,
                    internal_prerequisite_state, external_probe_state,
                    blocking_reasons, execution_boundary, requested_at,
                    completed_at, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                """, testLoginJobId, tenantId, providerKey, actorId, idempotencyKey,
                justification.trim(), lifecycleState, internalPrerequisiteState,
                externalProbeState, json(blockingReasons), executionBoundary,
                timestamp(requestedAt), timestamp(completedAt), correlationId);
        return new SsoReceiptWrite(
                requireSsoTestLoginReceiptByIdempotency(tenantId, idempotencyKey),
                inserted == 1);
    }

    public Optional<TenantSettingsDtos.SsoTestLoginReceipt> latestSsoTestLoginReceipt(Long tenantId) {
        return jdbc.query("""
                SELECT * FROM com_sso_test_login_receipts
                 WHERE tenant_id = ?
                 ORDER BY requested_at DESC, test_login_job_id DESC
                 LIMIT 1
                """, this::ssoTestLoginReceipt, tenantId).stream().findFirst();
    }

    public TenantSettingsDtos.SsoTestLoginReceipt requireSsoTestLoginReceipt(
            Long tenantId, UUID testLoginJobId) {
        return jdbc.query("""
                SELECT * FROM com_sso_test_login_receipts
                 WHERE tenant_id = ? AND test_login_job_id = ?
                """, this::ssoTestLoginReceipt, tenantId, testLoginJobId).stream().findFirst()
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    public List<TenantSettingsDtos.SsoTestLoginReceipt> ssoTestLoginReceipts(Long tenantId, int fetchSize) {
        return jdbc.query("""
                SELECT * FROM com_sso_test_login_receipts
                 WHERE tenant_id = ?
                 ORDER BY requested_at DESC, test_login_job_id DESC
                 LIMIT ?
                """, this::ssoTestLoginReceipt, tenantId, fetchSize);
    }

    private TenantSettingsDtos.SsoTestLoginReceipt requireSsoTestLoginReceiptByIdempotency(
            Long tenantId, UUID idempotencyKey) {
        return jdbc.query("""
                SELECT * FROM com_sso_test_login_receipts
                 WHERE tenant_id = ? AND idempotency_key = ?
                """, this::ssoTestLoginReceipt, tenantId, idempotencyKey).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "The SSO test-login receipt was not recorded."));
    }

    public long activeIdentityCount(Long tenantId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM com_users
                 WHERE tenant_id = ? AND status = 'ACTIVE'
                """, Long.class, tenantId);
        return count == null ? 0 : count;
    }

    public TenantDirectoryRow tenantDirectory(Long tenantId) {
        return jdbc.query("""
                SELECT tenant_id, code, name, default_locale, updated_at
                  FROM com_tenants WHERE tenant_id = ?
                """, (result, ignored) -> new TenantDirectoryRow(
                        result.getLong("tenant_id"), result.getString("code"),
                        result.getString("name"), result.getString("default_locale"),
                        instant(result, "updated_at")), tenantId).stream().findFirst()
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    public RecoveryCoverageRow recoveryCoverage(Long tenantId, Instant now) {
        return jdbc.query("""
                SELECT COUNT(*) FILTER (WHERE lifecycle_state = 'ACTIVE') AS total,
                       COUNT(*) FILTER (
                           WHERE lifecycle_state = 'ACTIVE'
                             AND verification_status = 'VERIFIED'
                             AND verification_due_at > ?) AS verified,
                       COUNT(*) FILTER (
                           WHERE lifecycle_state = 'ACTIVE'
                             AND verification_status IN ('VERIFIED', 'OVERDUE')
                             AND verification_due_at <= ?) AS overdue,
                       COUNT(*) FILTER (
                           WHERE lifecycle_state = 'ACTIVE'
                             AND verification_status = 'NOT_VERIFIED') AS not_verified,
                       MAX(last_verified_at) FILTER (WHERE lifecycle_state = 'ACTIVE')
                           AS freshest_verification_at
                  FROM com_emergency_access_principals
                 WHERE tenant_id = ?
                """, (result, ignored) -> new RecoveryCoverageRow(
                        result.getLong("total"), result.getLong("verified"),
                        result.getLong("overdue"), result.getLong("not_verified"),
                        instant(result, "freshest_verification_at")),
                timestamp(now), timestamp(now), tenantId).stream().findFirst()
                .orElse(new RecoveryCoverageRow(0, 0, 0, 0, null));
    }

    public UserPreferenceRow userPreference(Long tenantId, Long userId) {
        return jdbc.query("""
                SELECT user_record.user_id, user_record.preferred_locale,
                       tenant.default_locale, user_record.version, user_record.updated_at
                  FROM com_users user_record
                  JOIN com_tenants tenant ON tenant.tenant_id = user_record.tenant_id
                 WHERE user_record.tenant_id = ? AND user_record.user_id = ?
                """, (result, ignored) -> new UserPreferenceRow(
                        result.getLong("user_id"), result.getString("preferred_locale"),
                        result.getString("default_locale"), result.getLong("version"),
                        instant(result, "updated_at")), tenantId, userId).stream().findFirst()
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    public UserPreferenceRow restorePreferredLocale(
            Long tenantId, Long userId, long version, Long actorId, Instant now) {
        int updated = jdbc.update("""
                UPDATE com_users
                   SET preferred_locale = NULL, version = version + 1,
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND user_id = ? AND version = ?
                """, timestamp(now), actorId, tenantId, userId, version);
        requireUpdated(updated);
        return userPreference(tenantId, userId);
    }

    public TenantSettingsDtos.ChangeSet insertChangeSet(
            Long tenantId,
            JsonNode before,
            JsonNode proposed,
            String beforeHash,
            String proposedHash,
            TenantSettingsDtos.Impact impact,
            String justification,
            Long actorId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_tenant_setting_change_sets (
                    change_set_id, tenant_id, owner_type, owner_ref,
                    before_state, proposed_state, before_hash, proposed_hash,
                    impact_confidence, impact_count, impact_coverage,
                    impact_observed_at, impact_exclusions, justification,
                    requested_by, created_by, updated_by)
                VALUES (?, ?, 'AUTH_POLICY', 'tenant-authentication',
                        ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                """, id, tenantId, json(before), json(proposed), beforeHash, proposedHash,
                impact.confidence(), impact.populationCount(), impact.coverage(),
                timestamp(impact.observedAt()), json(impact.exclusions()), justification.trim(),
                actorId, actorId, actorId);
        return requireChangeSet(tenantId, id);
    }

    public List<TenantSettingsDtos.ChangeSet> listChangeSets(Long tenantId, int fetchLimit) {
        return jdbc.query("""
                SELECT * FROM com_tenant_setting_change_sets
                 WHERE tenant_id = ?
                 ORDER BY CASE WHEN lifecycle_state IN ('DRAFT', 'IN_REVIEW', 'APPROVED')
                               THEN 0 ELSE 1 END,
                          updated_at DESC, created_at DESC, change_set_id DESC
                 LIMIT ?
                """, this::changeSet, tenantId, fetchLimit);
    }

    public TenantSettingsDtos.ChangeSet requireChangeSet(Long tenantId, UUID changeSetId) {
        return jdbc.query("""
                SELECT * FROM com_tenant_setting_change_sets
                 WHERE tenant_id = ? AND change_set_id = ?
                """, this::changeSet, tenantId, changeSetId).stream().findFirst()
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    public TenantSettingsDtos.ChangeSet submit(
            Long tenantId, UUID changeSetId, long version, Long actorId, Instant now) {
        int updated = jdbc.update("""
                UPDATE com_tenant_setting_change_sets
                   SET lifecycle_state = 'IN_REVIEW', submitted_at = ?,
                       version = version + 1, updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND change_set_id = ?
                   AND lifecycle_state = 'DRAFT' AND version = ?
                """, timestamp(now), timestamp(now), actorId, tenantId, changeSetId, version);
        requireUpdated(updated);
        return requireChangeSet(tenantId, changeSetId);
    }

    public TenantSettingsDtos.ChangeSet decide(
            Long tenantId,
            UUID changeSetId,
            long version,
            String nextState,
            String reason,
            Long actorId,
            Instant now) {
        int updated = jdbc.update("""
                UPDATE com_tenant_setting_change_sets
                   SET lifecycle_state = ?, decided_by = ?, decided_at = ?,
                       decision_reason = ?, version = version + 1,
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND change_set_id = ?
                   AND lifecycle_state = 'IN_REVIEW' AND version = ?
                   AND requested_by <> ?
                """, nextState, actorId, timestamp(now), reason.trim(), timestamp(now), actorId,
                tenantId, changeSetId, version, actorId);
        requireUpdated(updated);
        return requireChangeSet(tenantId, changeSetId);
    }

    public TenantSettingsDtos.ChangeSet publish(
            Long tenantId,
            UUID changeSetId,
            long version,
            PolicyState policy,
            Long actorId,
            Instant now,
            UUID receiptId) {
        int policyUpdated = jdbc.update("""
                UPDATE sys_auth_policies
                   SET default_login_type = ?, allowed_login_types = ?,
                       local_login_enabled = ?, sso_login_enabled = ?,
                       sso_provider_key = ?, require_mfa = ?, token_ttl_sec = ?,
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ?
                """, policy.defaultLoginType(), String.join(",", policy.allowedLoginTypes()),
                policy.localLoginEnabled(), policy.ssoLoginEnabled(), policy.ssoProviderKey(),
                policy.requireMfa(), policy.tokenTtlSec(), timestamp(now), actorId, tenantId);
        if (policyUpdated != 1) throw new BaseException(ErrorCode.NOT_FOUND);
        int updated = jdbc.update("""
                UPDATE com_tenant_setting_change_sets
                   SET lifecycle_state = 'PUBLISHED', published_by = ?, published_at = ?,
                       publish_receipt_id = ?, version = version + 1,
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND change_set_id = ?
                   AND lifecycle_state = 'APPROVED' AND version = ?
                """, actorId, timestamp(now), receiptId, timestamp(now), actorId,
                tenantId, changeSetId, version);
        requireUpdated(updated);
        jdbc.update("""
                UPDATE com_tenant_setting_change_sets
                   SET lifecycle_state = 'SUPERSEDED', version = version + 1,
                       updated_at = ?, updated_by = ?
                 WHERE tenant_id = ? AND owner_type = 'AUTH_POLICY'
                   AND owner_ref = 'tenant-authentication'
                   AND change_set_id <> ? AND lifecycle_state = 'DRAFT'
                """, timestamp(now), actorId, tenantId, changeSetId);
        return requireChangeSet(tenantId, changeSetId);
    }

    public List<UserRow> users(Long tenantId, String query, int page, int size) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("query", query == null ? null : "%" + query.toLowerCase() + "%")
                .addValue("limit", size)
                .addValue("offset", page * size);
        return namedJdbc.query("""
                SELECT user_record.user_id, user_record.display_name, user_record.email,
                       user_record.status, user_record.mfa_enabled, user_record.updated_at
                  FROM com_users user_record
                 WHERE user_record.tenant_id = :tenantId
                   AND (CAST(:query AS varchar) IS NULL
                        OR lower(user_record.display_name) LIKE :query
                        OR lower(COALESCE(user_record.email, '')) LIKE :query)
                 ORDER BY user_record.display_name, user_record.user_id
                 LIMIT :limit OFFSET :offset
                """, params, (result, ignored) -> new UserRow(
                        result.getLong("user_id"), result.getString("display_name"),
                        result.getString("email"), result.getString("status"),
                        result.getBoolean("mfa_enabled"),
                        instant(result, "updated_at")));
    }

    public long userCount(Long tenantId, String query) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("query", query == null ? null : "%" + query.toLowerCase() + "%");
        Long count = namedJdbc.queryForObject("""
                SELECT COUNT(*) FROM com_users user_record
                 WHERE user_record.tenant_id = :tenantId
                   AND (CAST(:query AS varchar) IS NULL
                        OR lower(user_record.display_name) LIKE :query
                        OR lower(COALESCE(user_record.email, '')) LIKE :query)
                """, params, Long.class);
        return count == null ? 0 : count;
    }

    public Map<Long, List<TenantSettingsDtos.AccessGrant>> grants(Long tenantId, List<Long> userIds) {
        Map<Long, List<TenantSettingsDtos.AccessGrant>> grants = new LinkedHashMap<>();
        userIds.forEach(id -> grants.put(id, new ArrayList<>()));
        if (userIds.isEmpty()) return grants;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("userIds", userIds);
        namedJdbc.query("""
                SELECT direct.user_id, 'ROLE' AS entitlement_type, role.code AS entitlement_key,
                       role.name AS display_name, 'DIRECT' AS source_type,
                       direct.role_member_id::text AS source_id, NULL::text AS source_name,
                       'TENANT' AS scope_type, NULL::text AS scope_ref,
                       'ACTIVE' AS lifecycle_state, direct.created_at AS valid_from,
                       NULL::timestamptz AS valid_to, role.privileged,
                       NULL::bigint AS requested_by, NULL::bigint AS approved_by,
                       NULL::timestamptz AS approved_at,
                       NULL::bigint AS activated_by, NULL::timestamptz AS activated_at,
                       'NOT_APPLICABLE'::text AS approval_lineage_state
                  FROM com_role_members direct
                  JOIN com_roles role ON role.tenant_id = direct.tenant_id
                                     AND role.role_id = direct.role_id
                 WHERE direct.tenant_id = :tenantId AND direct.user_id IN (:userIds)
                   AND role.status = 'ACTIVE'
                UNION ALL
                SELECT member.user_id, 'ROLE', role.code, role.name, 'GROUP',
                       assignment.group_role_assignment_id::text, group_record.display_name,
                       assignment.scope_type, assignment.scope_ref,
                       assignment.lifecycle_state, assignment.valid_from, assignment.valid_to,
                       role.privileged, NULL::bigint, NULL::bigint, NULL::timestamptz,
                       NULL::bigint, NULL::timestamptz, 'NOT_APPLICABLE'::text
                  FROM com_group_members member
                  JOIN com_groups group_record ON group_record.tenant_id = member.tenant_id
                                              AND group_record.group_id = member.group_id
                  JOIN com_group_role_assignments assignment
                    ON assignment.tenant_id = member.tenant_id
                   AND assignment.group_id = member.group_id
                  JOIN com_roles role ON role.tenant_id = assignment.tenant_id
                                     AND role.role_id = assignment.role_id
                 WHERE member.tenant_id = :tenantId AND member.user_id IN (:userIds)
                   AND group_record.status = 'ACTIVE' AND role.status = 'ACTIVE'
                   AND assignment.lifecycle_state = 'ACTIVE'
                   AND assignment.assignment_type = 'ACTIVE'
                   AND (assignment.valid_from IS NULL OR assignment.valid_from <= CURRENT_TIMESTAMP)
                   AND (assignment.valid_to IS NULL OR assignment.valid_to > CURRENT_TIMESTAMP)
                UNION ALL
                SELECT grant_record.user_id, 'ROLE', role.code, role.name, 'PRIVILEGED',
                       grant_record.active_privileged_grant_id::text, 'Time-bound activation',
                       grant_record.scope_type, grant_record.scope_ref, 'ACTIVE',
                       grant_record.activated_at, grant_record.expires_at, TRUE,
                       NULL::bigint, NULL::bigint, NULL::timestamptz,
                       NULL::bigint, grant_record.activated_at, 'ACTIVATED'::text
                  FROM com_active_privileged_grants grant_record
                  JOIN com_roles role ON role.tenant_id = grant_record.tenant_id
                                     AND role.role_id = grant_record.role_id
                 WHERE grant_record.tenant_id = :tenantId
                   AND grant_record.user_id IN (:userIds)
                   AND grant_record.revoked_at IS NULL
                   AND grant_record.expires_at > CURRENT_TIMESTAMP
                UNION ALL
                SELECT user_record.user_id, 'APP_PRESET', preset.product_key,
                       preset.display_name, 'APP_PRESET',
                       assignment.app_preset_assignment_id::text,
                       assignment.preset_code, 'RESOURCE_SET',
                       assignment.resource_set_id::text, assignment.lifecycle_state,
                       assignment.valid_from, assignment.valid_to,
                       preset.risk_tier IN ('HIGH', 'CRITICAL'),
                       assignment.requested_by, assignment.approved_by, assignment.approved_at,
                       assignment.activated_by, assignment.activated_at,
                       CASE assignment.lifecycle_state
                           WHEN 'PENDING_APPROVAL' THEN 'REVIEW_PENDING'
                           WHEN 'APPROVED' THEN 'ACTIVATION_PENDING'
                           WHEN 'ACTIVE' THEN 'COMPLETE'
                           ELSE 'CLOSED'
                       END
                  FROM com_admin_app_preset_assignments assignment
                  JOIN sys_admin_app_preset_catalog preset
                    ON preset.preset_code = assignment.preset_code
                  JOIN com_users user_record
                    ON user_record.tenant_id = assignment.tenant_id
                   AND assignment.principal_type = 'USER'
                   AND assignment.principal_ref = user_record.user_id::text
                 WHERE assignment.tenant_id = :tenantId
                   AND user_record.user_id IN (:userIds)
                   AND assignment.lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED', 'ACTIVE')
                   AND (assignment.valid_to IS NULL OR assignment.valid_to > CURRENT_TIMESTAMP)
                UNION ALL
                SELECT member.user_id, 'APP_PRESET', preset.product_key,
                       preset.display_name, 'APP_PRESET_GROUP',
                       assignment.app_preset_assignment_id::text,
                       group_record.display_name, 'RESOURCE_SET',
                       assignment.resource_set_id::text, assignment.lifecycle_state,
                       assignment.valid_from, assignment.valid_to,
                       preset.risk_tier IN ('HIGH', 'CRITICAL'),
                       assignment.requested_by, assignment.approved_by, assignment.approved_at,
                       assignment.activated_by, assignment.activated_at,
                       CASE assignment.lifecycle_state
                           WHEN 'PENDING_APPROVAL' THEN 'REVIEW_PENDING'
                           WHEN 'APPROVED' THEN 'ACTIVATION_PENDING'
                           WHEN 'ACTIVE' THEN 'COMPLETE'
                           ELSE 'CLOSED'
                       END
                  FROM com_admin_app_preset_assignments assignment
                  JOIN sys_admin_app_preset_catalog preset
                    ON preset.preset_code = assignment.preset_code
                  JOIN com_groups group_record
                    ON group_record.tenant_id = assignment.tenant_id
                   AND assignment.principal_type = 'GROUP'
                   AND assignment.principal_ref = group_record.group_id::text
                  JOIN com_group_members member
                    ON member.tenant_id = group_record.tenant_id
                   AND member.group_id = group_record.group_id
                 WHERE assignment.tenant_id = :tenantId
                   AND member.user_id IN (:userIds)
                   AND group_record.status = 'ACTIVE'
                   AND assignment.lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED', 'ACTIVE')
                   AND (assignment.valid_to IS NULL OR assignment.valid_to > CURRENT_TIMESTAMP)
                UNION ALL
                SELECT user_record.user_id, 'APP_WORKFORCE', installation.product_key,
                       installation.product_key, 'TENANT_APP_ASSIGNMENT',
                       assignment.assignment_id::text, installation.app_resource_key,
                       'APP' AS scope_type, installation.installation_id::text,
                       CASE WHEN installation.lifecycle_state = 'ENABLED'
                            THEN assignment.lifecycle_state
                            ELSE 'BLOCKED_BY_INSTALLATION_' || installation.lifecycle_state
                       END, assignment.valid_from, assignment.valid_to,
                       FALSE, assignment.requested_by, assignment.approved_by,
                       assignment.approved_at, assignment.activated_by,
                       assignment.activated_at,
                       CASE assignment.lifecycle_state
                           WHEN 'PENDING_APPROVAL' THEN 'REVIEW_PENDING'
                           WHEN 'APPROVED' THEN 'ACTIVATION_PENDING'
                           WHEN 'ACTIVE' THEN 'COMPLETE'
                           ELSE 'CLOSED'
                       END
                  FROM com_tenant_app_workforce_assignments assignment
                  JOIN com_tenant_app_installations installation
                    ON installation.tenant_id = assignment.tenant_id
                   AND installation.installation_id = assignment.installation_id
                  JOIN com_users user_record
                    ON user_record.tenant_id = assignment.tenant_id
                   AND user_record.user_id::text = assignment.principal_ref
                 WHERE assignment.tenant_id = :tenantId
                   AND user_record.user_id IN (:userIds)
                   AND assignment.lifecycle_state IN ('PENDING_APPROVAL', 'APPROVED', 'ACTIVE')
                   AND (assignment.valid_to IS NULL OR assignment.valid_to > CURRENT_TIMESTAMP)
                 ORDER BY user_id, entitlement_type, entitlement_key, source_type
                """, params, result -> {
            long userId = result.getLong("user_id");
            grants.computeIfAbsent(userId, ignored -> new ArrayList<>()).add(accessGrant(result));
        });
        return grants;
    }

    public Instant freshestProjectionSource(Long tenantId) {
        return jdbc.query("""
                SELECT MAX(updated_at) FROM (
                    SELECT MAX(updated_at) AS updated_at FROM com_users WHERE tenant_id = ?
                    UNION ALL SELECT MAX(updated_at) FROM com_role_members WHERE tenant_id = ?
                    UNION ALL SELECT MAX(updated_at) FROM com_group_members WHERE tenant_id = ?
                    UNION ALL SELECT MAX(updated_at) FROM com_group_role_assignments WHERE tenant_id = ?
                    UNION ALL SELECT MAX(updated_at) FROM com_active_privileged_grants WHERE tenant_id = ?
                    UNION ALL SELECT MAX(updated_at) FROM com_admin_app_preset_assignments WHERE tenant_id = ?
                    UNION ALL SELECT MAX(updated_at) FROM com_tenant_app_workforce_assignments
                        WHERE tenant_id = ?
                ) sources
                """, result -> result.next() ? instant(result, 1) : null,
                tenantId, tenantId, tenantId, tenantId, tenantId, tenantId, tenantId);
    }

    private TenantSettingsDtos.AccessGrant accessGrant(ResultSet result) throws SQLException {
        return new TenantSettingsDtos.AccessGrant(
                result.getString("entitlement_type"), result.getString("entitlement_key"),
                result.getString("display_name"), result.getString("source_type"),
                result.getString("source_id"), result.getString("source_name"),
                result.getString("scope_type"), result.getString("scope_ref"),
                result.getString("lifecycle_state"),
                instant(result, "valid_from"),
                instant(result, "valid_to"),
                result.getBoolean("privileged"),
                (Long) result.getObject("requested_by"),
                (Long) result.getObject("approved_by"),
                instant(result, "approved_at"),
                (Long) result.getObject("activated_by"),
                instant(result, "activated_at"),
                result.getString("approval_lineage_state"));
    }

    private TenantSettingsDtos.ChangeSet changeSet(ResultSet result, int ignored)
            throws SQLException {
        return new TenantSettingsDtos.ChangeSet(
                result.getObject("change_set_id", UUID.class),
                result.getString("owner_type"), result.getString("owner_ref"),
                result.getString("lifecycle_state"),
                tree(result.getString("before_state")),
                tree(result.getString("proposed_state")),
                result.getString("before_hash"), result.getString("proposed_hash"),
                new TenantSettingsDtos.Impact(
                        result.getString("impact_confidence"),
                        (Long) result.getObject("impact_count"),
                        result.getString("impact_coverage"),
                        instant(result, "impact_observed_at"),
                        strings(result.getString("impact_exclusions"))),
                result.getString("justification"), result.getLong("requested_by"),
                instant(result, "submitted_at"),
                (Long) result.getObject("decided_by"),
                instant(result, "decided_at"),
                result.getString("decision_reason"),
                (Long) result.getObject("published_by"),
                instant(result, "published_at"),
                result.getObject("publish_receipt_id", UUID.class), result.getLong("version"),
                instant(result, "created_at"),
                instant(result, "updated_at"), List.of());
    }

    private TenantSettingsDtos.SsoTestLoginReceipt ssoTestLoginReceipt(
            ResultSet result, int ignored) throws SQLException {
        return new TenantSettingsDtos.SsoTestLoginReceipt(
                result.getObject("test_login_job_id", UUID.class),
                result.getLong("tenant_id"),
                result.getString("provider_key"),
                result.getString("lifecycle_state"),
                result.getString("internal_prerequisite_state"),
                result.getString("external_probe_state"),
                strings(result.getString("blocking_reasons")),
                result.getString("execution_boundary"),
                result.getLong("requested_by"),
                result.getObject("idempotency_key", UUID.class),
                instant(result, "requested_at"),
                instant(result, "completed_at"),
                result.getString("receipt_payload_canonical"),
                result.getString("receipt_sha256"));
    }

    private JsonNode tree(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid tenant settings state JSON.", exception);
        }
    }

    private List<String> strings(String value) {
        try {
            return objectMapper.readerForListOf(String.class).readValue(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid tenant settings string list JSON.", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Tenant settings JSON serialization failed.", exception);
        }
    }

    private void requireUpdated(int count) {
        if (count != 1) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The tenant setting change changed state or version. Refresh and retry.");
        }
    }

    private OffsetDateTime timestamp(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        java.sql.Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Instant instant(ResultSet result, int column) throws SQLException {
        java.sql.Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record PolicyState(
            String defaultLoginType,
            List<String> allowedLoginTypes,
            boolean localLoginEnabled,
            boolean ssoLoginEnabled,
            String ssoProviderKey,
            boolean requireMfa,
            Integer tokenTtlSec) {

        public PolicyState {
            allowedLoginTypes = List.copyOf(allowedLoginTypes);
        }
    }

    public record UserRow(
            Long userId,
            String displayName,
            String email,
            String status,
            boolean mfaEnabled,
            Instant updatedAt) {
    }

    public record TenantDirectoryRow(
            Long tenantId,
            String code,
            String name,
            String defaultLocale,
            Instant updatedAt) {
    }

    public record RecoveryCoverageRow(
            long total,
            long verified,
            long overdue,
            long notVerified,
            Instant freshestVerificationAt) {
    }

    public record UserPreferenceRow(
            Long userId,
            String preferredLocale,
            String tenantDefaultLocale,
            long version,
            Instant updatedAt) {
    }

    public record SsoReceiptWrite(
            TenantSettingsDtos.SsoTestLoginReceipt receipt, boolean created) { }
}
