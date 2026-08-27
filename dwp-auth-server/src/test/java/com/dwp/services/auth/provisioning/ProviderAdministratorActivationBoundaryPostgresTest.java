package com.dwp.services.auth.provisioning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class ProviderAdministratorActivationBoundaryPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static AuthTenantProvisioningService service;
    private static UUID providerTenantId;
    private static Long activeAdministratorUserId;
    private static Long invitedAdministratorUserId;
    private static UUID preBoundaryTokenId;

    @BeforeAll
    static void migrateAcrossTheActivationBoundary() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway baseline = Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .target("103")
                .cleanDisabled(false)
                .load();
        baseline.clean();
        baseline.migrate();
        jdbc = new JdbcTemplate(dataSource);

        providerTenantId = jdbc.queryForObject(
                "SELECT public_id FROM com_tenants WHERE tenant_id = 1",
                UUID.class);
        activeAdministratorUserId = jdbc.queryForObject("""
                SELECT user_record.user_id
                  FROM com_users user_record
                  JOIN com_role_members membership
                    ON membership.tenant_id = user_record.tenant_id
                   AND membership.user_id = user_record.user_id
                  JOIN com_roles role
                    ON role.tenant_id = membership.tenant_id
                   AND role.role_id = membership.role_id
                  JOIN com_user_accounts account
                    ON account.tenant_id = user_record.tenant_id
                   AND account.user_id = user_record.user_id
                 WHERE user_record.tenant_id = 1
                   AND role.code = 'TENANT_ADMIN'
                   AND account.provider_type = 'LOCAL'
                   AND account.provider_id = 'local'
                   AND account.status = 'ACTIVE'
                 ORDER BY user_record.user_id
                 LIMIT 1
                """, Long.class);
        Long accountId = jdbc.queryForObject("""
                SELECT user_account_id
                  FROM com_user_accounts
                 WHERE tenant_id = 1 AND user_id = ?
                   AND provider_type = 'LOCAL' AND provider_id = 'local'
                """, Long.class, activeAdministratorUserId);
        preBoundaryTokenId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sys_account_activation_tokens (
                    activation_token_id, tenant_id, user_id, user_account_id,
                    token_hash, lifecycle_state, expires_at)
                VALUES (?, 1, ?, ?, ?, 'ACTIVE', ?)
                """, preBoundaryTokenId, activeAdministratorUserId, accountId,
                "a".repeat(64), Timestamp.from(Instant.now().plusSeconds(3600)));

        Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .load()
                .migrate();
        service = new AuthTenantProvisioningService(jdbc, new BCryptPasswordEncoder());
        invitedAdministratorUserId = insertInvitedTenantAdministrator();
    }

    @Test
    void migrationRevokesOutstandingCapabilitiesAndDatabaseRejectsNewOnes() {
        assertThat(jdbc.queryForObject("""
                SELECT lifecycle_state
                  FROM sys_account_activation_tokens
                 WHERE activation_token_id = ?
                """, String.class, preBoundaryTokenId)).isEqualTo("REVOKED");

        Long accountId = jdbc.queryForObject("""
                SELECT user_account_id FROM com_user_accounts
                 WHERE tenant_id = 1 AND user_id = ?
                   AND provider_type = 'LOCAL' AND provider_id = 'local'
                """, Long.class, invitedAdministratorUserId);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO sys_account_activation_tokens (
                    tenant_id, user_id, user_account_id, token_hash, expires_at)
                VALUES (1, ?, ?, ?, ?)
                """, invitedAdministratorUserId, accountId, "b".repeat(64),
                Timestamp.from(Instant.now().plusSeconds(3600))))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("customer-owned out-of-band delivery");
    }

    @Test
    void activeTenantAdministratorCannotReceiveAProviderInvitation() {
        assertThatThrownBy(() -> service.issueInvitation(
                providerTenantId,
                new AuthTenantProvisioningDtos.IssueInvitationRequest(
                        activeAdministratorUserId, 60)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("active tenant administrator");
    }

    @Test
    void activeAdministratorCannotUseEvenAnActiveLegacyToken() {
        String token = "simulated-legacy-active-administrator-token";
        Long accountId = jdbc.queryForObject("""
                SELECT user_account_id FROM com_user_accounts
                 WHERE tenant_id = 1 AND user_id = ?
                   AND provider_type = 'LOCAL' AND provider_id = 'local'
                """, Long.class, activeAdministratorUserId);
        String passwordBefore = jdbc.queryForObject("""
                SELECT password_hash FROM com_user_accounts WHERE user_account_id = ?
                """, String.class, accountId);
        try {
            // Simulate a legacy or operationally drifted row. Application
            // enforcement must remain fail-closed even if DB issuance controls
            // are bypassed outside the normal write path.
            jdbc.update("""
                    UPDATE sys_account_activation_tokens
                       SET token_hash = ?, lifecycle_state = 'ACTIVE',
                           revoked_at = NULL, expires_at = ?
                     WHERE activation_token_id = ?
                    """, sha256(token), Timestamp.from(Instant.now().plusSeconds(3600)),
                    preBoundaryTokenId);

            assertThatThrownBy(() -> service.activation(token))
                    .isInstanceOfSatisfying(BaseException.class, error ->
                            assertThat(error.getErrorCode()).isEqualTo(ErrorCode.TOKEN_INVALID));
            assertThatThrownBy(() -> service.activate(
                    token,
                    new AuthTenantProvisioningDtos.ActivateAccountRequest(
                            "Boundary-Test-Password!1")))
                    .isInstanceOfSatisfying(BaseException.class, error ->
                            assertThat(error.getErrorCode()).isEqualTo(ErrorCode.TOKEN_INVALID));
            assertThat(jdbc.queryForObject("""
                    SELECT password_hash FROM com_user_accounts WHERE user_account_id = ?
                    """, String.class, accountId)).isEqualTo(passwordBefore);
        } finally {
            jdbc.update("""
                    UPDATE sys_account_activation_tokens
                       SET lifecycle_state = 'REVOKED', revoked_at = CURRENT_TIMESTAMP
                     WHERE activation_token_id = ?
                    """, preBoundaryTokenId);
        }
    }

    @Test
    void invitedAdministratorAlsoFailsClosedWithoutCustomerOwnedDelivery() {
        assertThatThrownBy(() -> service.issueInvitation(
                providerTenantId,
                new AuthTenantProvisioningDtos.IssueInvitationRequest(
                        invitedAdministratorUserId, 60)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("customer-owned out-of-band delivery");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_account_activation_tokens
                 WHERE tenant_id = 1 AND user_id = ? AND lifecycle_state = 'ACTIVE'
                """, Integer.class, invitedAdministratorUserId)).isZero();
    }

    private static Long insertInvitedTenantAdministrator() {
        String email = "activation-boundary-" + UUID.randomUUID() + "@example.test";
        Long userId = jdbc.queryForObject("""
                INSERT INTO com_users (
                    tenant_id, display_name, email, status, preferred_locale)
                VALUES (1, 'Activation boundary administrator', ?, 'INVITED', 'en-US')
                RETURNING user_id
                """, Long.class, email);
        jdbc.update("""
                INSERT INTO com_user_accounts (
                    tenant_id, user_id, provider_type, provider_id,
                    principal, password_hash, status)
                VALUES (1, ?, 'LOCAL', 'local', ?, NULL, 'INVITED')
                """, userId, email);
        Long tenantAdminRole = jdbc.queryForObject("""
                SELECT role_id FROM com_roles
                 WHERE tenant_id = 1 AND code = 'TENANT_ADMIN'
                """, Long.class);
        jdbc.update("""
                INSERT INTO com_role_members (tenant_id, role_id, user_id)
                VALUES (1, ?, ?)
                """, tenantAdminRole, userId);
        return userId;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
