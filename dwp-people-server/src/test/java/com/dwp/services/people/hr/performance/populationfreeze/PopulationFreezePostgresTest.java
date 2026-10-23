package com.dwp.services.people.hr.performance.populationfreeze;

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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
    private UUID seededOrganization;
    private UUID seededReviewer;
    private JdbcTemplate plainJdbc;
    private DataSource testDataSource;
    private PopulationFreezeRepository repository;
    private PerformanceReviewOperationsRepository reviewOperations;
    private PopulationFreezeCanonicalizer canonicalizer;

    @BeforeEach
    void migrateAndSeedTheExistingPreview() {
        testDataSource = dataSource();
        plainJdbc = new JdbcTemplate(testDataSource);
        plainJdbc.execute("DROP SCHEMA IF EXISTS hris_performance CASCADE");
        plainJdbc.execute("CREATE SCHEMA hris_performance");
        com.dwp.services.people.hris.performance.support
                .PerformanceMigrationRuntimeRoleFixture.prepare(testDataSource);
        Flyway.configure(getClass().getClassLoader())
                .dataSource(testDataSource)
                .locations("classpath:db/performance-migration")
                .defaultSchema("hris_performance")
                .schemas("hris_performance")
                .table("flyway_performance_schema_history")
                .validateOnMigrate(true)
                .load()
                .migrate();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        repository = new PopulationFreezeRepository(
                new NamedParameterJdbcTemplate(testDataSource), objectMapper);
        reviewOperations = new PerformanceReviewOperationsRepository(
                new NamedParameterJdbcTemplate(testDataSource));
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
    void databaseRejectsMutationAndForcesTenantRlsOnEveryProjectionTable() {
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
                       'prf_population_freeze_reviewers',
                       'prf_review_operation_history')
                   AND relrowsecurity
                   AND relforcerowsecurity
                 ORDER BY relname
                """, String.class)).containsExactly(
                "prf_population_freeze_members",
                "prf_population_freeze_reviewers",
                "prf_population_freezes",
                "prf_review_operation_history");
    }

    @Test
    void reviewOperationsKeepsAnAsOfSnapshotStableAcrossLaterStatusChanges() {
        String previewBefore = previewFingerprint();
        Instant anchoredAt = databaseNow();
        PerformanceReviewOperationsRepository.QueryCriteria criteria = criteria(anchoredAt);

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

        plainJdbc.execute("SELECT pg_sleep(0.01)");
        plainJdbc.update("""
                UPDATE hris_performance.prf_cycles
                   SET display_name = 'FY27 Review Revised', aggregate_version = 5
                 WHERE tenant_id = ? AND cycle_id = ?
                """, tenantId, cycleId);

        PerformanceReviewOperationsRepository.Page anchored = reviewOperations.query(criteria);
        assertThat(anchored.rows()).singleElement().satisfies(row -> {
            assertThat(row.displayName()).isEqualTo("FY27 Review");
            assertThat(row.statusCode()).isEqualTo("READY_TO_FREEZE");
            assertThat(row.openCount()).isEqualTo(1);
        });
        PerformanceReviewOperationsRepository.Page current = reviewOperations.query(
                criteria(databaseNow()));
        assertThat(current.rows()).singleElement().satisfies(row -> {
            assertThat(row.displayName()).isEqualTo("FY27 Review Revised");
            assertThat(row.statusCode()).isEqualTo("FROZEN");
            assertThat(row.openCount()).isZero();
        });
        assertThat(previewFingerprint()).isEqualTo(previewBefore);
    }

    @Test
    void reviewOperationsFiltersRowsByOwnerResolvedOrganizationOrManagerScope() {
        Instant asOf = databaseNow();
        PopulationFreezeAuthorityPort.TargetPopulation organizationScope =
                new PopulationFreezeAuthorityPort.TargetPopulation(
                        false, null, Set.of(seededOrganization), Set.of(), "org-scope");
        PopulationFreezeAuthorityPort.TargetPopulation managerScope =
                new PopulationFreezeAuthorityPort.TargetPopulation(
                        false, seededReviewer.toString(), Set.of(), Set.of(), "manager-scope");
        PopulationFreezeAuthorityPort.TargetPopulation deniedScope =
                new PopulationFreezeAuthorityPort.TargetPopulation(
                        false, null, Set.of(UUID.randomUUID()), Set.of(), "denied-scope");

        assertThat(reviewOperations.query(criteria(asOf, organizationScope)).rows())
                .singleElement();
        assertThat(reviewOperations.query(criteria(asOf, managerScope)).rows())
                .singleElement();
        assertThat(reviewOperations.query(criteria(asOf, deniedScope)).rows()).isEmpty();
    }

    @Test
    void serializesConcurrentPreviewsAndLeavesExactlyOneCurrentReadyCandidate()
            throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> firstInsert = executor.submit(() -> {
                start.await();
                return insertPreview(first, UUID.randomUUID(), 32L);
            });
            Future<Integer> secondInsert = executor.submit(() -> {
                start.await();
                return insertPreview(second, UUID.randomUUID(), 33L);
            });
            start.countDown();

            assertThat(firstInsert.get()).isOne();
            assertThat(secondInsert.get()).isOne();
        } finally {
            executor.shutdownNow();
        }

        assertThat(plainJdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM hris_performance.prf_population_previews
                 WHERE tenant_id = ? AND cycle_version_id = ?
                   AND preview_state = 'READY'
                """, Integer.class, tenantId, cycleVersionId)).isOne();
        PopulationFreezeRepository.FreezeCandidate oldCandidate = repository.candidate(
                tenantId, cycleId, previewId).orElseThrow();
        assertThat(oldCandidate.previewState()).isEqualTo("STALE");
        assertThat(oldCandidate.latestReadyPreviewId()).isIn(first, second);
        assertThat(oldCandidate.latestReadyPreviewId()).isNotEqualTo(previewId);
    }

    @Test
    void concurrentDifferentKeysForOneCommandIdHaveOneWinnerAndNoSideEffects()
            throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> {
                start.await();
                return repository.reserveReceipt(
                        tenantId, 19L, subjectId, receiptId, "collision-a", cycleId, 4L,
                        "a".repeat(64), authority());
            });
            Future<Boolean> second = executor.submit(() -> {
                start.await();
                return repository.reserveReceipt(
                        tenantId, 19L, subjectId, receiptId, "collision-b", cycleId, 4L,
                        "b".repeat(64), authority());
            });
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
        }
        assertThat(plainJdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_performance.prf_command_receipts
                 WHERE tenant_id = ? AND command_receipt_id = ?
                """, Integer.class, tenantId, receiptId)).isOne();
        assertThat(plainJdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_performance.prf_population_freezes
                 WHERE tenant_id = ?
                """, Integer.class, tenantId)).isZero();
        assertThat(plainJdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_performance.prf_outbox_events
                 WHERE tenant_id = ? AND command_receipt_id = ?
                """, Integer.class, tenantId, receiptId)).isZero();
    }

    @Test
    void runtimeAndMigrationOwnerRolesCannotBypassForcedTenantIsolation() {
        PopulationFreezeRepository.FreezeCandidate candidate = repository.candidate(
                tenantId, cycleId, previewId).orElseThrow();
        repository.reserveReceipt(
                tenantId, 19L, subjectId, receiptId, "freeze-rls", cycleId, 4L,
                "d".repeat(64), authority());
        repository.createFreeze(tenantId, 19L, receiptId, UUID.randomUUID(), candidate);
        createRlsRoles();

        assertThat(countAsRole("per_runtime_test", tenantId)).isPositive();
        assertThat(countAsRole("per_runtime_test", tenantId + 1)).isZero();
        assertThat(countAsRole("per_runtime_test", null)).isZero();
        assertThat(countAsRole("per_owner_test", tenantId)).isPositive();
        assertThat(countAsRole("per_owner_test", tenantId + 1)).isZero();
        assertThat(countAsRole("per_owner_test", null)).isZero();
        assertThat(canExecuteTenantContextCapture("per_runtime_test")).isFalse();
        assertThat(canExecuteTenantContextCapture("per_owner_test")).isFalse();
    }

    @Test
    void appendOnlyHardeningPreservesOwnerTriggerExecutionAndSealsDirectRuntimeCalls() {
        List<Map<String, Object>> routines = plainJdbc.queryForList("""
                SELECT routine.proname,
                       routine.prosecdef,
                       COALESCE(array_to_string(routine.proconfig, ','), '') AS configuration,
                       owner_role.rolname AS owner_name,
                       routine.prorettype::regtype::text AS return_type
                  FROM pg_proc routine
                  JOIN pg_namespace namespace ON namespace.oid = routine.pronamespace
                  JOIN pg_roles owner_role ON owner_role.oid = routine.proowner
                 WHERE namespace.nspname = 'hris_performance'
                   AND routine.proname IN (
                       'prf_serialize_population_preview',
                       'prf_capture_review_operation',
                       'prf_capture_review_operation_trigger',
                       'prf_reject_population_freeze_mutation',
                       'prf_guard_review_operation_history')
                 ORDER BY routine.proname
                """);
        assertThat(routines).hasSize(5);
        assertThat(routines).allSatisfy(routine ->
                assertThat(routine.get("owner_name")).isEqualTo(POSTGRES.getUsername()));
        assertThat(routines).filteredOn(routine ->
                Set.of(
                        "prf_serialize_population_preview",
                        "prf_capture_review_operation_trigger",
                        "prf_reject_population_freeze_mutation")
                        .contains(routine.get("proname")))
                .allSatisfy(routine -> {
                    assertThat(routine.get("prosecdef")).isEqualTo(true);
                    assertThat(routine.get("configuration")).isEqualTo("search_path=pg_catalog");
                    assertThat(routine.get("return_type")).isEqualTo("trigger");
                });
        assertThat(routines).filteredOn(routine ->
                "prf_capture_review_operation".equals(routine.get("proname")))
                .singleElement()
                .satisfies(routine -> {
                    assertThat(routine.get("prosecdef")).isEqualTo(false);
                    assertThat(routine.get("configuration")).isEqualTo("");
                    assertThat(routine.get("return_type")).isEqualTo("void");
                });
        assertThat(routines).filteredOn(routine ->
                "prf_guard_review_operation_history".equals(routine.get("proname")))
                .singleElement()
                .satisfies(routine -> {
                    assertThat(routine.get("prosecdef")).isEqualTo(false);
                    assertThat(routine.get("configuration")).isEqualTo("search_path=pg_catalog");
                    assertThat(routine.get("return_type")).isEqualTo("trigger");
                });

        int historyBefore = reviewOperationHistoryCount();
        plainJdbc.update("""
                UPDATE hris_performance.prf_cycles
                   SET display_name = 'FY27 Review owner-trigger proof', aggregate_version = 5
                 WHERE tenant_id = ? AND cycle_id = ?
                """, tenantId, cycleId);
        assertThat(reviewOperationHistoryCount()).isEqualTo(historyBefore + 1);

        createRlsRoles();
        for (String routine : List.of(
                "prf_serialize_population_preview()",
                "prf_capture_review_operation(bigint,uuid)",
                "prf_capture_review_operation_trigger()",
                "prf_reject_population_freeze_mutation()",
                "prf_guard_review_operation_history()")) {
            assertThat(canExecuteRoutine("per_runtime_test", routine)).isFalse();
            assertThat(canExecuteRoutine("per_owner_test", routine)).isFalse();
        }
        long directHistoryId = Long.MAX_VALUE - 100;
        assertThatThrownBy(() -> executeAsRole("per_runtime_test", """
                INSERT INTO hris_performance.prf_review_operation_history (
                    history_id, tenant_id, cycle_id, display_name,
                    lifecycle_state, aggregate_version)
                VALUES (?, ?, ?, 'forged history', 'VALIDATED', 999)
                """, directHistoryId, tenantId, cycleId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("PER_REVIEW_OPERATION_HISTORY_OWNER_PATH_REQUIRED");
        assertThatThrownBy(() -> executeAsRole("per_runtime_test", """
                UPDATE hris_performance.prf_review_operation_history
                   SET display_name = 'rewritten history'
                 WHERE tenant_id = ? AND cycle_id = ?
                """, tenantId, cycleId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("PER_REVIEW_OPERATION_HISTORY_IMMUTABLE");
        assertThatThrownBy(() -> executeAsRole("per_runtime_test", """
                DELETE FROM hris_performance.prf_review_operation_history
                 WHERE tenant_id = ? AND cycle_id = ?
                """, tenantId, cycleId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("PER_REVIEW_OPERATION_HISTORY_IMMUTABLE");
        assertThat(reviewOperationHistoryCount()).isEqualTo(historyBefore + 1);
    }

    private void seedPreview() {
        UUID includedParticipant = UUID.randomUUID();
        UUID includedAssignment = UUID.randomUUID();
        UUID excludedParticipant = UUID.randomUUID();
        UUID excludedAssignment = UUID.randomUUID();
        seededOrganization = UUID.randomUUID();
        seededReviewer = UUID.randomUUID();
        var members = java.util.List.of(
                new PopulationFreezeRepository.PreviewMember(
                        includedParticipant, includedAssignment, "ACTIVE", seededOrganization,
                        seededReviewer, null, null, "INCLUDED"),
                new PopulationFreezeRepository.PreviewMember(
                        excludedParticipant, excludedAssignment, "ACTIVE", seededOrganization,
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
                PopulationFreezeService.AUTHORITY_PURPOSE, "c".repeat(64),
                new PopulationFreezeAuthorityPort.TargetPopulation(
                        true, null, Set.of(), Set.of("DIRECTORY", "EMPLOYMENT"), "scope-1"),
                Map.of(
                        "REVIEW_ASSIGNMENT", PopulationFreezeAuthorityPort.FieldDecision.VIEW,
                        "WORK_QUEUE", PopulationFreezeAuthorityPort.FieldDecision.VIEW,
                        "DUE_STATUS", PopulationFreezeAuthorityPort.FieldDecision.VIEW),
                11, 23);
    }

    private PerformanceReviewOperationsRepository.QueryCriteria criteria(Instant asOf) {
        return criteria(asOf, authority().targetPopulation());
    }

    private PerformanceReviewOperationsRepository.QueryCriteria criteria(
            Instant asOf,
            PopulationFreezeAuthorityPort.TargetPopulation targetPopulation) {
        return new PerformanceReviewOperationsRepository.QueryCriteria(
                tenantId, 10, asOf, List.of("itemPublicId:ASC"), null,
                targetPopulation, List.of(), List.of(), null, null,
                List.of(), List.of(), null, null, null);
    }

    private Instant databaseNow() {
        return plainJdbc.queryForObject(
                "SELECT clock_timestamp()", java.time.OffsetDateTime.class).toInstant();
    }

    private int insertPreview(UUID id, UUID workforceSnapshotId, long snapshotRevision) {
        return plainJdbc.update("""
                INSERT INTO hris_performance.prf_population_previews (
                    population_preview_id, tenant_id, cycle_version_id,
                    source_cycle_aggregate_version, population_rule_version_id,
                    workforce_snapshot_id, workforce_snapshot_revision,
                    preview_state, participant_count, reviewer_assignment_count,
                    content_hash, aggregate_version, created_by, expires_at)
                VALUES (?, ?, ?, 4, ?, ?, ?, 'READY', 0, 0, ?, 1, 10,
                        CURRENT_TIMESTAMP + INTERVAL '1 hour')
                """, id, tenantId, cycleVersionId, ruleId, workforceSnapshotId,
                snapshotRevision, canonicalizer.previewContentHash(List.of()));
    }

    private void createRlsRoles() {
        plainJdbc.execute("""
                DO $$ BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'per_runtime_test') THEN
                        CREATE ROLE per_runtime_test NOLOGIN;
                    END IF;
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'per_owner_test') THEN
                        CREATE ROLE per_owner_test NOLOGIN;
                    END IF;
                END $$
                """);
        plainJdbc.execute("GRANT USAGE ON SCHEMA hris_performance TO per_runtime_test");
        plainJdbc.execute("""
                GRANT SELECT, INSERT, UPDATE, DELETE
                    ON hris_performance.prf_review_operation_history TO per_runtime_test
                """);
        plainJdbc.execute("""
                GRANT SELECT ON hris_performance.prf_population_freezes,
                    hris_performance.prf_population_freeze_members,
                    hris_performance.prf_population_freeze_reviewers TO per_runtime_test
                """);
        plainJdbc.execute("ALTER TABLE hris_performance.prf_population_freezes OWNER TO per_owner_test");
        plainJdbc.execute("""
                ALTER TABLE hris_performance.prf_population_freeze_members OWNER TO per_owner_test
                """);
        plainJdbc.execute("""
                ALTER TABLE hris_performance.prf_population_freeze_reviewers OWNER TO per_owner_test
                """);
        plainJdbc.execute("""
                ALTER TABLE hris_performance.prf_review_operation_history OWNER TO per_owner_test
                """);
        plainJdbc.execute("GRANT USAGE ON SCHEMA hris_performance TO per_owner_test");
    }

    private int countAsRole(String role, Long contextTenantId) {
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(testDataSource));
        Integer count = transaction.execute(status -> {
            JdbcTemplate jdbc = new JdbcTemplate(testDataSource);
            jdbc.execute("SET LOCAL ROLE " + role);
            if (contextTenantId != null) {
                jdbc.queryForObject(
                        "SELECT set_config('dwp.tenant_id', ?, TRUE)",
                        String.class, contextTenantId.toString());
            }
            return jdbc.queryForObject("""
                    SELECT
                        (SELECT COUNT(*)
                           FROM hris_performance.prf_population_freezes)
                      + (SELECT COUNT(*)
                           FROM hris_performance.prf_population_freeze_members)
                      + (SELECT COUNT(*)
                           FROM hris_performance.prf_population_freeze_reviewers)
                      + (SELECT COUNT(*)
                           FROM hris_performance.prf_review_operation_history)
                    """, Integer.class);
        });
        return count == null ? 0 : count;
    }

    private boolean canExecuteTenantContextCapture(String role) {
        return canExecuteRoutine(
                role, "prf_capture_review_operation(bigint,uuid)");
    }

    private boolean canExecuteRoutine(String role, String routine) {
        return Boolean.TRUE.equals(plainJdbc.queryForObject(
                "SELECT has_function_privilege(?, CAST(? AS regprocedure), 'EXECUTE')",
                Boolean.class,
                role,
                "hris_performance." + routine));
    }

    private int reviewOperationHistoryCount() {
        return plainJdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM hris_performance.prf_review_operation_history
                 WHERE tenant_id = ? AND cycle_id = ?
                """, Integer.class, tenantId, cycleId);
    }

    private int executeAsRole(String role, String sql, Object... parameters) {
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(testDataSource));
        Integer updated = transaction.execute(status -> {
            JdbcTemplate jdbc = new JdbcTemplate(testDataSource);
            jdbc.execute("SET LOCAL ROLE " + role);
            jdbc.queryForObject(
                    "SELECT set_config('dwp.tenant_id', ?, TRUE)",
                    String.class,
                    Long.toString(tenantId));
            return jdbc.update(sql, parameters);
        });
        return updated == null ? 0 : updated;
    }

    private DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }
}
