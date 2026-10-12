package com.dwp.services.people.hr.performance.populationfreeze;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class PopulationFreezePostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getenv().getOrDefault("DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));

    private final long tenantId = 501L;
    private final UUID cycleId = UUID.randomUUID();
    private final UUID cycleVersionId = UUID.randomUUID();
    private final UUID previewId = UUID.randomUUID();
    private final UUID ruleId = UUID.randomUUID();
    private final UUID snapshotId = UUID.randomUUID();
    private final UUID subjectId = UUID.randomUUID();
    private final UUID receiptId = UUID.randomUUID();
    private JdbcTemplate plainJdbc;
    private PopulationFreezeRepository repository;
    private PerformanceReviewOperationsRepository reviewOperations;
    private PopulationFreezeCanonicalizer canonicalizer;

    @BeforeEach
    void migrateAndSeedTheExistingPreview() {
        DataSource dataSource = dataSource();
        plainJdbc = new JdbcTemplate(dataSource);
        plainJdbc.execute("DROP SCHEMA IF EXISTS hris_performance CASCADE");
        plainJdbc.execute("CREATE SCHEMA hris_performance");
        Flyway.configure(getClass().getClassLoader())
                .dataSource(dataSource)
                .locations("classpath:db/performance-migration")
                .defaultSchema("hris_performance")
                .schemas("hris_performance")
                .table("flyway_performance_schema_history")
                .validateOnMigrate(true)
                .load()
                .migrate();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        repository = new PopulationFreezeRepository(
                new NamedParameterJdbcTemplate(dataSource), objectMapper);
        reviewOperations = new PerformanceReviewOperationsRepository(
                new NamedParameterJdbcTemplate(dataSource));
        canonicalizer = new PopulationFreezeCanonicalizer(objectMapper);
        seedPreview();
    }

    @Test
    void promotesPreviewReceiptMembersReviewersAndEventAtomicallyWithoutMutatingPreview() {
        String previewBefore = previewFingerprint();
        PopulationFreezeRepository.FreezeCandidate candidate = repository.candidate(
                tenantId, cycleId, previewId).orElseThrow();
        boolean reserved = repository.reserveReceipt(
                tenantId, 19L, subjectId, receiptId, "freeze-postgres-1", cycleId, 4L,
                "d".repeat(64), authority());

        PopulationFreezeDtos.PopulationFreeze freeze = repository.createFreeze(
                tenantId, 19L, receiptId, UUID.randomUUID(), candidate);

        assertThat(reserved).isTrue();
        assertThat(previewFingerprint()).isEqualTo(previewBefore);
        assertThat(freeze.state()).isEqualTo("FROZEN");
        assertThat(plainJdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM hris_performance.prf_population_freeze_members
                 WHERE tenant_id = ? AND population_freeze_id = ?
                """, Integer.class, tenantId, freeze.populationFreezeId())).isEqualTo(2);
        assertThat(plainJdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM hris_performance.prf_population_freeze_reviewers
                 WHERE tenant_id = ? AND population_freeze_id = ?
                """, Integer.class, tenantId, freeze.populationFreezeId())).isEqualTo(1);
        Map<String, Object> event = plainJdbc.queryForMap("""
                SELECT event_type, payload::text AS payload
                  FROM hris_performance.prf_outbox_events
                 WHERE tenant_id = ? AND command_receipt_id = ?
                """, tenantId, receiptId);
        assertThat(event.get("event_type")).isEqualTo("PerformancePopulationFrozen.v1");
        try {
            var payload = new ObjectMapper().readTree((String) event.get("payload"));
            assertThat(payload.size()).isEqualTo(7);
            assertThat(payload.get("populationFreezeId").asText())
                    .isEqualTo(freeze.populationFreezeId().toString());
            assertThat(payload.get("contentHash").asText()).isEqualTo(candidate.contentHash());
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new AssertionError(exception);
        }
        assertThat(repository.receiptById(tenantId, receiptId).orElseThrow().view().state())
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void databaseRejectsMutationAndEnablesTenantRlsOnEveryFreezeTable() {
        PopulationFreezeRepository.FreezeCandidate candidate = repository.candidate(
                tenantId, cycleId, previewId).orElseThrow();
        repository.reserveReceipt(
                tenantId, 19L, subjectId, receiptId, "freeze-postgres-2", cycleId, 4L,
                "d".repeat(64), authority());
        PopulationFreezeDtos.PopulationFreeze freeze = repository.createFreeze(
                tenantId, 19L, receiptId, UUID.randomUUID(), candidate);

        assertThatThrownBy(() -> plainJdbc.update("""
                UPDATE hris_performance.prf_population_freezes
                   SET participant_count = participant_count + 1
                 WHERE tenant_id = ? AND population_freeze_id = ?
                """, tenantId, freeze.populationFreezeId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("PER_POPULATION_FREEZE_IMMUTABLE");
        assertThat(plainJdbc.queryForList("""
                SELECT relname
                  FROM pg_class
                 WHERE relnamespace = 'hris_performance'::regnamespace
                   AND relname IN (
                       'prf_population_freezes',
                       'prf_population_freeze_members',
                       'prf_population_freeze_reviewers')
                   AND relrowsecurity
                 ORDER BY relname
                """, String.class)).containsExactly(
                "prf_population_freeze_members",
                "prf_population_freeze_reviewers",
                "prf_population_freezes");
    }

    @Test
    void reviewOperationsProjectsPreviewThenFreezeWithoutMutatingPreview() {
        String previewBefore = previewFingerprint();
        PerformanceReviewOperationsRepository.QueryCriteria criteria =
                new PerformanceReviewOperationsRepository.QueryCriteria(
                        tenantId, 10, 0, Instant.now(), List.of("itemPublicId:ASC"),
                        List.of(), List.of(), null, null, List.of(), List.of(),
                        null, null, null);

        PerformanceReviewOperationsRepository.Page before = reviewOperations.query(criteria);
        assertThat(before.rows()).singleElement().satisfies(row -> {
            assertThat(row.cycleId()).isEqualTo(cycleId);
            assertThat(row.statusCode()).isEqualTo("READY_TO_FREEZE");
            assertThat(row.openCount()).isEqualTo(1);
        });

        PopulationFreezeRepository.FreezeCandidate candidate = repository.candidate(
                tenantId, cycleId, previewId).orElseThrow();
        repository.reserveReceipt(
                tenantId, 19L, subjectId, receiptId, "freeze-postgres-query", cycleId, 4L,
                "d".repeat(64), authority());
        repository.createFreeze(
                tenantId, 19L, receiptId, UUID.randomUUID(), candidate);

        PerformanceReviewOperationsRepository.Page after = reviewOperations.query(criteria);
        assertThat(after.rows()).singleElement().satisfies(row -> {
            assertThat(row.statusCode()).isEqualTo("FROZEN");
            assertThat(row.openCount()).isZero();
        });
        assertThat(previewFingerprint()).isEqualTo(previewBefore);
    }

    private void seedPreview() {
        UUID includedParticipant = UUID.randomUUID();
        UUID includedAssignment = UUID.randomUUID();
        UUID excludedParticipant = UUID.randomUUID();
        UUID excludedAssignment = UUID.randomUUID();
        UUID organization = UUID.randomUUID();
        UUID reviewer = UUID.randomUUID();
        var members = java.util.List.of(
                new PopulationFreezeRepository.PreviewMember(
                        includedParticipant, includedAssignment, "ACTIVE", organization,
                        reviewer, null, null, "INCLUDED"),
                new PopulationFreezeRepository.PreviewMember(
                        excludedParticipant, excludedAssignment, "ACTIVE", organization,
                        null, null, null, "EXCLUDED"));
        String contentHash = canonicalizer.previewContentHash(members);
        plainJdbc.update("""
                INSERT INTO hris_performance.prf_cycles (
                    cycle_id, tenant_id, cycle_key, display_name, lifecycle_state,
                    active_version_no, aggregate_version, retention_policy_id,
                    created_by, updated_by)
                VALUES (?, ?, ?, 'FY27 Review', 'VALIDATED', 1, 4, ?, 10, 10)
                """, cycleId, tenantId, "FY27-" + UUID.randomUUID(), UUID.randomUUID());
        plainJdbc.update("""
                INSERT INTO hris_performance.prf_cycle_versions (
                    cycle_version_id, tenant_id, cycle_id, version_no, version_state,
                    aggregate_version, effective_from, timezone_id, policy_version_id,
                    population_rule_version_id, content_hash, authored_by)
                VALUES (?, ?, ?, 1, 'VALIDATED', 2, ?, 'UTC', ?, ?, ?, 10)
                """, cycleVersionId, tenantId, cycleId,
                Timestamp.from(Instant.parse("2027-01-01T00:00:00Z")),
                UUID.randomUUID(), ruleId,
                "a".repeat(64));
        plainJdbc.update("""
                INSERT INTO hris_performance.prf_population_previews (
                    population_preview_id, tenant_id, cycle_version_id,
                    source_cycle_aggregate_version, population_rule_version_id,
                    workforce_snapshot_id, workforce_snapshot_revision,
                    preview_state, participant_count, reviewer_assignment_count,
                    content_hash, aggregate_version, created_by, expires_at)
                VALUES (?, ?, ?, 4, ?, ?, 31, 'READY', 1, 1, ?, 1, 10,
                        CURRENT_TIMESTAMP + INTERVAL '1 hour')
                """, previewId, tenantId, cycleVersionId, ruleId, snapshotId, contentHash);
        for (var member : members) {
            plainJdbc.update("""
                    INSERT INTO hris_performance.prf_population_preview_members (
                        tenant_id, population_preview_id, participant_ref,
                        primary_assignment_ref, workforce_status, organization_ref,
                        reviewer_assignment_ref, job_profile_ref, grade_ref,
                        eligibility_code)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, tenantId, previewId, member.participantRef(),
                    member.primaryAssignmentRef(), member.workforceStatus(),
                    member.organizationRef(), member.reviewerAssignmentRef(),
                    member.jobProfileRef(), member.gradeRef(), member.eligibilityCode());
        }
    }

    private String previewFingerprint() {
        return plainJdbc.queryForObject("""
                SELECT p.preview_state || '|' || p.content_hash || '|' ||
                       p.participant_count || '|' || p.reviewer_assignment_count || '|' ||
                       COUNT(m.internal_id)
                  FROM hris_performance.prf_population_previews p
                  LEFT JOIN hris_performance.prf_population_preview_members m
                    ON m.tenant_id = p.tenant_id
                   AND m.population_preview_id = p.population_preview_id
                 WHERE p.tenant_id = ? AND p.population_preview_id = ?
                 GROUP BY p.preview_state, p.content_hash,
                          p.participant_count, p.reviewer_assignment_count
                """, String.class, tenantId, previewId);
    }

    private PopulationFreezeAuthorityPort.AuthorityEvidence authority() {
        return new PopulationFreezeAuthorityPort.AuthorityEvidence(
                tenantId, 19L, subjectId, PopulationFreezeService.APPLICATION_ENTITLEMENT,
                PopulationFreezeService.RESOURCE, PopulationFreezeService.AUTHORITY_ACTION,
                PopulationFreezeService.ORIGINATING_ACTION,
                PopulationFreezeService.AUTHORITY_PURPOSE, "c".repeat(64), 11, 23);
    }

    private DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }
}
