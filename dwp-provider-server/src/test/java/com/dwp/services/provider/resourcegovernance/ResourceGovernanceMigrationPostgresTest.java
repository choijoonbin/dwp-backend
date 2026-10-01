package com.dwp.services.provider.resourcegovernance;

import com.dwp.services.provider.security.ProviderOperatorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void boundedGovernanceQueriesAreValidAgainstTheMigratedSchema() {
        flyway(null).migrate();
        var named = new NamedParameterJdbcTemplate(dataSource);
        var mapper = new ObjectMapper().findAndRegisterModules();
        var resources = new ResourceCommitmentJdbcRepository(named, mapper);
        var lifecycle = new TenantLifecycleGovernanceJdbcRepository(named, mapper);
        var artifacts = new ArtifactGovernanceJdbcRepository(named, mapper);

        assertThat(resources.commitments(null, 101)).isEmpty();
        assertThat(resources.resourceChanges(null, 101)).isEmpty();
        assertThat(lifecycle.lifecycleRequests(null, 101)).isEmpty();
        assertThat(artifacts.artifacts(101)).isEmpty();
        assertThat(artifacts.plans(101)).isEmpty();
        assertThat(artifacts.reviews(UUID.randomUUID(), 51)).isEmpty();
        assertThat(artifacts.evidence(UUID.randomUUID(), 51)).isEmpty();
        assertThat(artifacts.readinessEvidence(UUID.randomUUID())).isEmpty();

        NamedParameterJdbcTemplate capturedJdbc = mock(NamedParameterJdbcTemplate.class);
        when(capturedJdbc.query(
                anyString(), any(MapSqlParameterSource.class),
                ArgumentMatchers.<RowMapper<
                        ResourceGovernanceRepository.ArtifactRow>>any()))
                .thenReturn(List.of());
        new ArtifactGovernanceJdbcRepository(capturedJdbc, mapper).artifacts(101);
        var sql = ArgumentCaptor.forClass(String.class);
        verify(capturedJdbc).query(
                sql.capture(), any(MapSqlParameterSource.class),
                ArgumentMatchers.<RowMapper<
                        ResourceGovernanceRepository.ArtifactRow>>any());
        assertThat(sql.getValue())
                .contains("artifact.artifact_id DESC", "LIMIT :fetchLimit");
        flyway(null).validate();
    }

    @Test
    void lifecycleCancellationIsOwnerBoundVersionedAndTerminal() {
        flyway(null).migrate();
        var repository = new TenantLifecycleGovernanceJdbcRepository(
                new NamedParameterJdbcTemplate(dataSource),
                new ObjectMapper().findAndRegisterModules());
        UUID tenantId = jdbc.queryForObject(
                "SELECT provider_tenant_id FROM prv_tenants ORDER BY created_at LIMIT 1",
                UUID.class);
        Long ownerId = jdbc.queryForObject(
                "SELECT provider_operator_id FROM prv_operators ORDER BY provider_operator_id LIMIT 1",
                Long.class);
        UUID requestId = UUID.randomUUID();
        repository.createLifecycleRequest(
                requestId,
                tenantId,
                new ResourceGovernanceDtos.CreateTenantLifecycleRequest(
                        "PURGE", "Contract ended"),
                "DRAFT",
                "OWNER_VERIFICATION_REQUIRED",
                List.of(),
                ownerId);

        assertThat(repository.cancelLifecycleRequest(
                requestId, 0L, ownerId + 10_000L, "Wrong owner")).isFalse();
        assertThat(repository.cancelLifecycleRequest(
                requestId, 1L, ownerId, "Stale version")).isFalse();
        assertThat(repository.cancelLifecycleRequest(
                requestId, 0L, ownerId, "Request is no longer required")).isTrue();

        var cancelled = repository.lifecycleRequest(requestId).orElseThrow();
        assertThat(cancelled.lifecycleState()).isEqualTo("CANCELLED");
        assertThat(cancelled.executionState()).isEqualTo("NOT_REQUIRED");
        assertThat(cancelled.decisionReason()).isEqualTo("Request is no longer required");
        assertThat(cancelled.version()).isEqualTo(1L);
        assertThat(repository.cancelLifecycleRequest(
                requestId, 1L, ownerId, "Cannot cancel twice")).isFalse();
        flyway(null).validate();
    }

    @Test
    void v63ReconcilesExistingCancelledRequestsToNoHandoffRequired() {
        flyway("62").migrate();
        UUID tenantId = jdbc.queryForObject(
                "SELECT provider_tenant_id FROM prv_tenants ORDER BY created_at LIMIT 1",
                UUID.class);
        Long ownerId = jdbc.queryForObject(
                "SELECT provider_operator_id FROM prv_operators ORDER BY provider_operator_id LIMIT 1",
                Long.class);
        UUID requestId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO prv_tenant_lifecycle_requests (
                    lifecycle_request_id, provider_tenant_id, requested_action,
                    lifecycle_state, hold_evaluation_state, justification,
                    requested_by, decision_reason)
                VALUES (?, ?, 'RETIRE', 'CANCELLED', 'OWNER_VERIFICATION_REQUIRED',
                        'Historical cancellation', ?, 'Cancelled before V63')
                """, requestId, tenantId, ownerId);
        assertThat(jdbc.queryForObject("""
                SELECT execution_state FROM prv_tenant_lifecycle_requests
                 WHERE lifecycle_request_id = ?
                """, String.class, requestId)).isEqualTo("OWNER_HANDOFF_REQUIRED");

        flyway(null).migrate();

        assertThat(jdbc.queryForObject("""
                SELECT execution_state FROM prv_tenant_lifecycle_requests
                 WHERE lifecycle_request_id = ?
                """, String.class, requestId)).isEqualTo("NOT_REQUIRED");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE prv_tenant_lifecycle_requests
                   SET execution_state = 'OWNER_HANDOFF_REQUIRED'
                 WHERE lifecycle_request_id = ?
                """, requestId)).isInstanceOf(DataAccessException.class);
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
