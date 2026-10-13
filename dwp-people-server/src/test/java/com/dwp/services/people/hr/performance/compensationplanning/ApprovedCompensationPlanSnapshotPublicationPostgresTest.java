package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.contracts.hris.xcon.v1.Xcon021ApprovedCompensationPlanSnapshotPublishedV2;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class ApprovedCompensationPlanSnapshotPublicationPostgresTest {

    private static final long TENANT = 101L;
    private static final UUID SUBJECT =
            UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID CYCLE =
            UUID.fromString("30000000-0000-4000-8000-000000000002");
    private static final UUID APPROVAL_RECEIPT =
            UUID.fromString("30000000-0000-4000-8000-000000000003");
    private static final UUID PUBLISH_RECEIPT =
            UUID.fromString("30000000-0000-4000-8000-000000000004");
    private static final UUID CORRELATION =
            UUID.fromString("30000000-0000-4000-8000-000000000005");
    private static final UUID SNAPSHOT =
            UUID.fromString("30000000-0000-4000-8000-000000000006");
    private static final UUID WORKER =
            UUID.fromString("30000000-0000-4000-8000-000000000007");
    private static final UUID ASSIGNMENT =
            UUID.fromString("30000000-0000-4000-8000-000000000008");
    private static final UUID PROPOSAL =
            UUID.fromString("30000000-0000-4000-8000-000000000009");
    private static final UUID LINE =
            UUID.fromString("30000000-0000-4000-8000-00000000000a");
    private static final LocalDate EFFECTIVE = LocalDate.parse("2027-01-01");
    private static final Instant NOW = Instant.parse("2027-01-02T00:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getenv().getOrDefault(
                    "DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private CompensationPlanningRepository repository;
    private CompensationPlanningRepository.CycleRow cycle;
    private CompensationPlanningCanonicalizer canonicalizer;
    private ObjectMapper mapper;

    @BeforeEach
    void migrateAndSeedApprovedCycle() {
        dataSource = dataSource();
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
        mapper = new ObjectMapper().findAndRegisterModules();
        canonicalizer = new CompensationPlanningCanonicalizer(mapper);
        repository = new CompensationPlanningRepository(
                new NamedParameterJdbcTemplate(dataSource), mapper);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        cycle = seedCycleAndReceipts();
    }

    @Test
    void commitsCycleSnapshotLinesReceiptAndExactV2OutboxAtomically() throws Exception {
        CompensationPlanningDtos.SnapshotLine line = validLine(LINE, 1);
        String payloadDigest = canonicalizer.payloadDigest(
                APPROVAL_RECEIPT, CYCLE, EFFECTIVE, null, List.of(line),
                SNAPSHOT, 1L, 7L);

        transaction.executeWithoutResult(status -> {
            repository.bindTenant(TENANT);
            CompensationPlanningRepository.SnapshotInsert inserted =
                    repository.insertSnapshot(
                            TENANT, SUBJECT, cycle, 7L, APPROVAL_RECEIPT,
                            SNAPSHOT, 1L, List.of(line), payloadDigest,
                            CORRELATION, NOW);
            repository.completeReceipt(
                    TENANT, PUBLISH_RECEIPT, inserted.aggregateVersion(), SNAPSHOT, NOW);
            repository.insertSnapshotOutbox(
                    TENANT, PUBLISH_RECEIPT,
                    UUID.fromString("30000000-0000-4000-8000-00000000000b"),
                    event(payloadDigest, inserted.aggregateVersion()));
        });

        assertThat(jdbc.queryForMap("""
                SELECT status, aggregate_version
                  FROM hris_performance.prf_cmp_cycles
                 WHERE tenant_id = ? AND public_id = ?
                """, TENANT, CYCLE)).containsEntry("status", "PUBLISHED")
                .containsEntry("aggregate_version", 8L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM hris_performance.prf_cmp_approved_snapshots snapshot
                  JOIN hris_performance.prf_cmp_approved_snapshot_lines line
                    ON line.tenant_id = snapshot.tenant_id
                   AND line.approved_compensation_snapshot_id =
                       snapshot.prf_cmp_approved_snapshots_internal_id
                 WHERE snapshot.tenant_id = ? AND snapshot.public_id = ?
                """, Integer.class, TENANT, SNAPSHOT)).isOne();
        assertThat(jdbc.queryForMap("""
                SELECT receipt_state, applied_aggregate_version, result_ref
                  FROM hris_performance.prf_command_receipts
                 WHERE tenant_id = ? AND command_receipt_id = ?
                """, TENANT, PUBLISH_RECEIPT))
                .containsEntry("receipt_state", "SUCCEEDED")
                .containsEntry("applied_aggregate_version", 8L)
                .containsEntry("result_ref", SNAPSHOT);

        Map<String, Object> outbox = jdbc.queryForMap("""
                SELECT event_type, aggregate_id, aggregate_version,
                       delivery_state, payload::text AS payload
                  FROM hris_performance.prf_outbox_events
                 WHERE tenant_id = ? AND command_receipt_id = ?
                """, TENANT, PUBLISH_RECEIPT);
        assertThat(outbox)
                .containsEntry("event_type",
                        "ApprovedCompensationPlanSnapshotPublished.v2")
                .containsEntry("aggregate_id", CYCLE)
                .containsEntry("aggregate_version", 8L)
                .containsEntry("delivery_state", "PENDING");
        JsonNode payload = mapper.readTree((String) outbox.get("payload"));
        assertThat(payload.size()).isEqualTo(16);
        assertThat(payload.path("aggregateId").asText()).isEqualTo(CYCLE.toString());
        assertThat(payload.path("planId").asText()).isEqualTo(CYCLE.toString());
        assertThat(payload.path("snapshotId").asText()).isEqualTo(SNAPSHOT.toString());
        assertThat(payload.path("sourceVersion").asLong()).isEqualTo(7L);
        assertThat(payload.path("aggregateVersion").asLong()).isEqualTo(8L);
        assertThat(payload.path("approvalRevision").asLong()).isEqualTo(7L);
        assertThat(payload.path("payloadDigest").asText()).isEqualTo(payloadDigest);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_performance.prf_outbox_events
                 WHERE tenant_id = ?
                   AND event_type IN (
                       'CompensationPlanApproved.v1',
                       'ApprovedCompensationPlanSnapshotPublished.v1')
                """, Integer.class, TENANT)).isZero();
    }

    @Test
    void aLineFailureRollsBackTheCycleHeaderReceiptAndOutbox() {
        UUID invalidVersionOneLine =
                UUID.fromString("30000000-0000-1000-8000-00000000000c");
        CompensationPlanningDtos.SnapshotLine invalid =
                validLine(invalidVersionOneLine, 1);
        String digest = canonicalizer.payloadDigest(
                APPROVAL_RECEIPT, CYCLE, EFFECTIVE, null, List.of(invalid),
                SNAPSHOT, 1L, 7L);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            repository.bindTenant(TENANT);
            repository.insertSnapshot(
                    TENANT, SUBJECT, cycle, 7L, APPROVAL_RECEIPT,
                    SNAPSHOT, 1L, List.of(invalid), digest, CORRELATION, NOW);
            repository.completeReceipt(TENANT, PUBLISH_RECEIPT, 8L, SNAPSHOT, NOW);
            repository.insertSnapshotOutbox(
                    TENANT, PUBLISH_RECEIPT, UUID.randomUUID(), event(digest, 8L));
        })).isInstanceOf(DataAccessException.class);

        assertPristineApprovedState();
    }

    @Test
    void deferredCardinalityRejectsAnIncompleteSnapshotAtCommit() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO hris_performance.prf_cmp_approved_snapshots (
                        tenant_id, public_id, aggregate_version, created_at, created_by,
                        updated_at, correlation_id, compensation_cycle_id,
                        approval_receipt_public_id, snapshot_revision, source_version,
                        effective_from, effective_to, line_count, payload_digest)
                    VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?, 1, 7, ?, NULL, 2, ?)
                    """, TENANT, SNAPSHOT, timestamp(), SUBJECT, timestamp(), CORRELATION,
                    cycle.internalId(), APPROVAL_RECEIPT, Date.valueOf(EFFECTIVE),
                    "a".repeat(64));
        })).isInstanceOfAny(
                        DataAccessException.class,
                        org.springframework.transaction.TransactionSystemException.class)
                .hasStackTraceContaining("PER_COMPENSATION_SNAPSHOT_LINES_INCOMPLETE");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_performance.prf_cmp_approved_snapshots
                 WHERE tenant_id = ? AND public_id = ?
                """, Integer.class, TENANT, SNAPSHOT)).isZero();
    }

    @Test
    void immutableReceiptReferencesRemainExactWhenLaterEvidenceSharesTheTimestamp() {
        UUID replayReceipt = UUID.randomUUID();
        insertReceipt(replayReceipt, "modern.compplan.cycle.revise", "PROPOSE",
                "SUCCEEDED", 7L, 8L, cycle.guidelineArtifactId());
        UUID laterGuidelines = UUID.randomUUID();
        UUID laterPopulation = UUID.randomUUID();
        insertCycleArtifact(laterGuidelines, "COMPENSATION_GUIDELINES", 2L);
        insertCycleArtifact(laterPopulation, "COMPENSATION_POPULATION", 2L);

        CompensationPlanningRepository.CycleArtifactEvidence cycleEvidence = repository
                .cycleArtifactsForReceipt(TENANT, replayReceipt, CYCLE)
                .orElseThrow();
        assertThat(cycleEvidence.guidelineArtifactId())
                .isEqualTo(cycle.guidelineArtifactId());
        assertThat(cycleEvidence.populationArtifactId())
                .isEqualTo(cycle.populationArtifactId());

        UUID proposal = UUID.randomUUID();
        UUID firstRevision = UUID.randomUUID();
        UUID firstArtifact = UUID.randomUUID();
        UUID secondRevision = UUID.randomUUID();
        UUID secondArtifact = UUID.randomUUID();
        UUID secondMaker = UUID.randomUUID();
        insertProposalArtifact(firstArtifact, firstRevision);
        transaction.executeWithoutResult(status -> {
            long proposalInternalId = jdbc.queryForObject("""
                    INSERT INTO hris_performance.prf_cmp_proposals (
                        tenant_id, public_id, aggregate_version, created_at, created_by,
                        updated_at, correlation_id, compensation_cycle_id,
                        worker_public_id, assignment_public_id, component_code,
                        currency_code, amount, effective_from, effective_to,
                        current_revision, status, supersedes_proposal_public_id)
                    VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?, ?, 'BASE', 'USD', 10,
                            ?, NULL, 1, 'DRAFT', NULL)
                    RETURNING prf_cmp_proposals_internal_id
                    """, Long.class, TENANT, proposal, timestamp(), SUBJECT, timestamp(),
                    CORRELATION, cycle.internalId(), WORKER, ASSIGNMENT,
                    Date.valueOf(EFFECTIVE));
            insertProposalRevision(
                    proposalInternalId, firstRevision, 1L, firstArtifact);
        });
        UUID proposalReceipt = UUID.randomUUID();
        insertReceipt(proposalReceipt, "modern.compplan.proposal.upsert", "PROPOSE",
                "SUCCEEDED", 0L, 1L, firstRevision);

        insertProposalArtifact(secondArtifact, secondRevision);
        transaction.executeWithoutResult(status -> {
            jdbc.update("""
                    UPDATE hris_performance.prf_cmp_proposals
                       SET aggregate_version = 2, current_revision = 2,
                           updated_at = ?, correlation_id = ?
                     WHERE tenant_id = ? AND public_id = ?
                    """, timestamp(), CORRELATION, TENANT, proposal);
            insertProposalRevision(
                    cycleProposalInternalId(proposal), secondRevision, 2L,
                    secondArtifact, secondMaker);
        });

        CompensationPlanningRepository.ProposalRevisionEvidence proposalEvidence = repository
                .proposalRevisionForReceipt(TENANT, proposalReceipt, CYCLE, proposal)
                .orElseThrow();
        assertThat(proposalEvidence.revisionId()).isEqualTo(firstRevision);
        assertThat(proposalEvidence.revision()).isOne();
        assertThat(proposalEvidence.artifactId()).isEqualTo(firstArtifact);
        assertThat(repository.proposalMakerIds(TENANT, cycle.internalId()))
                .containsExactlyInAnyOrder(SUBJECT, secondMaker);
    }

    private CompensationPlanningRepository.CycleRow seedCycleAndReceipts() {
        UUID guideline = UUID.randomUUID();
        UUID population = UUID.randomUUID();
        insertArtifact(guideline, "COMPENSATION_GUIDELINES");
        insertArtifact(population, "COMPENSATION_POPULATION");
        long internalId = jdbc.queryForObject("""
                INSERT INTO hris_performance.prf_cmp_cycles (
                    tenant_id, public_id, aggregate_version, created_at, created_by,
                    updated_at, correlation_id, cycle_code,
                    guideline_artifact_public_id, population_artifact_public_id,
                    effective_from, effective_to, status)
                VALUES (?, ?, 7, ?, ?, ?, ?, 'FY27', ?, ?, ?, NULL, 'APPROVED')
                RETURNING prf_cmp_cycles_internal_id
                """, Long.class, TENANT, CYCLE, timestamp(), SUBJECT, timestamp(),
                CORRELATION, guideline, population, Date.valueOf(EFFECTIVE));
        insertReceipt(APPROVAL_RECEIPT, "modern.compplan.plan.approve", "APPROVE",
                "SUCCEEDED", 6L, 7L, CYCLE);
        insertReceipt(PUBLISH_RECEIPT, "modern.compplan.snapshot.publish", "APPROVE",
                "ACCEPTED", 7L, null, null);
        return new CompensationPlanningRepository.CycleRow(
                internalId, CYCLE, 7L, NOW.minusSeconds(60), SUBJECT, NOW.minusSeconds(30),
                CORRELATION, "FY27", guideline, population, EFFECTIVE, null, "APPROVED");
    }

    private void insertArtifact(UUID id, String type) {
        insertCycleArtifact(id, type, 1L);
    }

    private void insertCycleArtifact(UUID id, String type, long revision) {
        jdbc.update("""
                INSERT INTO hris_performance.prf_per_artifact_versions (
                    tenant_id, public_id, artifact_type, schema_version,
                    artifact_revision, owner_aggregate_type,
                    owner_aggregate_public_id, canonical_content_digest,
                    encrypted_content, encryption_key_version_public_id,
                    effective_from, effective_to, created_at, created_by)
                VALUES (?, ?, ?, 1, ?, 'COMPENSATION_CYCLE', ?, ?, ?, ?, ?, NULL, ?, ?)
                """, TENANT, id, type, revision, CYCLE, "a".repeat(64), new byte[29],
                UUID.randomUUID(), Date.valueOf(EFFECTIVE), timestamp(), SUBJECT);
    }

    private void insertProposalArtifact(UUID id, UUID revisionId) {
        jdbc.update("""
                INSERT INTO hris_performance.prf_per_artifact_versions (
                    tenant_id, public_id, artifact_type, schema_version,
                    artifact_revision, owner_aggregate_type,
                    owner_aggregate_public_id, canonical_content_digest,
                    encrypted_content, encryption_key_version_public_id,
                    effective_from, effective_to, created_at, created_by)
                VALUES (?, ?, 'COMPENSATION_PROPOSAL', 1, 1,
                        'COMPENSATION_PROPOSAL_REVISION', ?, ?, ?, ?, ?, NULL, ?, ?)
                """, TENANT, id, revisionId, "c".repeat(64), new byte[29],
                UUID.randomUUID(), Date.valueOf(EFFECTIVE), timestamp(), SUBJECT);
    }

    private void insertProposalRevision(
            long proposalInternalId,
            UUID revisionId,
            long revision,
            UUID artifactId) {
        insertProposalRevision(
                proposalInternalId, revisionId, revision, artifactId, SUBJECT);
    }

    private void insertProposalRevision(
            long proposalInternalId,
            UUID revisionId,
            long revision,
            UUID artifactId,
            UUID createdBy) {
        jdbc.update("""
                INSERT INTO hris_performance.prf_cmp_proposal_revisions (
                    tenant_id, public_id, aggregate_version, created_at, created_by,
                    updated_at, correlation_id, compensation_proposal_id,
                    revision_no, typed_content_artifact_public_id, content_digest,
                    exception_approval_receipt_public_id)
                VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                """, TENANT, revisionId, timestamp(), createdBy, timestamp(), CORRELATION,
                proposalInternalId, revision, artifactId, "c".repeat(64));
    }

    private long cycleProposalInternalId(UUID proposalId) {
        return jdbc.queryForObject("""
                SELECT prf_cmp_proposals_internal_id
                  FROM hris_performance.prf_cmp_proposals
                 WHERE tenant_id = ? AND public_id = ?
                """, Long.class, TENANT, proposalId);
    }

    private void insertReceipt(
            UUID receipt,
            String operation,
            String action,
            String state,
            long expected,
            Long applied,
            UUID resultRef) {
        jdbc.update("""
                INSERT INTO hris_performance.prf_command_receipts (
                    command_receipt_id, tenant_id, idempotency_key, command_type,
                    originating_action, actor_id, subject_principal_public_id,
                    aggregate_id, expected_aggregate_version,
                    applied_aggregate_version, request_hash,
                    population_scope_digest, field_policy_revision, purpose_code,
                    authorization_revision, receipt_state, result_ref,
                    accepted_at, completed_at)
                VALUES (?, ?, ?, ?, ?, 9, ?, ?, ?, ?, ?, ?, 1,
                        'COMPENSATION_PLANNING', 1, ?, ?, ?, ?)
                """, receipt, TENANT, "idem-" + receipt, operation, action, SUBJECT,
                CYCLE, expected, applied, "a".repeat(64), "b".repeat(64), state,
                resultRef, timestamp(), "SUCCEEDED".equals(state) ? timestamp() : null);
    }

    private CompensationPlanningDtos.SnapshotLine validLine(UUID lineId, int sequence) {
        String lineDigest = canonicalizer.lineDigest(
                lineId, sequence, WORKER, ASSIGNMENT, "BASE", "USD", "99.5",
                EFFECTIVE, null);
        return new CompensationPlanningDtos.SnapshotLine(
                lineId, sequence, PROPOSAL, WORKER, ASSIGNMENT,
                "BASE", "USD", "99.5", EFFECTIVE, null, lineDigest);
    }

    private Xcon021ApprovedCompensationPlanSnapshotPublishedV2 event(
            String payloadDigest,
            long aggregateVersion) {
        return new Xcon021ApprovedCompensationPlanSnapshotPublishedV2(
                CYCLE, aggregateVersion, APPROVAL_RECEIPT, 7L, CORRELATION, CYCLE,
                EFFECTIVE, "APPROVED", 1, NOW, payloadDigest, CYCLE, SNAPSHOT,
                1L, 7L, "PUBLISHED");
    }

    private void assertPristineApprovedState() {
        assertThat(jdbc.queryForMap("""
                SELECT status, aggregate_version
                  FROM hris_performance.prf_cmp_cycles
                 WHERE tenant_id = ? AND public_id = ?
                """, TENANT, CYCLE)).containsEntry("status", "APPROVED")
                .containsEntry("aggregate_version", 7L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_performance.prf_cmp_approved_snapshots
                 WHERE tenant_id = ?
                """, Integer.class, TENANT)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_performance.prf_outbox_events
                 WHERE tenant_id = ? AND command_receipt_id = ?
                """, Integer.class, TENANT, PUBLISH_RECEIPT)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT receipt_state FROM hris_performance.prf_command_receipts
                 WHERE tenant_id = ? AND command_receipt_id = ?
                """, String.class, TENANT, PUBLISH_RECEIPT)).isEqualTo("ACCEPTED");
    }

    private static Timestamp timestamp() {
        return Timestamp.from(NOW);
    }

    private static DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }
}
