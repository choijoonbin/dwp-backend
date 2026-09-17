package com.dwp.services.platform.home.runtime;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class DwaionHomeProviderMigrationPostgresIntegrationTest {

    private static final String DEFINITION_ID =
            "36400000-0000-0000-0000-000000000002";
    private static final String VERSION_ID =
            "36500000-0000-0000-0000-000000000003";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateLatest() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(source)
                .locations("filesystem:src/main/resources/db/migration")
                .load();
        assertThat(flyway.migrate().migrationsExecuted).isPositive();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        jdbc = new JdbcTemplate(source);
    }

    @Test
    void publishesExactImmutableDefinitionEvidenceAndCurrentCatalogBinding() {
        Map<String, Object> version = jdbc.queryForMap("""
                SELECT semantic_version, manifest_hash, certification_status,
                       manifest -> 'requiredAuthorities' AS authorities,
                       release_state, safety_state, immutable
                  FROM plt_widget_definition_versions
                 WHERE version_id = ?::uuid
                """, VERSION_ID);
        assertThat(version.get("semantic_version")).isEqualTo("1.1.0");
        assertThat(version.get("manifest_hash"))
                .isEqualTo(DwaionHomeWorkloadProtocol.DEFINITION_MANIFEST_HASH);
        assertThat(version.get("certification_status")).isEqualTo("PASS");
        assertThat(String.valueOf(version.get("authorities")))
                .isEqualTo("[\"APP.ASK:VIEW\", \"APP.DWAION_ARTIFACTS:VIEW\"]");
        assertThat(version).containsEntry("release_state", "PUBLISHED")
                .containsEntry("safety_state", "CLEAR")
                .containsEntry("immutable", true);

        assertThat(jdbc.queryForObject("""
                SELECT binding_revision
                  FROM plt_widget_renderer_bindings
                 WHERE renderer_key = 'home.dwaion.artifact'
                """, String.class)).isEqualTo(DwaionHomeWorkloadProtocol.DEFINITION_MANIFEST_HASH);
        assertThat(jdbc.queryForObject("""
                SELECT current_version_id::text || '|' || previous_version_id::text
                  FROM plt_widget_release_channels
                 WHERE definition_id = ?::uuid AND channel = 'STABLE'
                """, String.class, DEFINITION_ID)).isEqualTo(
                VERSION_ID + "|36500000-0000-0000-0000-000000000002");
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM plt_widget_evidence
                 WHERE version_id = ?::uuid
                   AND evidence_status = 'PASS'
                   AND manifest_hash = ?
                   AND evidence_type IN ('MANIFEST', 'SECURITY', 'PRIVACY')
                """, Integer.class, VERSION_ID,
                DwaionHomeWorkloadProtocol.DEFINITION_MANIFEST_HASH)).isEqualTo(3);
    }

    @Test
    void enablesRevisionTwoForEveryLiveTenantAndRetainsShadowRegistrySafety() {
        Integer liveTenants = jdbc.queryForObject("""
                SELECT count(*) FROM sys_service_tenants
                 WHERE lifecycle_state <> 'RETIRED'
                """, Integer.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM adm_tenant_widget_policy_heads head
                  JOIN adm_tenant_widget_policy_revisions policy
                    ON policy.policy_revision_id = head.current_revision_id
                 WHERE head.definition_id = ?::uuid
                   AND policy.revision_number = 2
                   AND policy.policy_state = 'PUBLISHED'
                   AND policy.enabled
                """, Integer.class, DEFINITION_ID)).isEqualTo(liveTenants);
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM plt_widget_registry_events
                 WHERE registry_revision = 26
                   AND aggregate_id = ?
                   AND event_type = 'OWNER_WIDGET_PROVIDER_ACTIVATED'
                """, Integer.class, DEFINITION_ID)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT migration_mode || '|' || runtime_activation_ready::text
                  FROM plt_widget_registry_state
                 WHERE environment = 'GLOBAL'
                """, String.class)).isEqualTo("SHADOW|false");
    }

    @Test
    void leavesRetiredTenantHeadOnItsExistingRevisionDuringUpgrade() {
        String schema = "dwaion_retired_" + UUID.randomUUID().toString().replace("-", "");
        Flyway beforeActivation = flyway(schema, "264");
        assertThat(beforeActivation.migrate().migrationsExecuted).isPositive();

        JdbcTemplate isolated = new JdbcTemplate(dataSource(schema));
        Long tenantId = isolated.queryForObject("""
                SELECT tenant_id
                  FROM adm_tenant_widget_policy_heads
                 WHERE definition_id = ?::uuid
                 ORDER BY tenant_id
                 LIMIT 1
                """, Long.class, DEFINITION_ID);
        String priorRevision = isolated.queryForObject("""
                SELECT current_revision_id::text
                  FROM adm_tenant_widget_policy_heads
                 WHERE tenant_id = ? AND definition_id = ?::uuid
                """, String.class, tenantId, DEFINITION_ID);
        assertThat(isolated.update("""
                UPDATE sys_service_tenants
                   SET lifecycle_state = 'RETIRED'
                 WHERE tenant_id = ?
                """, tenantId)).isEqualTo(1);

        Flyway activation = flyway(schema, null);
        assertThat(activation.migrate().migrationsExecuted).isPositive();
        assertThat(isolated.queryForObject("""
                SELECT current_revision_id::text
                  FROM adm_tenant_widget_policy_heads
                 WHERE tenant_id = ? AND definition_id = ?::uuid
                """, String.class, tenantId, DEFINITION_ID)).isEqualTo(priorRevision);
        assertThat(isolated.queryForObject("""
                SELECT count(*)
                  FROM adm_tenant_widget_policy_revisions
                 WHERE tenant_id = ? AND definition_id = ?::uuid AND revision_number = 2
                """, Integer.class, tenantId, DEFINITION_ID)).isZero();
    }

    private static Flyway flyway(String schema, String target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false);
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private static PGSimpleDataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }

    private static PGSimpleDataSource dataSource(String schema) {
        PGSimpleDataSource source = dataSource();
        source.setCurrentSchema(schema);
        return source;
    }
}
