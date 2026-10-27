package com.dwp.services.time.absence.employment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.database.RuntimeRoutineExecutionGuard;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmploymentAbsenceProjectionMigrationPostgresTest {

    private static final long TENANT = 83_003L;
    private static final UUID RELATIONSHIP_ID =
            UUID.fromString("83003000-0000-4000-8000-000000000001");
    private static final UUID LEGAL_ENTITY_ID =
            UUID.fromString("83003000-0000-4000-8000-000000000002");

    private EmploymentAbsenceProjectionMigrationDatabase database;
    private JdbcTemplate superuser;
    private JdbcTemplate migration;
    private JdbcTemplate runtime;

    @BeforeAll
    void migrateCleanDatabaseAndPrepareV13Upgrade() {
        assertThat(DockerClientFactory.instance().isDockerAvailable())
                .as("Docker is required for the hermetic TIM-003 V14 migration gate")
                .isTrue();
        database = EmploymentAbsenceProjectionMigrationDatabase.instance();
        superuser = new JdbcTemplate(database.superuserDataSource());
        migration = new JdbcTemplate(database.migrationDataSource());
        runtime = new JdbcTemplate(database.runtimeDataSource());
    }

    @Test
    void cleanAndV13UpgradeInstallExactlyTheLeasedV14Table() {
        assertThat(database.cleanFlyway().validateWithResult().validationSuccessful).isTrue();
        assertThat(database.cleanFlyway().migrate().migrationsExecuted).isZero();
        assertThat(superuser.queryForObject("""
                SELECT count(*)
                  FROM flyway_schema_history
                 WHERE success
                   AND version = '14'
                   AND script =
                       'V14__tim_base_tfr_tim_003_employment_absence_projection.sql'
                """, Integer.class)).isOne();
        assertThat(superuser.queryForObject("""
                SELECT count(*)
                  FROM pg_class
                 WHERE relnamespace = 'public'::regnamespace
                   AND relkind = 'r'
                   AND relname LIKE 'abs_employment%'
                """, Integer.class)).isOne();
        assertThat(superuser.queryForObject("""
                SELECT relrowsecurity AND relforcerowsecurity
                  FROM pg_class
                 WHERE relnamespace = 'public'::regnamespace
                   AND relname = 'abs_employment_relationship_projections'
                """, Boolean.class)).isTrue();

        assertThat(database.upgradeFlyway().migrate().migrationsExecuted).isOne();
        assertThat(database.upgradeFlyway().info().current().getVersion())
                .isEqualTo(MigrationVersion.fromVersion("14"));
        assertThat(database.upgradeFlyway().migrate().migrationsExecuted).isZero();
        assertThat(database.upgradeFlyway().validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void assignmentValidatorAcceptsZeroRevisionAndRejectsEveryMalformedShape() {
        String valid = assignment(
                "83003000-0000-4000-8000-000000000010", "0", LEGAL_ENTITY_ID,
                "null", "null", "null", "2026-01-01", "null", "");
        assertThat(validAssignments("[" + valid + "]", LEGAL_ENTITY_ID)).isTrue();
        assertThat(validAssignments("[]", LEGAL_ENTITY_ID)).isTrue();

        Map<String, String> malformed = Map.ofEntries(
                Map.entry("negative revision", assignment(
                        "83003000-0000-4000-8000-000000000010", "-1",
                        LEGAL_ENTITY_ID, "null", "null", "null",
                        "2026-01-01", "null", "")),
                Map.entry("fractional revision", assignment(
                        "83003000-0000-4000-8000-000000000010", "1.5",
                        LEGAL_ENTITY_ID, "null", "null", "null",
                        "2026-01-01", "null", "")),
                Map.entry("revision overflow", assignment(
                        "83003000-0000-4000-8000-000000000010",
                        "9223372036854775808", LEGAL_ENTITY_ID,
                        "null", "null", "null", "2026-01-01", "null", "")),
                Map.entry("uppercase uuid", assignment(
                        "83003000-0000-4000-8000-0000000000AA", "1",
                        LEGAL_ENTITY_ID, "null", "null", "null",
                        "2026-01-01", "null", "")),
                Map.entry("wrong legal entity", assignment(
                        "83003000-0000-4000-8000-000000000010", "1",
                        UUID.fromString("83003000-0000-4000-8000-000000000099"),
                        "null", "null", "null", "2026-01-01", "null", "")),
                Map.entry("unpaired location", assignment(
                        "83003000-0000-4000-8000-000000000010", "1",
                        LEGAL_ENTITY_ID, "null",
                        "\"83003000-0000-4000-8000-000000000011\"", "null",
                        "2026-01-01", "null", "")),
                Map.entry("invalid date", assignment(
                        "83003000-0000-4000-8000-000000000010", "1",
                        LEGAL_ENTITY_ID, "null", "null", "null",
                        "2026-02-30", "null", "")),
                Map.entry("unknown key", assignment(
                        "83003000-0000-4000-8000-000000000010", "1",
                        LEGAL_ENTITY_ID, "null", "null", "null",
                        "2026-01-01", "null", ",\"unknown\":true")));
        malformed.forEach((description, json) -> assertThat(validAssignments(
                "[" + json + "]", LEGAL_ENTITY_ID)).as(description).isFalse());

        String later = assignment(
                "83003000-0000-4000-8000-000000000020", "1", LEGAL_ENTITY_ID,
                "null", "null", "null", "2026-01-01", "null", "");
        assertThat(validAssignments("[" + later + "," + valid + "]", LEGAL_ENTITY_ID))
                .as("assignment ids must be strictly ordered and unique")
                .isFalse();
        assertThat(validAssignments("[" + valid + "," + valid + "]", LEGAL_ENTITY_ID))
                .as("assignment ids must be unique")
                .isFalse();
    }

    @Test
    void exactRuntimePrivilegesAndProjectionCasAreDatabaseEnforced() {
        RuntimeRoutineExecutionGuard.verifyExact(
                "Time",
                database.runtimeDataSource(),
                List.of("public"),
                Set.of(new RuntimeRoutineExecutionGuard.Routine(
                        "public",
                        "tim003_assignment_snapshots_structurally_valid",
                        "jsonb,uuid")));

        assertThat(superuser.queryForObject("""
                SELECT has_table_privilege(
                           ?, 'public.abs_employment_relationship_projections', 'SELECT')
                   AND NOT has_table_privilege(
                           ?, 'public.abs_employment_relationship_projections', 'INSERT')
                   AND NOT has_table_privilege(
                           ?, 'public.abs_employment_relationship_projections', 'UPDATE')
                   AND NOT has_table_privilege(
                           ?, 'public.abs_employment_relationship_projections', 'DELETE')
                   AND NOT has_table_privilege(
                           ?, 'public.abs_employment_relationship_projections', 'TRUNCATE')
                   AND NOT has_table_privilege(
                           ?, 'public.abs_employment_relationship_projections', 'REFERENCES')
                   AND NOT has_table_privilege(
                           ?, 'public.abs_employment_relationship_projections', 'TRIGGER')
                """, Boolean.class,
                EmploymentAbsenceProjectionMigrationDatabase.RUNTIME_ROLE,
                EmploymentAbsenceProjectionMigrationDatabase.RUNTIME_ROLE,
                EmploymentAbsenceProjectionMigrationDatabase.RUNTIME_ROLE,
                EmploymentAbsenceProjectionMigrationDatabase.RUNTIME_ROLE,
                EmploymentAbsenceProjectionMigrationDatabase.RUNTIME_ROLE,
                EmploymentAbsenceProjectionMigrationDatabase.RUNTIME_ROLE,
                EmploymentAbsenceProjectionMigrationDatabase.RUNTIME_ROLE)).isTrue();

        assertThat(columnGrants("INSERT")).containsExactlyInAnyOrderElementsOf(
                insertColumns());
        assertThat(columnGrants("UPDATE")).containsExactlyInAnyOrderElementsOf(
                updateColumns());
        assertThat(superuser.queryForObject("""
                SELECT has_function_privilege(
                           ?, 'public.tim003_assignment_snapshots_structurally_valid(jsonb,uuid)',
                           'EXECUTE')
                   AND NOT has_function_privilege(
                           ?, 'public.tim003_seal_employment_projection_update()',
                           'EXECUTE')
                """, Boolean.class,
                EmploymentAbsenceProjectionMigrationDatabase.RUNTIME_ROLE,
                EmploymentAbsenceProjectionMigrationDatabase.RUNTIME_ROLE)).isTrue();
        assertThat(superuser.queryForObject("""
                SELECT count(*)
                  FROM information_schema.routine_privileges
                 WHERE grantee = 'PUBLIC'
                   AND routine_schema = 'public'
                   AND routine_name IN (
                       'tim003_assignment_snapshots_structurally_valid',
                       'tim003_seal_employment_projection_update')
                   AND privilege_type = 'EXECUTE'
                """, Integer.class)).isZero();

        tenant(database.runtimeDataSource(), TENANT, () -> runtime.update("""
                INSERT INTO abs_employment_relationship_projections (
                    tenant_id, relationship_public_id, freshness_state,
                    last_observed_event_sequence, required_relationship_revision,
                    shared_offset_sequence, gap_expected_sequence,
                    last_invalidation_event_id, last_invalidation_change_type,
                    last_invalidation_effective_date,
                    last_invalidation_payload_sha256, last_correlation_id,
                    recovery_outcome)
                VALUES (?, ?, 'GAP', 2, 2, 0, 1, ?, 'RELATIONSHIP_CHANGED',
                        ?, ?, 'tim003-migration-test', 'NOT_ATTEMPTED')
                """, TENANT, RELATIONSHIP_ID,
                UUID.fromString("83003000-0000-4000-8000-000000000003"),
                LocalDate.parse("2026-10-21"), "a".repeat(64)));

        assertThat(tenant(database.runtimeDataSource(), TENANT, () -> runtime.update("""
                UPDATE abs_employment_relationship_projections
                   SET freshness_state = 'REFETCH_PENDING',
                       gap_expected_sequence = NULL,
                       recovery_outcome = 'PENDING',
                       last_recovery_at = ?
                 WHERE tenant_id = ?
                   AND relationship_public_id = ?
                   AND projection_version = 1
                """, Timestamp.from(Instant.parse("2026-10-21T08:00:00Z")),
                TENANT, RELATIONSHIP_ID))).isOne();
        assertThat(tenant(database.runtimeDataSource(), TENANT, () -> runtime.queryForObject("""
                SELECT projection_version
                  FROM abs_employment_relationship_projections
                 WHERE tenant_id = ? AND relationship_public_id = ?
                """, Long.class, TENANT, RELATIONSHIP_ID))).isEqualTo(2L);
        assertThat(tenant(database.runtimeDataSource(), TENANT, () -> runtime.update("""
                UPDATE abs_employment_relationship_projections
                   SET freshness_state = 'REFETCH_PENDING'
                 WHERE tenant_id = ?
                   AND relationship_public_id = ?
                   AND projection_version = 1
                """, TENANT, RELATIONSHIP_ID))).isZero();

        UUID recoveredRelationship =
                UUID.fromString("83003000-0000-4000-8000-000000000004");
        assertThat(tenant(database.runtimeDataSource(), TENANT, () -> runtime.update("""
                INSERT INTO abs_employment_relationship_projections (
                    tenant_id, relationship_public_id, worker_public_id,
                    freshness_state, shared_offset_sequence,
                    owner_relationship_revision, worker_status, relationship_type,
                    relationship_status, legal_entity_public_id,
                    relationship_effective_from, owner_effective_on,
                    assignment_snapshots, owner_contract_id, owner_schema_version,
                    owner_revision, owner_as_of, owner_valid_until,
                    owner_content_sha256, owner_service, purpose_code,
                    owner_scope_digest, owner_business_time_zone,
                    owner_minimum_relationship_revision, tenant_grant_revision,
                    authorization_revision, field_policy_revision, owner_issued_at,
                    owner_evidence_digest, owner_key_id,
                    recovery_outcome, last_recovery_at)
                VALUES (?, ?, ?, 'CURRENT', 5, 5, 'ACTIVE', 'EMPLOYEE', 'ACTIVE',
                        ?, ?, ?, CAST('[]' AS JSONB),
                        'SVC-PEP-HRM-007/TIME_ABSENCE_V1', 1, 5, ?, ?, ?,
                        'dwp-people-server', 'TIM_LEAVE_EMPLOYMENT_RECOVERY', ?,
                        'UTC', 0, 1, 1, 1, ?, ?, 'tim003-owner-key',
                        'SUCCEEDED', ?)
                """, TENANT, recoveredRelationship,
                UUID.fromString("83003000-0000-4000-8000-000000000005"),
                LEGAL_ENTITY_ID, LocalDate.parse("2026-01-01"),
                LocalDate.parse("2026-10-21"),
                Timestamp.from(Instant.parse("2026-10-21T08:00:00Z")),
                Timestamp.from(Instant.parse("2026-10-21T08:05:00Z")),
                "b".repeat(64), "c".repeat(64),
                Timestamp.from(Instant.parse("2026-10-21T08:00:00Z")),
                "d".repeat(64),
                Timestamp.from(Instant.parse("2026-10-21T08:00:00Z")))))
                .as("the exact 25-member owner bundle must be accepted")
                .isOne();
        assertThatThrownBy(() -> tenant(database.runtimeDataSource(), TENANT, () ->
                runtime.update("""
                        UPDATE abs_employment_relationship_projections
                           SET owner_key_id = NULL
                         WHERE tenant_id = ? AND relationship_public_id = ?
                        """, TENANT, recoveredRelationship)))
                .as("a partial owner bundle must be rejected")
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> tenant(database.runtimeDataSource(), TENANT, () ->
                runtime.update("""
                        UPDATE abs_employment_relationship_projections
                           SET projection_version = 9
                         WHERE tenant_id = ? AND relationship_public_id = ?
                        """, TENANT, RELATIONSHIP_ID)))
                .isInstanceOf(DataAccessException.class);
    }

    private boolean validAssignments(String json, UUID legalEntityId) {
        return Boolean.TRUE.equals(migration.queryForObject("""
                SELECT public.tim003_assignment_snapshots_structurally_valid(
                    CAST(? AS JSONB), CAST(? AS UUID))
                """, Boolean.class, json, legalEntityId.toString()));
    }

    private List<String> columnGrants(String privilege) {
        return superuser.queryForList("""
                SELECT column_name
                  FROM information_schema.role_column_grants
                 WHERE grantee = ?
                   AND table_schema = 'public'
                   AND table_name = 'abs_employment_relationship_projections'
                   AND privilege_type = ?
                 ORDER BY column_name
                """, String.class,
                EmploymentAbsenceProjectionMigrationDatabase.RUNTIME_ROLE, privilege);
    }

    private static String assignment(
            String assignmentId,
            String revision,
            UUID legalEntityId,
            String organization,
            String location,
            String timeZone,
            String effectiveFrom,
            String effectiveTo,
            String extra) {
        return """
                {"assignmentPublicId":"%s","assignmentRevision":%s,
                 "assignmentStatus":"ACTIVE","legalEntityPublicId":"%s",
                 "organizationPublicId":%s,"locationPublicId":%s,"timeZone":%s,
                 "effectiveFrom":"%s","effectiveTo":%s%s}
                """.formatted(
                assignmentId, revision, legalEntityId, organization, location,
                timeZone, effectiveFrom, effectiveTo, extra).replace("\n", "");
    }

    private static List<String> insertColumns() {
        return List.of(
                "tenant_id", "relationship_public_id", "worker_public_id",
                "freshness_state", "last_observed_event_sequence",
                "required_relationship_revision", "shared_offset_sequence",
                "gap_expected_sequence", "owner_relationship_revision",
                "worker_status", "relationship_type", "relationship_status",
                "legal_entity_public_id", "relationship_effective_from",
                "relationship_effective_to", "owner_effective_on",
                "assignment_snapshots", "owner_contract_id", "owner_schema_version",
                "owner_revision", "owner_as_of", "owner_valid_until",
                "owner_content_sha256", "owner_service", "purpose_code",
                "owner_scope_digest", "owner_business_time_zone",
                "owner_minimum_relationship_revision", "tenant_grant_revision",
                "authorization_revision", "field_policy_revision", "owner_issued_at",
                "owner_evidence_digest", "owner_key_id", "last_invalidation_event_id",
                "last_invalidation_change_type", "last_invalidation_effective_date",
                "last_invalidation_payload_sha256", "last_correlation_id",
                "recovery_outcome", "last_recovery_error_code", "last_recovery_at");
    }

    private static List<String> updateColumns() {
        return insertColumns().subList(2, insertColumns().size());
    }

    private static <T> T tenant(
            DataSource source, long tenantId, Supplier<T> work) {
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(source));
        return transaction.execute(ignored -> {
            String expected = Long.toString(tenantId);
            String actual = new JdbcTemplate(source).queryForObject(
                    "SELECT set_config('dwp.tenant_id', ?, true)",
                    String.class, expected);
            assertThat(actual).isEqualTo(expected);
            return work.get();
        });
    }
}
