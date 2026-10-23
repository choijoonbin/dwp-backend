package com.dwp.services.people.hris.performance.review;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class PerformanceReviewMigrationContractTest {

    private static final String MIGRATION =
            "db/performance-migration/V18__per_base_tfr_per_013.sql";
    private static final List<String> TABLES = List.of(
            "prf_review_assignments",
            "prf_review_command_results",
            "prf_review_submissions");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getenv().getOrDefault("DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));

    @Test
    void definesOnlyTheThreeV18TablesWithTenantRlsAndImmutableEvidence()
            throws IOException {
        String sql = migration();

        assertThat(sql.lines()
                .filter(line -> line.startsWith("CREATE TABLE hris_performance.")))
                .hasSize(3);
        for (String table : TABLES) {
            assertThat(sql)
                    .contains("CREATE TABLE hris_performance." + table)
                    .contains("ALTER TABLE hris_performance." + table
                            + " ENABLE ROW LEVEL SECURITY")
                    .contains("ALTER TABLE hris_performance." + table
                            + " FORCE ROW LEVEL SECURITY")
                    .contains("CREATE POLICY " + table + "_tenant_isolation");
        }
        assertThat(sql)
                .contains("PER_REVIEW_IMMUTABLE_EVIDENCE")
                .contains("fk_prf_review_submissions_receipt")
                .contains("fk_prf_review_command_results_receipt")
                .contains("reviewer_assignment_id UUID NOT NULL")
                .contains("template_version_id UUID NOT NULL")
                .contains("template_content_hash CHAR(64) NOT NULL")
                .doesNotContain("ALTER TABLE hris_performance.prf_cmp_")
                .doesNotContain("ppl_")
                .doesNotContain("hrm_")
                .doesNotContain("REFERENCES public.")
                .doesNotContain("dblink")
                .doesNotContain("http://")
                .doesNotContain("https://");
    }

    @Test
    void cleanPerformanceStreamMigratesThroughV18WithValidatedTenantTables()
            throws Exception {
        DataSource dataSource = dataSource();
        reset(dataSource);

        assertThat(flyway(dataSource, null).migrate().migrationsExecuted).isPositive();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForList("""
                SELECT version
                  FROM hris_performance.flyway_performance_schema_history
                 WHERE success
                 ORDER BY installed_rank
                """, String.class)).contains("1", "2", "3", "16", "17", "18");
        assertThat(jdbc.queryForList("""
                SELECT relname
                  FROM pg_class
                 WHERE relnamespace = 'hris_performance'::regnamespace
                   AND relkind = 'r'
                   AND relname = ANY (?)
                   AND relrowsecurity
                   AND relforcerowsecurity
                 ORDER BY relname
                """, String.class, jdbc.getDataSource().getConnection()
                .createArrayOf("text", TABLES.toArray()))).containsExactlyElementsOf(TABLES);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM pg_constraint constraint_record
                  JOIN pg_class relation_record
                    ON relation_record.oid = constraint_record.conrelid
                 WHERE relation_record.relnamespace =
                       'hris_performance'::regnamespace
                   AND relation_record.relname = ANY (?)
                   AND NOT constraint_record.convalidated
                """, Integer.class, jdbc.getDataSource().getConnection()
                .createArrayOf("text", TABLES.toArray()))).isZero();
    }

    @Test
    void upgradesV17ToV18WithoutChangingV17ChecksumOrCompensationData()
            throws Exception {
        DataSource dataSource = dataSource();
        reset(dataSource);
        flyway(dataSource, "17").migrate();
        UUID artifactId = UUID.randomUUID();
        insertCompensationArtifact(dataSource, artifactId);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Integer checksum = jdbc.queryForObject("""
                SELECT checksum
                  FROM hris_performance.flyway_performance_schema_history
                 WHERE version = '17' AND success
                """, Integer.class);

        assertThat(flyway(dataSource, "18").migrate().migrationsExecuted).isEqualTo(1);

        assertThat(jdbc.queryForObject("""
                SELECT checksum
                  FROM hris_performance.flyway_performance_schema_history
                 WHERE version = '17' AND success
                """, Integer.class)).isEqualTo(checksum);
        assertThat(compensationArtifactExists(dataSource, artifactId)).isTrue();
        assertThat(jdbc.queryForList("""
                SELECT version
                  FROM hris_performance.flyway_performance_schema_history
                 WHERE success
                 ORDER BY installed_rank
                """, String.class)).containsSubsequence("17", "18");
    }

    @Test
    void tenantPolicyBlocksCrossTenantReadsAndWritesForANonOwnerRole()
            throws Exception {
        DataSource dataSource = dataSource();
        reset(dataSource);
        flyway(dataSource, null).migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UUID ownReview = insertReviewAssignment(
                jdbc, PerformanceReviewTestFixtures.TENANT);
        UUID otherReview = insertReviewAssignment(
                jdbc, PerformanceReviewTestFixtures.TENANT + 1);
        String role = "per_review_rls_" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 16);
        jdbc.execute("CREATE ROLE " + role);
        try {
            jdbc.execute("GRANT USAGE ON SCHEMA hris_performance TO " + role);
            jdbc.execute("GRANT SELECT, UPDATE ON "
                    + "hris_performance.prf_review_assignments TO " + role);

            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET ROLE " + role);
                }
                try (PreparedStatement tenant = connection.prepareStatement(
                        "SELECT set_config('dwp.tenant_id', ?, TRUE)")) {
                    tenant.setString(1, Long.toString(PerformanceReviewTestFixtures.TENANT));
                    tenant.execute();
                }
                try (PreparedStatement visible = connection.prepareStatement("""
                        SELECT review_id
                          FROM hris_performance.prf_review_assignments
                         ORDER BY review_id
                        """)) {
                    try (ResultSet rows = visible.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getObject(1, UUID.class)).isEqualTo(ownReview);
                        assertThat(rows.next()).isFalse();
                    }
                }
                try (PreparedStatement crossTenantUpdate = connection.prepareStatement("""
                        UPDATE hris_performance.prf_review_assignments
                           SET stage_key = 'ILLEGAL_CROSS_TENANT_WRITE'
                         WHERE tenant_id = ? AND review_id = ?
                        """)) {
                    crossTenantUpdate.setLong(1, PerformanceReviewTestFixtures.TENANT + 1);
                    crossTenantUpdate.setObject(2, otherReview);
                    assertThat(crossTenantUpdate.executeUpdate()).isZero();
                } finally {
                    connection.rollback();
                }
            }
        } finally {
            jdbc.execute("DROP OWNED BY " + role);
            jdbc.execute("DROP ROLE " + role);
        }
    }

    private static void reset(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA IF EXISTS hris_performance CASCADE");
        jdbc.execute("CREATE SCHEMA hris_performance");
        com.dwp.services.people.hris.performance.support
                .PerformanceMigrationRuntimeRoleFixture.prepare(dataSource);
    }

    private static Flyway flyway(DataSource dataSource, String target) {
        var configuration = Flyway.configure(
                        PerformanceReviewMigrationContractTest.class.getClassLoader())
                .dataSource(dataSource)
                .locations("classpath:db/performance-migration")
                .defaultSchema("hris_performance")
                .schemas("hris_performance")
                .table("flyway_performance_schema_history")
                .validateOnMigrate(true)
                .outOfOrder(false);
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private static void insertCompensationArtifact(
            DataSource dataSource,
            UUID artifactId) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement tenant = connection.prepareStatement(
                    "SELECT set_config('dwp.tenant_id', ?, TRUE)")) {
                tenant.setString(1, Long.toString(PerformanceReviewTestFixtures.TENANT));
                tenant.execute();
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    INSERT INTO hris_performance.prf_per_artifact_versions (
                        tenant_id, public_id, artifact_type, schema_version,
                        artifact_revision, owner_aggregate_type,
                        owner_aggregate_public_id, canonical_content_digest,
                        encrypted_content, encryption_key_version_public_id,
                        effective_from, effective_to, created_at, created_by)
                    VALUES (?, ?, 'COMPENSATION_GUIDELINES', 1, 1,
                            'COMPENSATION_CYCLE', ?, ?, ?, ?,
                            DATE '2027-01-01', NULL, CURRENT_TIMESTAMP, ?)
                    """)) {
                insert.setLong(1, PerformanceReviewTestFixtures.TENANT);
                insert.setObject(2, artifactId);
                insert.setObject(3, UUID.randomUUID());
                insert.setString(4, "a".repeat(64));
                insert.setBytes(5, new byte[29]);
                insert.setObject(6, UUID.randomUUID());
                insert.setObject(7, UUID.randomUUID());
                insert.executeUpdate();
            }
            connection.commit();
        }
    }

    private static UUID insertReviewAssignment(JdbcTemplate jdbc, long tenantId) {
        UUID reviewId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO hris_performance.prf_review_assignments (
                    tenant_id, review_id, participant_id, reviewer_assignment_id,
                    reviewer_principal_public_id, organization_public_id,
                    reviewer_type, stage_key, review_state, template_version_id,
                    template_content_hash, aggregate_version, writable,
                    draft_content_hash, form_definition, draft_answers,
                    created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, 'MANAGER', 'MANAGER_REVIEW',
                        'NOT_STARTED', ?, ?, 0, TRUE, ?, '{}'::jsonb, '[]'::jsonb,
                        ?, ?)
                """, tenantId, reviewId, participantId, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "a".repeat(64), "b".repeat(64),
                PerformanceReviewTestFixtures.ACTOR,
                PerformanceReviewTestFixtures.ACTOR);
        return reviewId;
    }

    private static boolean compensationArtifactExists(
            DataSource dataSource,
            UUID artifactId) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement tenant = connection.prepareStatement(
                    "SELECT set_config('dwp.tenant_id', ?, TRUE)")) {
                tenant.setString(1, Long.toString(PerformanceReviewTestFixtures.TENANT));
                tenant.execute();
            }
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT COUNT(*)
                      FROM hris_performance.prf_per_artifact_versions
                     WHERE tenant_id = ? AND public_id = ?
                    """)) {
                query.setLong(1, PerformanceReviewTestFixtures.TENANT);
                query.setObject(2, artifactId);
                try (ResultSet rows = query.executeQuery()) {
                    rows.next();
                    return rows.getInt(1) == 1;
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private static DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    private static String migration() throws IOException {
        return new ClassPathResource(MIGRATION)
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
