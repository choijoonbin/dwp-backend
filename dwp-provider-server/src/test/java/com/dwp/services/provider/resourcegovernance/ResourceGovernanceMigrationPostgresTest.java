package com.dwp.services.provider.resourcegovernance;

import com.dwp.services.provider.security.ProviderOperatorService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class ResourceGovernanceMigrationPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private PGSimpleDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        flyway(null).clean();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void v59AddsLeastPrivilegeAuthorityAndImmutableArtifactReviewEvidence() {
        flyway("58").migrate();
        assertThat(permissionCount("RESOURCE_GOVERNANCE_READ")).isZero();

        flyway(null).migrate();

        assertThat(roleCodesFor("RESOURCE_GOVERNANCE_READ")).containsExactly(
                "PROVIDER_ADMIN", "PROVIDER_AUDITOR", "PROVIDER_CHANGE_APPROVER",
                "PROVIDER_ENTITLEMENT_ADMIN", "PROVIDER_OPERATOR");
        assertThat(roleCodesFor("ARTIFACT_GOVERNANCE_APPROVE")).containsExactly(
                "PROVIDER_ADMIN", "PROVIDER_CHANGE_APPROVER", "PROVIDER_RELEASE_APPROVER");

        var operator = new ProviderOperatorService(jdbc).activeOperator(1L, 900001L).orElseThrow();
        assertThat(operator.permissions()).contains(
                "RESOURCE_GOVERNANCE_READ", "RESOURCE_GOVERNANCE_WRITE",
                "RESOURCE_GOVERNANCE_APPROVE",
                "ARTIFACT_GOVERNANCE_READ", "ARTIFACT_GOVERNANCE_WRITE",
                "ARTIFACT_GOVERNANCE_APPROVE", "TENANT_LIFECYCLE_GOVERNANCE_APPROVE");
        assertThat(roleCodesFor("RESOURCE_GOVERNANCE_APPROVE")).containsExactly(
                "PROVIDER_ADMIN", "PROVIDER_CHANGE_APPROVER");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_name = 'prv_resource_commitment_changes'
                """, Integer.class)).isEqualTo(1);

        UUID artifactId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO prv_product_artifact_manifests (
                    artifact_id, product_key, artifact_version, artifact_type,
                    manifest_schema_version, manifest, compatibility_policy,
                    created_by, updated_by)
                VALUES (?, 'workspace.shell', '2026.09.29', 'SERVICE', 1,
                        CAST(? AS jsonb), CAST(? AS jsonb), ?, ?)
                """, artifactId, "{\"entrypoint\":\"internal\"}", """
                        {
                          "schema": {
                            "currentVersion": "2.4", "targetVersion": "2.5",
                            "migrationState": "ADDITIVE_ONLY"
                          },
                          "clients": [],
                          "dependencies": [],
                          "capabilities": {
                            "allowAdded": true, "allowRemoved": false, "allowIncreased": false
                          },
                          "rollback": {"required": true, "strategy": "TRAFFIC_REVERT"}
                        }
                        """,
                operator.operatorId(), operator.operatorId());
        jdbc.update("""
                INSERT INTO prv_product_artifact_reviews (
                    review_id, artifact_id, decision, reason, evidence, reviewed_by)
                VALUES (?, ?, 'RETURNED', 'Independent evidence review', CAST(? AS jsonb), ?)
                """, reviewId, artifactId, "{\"source\":\"internal\"}", operator.operatorId());

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE prv_product_artifact_reviews SET reason = 'Changed' WHERE review_id = ?", reviewId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE prv_product_artifact_manifests SET signature_state = 'VERIFIED' WHERE artifact_id = ?",
                artifactId))
                .isInstanceOf(DataAccessException.class);
        flyway(null).validate();
    }

    private int permissionCount(String permissionCode) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM prv_operator_permission_catalog
                 WHERE permission_code = ?
                """, Integer.class, permissionCode);
    }

    private List<String> roleCodesFor(String permissionCode) {
        return jdbc.queryForList("""
                SELECT role_code FROM prv_operator_role_permissions
                 WHERE permission_code = ?
                 ORDER BY role_code
                """, String.class, permissionCode);
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .cleanDisabled(false);
        if (target != null) configuration.target(target);
        return configuration.load();
    }
}
