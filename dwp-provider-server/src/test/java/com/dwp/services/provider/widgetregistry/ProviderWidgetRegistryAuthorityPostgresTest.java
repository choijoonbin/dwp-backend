package com.dwp.services.provider.widgetregistry;

import static org.assertj.core.api.Assertions.assertThat;

import com.dwp.services.provider.security.ProviderOperatorService;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ProviderWidgetRegistryAuthorityPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private PGSimpleDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        dataSource = dataSource();
        flyway(null).clean();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void resolvesWidgetPermissionsAndOnlyActiveDatabaseOwnerScopes() {
        flyway(null).migrate();
        ProviderOperatorService operators = new ProviderOperatorService(jdbc);

        var actor = operators.activeOperator(1L, 900001L).orElseThrow();

        assertThat(actor.permissions()).contains(
                "WIDGET_CATALOG_READ",
                "WIDGET_DEFINITION_WRITE",
                "WIDGET_DEFINITION_REVIEW",
                "WIDGET_DEFINITION_RELEASE",
                "WIDGET_DEFINITION_REVOKE");
        assertThat(actor.ownerProductKeys()).containsExactlyInAnyOrder(
                "core.activity", "core.calendar", "core.work", "core.workspace");

        jdbc.update("""
                UPDATE prv_operator_widget_owner_scopes
                   SET lifecycle_state = 'REVOKED'
                 WHERE provider_operator_id = ?
                   AND owner_product_key = 'core.work'
                """, actor.operatorId());

        assertThat(operators.activeOperator(1L, 900001L).orElseThrow().ownerProductKeys())
                .containsExactlyInAnyOrder("core.activity", "core.calendar", "core.workspace")
                .doesNotContain("core.work");

        flyway(null).validate();
    }

    @Test
    void v57UpgradeBackfillsOnlyCurrentHumanAuthoritiesAndPreservesSupportContainment() {
        flyway("56").migrate();
        long adminId = operatorId(900001L);
        assertThat(hasEffectiveSupportAuthority(adminId)).isTrue();
        assertThat(systemAuthorityEdgeCount()).isZero();

        long suspendedOperator = insertOperator(991001L, "Suspended widget operator", "SUSPENDED");
        insertAssignment(suspendedOperator, "PROVIDER_OPERATOR", null, null);
        long expiredOperator = insertOperator(991002L, "Expired widget operator", "ACTIVE");
        insertAssignment(expiredOperator, "PROVIDER_OPERATOR", "-2 days", "-1 day");
        long futureOperator = insertOperator(991003L, "Future widget operator", "ACTIVE");
        insertAssignment(futureOperator, "PROVIDER_OPERATOR", "+1 day", "+2 days");
        long supportOperator = insertOperator(991004L, "Unrelated support operator", "ACTIVE");
        insertAssignment(supportOperator, "PROVIDER_SUPPORT", null, null);

        flyway(null).migrate();

        assertThat(latestVersion()).isEqualTo(57);
        assertThat(jdbc.queryForList("""
                SELECT owner_product_key
                  FROM prv_operator_widget_owner_scopes
                 WHERE provider_operator_id = ?
                 ORDER BY owner_product_key
                """, String.class, adminId)).containsExactly(
                        "core.activity", "core.calendar", "core.work", "core.workspace");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM prv_operator_widget_owner_scopes scope
                  JOIN prv_operators operator
                    ON operator.provider_operator_id = scope.provider_operator_id
                 WHERE operator.auth_tenant_id <= 0
                    OR operator.auth_user_id <= 0
                    OR operator.lifecycle_state <> 'ACTIVE'
                """, Integer.class)).isZero();
        assertThat(ownerScopeCount(expiredOperator)).isZero();
        assertThat(ownerScopeCount(futureOperator)).isZero();
        assertThat(ownerScopeCount(supportOperator)).isZero();
        assertThat(ownerScopeCount(suspendedOperator)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM prv_operator_widget_owner_scopes scope
                  JOIN prv_operators operator
                    ON operator.provider_operator_id = scope.provider_operator_id
                 WHERE scope.created_by <> operator.auth_user_id
                """, Integer.class)).isZero();
        assertThat(hasEffectiveSupportAuthority(adminId)).isTrue();
        assertThat(systemAuthorityEdgeCount()).isZero();
        flyway(null).validate();
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .cleanDisabled(false);
        if (target != null) {
            configuration.target(MigrationVersion.fromVersion(target));
        }
        return configuration.load();
    }

    private long insertOperator(
            long authUserId, String displayName, String lifecycleState) {
        return jdbc.queryForObject("""
                INSERT INTO prv_operators (
                    auth_tenant_id, auth_user_id, display_name,
                    role_code, lifecycle_state)
                VALUES (1, ?, ?, 'PROVIDER_OPERATOR', ?)
                RETURNING provider_operator_id
                """, Long.class, authUserId, displayName, lifecycleState);
    }

    private void insertAssignment(
            long operatorId, String roleCode, String validFromOffset, String validToOffset) {
        jdbc.update("""
                INSERT INTO prv_operator_role_assignments (
                    provider_operator_id, role_code, lifecycle_state,
                    valid_from, valid_to, created_by)
                VALUES (?, ?, 'ACTIVE',
                        statement_timestamp() + CAST(? AS INTERVAL),
                        statement_timestamp() + CAST(? AS INTERVAL),
                        900001)
                """, operatorId, roleCode, validFromOffset, validToOffset);
    }

    private long operatorId(long authUserId) {
        return jdbc.queryForObject("""
                SELECT provider_operator_id
                  FROM prv_operators
                 WHERE auth_tenant_id = 1
                   AND auth_user_id = ?
                """, Long.class, authUserId);
    }

    private int ownerScopeCount(long operatorId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM prv_operator_widget_owner_scopes
                 WHERE provider_operator_id = ?
                """, Integer.class, operatorId);
    }

    private int latestVersion() {
        return jdbc.queryForObject("""
                SELECT MAX(version::INTEGER)
                  FROM flyway_schema_history
                 WHERE success = TRUE
                   AND version IS NOT NULL
                """, Integer.class);
    }

    private boolean hasEffectiveSupportAuthority(long operatorId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT prv_operator_has_effective_support_authority(?)
                """, Boolean.class, operatorId));
    }

    private int systemAuthorityEdgeCount() {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM prv_operator_role_assignments assignment
                  JOIN prv_operators operator
                    ON operator.provider_operator_id = assignment.provider_operator_id
                 WHERE operator.auth_tenant_id = -1
                    OR assignment.role_code = 'PROVIDER_SYSTEM_CONTAINMENT'
                """, Integer.class);
    }

    private PGSimpleDataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }
}
