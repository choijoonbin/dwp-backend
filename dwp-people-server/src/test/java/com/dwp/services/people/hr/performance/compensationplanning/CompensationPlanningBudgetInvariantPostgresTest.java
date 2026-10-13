package com.dwp.services.people.hr.performance.compensationplanning;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class CompensationPlanningBudgetInvariantPostgresTest {

    private static final long TENANT = 91L;
    private static final UUID SUBJECT =
            UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID CYCLE =
            UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID ORGANIZATION =
            UUID.fromString("20000000-0000-4000-8000-000000000003");
    private static final UUID CORRELATION =
            UUID.fromString("20000000-0000-4000-8000-000000000004");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getenv().getOrDefault(
                    "DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));

    private JdbcTemplate jdbc;
    private long cycleInternalId;

    @BeforeEach
    void migrateAndSeedCycle() {
        DataSource dataSource = dataSource();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA IF EXISTS hris_performance CASCADE");
        jdbc.execute("CREATE SCHEMA hris_performance");
        Flyway.configure(getClass().getClassLoader())
                .dataSource(dataSource)
                .locations("classpath:db/performance-migration")
                .defaultSchema("hris_performance")
                .schemas("hris_performance")
                .table("flyway_performance_schema_history")
                .validateOnMigrate(true)
                .load()
                .migrate();
        cycleInternalId = seedCycle(CYCLE, "OPEN");
    }

    @Test
    void exactReversalProducesAZeroSignedTotalAndLedgerRemainsAppendOnly() {
        UUID allocation = UUID.randomUUID();
        UUID reversal = UUID.randomUUID();
        insertEntry(allocation, 1L, "1250.25", "USD", "ALLOCATE", null,
                cycleInternalId);
        insertEntry(reversal, 2L, "1250.25", "USD", "REVERSE", allocation,
                cycleInternalId);

        assertThat(jdbc.queryForObject("""
                SELECT SUM(CASE WHEN entry_type = 'ALLOCATE'
                           THEN amount ELSE -amount END)
                  FROM hris_performance.prf_cmp_budget_ledger
                 WHERE tenant_id = ? AND compensation_cycle_id = ?
                """, BigDecimal.class, TENANT, cycleInternalId))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_performance.prf_cmp_budget_ledger
                 WHERE tenant_id = ? AND compensation_cycle_id = ?
                """, Integer.class, TENANT, cycleInternalId)).isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE hris_performance.prf_cmp_budget_ledger
                   SET amount = 1
                 WHERE tenant_id = ? AND public_id = ?
                """, TENANT, allocation))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("PER_COMPENSATION_APPEND_ONLY");
        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM hris_performance.prf_cmp_budget_ledger
                 WHERE tenant_id = ? AND public_id = ?
                """, TENANT, allocation))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("PER_COMPENSATION_APPEND_ONLY");
        assertThatThrownBy(() -> jdbc.execute(
                "TRUNCATE hris_performance.prf_cmp_budget_ledger"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("PER_COMPENSATION_APPEND_ONLY");
    }

    @Test
    void rejectsMismatchedMissingDuplicateAndCollidingReversals() {
        UUID allocation = UUID.randomUUID();
        insertEntry(allocation, 1L, "100", "USD", "ALLOCATE", null,
                cycleInternalId);

        assertRejected(() -> insertEntry(
                UUID.randomUUID(), 2L, "99", "USD", "REVERSE", allocation,
                cycleInternalId));
        assertRejected(() -> insertEntry(
                UUID.randomUUID(), 2L, "100", "EUR", "REVERSE", allocation,
                cycleInternalId));
        assertRejected(() -> insertEntry(
                UUID.randomUUID(), 2L, "100", "USD", "REVERSE", UUID.randomUUID(),
                cycleInternalId));

        UUID reversal = UUID.randomUUID();
        insertEntry(reversal, 2L, "100", "USD", "REVERSE", allocation,
                cycleInternalId);
        assertRejected(() -> insertEntry(
                UUID.randomUUID(), 3L, "100", "USD", "REVERSE", allocation,
                cycleInternalId));
        assertRejected(() -> insertEntry(
                reversal, 3L, "1", "USD", "ALLOCATE", null, cycleInternalId));
        assertRejected(() -> insertEntry(
                UUID.randomUUID(), 2L, "1", "USD", "ALLOCATE", null,
                cycleInternalId));
    }

    @Test
    void rejectsInvalidAmountsAndBudgetWritesOutsideDraftOrOpen() {
        assertRejected(() -> insertEntry(
                UUID.randomUUID(), 1L, "0", "USD", "ALLOCATE", null,
                cycleInternalId));
        assertRejected(() -> insertEntry(
                UUID.randomUUID(), 1L, "-1", "USD", "ALLOCATE", null,
                cycleInternalId));
        assertRejected(() -> insertEntry(
                UUID.randomUUID(), 1L, "NaN", "USD", "ALLOCATE", null,
                cycleInternalId));
        assertRejected(() -> insertEntry(
                UUID.randomUUID(), 1L, "1000000000000000.0000", "USD",
                "ALLOCATE", null, cycleInternalId));

        long approvedCycle = seedCycle(UUID.randomUUID(), "APPROVED");
        assertThatThrownBy(() -> insertEntry(
                UUID.randomUUID(), 1L, "1", "USD", "ALLOCATE", null,
                approvedCycle))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("PER_COMPENSATION_BUDGET_INVALID_STATE");
    }

    @Test
    void rejectsAggregateBudgetOverflowAcrossIndividuallyValidMaximumRows() {
        String maximum = "999999999999999.9999";
        insertEntry(UUID.randomUUID(), 1L, maximum, "USD", "ALLOCATE", null,
                cycleInternalId);

        assertThatThrownBy(() -> insertEntry(
                UUID.randomUUID(), 2L, maximum, "USD", "ALLOCATE", null,
                cycleInternalId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("PER_COMPENSATION_BUDGET_OVERFLOW");

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_performance.prf_cmp_budget_ledger
                 WHERE tenant_id = ? AND compensation_cycle_id = ?
                """, Integer.class, TENANT, cycleInternalId)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT SUM(amount) FROM hris_performance.prf_cmp_budget_ledger
                 WHERE tenant_id = ? AND compensation_cycle_id = ?
                """, BigDecimal.class, TENANT, cycleInternalId))
                .isEqualByComparingTo(maximum);
        assertThat(jdbc.queryForMap("""
                SELECT status, aggregate_version
                  FROM hris_performance.prf_cmp_cycles
                 WHERE tenant_id = ? AND prf_cmp_cycles_internal_id = ?
                """, TENANT, cycleInternalId))
                .containsEntry("status", "OPEN")
                .containsEntry("aggregate_version", 1L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_performance.prf_command_receipts
                 WHERE tenant_id = ?
                """, Integer.class, TENANT)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_performance.prf_outbox_events
                 WHERE tenant_id = ?
                """, Integer.class, TENANT)).isZero();
    }

    @Test
    void rejectsHostileFrozenCommitmentsAndCannotReverseTheirBackingBudget() {
        UUID allocation = UUID.randomUUID();
        insertEntry(allocation, 1L, "100", "USD", "ALLOCATE", null,
                cycleInternalId);
        long proposalInternalId = seedDraftProposal();
        jdbc.update("""
                UPDATE hris_performance.prf_cmp_proposals
                   SET aggregate_version = aggregate_version + 1,
                       status = 'SUBMITTED', updated_at = ?, correlation_id = ?
                 WHERE tenant_id = ? AND prf_cmp_proposals_internal_id = ?
                """, timestamp(), CORRELATION, TENANT, proposalInternalId);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE hris_performance.prf_cmp_proposals
                   SET aggregate_version = aggregate_version + 1,
                       status = 'FROZEN', updated_at = ?, correlation_id = ?
                 WHERE tenant_id = ? AND prf_cmp_proposals_internal_id = ?
                """, timestamp(), CORRELATION, TENANT, proposalInternalId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("PER_COMPENSATION_PROPOSAL_CAS_VIOLATION");

        jdbc.execute("""
                ALTER TABLE hris_performance.prf_cmp_proposals
                DISABLE TRIGGER trg_prf_cmp_proposals_cas
                """);
        try {
            jdbc.update("""
                    UPDATE hris_performance.prf_cmp_proposals
                       SET aggregate_version = aggregate_version + 1,
                           status = 'FROZEN', updated_at = ?, correlation_id = ?
                     WHERE tenant_id = ? AND prf_cmp_proposals_internal_id = ?
                    """, timestamp(), CORRELATION, TENANT, proposalInternalId);
        } finally {
            jdbc.execute("""
                    ALTER TABLE hris_performance.prf_cmp_proposals
                    ENABLE TRIGGER trg_prf_cmp_proposals_cas
                    """);
        }

        assertThatThrownBy(() -> insertEntry(
                UUID.randomUUID(), 2L, "100", "USD", "REVERSE", allocation,
                cycleInternalId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("PER_COMPENSATION_BUDGET_COMMITTED");
        assertThat(jdbc.queryForObject("""
                SELECT SUM(CASE WHEN entry_type = 'ALLOCATE'
                           THEN amount ELSE -amount END)
                  FROM hris_performance.prf_cmp_budget_ledger
                 WHERE tenant_id = ? AND compensation_cycle_id = ?
                """, BigDecimal.class, TENANT, cycleInternalId))
                .isEqualByComparingTo("100");
    }

    private long seedCycle(UUID cycleId, String status) {
        UUID guideline = UUID.randomUUID();
        UUID population = UUID.randomUUID();
        insertArtifact(guideline, "COMPENSATION_GUIDELINES", cycleId);
        insertArtifact(population, "COMPENSATION_POPULATION", cycleId);
        return jdbc.queryForObject("""
                INSERT INTO hris_performance.prf_cmp_cycles (
                    tenant_id, public_id, aggregate_version, created_at, created_by,
                    updated_at, correlation_id, cycle_code,
                    guideline_artifact_public_id, population_artifact_public_id,
                    effective_from, effective_to, status)
                VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?)
                RETURNING prf_cmp_cycles_internal_id
                """, Long.class, TENANT, cycleId, timestamp(), SUBJECT, timestamp(),
                CORRELATION, "FY27-" + cycleId.toString().substring(0, 8).toUpperCase(),
                guideline, population, Date.valueOf(LocalDate.parse("2027-01-01")), status);
    }

    private void insertArtifact(UUID artifactId, String type, UUID owner) {
        jdbc.update("""
                INSERT INTO hris_performance.prf_per_artifact_versions (
                    tenant_id, public_id, artifact_type, schema_version,
                    artifact_revision, owner_aggregate_type,
                    owner_aggregate_public_id, canonical_content_digest,
                    encrypted_content, encryption_key_version_public_id,
                    effective_from, effective_to, created_at, created_by)
                VALUES (?, ?, ?, 1, 1, 'COMPENSATION_CYCLE', ?, ?, ?, ?, ?, NULL, ?, ?)
                """, TENANT, artifactId, type, owner, "a".repeat(64), new byte[29],
                UUID.randomUUID(), Date.valueOf(LocalDate.parse("2027-01-01")),
                timestamp(), SUBJECT);
    }

    private long seedDraftProposal() {
        UUID proposalId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID artifactId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO hris_performance.prf_per_artifact_versions (
                    tenant_id, public_id, artifact_type, schema_version,
                    artifact_revision, owner_aggregate_type,
                    owner_aggregate_public_id, canonical_content_digest,
                    encrypted_content, encryption_key_version_public_id,
                    effective_from, effective_to, created_at, created_by)
                VALUES (?, ?, 'COMPENSATION_PROPOSAL', 1, 1,
                    'COMPENSATION_PROPOSAL_REVISION', ?, ?, ?, ?, ?, NULL, ?, ?)
                """, TENANT, artifactId, revisionId, "b".repeat(64), new byte[29],
                UUID.randomUUID(), Date.valueOf(LocalDate.parse("2027-01-01")),
                timestamp(), SUBJECT);
        return jdbc.queryForObject("""
                WITH proposal AS (
                    INSERT INTO hris_performance.prf_cmp_proposals (
                        tenant_id, public_id, aggregate_version, created_at, created_by,
                        updated_at, correlation_id, compensation_cycle_id,
                        worker_public_id, assignment_public_id, component_code,
                        currency_code, amount, effective_from, effective_to,
                        current_revision, status, supersedes_proposal_public_id)
                    VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?, ?, 'BASE', 'USD', 100,
                        ?, NULL, 1, 'DRAFT', NULL)
                    RETURNING prf_cmp_proposals_internal_id
                ), revision AS (
                    INSERT INTO hris_performance.prf_cmp_proposal_revisions (
                        tenant_id, public_id, aggregate_version, created_at, created_by,
                        updated_at, correlation_id, compensation_proposal_id,
                        revision_no, typed_content_artifact_public_id, content_digest,
                        exception_approval_receipt_public_id)
                    SELECT ?, ?, 1, ?, ?, ?, ?, prf_cmp_proposals_internal_id,
                           1, ?, ?, NULL
                      FROM proposal
                )
                SELECT prf_cmp_proposals_internal_id FROM proposal
                """, Long.class,
                TENANT, proposalId, timestamp(), SUBJECT, timestamp(), CORRELATION,
                cycleInternalId, UUID.randomUUID(), UUID.randomUUID(),
                Date.valueOf(LocalDate.parse("2027-01-01")),
                TENANT, revisionId, timestamp(), SUBJECT, timestamp(), CORRELATION,
                artifactId, "b".repeat(64));
    }

    private void insertEntry(
            UUID entryId,
            long sequence,
            String amount,
            String currency,
            String entryType,
            UUID source,
            long targetCycle) {
        jdbc.update("""
                INSERT INTO hris_performance.prf_cmp_budget_ledger (
                    tenant_id, public_id, aggregate_version, created_at, created_by,
                    updated_at, correlation_id, compensation_cycle_id,
                    org_public_id, currency_code, amount, entry_sequence,
                    entry_type, source_entry_public_id)
                VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?, ?, CAST(? AS NUMERIC), ?, ?, ?)
                """, TENANT, entryId, timestamp(), SUBJECT, timestamp(), CORRELATION,
                targetCycle, ORGANIZATION, currency, amount, sequence, entryType, source);
    }

    private static void assertRejected(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(DataAccessException.class);
    }

    private static Timestamp timestamp() {
        return Timestamp.from(Instant.parse("2026-10-13T00:00:00Z"));
    }

    private static DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }
}
