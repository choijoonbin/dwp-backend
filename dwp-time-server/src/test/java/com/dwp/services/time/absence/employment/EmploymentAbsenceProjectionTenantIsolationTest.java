package com.dwp.services.time.absence.employment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmploymentAbsenceProjectionTenantIsolationTest {

    private static final long TENANT_A = 83_101L;
    private static final long TENANT_B = 83_102L;
    private static final UUID RELATIONSHIP_A =
            UUID.fromString("83101000-0000-4000-8000-000000000001");
    private static final UUID RELATIONSHIP_B =
            UUID.fromString("83102000-0000-4000-8000-000000000001");

    private EmploymentAbsenceProjectionMigrationDatabase database;
    private JdbcTemplate migration;
    private JdbcTemplate runtime;

    @BeforeAll
    void migrateDatabase() {
        assertThat(DockerClientFactory.instance().isDockerAvailable())
                .as("Docker is required for the hermetic TIM-003 tenant-isolation gate")
                .isTrue();
        database = EmploymentAbsenceProjectionMigrationDatabase.instance();
        migration = new JdbcTemplate(database.migrationDataSource());
        runtime = new JdbcTemplate(database.runtimeDataSource());
        insert(TENANT_A, RELATIONSHIP_A);
        insert(TENANT_B, RELATIONSHIP_B);
    }

    @Test
    void missingAndWrongTenantContextCannotReadOrMutateProjectionRows() {
        assertThat(runtime.queryForObject("""
                SELECT count(*) FROM abs_employment_relationship_projections
                 WHERE tenant_id IN (?, ?)
                """, Integer.class, TENANT_A, TENANT_B)).isZero();
        assertThat(migration.queryForObject("""
                SELECT count(*) FROM abs_employment_relationship_projections
                 WHERE tenant_id IN (?, ?)
                """, Integer.class, TENANT_A, TENANT_B)).isZero();

        assertThat(tenant(database.runtimeDataSource(), TENANT_A, () ->
                runtime.queryForList("""
                        SELECT relationship_public_id
                          FROM abs_employment_relationship_projections
                         WHERE tenant_id IN (?, ?)
                        """, UUID.class, TENANT_A, TENANT_B)))
                .containsExactly(RELATIONSHIP_A);
        assertThat(tenant(database.runtimeDataSource(), TENANT_B, () ->
                runtime.queryForList("""
                        SELECT relationship_public_id
                          FROM abs_employment_relationship_projections
                         WHERE tenant_id IN (?, ?)
                        """, UUID.class, TENANT_A, TENANT_B)))
                .containsExactly(RELATIONSHIP_B);

        assertThat(tenant(database.runtimeDataSource(), TENANT_A, () -> runtime.update("""
                UPDATE abs_employment_relationship_projections
                   SET last_invalidation_change_type = 'CROSS_TENANT_ATTEMPT'
                 WHERE tenant_id = ? AND relationship_public_id = ?
                """, TENANT_B, RELATIONSHIP_B))).isZero();

        assertThatThrownBy(() -> tenant(database.runtimeDataSource(), TENANT_A, () ->
                runtime.update("""
                        INSERT INTO abs_employment_relationship_projections (
                            tenant_id, relationship_public_id, freshness_state,
                            last_observed_event_sequence,
                            required_relationship_revision, shared_offset_sequence,
                            gap_expected_sequence, last_invalidation_event_id,
                            last_invalidation_change_type,
                            last_invalidation_effective_date,
                            last_invalidation_payload_sha256, last_correlation_id,
                            recovery_outcome)
                        VALUES (?, ?, 'GAP', 2, 2, 0, 1, ?, 'CROSS_TENANT_ATTEMPT',
                                ?, ?, 'tim003-tenant-isolation-test', 'NOT_ATTEMPTED')
                        """, TENANT_B,
                        UUID.fromString("83102000-0000-4000-8000-000000000099"),
                        UUID.fromString("83102000-0000-4000-8000-000000000098"),
                        LocalDate.parse("2026-10-21"), "f".repeat(64))))
                .isInstanceOf(DataAccessException.class);
    }

    private void insert(long tenantId, UUID relationshipId) {
        tenant(database.runtimeDataSource(), tenantId, () -> runtime.update("""
                INSERT INTO abs_employment_relationship_projections (
                    tenant_id, relationship_public_id, freshness_state,
                    last_observed_event_sequence, required_relationship_revision,
                    shared_offset_sequence, gap_expected_sequence,
                    last_invalidation_event_id, last_invalidation_change_type,
                    last_invalidation_effective_date,
                    last_invalidation_payload_sha256, last_correlation_id,
                    recovery_outcome)
                VALUES (?, ?, 'GAP', 2, 2, 0, 1, ?, 'RELATIONSHIP_CHANGED',
                        ?, ?, 'tim003-tenant-isolation-test', 'NOT_ATTEMPTED')
                """, tenantId, relationshipId, UUID.randomUUID(),
                LocalDate.parse("2026-10-21"), "e".repeat(64)));
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
