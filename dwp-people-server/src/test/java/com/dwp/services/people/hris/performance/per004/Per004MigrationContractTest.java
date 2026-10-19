package com.dwp.services.people.hris.performance.per004;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class Per004MigrationContractTest {
    private static final List<String> TABLES = List.of(
            "prf_per004_artifact_preparations",
            "prf_per004_configuration_version_bindings",
            "prf_per004_owner_evidence_receipts");
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getenv().getOrDefault("DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));

    @Test
    void cleanMigrationHasOnlyThreeNewForcedTenantTables() {
        DataSource source = dataSource();
        reset(source);
        assertThat(flyway(source, null).migrate().migrationsExecuted).isPositive();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM hris_performance.flyway_performance_schema_history
                 WHERE version = '19' AND success
                """, Integer.class)).isEqualTo(1);
        for (String table : TABLES) {
            assertThat(jdbc.queryForObject("""
                    SELECT relrowsecurity AND relforcerowsecurity
                      FROM pg_class WHERE oid = ?::regclass
                    """, Boolean.class, "hris_performance." + table)).isTrue();
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_class
                 WHERE relnamespace = 'hris_performance'::regnamespace
                   AND relkind = 'r' AND relname LIKE 'prf_per004_%'
                """, Integer.class)).isEqualTo(3);
    }

    @Test
    void v18UpgradePreservesCompensationAndEnforcesPerDateMappingsAndImmutability() throws Exception {
        DataSource source = dataSource();
        reset(source);
        flyway(source, "18").migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        Integer oldChecksum = jdbc.queryForObject("""
                SELECT checksum FROM hris_performance.flyway_performance_schema_history
                 WHERE version = '18' AND success
                """, Integer.class);
        UUID compensation = insertArtifact(source, "COMPENSATION_GUIDELINES",
                "COMPENSATION_CYCLE", true);
        assertThat(flyway(source, null).migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT checksum FROM hris_performance.flyway_performance_schema_history
                 WHERE version = '18' AND success
                """, Integer.class)).isEqualTo(oldChecksum);
        assertThat(artifactExists(source, compensation)).isTrue();
        UUID form = insertArtifact(source, "PERFORMANCE_REVIEW_FORM",
                "PERFORMANCE_CONFIGURATION_VERSION", false);
        assertThat(artifactExists(source, form)).isTrue();
        assertThatThrownBy(() -> insertArtifact(source, "COMPENSATION_GUIDELINES",
                "COMPENSATION_CYCLE", false)).hasMessageContaining("ck_prf_per_artifact_versions_dates");
        assertThatThrownBy(() -> insertArtifact(source, "PERFORMANCE_POLICY",
                "PERFORMANCE_CONFIGURATION_VERSION", true)).hasMessageContaining("ck_prf_per_artifact_versions_dates");
        assertThatThrownBy(() -> updateArtifact(source, form))
                .hasMessageContaining("PER_COMPENSATION_APPEND_ONLY");
    }

    @Test
    void missingTenantAndCrossTenantRlsDenyPreparedRows() throws Exception {
        DataSource source = dataSource();
        reset(source);
        flyway(source, null).migrate();
        UUID artifact = insertArtifact(source, "PERFORMANCE_POLICY",
                "PERFORMANCE_CONFIGURATION_VERSION", false);
        UUID configuration = UUID.randomUUID();
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            tenant(connection, 42);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO hris_performance.prf_per004_artifact_preparations (
                        tenant_id, configuration_id, artifact_public_id, artifact_revision,
                        create_subject_principal_public_id, originating_action,
                        create_idempotency_key, create_request_digest, schema_key,
                        schema_version, payload_reference, payload_digest, scope_digest,
                        preparation_receipt_id)
                    VALUES (42, ?, ?, 1, ?, 'create-version', 'key', ?,
                        'dwp.hris.performance.policy.v1', 1, ?, ?, ?, ?)
                    """)) {
                statement.setObject(1, configuration);
                statement.setObject(2, artifact);
                statement.setObject(3, UUID.randomUUID());
                statement.setString(4, "a".repeat(64));
                statement.setString(5, "ref_per_artifact_" + artifact);
                statement.setString(6, "b".repeat(64));
                statement.setString(7, "c".repeat(64));
                statement.setObject(8, UUID.randomUUID());
                statement.executeUpdate();
            }
            connection.commit();
        }
        JdbcTemplate jdbc = new JdbcTemplate(source);
        String role = "per004_rls_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.execute("CREATE ROLE " + role);
        try {
            jdbc.execute("GRANT USAGE ON SCHEMA hris_performance TO " + role);
            jdbc.execute("GRANT SELECT ON hris_performance.prf_per004_artifact_preparations TO " + role);
            assertThat(countAs(source, role, null)).isZero();
            assertThat(countAs(source, role, 43L)).isZero();
            assertThat(countAs(source, role, 42L)).isEqualTo(1);
        } finally {
            jdbc.execute("DROP OWNED BY " + role);
            jdbc.execute("DROP ROLE " + role);
        }
    }

    private static int countAs(DataSource source, String role, Long tenantId) throws Exception {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement setRole = connection.prepareStatement("SET LOCAL ROLE " + role)) {
                setRole.execute();
            }
            if (tenantId != null) tenant(connection, tenantId);
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT count(*) FROM hris_performance.prf_per004_artifact_preparations")) {
                try (ResultSet result = query.executeQuery()) {
                    result.next();
                    return result.getInt(1);
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private static UUID insertArtifact(DataSource source, String type, String owner, boolean dated)
            throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            tenant(connection, 42);
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO hris_performance.prf_per_artifact_versions (
                        tenant_id, public_id, artifact_type, schema_version, artifact_revision,
                        owner_aggregate_type, owner_aggregate_public_id, canonical_content_digest,
                        encrypted_content, encryption_key_version_public_id, effective_from,
                        created_at, created_by)
                    VALUES (42, ?, ?, 1, 1, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?)
                    """)) {
                insert.setObject(1, id);
                insert.setString(2, type);
                insert.setString(3, owner);
                insert.setObject(4, UUID.randomUUID());
                insert.setString(5, "a".repeat(64));
                insert.setBytes(6, new byte[29]);
                insert.setObject(7, UUID.randomUUID());
                insert.setObject(8, dated ? java.sql.Date.valueOf("2027-01-01") : null);
                insert.setObject(9, UUID.randomUUID());
                insert.executeUpdate();
            }
            connection.commit();
        }
        return id;
    }

    private static boolean artifactExists(DataSource source, UUID id) throws Exception {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            tenant(connection, 42);
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT count(*) FROM hris_performance.prf_per_artifact_versions
                     WHERE tenant_id = 42 AND public_id = ?
                    """)) {
                query.setObject(1, id);
                try (ResultSet result = query.executeQuery()) {
                    result.next();
                    return result.getInt(1) == 1;
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private static void updateArtifact(DataSource source, UUID id) throws Exception {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            tenant(connection, 42);
            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE hris_performance.prf_per_artifact_versions
                       SET canonical_content_digest = ? WHERE tenant_id = 42 AND public_id = ?
                    """)) {
                update.setString(1, "b".repeat(64));
                update.setObject(2, id);
                update.executeUpdate();
            }
        }
    }

    private static void tenant(Connection connection, long tenantId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('dwp.tenant_id', ?, TRUE)")) {
            statement.setString(1, Long.toString(tenantId));
            statement.execute();
        }
    }
    private static void reset(DataSource source) {
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP SCHEMA IF EXISTS hris_performance CASCADE");
        jdbc.execute("CREATE SCHEMA hris_performance");
    }
    private static Flyway flyway(DataSource source, String target) {
        var configuration = Flyway.configure(Per004MigrationContractTest.class.getClassLoader())
                .dataSource(source).locations("classpath:db/performance-migration")
                .defaultSchema("hris_performance").schemas("hris_performance")
                .table("flyway_performance_schema_history").validateOnMigrate(true)
                .outOfOrder(false);
        if (target != null) configuration.target(target);
        return configuration.load();
    }
    private static DataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }
}
