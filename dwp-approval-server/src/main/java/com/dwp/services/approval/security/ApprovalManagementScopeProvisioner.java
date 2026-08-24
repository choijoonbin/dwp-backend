package com.dwp.services.approval.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Idempotently provisions immutable baseline objects for an Auth-approved scope. */
@Component
public class ApprovalManagementScopeProvisioner {

    private static final String ROOT_SCOPE = "RS_APPROVALS";

    private final NamedParameterJdbcTemplate jdbc;
    private final boolean nonRootWritesEnabled;

    public ApprovalManagementScopeProvisioner(
            NamedParameterJdbcTemplate jdbc,
            @Value("${dwp.approval.management-scope-writes-enabled:false}")
                    boolean nonRootWritesEnabled) {
        this.jdbc = jdbc;
        this.nonRootWritesEnabled = nonRootWritesEnabled;
    }

    @Transactional
    public void ensure(long tenantId, String resourceSetKey) {
        if (tenantId <= 0 || resourceSetKey == null
                || !resourceSetKey.matches("^[A-Z][A-Z0-9_]{2,79}$")) {
            throw new IllegalArgumentException(
                    "Approval management resource-set key is invalid.");
        }
        if (ROOT_SCOPE.equals(resourceSetKey)) return;
        if (!nonRootWritesEnabled) {
            throw new IllegalStateException(
                    "Non-root Approval management scope writes are not enabled.");
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("managementScope", resourceSetKey);
        List<String> tenantStates = jdbc.query("""
                SELECT lifecycle_state
                  FROM apr_tenants
                 WHERE tenant_id = :tenantId
                 FOR UPDATE
                """, params, (result, ignored) -> result.getString("lifecycle_state"));
        if (!tenantStates.isEmpty() && !"ACTIVE".equals(tenantStates.getFirst())) {
            throw new IllegalStateException(
                    "Approval management scope cannot provision an inactive tenant.");
        }
        // The security filter runs before ApprovalService.prepare(). Seed the
        // root catalog here in the same transaction so the first non-root hit
        // cannot observe or clone an empty baseline.
        jdbc.queryForObject(
                "SELECT seed_approval_tenant(:tenantId)", params, Object.class);
        jdbc.queryForObject(
                "SELECT seed_approval_product_templates(:tenantId)",
                params, Object.class);
        jdbc.queryForObject(
                "SELECT seed_approval_form_catalog(:tenantId)",
                params, Object.class);
        jdbc.update("""
                INSERT INTO apr_policy_rules (
                    policy_id, tenant_id, policy_key, name_ko, name_en,
                    policy_type, enforcement_mode, severity, rule_payload,
                    lifecycle_state, management_resource_set_key,
                    created_by, updated_by)
                SELECT md5('approval-policy-scope:' || root.tenant_id || ':'
                           || :managementScope || ':' || root.policy_key)::uuid,
                       root.tenant_id, root.policy_key, root.name_ko, root.name_en,
                       root.policy_type, root.enforcement_mode, root.severity,
                       root.rule_payload, root.lifecycle_state, :managementScope,
                       root.created_by, root.updated_by
                  FROM apr_policy_rules root
                 WHERE root.tenant_id = :tenantId
                   AND root.management_resource_set_key = 'RS_APPROVALS'
                ON CONFLICT (tenant_id, management_resource_set_key, policy_key)
                DO NOTHING
                """, params);
        jdbc.update("""
                INSERT INTO apr_policy_rule_versions (
                    policy_version_id, tenant_id, policy_id, version_number,
                    enforcement_mode, severity, lifecycle_state, rule_payload,
                    change_reason, submitted_by, submitted_at,
                    published_by, published_at, review_comment)
                SELECT md5('approval-policy-scope-version:' || policy.tenant_id || ':'
                           || policy.management_resource_set_key || ':'
                           || policy.policy_key || ':1')::uuid,
                       policy.tenant_id, policy.policy_id, 1,
                       policy.enforcement_mode, policy.severity,
                       policy.lifecycle_state, policy.rule_payload,
                       'Management scope baseline provisioned', NULL, NULL,
                       1, CURRENT_TIMESTAMP, 'Provisioned from the root baseline'
                  FROM apr_policy_rules policy
                 WHERE policy.tenant_id = :tenantId
                   AND policy.management_resource_set_key = :managementScope
                ON CONFLICT (tenant_id, policy_id, version_number) DO NOTHING
                """, params);
        jdbc.update("""
                INSERT INTO apr_signature_providers (
                    provider_id, tenant_id, provider_key, display_name,
                    provider_type, lifecycle_state, capability_metadata,
                    credential_reference, management_resource_set_key,
                    created_by, updated_by)
                SELECT md5('approval-signature-scope:' || root.tenant_id || ':'
                           || :managementScope || ':' || root.provider_key)::uuid,
                       root.tenant_id, root.provider_key, root.display_name,
                       root.provider_type,
                       CASE WHEN root.provider_type = 'INTERNAL_ATTESTATION'
                            THEN 'ACTIVE' ELSE 'CONFIGURATION_REQUIRED' END,
                       root.capability_metadata, NULL, :managementScope,
                       root.created_by, root.updated_by
                  FROM apr_signature_providers root
                 WHERE root.tenant_id = :tenantId
                   AND root.management_resource_set_key = 'RS_APPROVALS'
                ON CONFLICT (tenant_id, management_resource_set_key, provider_key)
                DO NOTHING
                """, params);
        Integer policyCount = jdbc.queryForObject("""
                SELECT COUNT(*)::INTEGER
                  FROM apr_policy_rules
                 WHERE tenant_id = :tenantId
                   AND management_resource_set_key = :managementScope
                   AND policy_key IN (
                       'BLOCK_SELF_APPROVAL', 'REQUIRE_REJECT_REASON',
                       'CAPTURE_DECISION_EVIDENCE', 'SLA_ESCALATION')
                """, params, Integer.class);
        Integer versionCount = jdbc.queryForObject("""
                SELECT COUNT(*)::INTEGER
                  FROM apr_policy_rule_versions version
                  JOIN apr_policy_rules policy
                    ON policy.tenant_id = version.tenant_id
                   AND policy.policy_id = version.policy_id
                 WHERE policy.tenant_id = :tenantId
                   AND policy.management_resource_set_key = :managementScope
                   AND policy.policy_key IN (
                       'BLOCK_SELF_APPROVAL', 'REQUIRE_REJECT_REASON',
                       'CAPTURE_DECISION_EVIDENCE', 'SLA_ESCALATION')
                   AND version.version_number = 1
                """, params, Integer.class);
        Integer signatureCount = jdbc.queryForObject("""
                SELECT COUNT(*)::INTEGER
                  FROM apr_signature_providers
                 WHERE tenant_id = :tenantId
                   AND management_resource_set_key = :managementScope
                """, params, Integer.class);
        if (!Integer.valueOf(4).equals(policyCount)
                || !Integer.valueOf(4).equals(versionCount)
                || signatureCount == null || signatureCount < 1) {
            throw new IllegalStateException(
                    "Approval management scope baseline is incomplete.");
        }
        int fenced = jdbc.update("""
                UPDATE apr_management_scope_schema_fence
                   SET non_root_writes_activated_at = COALESCE(
                           non_root_writes_activated_at, CURRENT_TIMESTAMP),
                       activated_by_release = 'approval-management-scope-v1',
                       updated_at = CURRENT_TIMESTAMP
                 WHERE fence_key = 'APPROVAL_MANAGEMENT_SCOPE_V1'
                """, params);
        if (fenced != 1) {
            throw new IllegalStateException(
                    "Approval management scope schema fence is unavailable.");
        }
    }
}
