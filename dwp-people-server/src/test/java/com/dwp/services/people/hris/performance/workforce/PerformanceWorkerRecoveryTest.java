package com.dwp.services.people.hris.performance.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import com.dwp.contracts.hris.xcon.v1.Xcon003WorkerChangedV1;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PerformanceWorkerRecoveryTest {

    private static final String SCHEMA = "hris_performance";
    private static final String HISTORY_TABLE = "flyway_performance_schema_history";
    private static final String RLS_READER = "per_workforce_recovery_reader";
    private static final long OTHER_TENANT = PerformanceWorkforceTestSupport.TENANT + 1;
    private static final LocalDate EFFECTIVE_DATE = LocalDate.parse("2030-01-03");
    private static final UUID REVISION_ONE_EVENT = PerformanceWorkforceTestSupport.uuid(
            "32000000-0000-0000-0000-000000000001");
    private static final UUID REVISION_THREE_EVENT = PerformanceWorkforceTestSupport.uuid(
            "32000000-0000-0000-0000-000000000003");
    private static final UUID LATE_ORGANIZATION_EVENT = PerformanceWorkforceTestSupport.uuid(
            "32000000-0000-0000-0000-000000000004");
    private static final UUID LATE_ORGANIZATION_REVISION = PerformanceWorkforceTestSupport.uuid(
            "33000000-0000-0000-0000-000000000004");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getenv().getOrDefault("DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));

    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactionManager;
    private JdbcPerformanceWorkforceProjectionStore store;
    private PerformanceWorkforceInvalidationConsumer consumer;

    @BeforeEach
    void migrateTheFullPerformanceStreamAndCreateTheRealStore() {
        DataSource dataSource = dataSource();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        jdbc.execute("CREATE SCHEMA " + SCHEMA);

        Flyway.configure(getClass().getClassLoader())
                .dataSource(dataSource)
                .locations("classpath:db/performance-migration")
                .defaultSchema(SCHEMA)
                .schemas(SCHEMA)
                .table(HISTORY_TABLE)
                .baselineOnMigrate(false)
                .validateOnMigrate(true)
                .outOfOrder(false)
                .load()
                .migrate();

        transactionManager = new DataSourceTransactionManager(dataSource);
        store = new JdbcPerformanceWorkforceProjectionStore(
                new NamedParameterJdbcTemplate(dataSource), transactionManager);
        consumer = new PerformanceWorkforceInvalidationConsumer(store);
    }

    @Test
    void persistsRecoversDeduplicatesAndQuarantinesWithoutTouchingFrozenPopulations() {
        PerformanceWorkforceProjection.Snapshot snapshot =
                PerformanceWorkforceTestSupport.snapshot();
        List<Integer> freezeCountsBefore = freezeCounts();

        PerformanceWorkforceProjection.Head initial = store.replaceFromOwner(snapshot, null);

        assertThat(initial.state()).isEqualTo(PerformanceWorkforceProjection.State.READY);
        assertThat(initial.localRevision()).isEqualTo(1L);
        assertThat(initial.snapshot()).isEqualTo(snapshot);
        assertThat(persistedMembers(1L))
                .hasSize(2)
                .containsExactlyElementsOf(snapshot.members());
        assertThat(store.head(PerformanceWorkforceTestSupport.TENANT))
                .hasValueSatisfying(head -> {
                    assertThat(head.state())
                            .isEqualTo(PerformanceWorkforceProjection.State.READY);
                    assertThat(head.snapshot()).isEqualTo(snapshot);
                });

        Xcon003WorkerChangedV1 revisionOne = worker(1L, "STATUS_CHANGED");
        PerformanceWorkforceInvalidationConsumer.TrustedEnvelope<Xcon003WorkerChangedV1>
                revisionOneEnvelope = envelope(REVISION_ONE_EVENT, revisionOne);

        PerformanceWorkforceInvalidationConsumer.InvalidationReceipt stale =
                consumer.consumeWorkerChanged(revisionOneEnvelope);
        assertThat(stale.outcome())
                .isEqualTo(PerformanceWorkforceInvalidationConsumer.Outcome.STALE);
        assertThat(stale.duplicate()).isFalse();
        assertThat(stale.projectionState())
                .isEqualTo(PerformanceWorkforceProjection.State.STALE);

        PerformanceWorkforceInvalidationConsumer.InvalidationReceipt duplicateStale =
                consumer.consumeWorkerChanged(revisionOneEnvelope);
        assertThat(duplicateStale.outcome())
                .isEqualTo(PerformanceWorkforceInvalidationConsumer.Outcome.STALE);
        assertThat(duplicateStale.duplicate()).isTrue();
        assertThat(inboxCount(REVISION_ONE_EVENT)).isOne();

        Xcon003WorkerChangedV1 revisionThree = worker(3L, "CORRECTION");
        PerformanceWorkforceInvalidationConsumer.TrustedEnvelope<Xcon003WorkerChangedV1>
                revisionThreeEnvelope = envelope(REVISION_THREE_EVENT, revisionThree);
        PerformanceWorkforceInvalidationConsumer.InvalidationReceipt gap =
                consumer.consumeWorkerChanged(revisionThreeEnvelope);

        assertThat(gap.outcome()).isEqualTo(
                PerformanceWorkforceInvalidationConsumer.Outcome.BLOCKED_DEPENDENCY_GAP);
        assertThat(gap.duplicate()).isFalse();
        assertThat(gap.projectionState())
                .isEqualTo(PerformanceWorkforceProjection.State.BLOCKED_DEPENDENCY_GAP);
        assertThat(gap.requiredAggregateRevision()).isEqualTo(2L);
        assertThat(gap.observedAggregateRevision()).isEqualTo(3L);
        assertThat(store.head(PerformanceWorkforceTestSupport.TENANT))
                .hasValueSatisfying(head -> {
                    assertThat(head.requiredAggregateRevision()).isEqualTo(2L);
                    assertThat(head.observedAggregateRevision()).isEqualTo(3L);
                });

        PerformanceWorkforceProjectionStore.ReplayFence firstFence = store.beginReplay(
                PerformanceWorkforceTestSupport.TENANT, REVISION_THREE_EVENT);
        PerformanceWorkforceProjectionStore.InvalidationResult lateInvalidation =
                store.invalidate(new PerformanceWorkforceProjectionStore.InvalidationEvent(
                        PerformanceWorkforceTestSupport.TENANT,
                        LATE_ORGANIZATION_EVENT,
                        PerformanceWorkforceInvalidationConsumer.ORGANIZATION_CHANGED,
                        PerformanceWorkforceProjectionStore.SubjectType.ORGANIZATION,
                        LATE_ORGANIZATION_REVISION,
                        null,
                        EFFECTIVE_DATE,
                        "f".repeat(64)));
        assertThat(lateInvalidation.outcome())
                .isEqualTo(PerformanceWorkforceProjectionStore.InvalidationOutcome.STALE);

        PerformanceWorkforceProjection.Head fencedReplay =
                store.replaceFromOwner(snapshot, firstFence);

        assertThat(fencedReplay.state())
                .isEqualTo(PerformanceWorkforceProjection.State.STALE);
        assertThat(fencedReplay.localRevision()).isEqualTo(2L);
        assertThat(replayEvidence(REVISION_ONE_EVENT))
                .containsEntry("processing_state", "PROCESSED")
                .containsEntry("outcome_code", "REPLAYED")
                .containsEntry("resulting_projection_state", "STALE");
        assertThat(replayEvidence(REVISION_THREE_EVENT))
                .containsEntry("processing_state", "PROCESSED")
                .containsEntry("outcome_code", "REPLAYED")
                .containsEntry("resulting_projection_state", "STALE");
        assertThat(replayEvidence(LATE_ORGANIZATION_EVENT))
                .containsEntry("processing_state", "REPLAY_REQUIRED")
                .containsEntry("outcome_code", "STALE");

        PerformanceWorkforceProjectionStore.ReplayFence lateFence = store.beginReplay(
                PerformanceWorkforceTestSupport.TENANT, LATE_ORGANIZATION_EVENT);
        PerformanceWorkforceProjection.Head replayed =
                store.replaceFromOwner(snapshot, lateFence);

        assertThat(replayed.state()).isEqualTo(PerformanceWorkforceProjection.State.READY);
        assertThat(replayed.localRevision()).isEqualTo(3L);
        assertThat(jdbc.queryForList("""
                SELECT local_revision
                  FROM hris_performance.prf_workforce_projection_revisions
                 WHERE tenant_id = ?
                 ORDER BY local_revision
                """, Long.class, PerformanceWorkforceTestSupport.TENANT))
                .containsExactly(1L, 2L, 3L);
        assertThat(jdbc.queryForList("""
                SELECT replay_of_local_revision
                  FROM hris_performance.prf_workforce_projection_revisions
                 WHERE tenant_id = ?
                   AND local_revision > 1
                 ORDER BY local_revision
                """, Long.class, PerformanceWorkforceTestSupport.TENANT))
                .containsExactly(1L, 2L);
        assertThat(replayEvidence(LATE_ORGANIZATION_EVENT))
                .containsEntry("processing_state", "PROCESSED")
                .containsEntry("outcome_code", "REPLAYED")
                .containsEntry("resulting_projection_state", "READY");

        PerformanceWorkforceInvalidationConsumer.InvalidationReceipt duplicateGap =
                consumer.consumeWorkerChanged(revisionThreeEnvelope);
        assertThat(duplicateGap.outcome())
                .isEqualTo(PerformanceWorkforceInvalidationConsumer.Outcome.REPLAYED);
        assertThat(duplicateGap.duplicate()).isTrue();
        assertThat(duplicateGap.projectionState())
                .isEqualTo(PerformanceWorkforceProjection.State.READY);

        Xcon003WorkerChangedV1 conflictingRevisionThree =
                worker(3L, "PROFILE_CHANGED");
        String originalPayloadHash = PerformanceWorkforceInvalidationConsumer.payloadDigest(
                revisionThree);
        String conflictingPayloadHash = PerformanceWorkforceInvalidationConsumer.payloadDigest(
                conflictingRevisionThree);
        PerformanceWorkforceInvalidationConsumer.InvalidationReceipt quarantined =
                consumer.consumeWorkerChanged(
                        new PerformanceWorkforceInvalidationConsumer.TrustedEnvelope<>(
                                PerformanceWorkforceTestSupport.TENANT,
                                REVISION_THREE_EVENT,
                                conflictingPayloadHash,
                                conflictingRevisionThree));

        assertThat(quarantined.outcome())
                .isEqualTo(PerformanceWorkforceInvalidationConsumer.Outcome.QUARANTINED);
        assertThat(quarantined.duplicate()).isTrue();
        assertThat(quarantined.projectionState())
                .isEqualTo(PerformanceWorkforceProjection.State.QUARANTINED);
        Map<String, Object> conflictEvidence = conflictEvidence(REVISION_THREE_EVENT);
        assertThat(conflictEvidence)
                .containsEntry("payload_hash", originalPayloadHash)
                .containsEntry("conflicting_payload_hash", conflictingPayloadHash)
                .containsEntry("processing_state", "QUARANTINED")
                .containsEntry("outcome_code", "QUARANTINED")
                .containsEntry("resulting_projection_state", "QUARANTINED")
                .containsKey("conflict_detected_at");
        assertThat(conflictEvidence.get("conflict_detected_at"))
                .isNotNull();

        assertThat(store.head(OTHER_TENANT)).isEmpty();
        assertForcedTenantIsolation();
        assertThat(freezeCounts()).isEqualTo(freezeCountsBefore);
    }

    private List<PerformanceWorkforceProjection.Member> persistedMembers(long localRevision) {
        return jdbc.query("""
                SELECT worker_public_id,
                       assignment_public_id,
                       workforce_status,
                       organization_public_id,
                       manager_assignment_public_id,
                       job_profile_public_id,
                       grade_public_id
                  FROM hris_performance.prf_workforce_projection_members
                 WHERE tenant_id = ?
                   AND local_revision = ?
                 ORDER BY assignment_public_id, worker_public_id
                """, (result, ignored) -> new PerformanceWorkforceProjection.Member(
                        result.getObject("worker_public_id", UUID.class),
                        result.getObject("assignment_public_id", UUID.class),
                        result.getString("workforce_status"),
                        result.getObject("organization_public_id", UUID.class),
                        result.getObject("manager_assignment_public_id", UUID.class),
                        result.getObject("job_profile_public_id", UUID.class),
                        result.getObject("grade_public_id", UUID.class)),
                PerformanceWorkforceTestSupport.TENANT,
                localRevision);
    }

    private int inboxCount(UUID eventId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM hris_performance.prf_workforce_projection_inbox
                 WHERE tenant_id = ?
                   AND event_id = ?
                """, Integer.class, PerformanceWorkforceTestSupport.TENANT, eventId);
    }

    private Map<String, Object> conflictEvidence(UUID eventId) {
        return jdbc.queryForMap("""
                SELECT btrim(payload_hash) AS payload_hash,
                       btrim(conflicting_payload_hash) AS conflicting_payload_hash,
                       processing_state,
                       outcome_code,
                       resulting_projection_state,
                       conflict_detected_at
                  FROM hris_performance.prf_workforce_projection_inbox
                 WHERE tenant_id = ?
                   AND event_id = ?
                """, PerformanceWorkforceTestSupport.TENANT, eventId);
    }

    private Map<String, Object> replayEvidence(UUID eventId) {
        return jdbc.queryForMap("""
                SELECT processing_state,
                       outcome_code,
                       resulting_projection_state
                  FROM hris_performance.prf_workforce_projection_inbox
                 WHERE tenant_id = ?
                   AND event_id = ?
                """, PerformanceWorkforceTestSupport.TENANT, eventId);
    }

    private List<Integer> freezeCounts() {
        return List.of(
                rowCount("prf_population_freezes"),
                rowCount("prf_population_freeze_members"),
                rowCount("prf_population_freeze_reviewers"));
    }

    private int rowCount(String table) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM hris_performance." + table, Integer.class);
    }

    private void assertForcedTenantIsolation() {
        jdbc.execute("CREATE ROLE " + RLS_READER + " NOLOGIN NOSUPERUSER NOBYPASSRLS");
        jdbc.execute("GRANT USAGE ON SCHEMA hris_performance TO " + RLS_READER);
        jdbc.execute("GRANT SELECT ON hris_performance.prf_workforce_projection_heads TO "
                + RLS_READER);

        assertThat(headCountVisibleAsRlsReader(PerformanceWorkforceTestSupport.TENANT)).isOne();
        assertThat(headCountVisibleAsRlsReader(OTHER_TENANT)).isZero();
    }

    private int headCountVisibleAsRlsReader(long tenantId) {
        Integer count = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.execute("SET LOCAL ROLE " + RLS_READER);
            String applied = jdbc.queryForObject(
                    "SELECT set_config('dwp.tenant_id', CAST(? AS VARCHAR), TRUE)",
                    String.class,
                    tenantId);
            assertThat(applied).isEqualTo(Long.toString(tenantId));
            return jdbc.queryForObject("""
                    SELECT COUNT(*)
                      FROM hris_performance.prf_workforce_projection_heads
                    """, Integer.class);
        });
        assertThat(count).isNotNull();
        return count;
    }

    private static Xcon003WorkerChangedV1 worker(long revision, String changeType) {
        return new Xcon003WorkerChangedV1(
                changeType,
                EFFECTIVE_DATE,
                revision,
                PerformanceWorkforceTestSupport.MEMBER_ONE.workerPublicId());
    }

    private static PerformanceWorkforceInvalidationConsumer.TrustedEnvelope<
            Xcon003WorkerChangedV1> envelope(
                    UUID eventId,
                    Xcon003WorkerChangedV1 payload) {
        return new PerformanceWorkforceInvalidationConsumer.TrustedEnvelope<>(
                PerformanceWorkforceTestSupport.TENANT,
                eventId,
                PerformanceWorkforceInvalidationConsumer.payloadDigest(payload),
                payload);
    }

    private static DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }
}
