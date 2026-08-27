package com.dwp.services.auth.security;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class ProviderTenantRoleBoundaryPostgresIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static UUID bootstrapSessionId;
    private static UUID legacyProviderReviewCampaignId;
    private static UUID legacyProviderReviewItemId;

    @BeforeAll
    static void migrateFromThePreviouslyReleasedSchema() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway baseline = Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .target("98")
                .cleanDisabled(false)
                .load();
        baseline.clean();
        baseline.migrate();
        jdbc = new JdbcTemplate(dataSource);
        bootstrapSessionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sys_auth_sessions (
                    session_id, token_id, tenant_id, user_id, expires_at,
                    session_family_id, session_started_at, issued_at,
                    last_seen_at, idle_expires_at)
                VALUES (?, ?, 1, 1, CURRENT_TIMESTAMP + INTERVAL '1 hour',
                        ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '30 minutes')
                """, bootstrapSessionId, UUID.randomUUID().toString(), UUID.randomUUID());

        Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .target("102")
                .load()
                .migrate();

        seedLegacyProviderReviewAssignment();

        Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .load()
                .migrate();
    }

    @Test
    void migratesTheBootstrapAndLocalReviewAccountsToSeparatedPlanes() {
        assertThat(roleCodes("admin@dwp.local"))
                .containsExactly("PROVIDER_ADMIN");
        assertThat(roleCodes("provider.admin@dwp.local"))
                .containsExactly("PROVIDER_ADMIN");
        assertThat(roleCodes("hyunwoo.park@sk.com"))
                .contains("TENANT_ADMIN")
                .noneMatch(role -> role.startsWith("PROVIDER_"));
        assertThat(identityPlane("admin@dwp.local")).isEqualTo("PROVIDER");
        assertThat(identityPlane("provider.admin@dwp.local")).isEqualTo("PROVIDER");
        assertThat(identityPlane("hyunwoo.park@sk.com")).isEqualTo("TENANT");
        assertThat(jdbc.queryForObject("""
                SELECT revoked_at IS NOT NULL
                  FROM sys_auth_sessions
                 WHERE session_id = ?
                """, Boolean.class, bootstrapSessionId)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM v_sys_role_plane_conflicts",
                Integer.class)).isZero();
    }

    @Test
    void providerPlaneRemainsDurableWhenItsLastRoleIsRemoved() {
        Long providerUser = userId("admin@dwp.local");
        Long providerRole = roleId("PROVIDER_ADMIN");
        Long workspaceRole = roleId("WORKSPACE_MEMBER");
        try {
            jdbc.update("""
                    DELETE FROM com_role_members
                     WHERE tenant_id = 1 AND user_id = ? AND role_id = ?
                    """, providerUser, providerRole);

            assertThat(identityPlane("admin@dwp.local")).isEqualTo("PROVIDER");
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO com_role_members (tenant_id, role_id, user_id)
                    VALUES (1, ?, ?)
                    """, workspaceRole, providerUser))
                    .rootCause()
                    .hasMessageContaining("Identity plane PROVIDER does not match role namespace");
        } finally {
            jdbc.update("""
                    INSERT INTO com_role_members (tenant_id, role_id, user_id)
                    VALUES (1, ?, ?)
                    ON CONFLICT (tenant_id, role_id, user_id) DO NOTHING
                    """, providerRole, providerUser);
        }
    }

    @Test
    void customRoleCannotClaimTheReservedProviderNamespace() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO com_roles (tenant_id, code, name, description, status)
                VALUES (1, ?, 'Invalid custom provider role',
                        'Reserved namespace boundary test', 'ACTIVE')
                """, "PROVIDER_CUSTOM_" + UUID.randomUUID().toString().replace("-", "")))
                .rootCause()
                .hasMessageContaining(
                        "The PROVIDER_* role namespace is reserved for built-in provider roles");
    }

    @Test
    void ordinaryCustomTenantRolesRemainAssignableToTenantIdentities() {
        String roleCode = "CUSTOM_BOUNDARY_" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 16);
        Long roleId = jdbc.queryForObject("""
                INSERT INTO com_roles (tenant_id, code, name, description, status)
                VALUES (1, ?, 'Custom tenant boundary role',
                        'Positive identity-plane integration control', 'ACTIVE')
                RETURNING role_id
                """, Long.class, roleCode);
        Long tenantUser = userId("hyunwoo.park@sk.com");

        assertThat(jdbc.update("""
                INSERT INTO com_role_members (tenant_id, role_id, user_id)
                VALUES (1, ?, ?)
                """, roleId, tenantUser)).isEqualTo(1);
    }

    @Test
    void providerCatalogFamilyMustUseTheReservedProviderRoleNamespace() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO sys_builtin_role_catalog (
                    role_code, display_name, description, role_family, label_i18n)
                VALUES ('CONTROL_PLANE_TEST', 'Invalid provider test',
                        'Provider-family roles require the reserved prefix.',
                        'PROVIDER', '{}'::jsonb)
                """))
                .rootCause()
                .hasMessageContaining("ck_sys_builtin_role_provider_namespace");
    }

    @Test
    void directRoleMutationCannotMixThePlanes() {
        Long tenantAdmin = userId("hyunwoo.park@sk.com");
        Long providerRole = roleId("PROVIDER_ADMIN");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO com_role_members (tenant_id, role_id, user_id)
                VALUES (1, ?, ?)
                """, providerRole, tenantAdmin))
                .rootCause()
                .hasMessageContaining("Identity plane TENANT does not match role namespace");
    }

    @Test
    void groupMembershipAndGroupRoleAssignmentCannotBypassTheBoundary() {
        Long providerUser = userId("admin@dwp.local");
        Long workspaceRole = roleId("WORKSPACE_MEMBER");
        Long providerRole = roleId("PROVIDER_ADMIN");
        Long tenantGroup = jdbc.queryForObject("""
                INSERT INTO com_groups (
                    tenant_id, group_key, display_name, source_type, status)
                VALUES (1, ?, 'Tenant boundary test', 'LOCAL', 'ACTIVE')
                RETURNING group_id
                """, Long.class, "role-plane-test-" + UUID.randomUUID());
        jdbc.update("""
                INSERT INTO com_group_role_assignments (
                    tenant_id, group_id, role_id, assignment_type,
                    scope_type, lifecycle_state)
                VALUES (1, ?, ?, 'ACTIVE', 'TENANT', 'ACTIVE')
                """, tenantGroup, workspaceRole);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO com_group_members (
                    tenant_id, group_id, user_id, source_type)
                VALUES (1, ?, ?, 'LOCAL')
                """, tenantGroup, providerUser))
                .rootCause()
                .hasMessageContaining("Provider identities cannot join tenant groups");

        Long emptyGroup = jdbc.queryForObject("""
                INSERT INTO com_groups (
                    tenant_id, group_key, display_name, source_type, status)
                VALUES (1, ?, 'Provider group test', 'LOCAL', 'ACTIVE')
                RETURNING group_id
                """, Long.class, "provider-group-test-" + UUID.randomUUID());
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO com_group_role_assignments (
                    tenant_id, group_id, role_id, assignment_type,
                    scope_type, lifecycle_state)
                VALUES (1, ?, ?, 'ACTIVE', 'TENANT', 'ACTIVE')
                """, emptyGroup, providerRole))
                .rootCause()
                .hasMessageContaining(
                        "Provider control-plane roles cannot be assigned to groups");
    }

    @Test
    void activePrivilegedGrantCannotBypassTheBoundary() {
        Long tenantAdmin = userId("hyunwoo.park@sk.com");
        Long providerRole = roleId("PROVIDER_ADMIN");
        UUID requestId = jdbc.queryForObject("""
                INSERT INTO com_privileged_access_requests (
                    tenant_id, requester_user_id, role_id, request_type,
                    scope_type, duration_minutes, justification, ticket_reference,
                    assurance_level, approval_quorum, lifecycle_state)
                VALUES (1, ?, ?, 'JIT', 'TENANT', 15,
                        'Boundary integration test', 'TEST-1', 'MFA', 1,
                        'CANCELLED')
                RETURNING privileged_access_request_id
                """, UUID.class, tenantAdmin, providerRole);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO com_active_privileged_grants (
                    active_privileged_grant_id, privileged_access_request_id,
                    tenant_id, user_id, role_id, scope_type,
                    activated_at, expires_at)
                VALUES (?, ?, 1, ?, ?, 'TENANT', CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP + INTERVAL '15 minutes')
                """, UUID.randomUUID(), requestId, tenantAdmin, providerRole))
                .rootCause()
                .hasMessageContaining("Active privileged grants are disabled for this release");
    }

    @Test
    void providerIdentityCannotReceiveFutureTenantResourceAuthority() {
        Long providerUser = userId("admin@dwp.local");
        Long resourceId = jdbc.queryForObject(
                "SELECT resource_id FROM com_resources WHERE tenant_id = 1 ORDER BY resource_id LIMIT 1",
                Long.class);
        Long permissionId = jdbc.queryForObject(
                "SELECT permission_id FROM com_permissions ORDER BY permission_id LIMIT 1",
                Long.class);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO com_principal_resource_grants (
                    tenant_id, principal_type, principal_ref, resource_id, permission_id,
                    source_type, source_ref, lifecycle_state, justification)
                VALUES (1, 'USER', ?, ?, ?, 'ADMIN_DIRECT', ?, 'ACTIVE',
                        'Provider identity boundary integration test')
                """, providerUser.toString(), resourceId, permissionId,
                "provider-boundary-" + UUID.randomUUID()))
                .rootCause()
                .hasMessageContaining("Provider identities cannot hold tenant authority");
    }

    @Test
    void migrationRevokesLegacyProviderReviewersAndTriggersRejectNewOnes() {
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state
                  FROM com_access_review_campaigns
                 WHERE access_review_campaign_id = ?
                """, String.class, legacyProviderReviewCampaignId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("""
                SELECT reviewer_assignment_state
                  FROM com_access_review_items
                 WHERE access_review_item_id = ?
                """, String.class, legacyProviderReviewItemId)).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sys_identity_audit_events
                 WHERE action = 'identity.provider-access-review-assignment.revoked-by-policy'
                   AND actor_type = 'SYSTEM'
                   AND actor_id IS NULL
                   AND correlation_id = 'migration:V103'
                   AND target_id IN (?, ?)
                   AND before_snapshot NOT LIKE '%@%'
                   AND after_snapshot NOT LIKE '%@%'
                """, Integer.class, legacyProviderReviewCampaignId.toString(),
                legacyProviderReviewItemId.toString())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sys_audit_outbox outbox
                  JOIN sys_identity_audit_events event
                    ON event.audit_event_id = outbox.event_id
                 WHERE event.action =
                       'identity.provider-access-review-assignment.revoked-by-policy'
                   AND event.target_id IN (?, ?)
                   AND outbox.payload ->> 'actorType' = 'SYSTEM'
                   AND outbox.payload -> 'actorId' = 'null'::jsonb
                """, Integer.class, legacyProviderReviewCampaignId.toString(),
                legacyProviderReviewItemId.toString())).isEqualTo(2);

        Long providerUser = userId("admin@dwp.local");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO com_access_review_campaigns (
                    access_review_campaign_id, tenant_id, name, scope_type,
                    reviewer_strategy, reviewer_user_id, lifecycle_state, due_at)
                VALUES (?, 1, 'Rejected provider reviewer', 'TENANT',
                        'NAMED_REVIEWER', ?, 'DRAFT',
                        CURRENT_TIMESTAMP + INTERVAL '1 day')
                """, UUID.randomUUID(), providerUser))
                .rootCause()
                .hasMessageContaining("Provider identities cannot be access-review reviewers");
    }

    private static void seedLegacyProviderReviewAssignment() {
        Long providerUser = userId("admin@dwp.local");
        Long subjectUser = userId("hyunwoo.park@sk.com");
        Long tenantRole = roleId("TENANT_ADMIN");
        Long roleMemberId = jdbc.queryForObject("""
                SELECT role_member_id
                  FROM com_role_members
                 WHERE tenant_id = 1 AND user_id = ? AND role_id = ?
                """, Long.class, subjectUser, tenantRole);
        legacyProviderReviewCampaignId = UUID.randomUUID();
        legacyProviderReviewItemId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO com_access_review_campaigns (
                    access_review_campaign_id, tenant_id, name, scope_type,
                    reviewer_strategy, reviewer_user_id, lifecycle_state,
                    due_at, activated_at)
                VALUES (?, 1, 'Legacy provider reviewer', 'TENANT',
                        'NAMED_REVIEWER', ?, 'ACTIVE',
                        CURRENT_TIMESTAMP + INTERVAL '1 day', CURRENT_TIMESTAMP)
                """, legacyProviderReviewCampaignId, providerUser);
        jdbc.update("""
                INSERT INTO com_access_review_items (
                    access_review_item_id, access_review_campaign_id, tenant_id,
                    subject_user_id, role_id, access_source_type, access_source_id,
                    reviewer_user_id)
                VALUES (?, ?, 1, ?, ?, 'DIRECT', ?, ?)
                """, legacyProviderReviewItemId, legacyProviderReviewCampaignId,
                subjectUser, tenantRole, roleMemberId, providerUser);
    }

    private static List<String> roleCodes(String email) {
        return jdbc.queryForList("""
                SELECT role.code
                  FROM com_users user_record
                  JOIN com_role_members membership
                    ON membership.tenant_id = user_record.tenant_id
                   AND membership.user_id = user_record.user_id
                  JOIN com_roles role
                    ON role.tenant_id = membership.tenant_id
                   AND role.role_id = membership.role_id
                 WHERE user_record.email_normalized = ?
                 ORDER BY role.code
                """, String.class, email);
    }

    private static Long userId(String email) {
        return jdbc.queryForObject(
                "SELECT user_id FROM com_users WHERE email_normalized = ?",
                Long.class,
                email);
    }

    private static String identityPlane(String email) {
        return jdbc.queryForObject(
                "SELECT identity_plane FROM com_users WHERE email_normalized = ?",
                String.class,
                email);
    }

    private static Long roleId(String roleCode) {
        return jdbc.queryForObject(
                "SELECT role_id FROM com_roles WHERE tenant_id = 1 AND code = ?",
                Long.class,
                roleCode);
    }
}
