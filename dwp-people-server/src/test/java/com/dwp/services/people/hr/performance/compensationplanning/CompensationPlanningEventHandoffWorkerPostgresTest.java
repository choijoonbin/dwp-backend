package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.contracts.hris.xcon.v1.Xcon021ApprovedCompensationPlanSnapshotPublishedV2;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventOutboxRepository;
import com.dwp.core.event.DomainEventRecorder;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

@Testcontainers(disabledWithoutDocker = true)
class CompensationPlanningEventHandoffWorkerPostgresTest {

    private static final long TENANT = 101L;
    private static final UUID EVENT =
            UUID.fromString("31000000-0000-4000-8000-000000000001");
    private static final UUID RECEIPT =
            UUID.fromString("31000000-0000-4000-8000-000000000002");
    private static final UUID SUBJECT =
            UUID.fromString("31000000-0000-4000-8000-000000000003");
    private static final UUID CYCLE =
            UUID.fromString("31000000-0000-4000-8000-000000000004");
    private static final UUID SNAPSHOT =
            UUID.fromString("31000000-0000-4000-8000-000000000005");
    private static final UUID APPROVAL_RECEIPT =
            UUID.fromString("31000000-0000-4000-8000-000000000006");
    private static final UUID CORRELATION =
            UUID.fromString("31000000-0000-4000-8000-000000000007");
    private static final UUID SUCCESSOR_RECEIPT =
            UUID.fromString("31000000-0000-4000-8000-000000000008");
    private static final UUID SUCCESSOR_EVENT =
            UUID.fromString("31000000-0000-4000-8000-000000000009");
    private static final Instant OCCURRED_AT =
            Instant.parse("2026-01-02T03:04:05Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getenv().getOrDefault(
                    "DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));

    private JdbcTemplate jdbc;
    private NamedParameterJdbcTemplate named;
    private ObjectMapper mapper;
    private CompensationPlanningEventHandoffRepository repository;
    private DomainEventRecorder recorder;
    private AuditOutboxRecorder audit;
    private DomainEventContractRegistry contracts;
    private DataSourceTransactionManager transactionManager;

    @BeforeEach
    void migrateAndSeedPendingSnapshotEvent() throws Exception {
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
        try (Connection connection = dataSource.getConnection()) {
            ClassPathResource resource = new ClassPathResource(
                    "db/migration/R__create_domain_event_delivery_ledger.sql");
            try (java.sql.Statement statement = connection.createStatement()) {
                statement.execute(resource.getContentAsString(StandardCharsets.UTF_8));
            }
        }
        jdbc.execute("TRUNCATE TABLE sys_domain_event_outbox");

        mapper = new ObjectMapper().findAndRegisterModules();
        named = new NamedParameterJdbcTemplate(dataSource);
        repository = new CompensationPlanningEventHandoffRepository(named, mapper);
        DomainEventOutboxRepository durableOutbox =
                new DomainEventOutboxRepository(named, mapper);
        contracts = new DomainEventContractRegistry();
        recorder = new DomainEventRecorder(durableOutbox, contracts, mapper);
        transactionManager = new DataSourceTransactionManager(dataSource);
        createAuditOutbox();
        audit = new AuditOutboxRecorder(
                named, mapper, "dwp-people-server", "test-instance", "test");
        seedReceiptAndEvent();
    }

    @Test
    void crashAfterDurableRecordRollsBackAndRestartHandsOffExactlyOnce() {
        CompensationPlanningEventHandoffWorker crashing = worker(
                "worker-a",
                event -> {
                    throw new IllegalStateException("simulated process crash");
                });

        assertThat(crashing.handoffOnce()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_domain_event_outbox",
                Integer.class)).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT delivery_state, delivery_attempts
                  FROM hris_performance.prf_outbox_events
                 WHERE event_id = ?
                """, EVENT))
                .containsEntry("delivery_state", "PENDING")
                .containsEntry("delivery_attempts", 1);

        expireVisibilityLease();
        CompensationPlanningEventHandoffWorker restarted =
                worker("worker-b", ignored -> { });

        assertThat(restarted.handoffOnce()).isOne();
        assertThat(jdbc.queryForMap("""
                SELECT delivery_state, delivery_attempts
                  FROM hris_performance.prf_outbox_events
                 WHERE event_id = ?
                """, EVENT))
                .containsEntry("delivery_state", "DELIVERED")
                .containsEntry("delivery_attempts", 2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_domain_event_outbox WHERE event_id = ?",
                Integer.class,
                EVENT)).isOne();

        String envelopeText = jdbc.queryForObject("""
                SELECT payload::text
                  FROM sys_domain_event_outbox
                 WHERE event_id = ?
                """, String.class, EVENT);
        JsonNode envelope = read(envelopeText);
        assertThat(envelope.path("id").asText()).isEqualTo(EVENT.toString());
        assertThat(envelope.path("type").asText()).isEqualTo(
                CompensationPlanningEventHandoffWorker.SNAPSHOT_PUBLISHED_TOPIC);
        assertThat(envelope.path("data").size()).isEqualTo(16);
        assertThat(envelope.path("data").path("snapshotId").asText())
                .isEqualTo(SNAPSHOT.toString());
        assertThat(envelope.path("data").path("snapshotRevision").asLong())
                .isOne();
        assertThat(envelope.path("data").path("payloadDigest").asText())
                .isEqualTo("a".repeat(64));
    }

    @Test
    void expiredClaimIsReclaimedAndItsStaleCompletionIsFenced() {
        List<CompensationPlanningEventHandoffRepository.ClaimedEvent> first =
                repository.claim(
                        "worker-a", 1, Duration.ofSeconds(30),
                        Set.of("COMPENSATION_CYCLE"));
        assertThat(first).singleElement().satisfies(
                event -> assertThat(event.deliveryAttempt()).isOne());

        expireVisibilityLease();
        List<CompensationPlanningEventHandoffRepository.ClaimedEvent> second =
                repository.claim(
                        "worker-b", 1, Duration.ofSeconds(30),
                        Set.of("COMPENSATION_CYCLE"));

        assertThat(second).singleElement().satisfies(
                event -> assertThat(event.deliveryAttempt()).isEqualTo(2));
        assertThat(repository.markDelivered(first.getFirst())).isFalse();
        assertThat(repository.markDelivered(second.getFirst())).isTrue();
        assertThat(jdbc.queryForMap("""
                SELECT delivery_state, delivery_attempts
                  FROM hris_performance.prf_outbox_events
                 WHERE event_id = ?
                """, EVENT))
                .containsEntry("delivery_state", "DELIVERED")
                .containsEntry("delivery_attempts", 2);
    }

    @Test
    void aggregateSuccessorWaitsUntilItsPredecessorIsDurablyHandedOff() throws Exception {
        seedOrderedCancellationEvents();
        Set<String> supported = Set.of("COMPENSATION_CYCLE");

        List<CompensationPlanningEventHandoffRepository.ClaimedEvent> first =
                repository.claim("worker-a", 10, Duration.ofSeconds(30), supported);

        assertThat(first).singleElement().satisfies(event -> {
            assertThat(event.eventId()).isEqualTo(EVENT);
            assertThat(event.aggregateVersion()).isEqualTo(8L);
        });
        assertThat(repository.markDelivered(first.getFirst())).isTrue();

        List<CompensationPlanningEventHandoffRepository.ClaimedEvent> second =
                repository.claim("worker-a", 10, Duration.ofSeconds(30), supported);
        assertThat(second).singleElement().satisfies(event -> {
            assertThat(event.eventId()).isEqualTo(SUCCESSOR_EVENT);
            assertThat(event.aggregateVersion()).isEqualTo(9L);
        });
    }

    @Test
    void malformedSealedPayloadIsQuarantinedWithoutSharedPublication() {
        jdbc.update("""
                UPDATE hris_performance.prf_outbox_events
                   SET payload = payload || '{"unexpected":true}'::jsonb
                 WHERE event_id = ?
                """, EVENT);

        assertThat(worker("worker-a", ignored -> { }).handoffOnce()).isZero();

        assertThat(jdbc.queryForMap("""
                SELECT delivery_state, delivery_attempts
                  FROM hris_performance.prf_outbox_events
                 WHERE event_id = ?
                """, EVENT))
                .containsEntry("delivery_state", "QUARANTINED")
                .containsEntry("delivery_attempts", 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_domain_event_outbox",
                Integer.class)).isZero();
        JsonNode quarantineAudit = read(jdbc.queryForObject("""
                SELECT payload::text
                  FROM sys_audit_outbox
                 WHERE payload ->> 'action' =
                       'compensation.event-handoff.quarantined'
                """, String.class));
        assertThat(quarantineAudit.path("reason").asText())
                .isEqualTo("INVALID_SEALED_EVENT");
    }

    @Test
    void quarantineTransitionRollsBackWhenDurableAuditCannotBeRecorded() {
        jdbc.update("""
                UPDATE hris_performance.prf_outbox_events
                   SET payload = payload || '{"unexpected":true}'::jsonb
                 WHERE event_id = ?
                """, EVENT);
        AuditOutboxRecorder failingAudit = mock(AuditOutboxRecorder.class);
        doThrow(new IllegalStateException("simulated audit outage"))
                .when(failingAudit).record(any());
        CompensationPlanningEventHandoffWorker worker =
                new CompensationPlanningEventHandoffWorker(
                        repository, recorder, failingAudit, contracts, mapper,
                        transactionManager, true, "worker-audit-failure", 10,
                        Duration.ofSeconds(30), 1, ignored -> { });

        assertThat(worker.handoffOnce()).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT delivery_state, delivery_attempts
                  FROM hris_performance.prf_outbox_events
                 WHERE event_id = ?
                """, EVENT))
                .containsEntry("delivery_state", "PENDING")
                .containsEntry("delivery_attempts", 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_audit_outbox", Integer.class)).isZero();
    }

    @Test
    void unsupportedCompensationDescriptorIsReachablyQuarantinedAndFencesSuccessor()
            throws Exception {
        seedOrderedCancellationEvents();
        jdbc.update("""
                UPDATE hris_performance.prf_outbox_events
                   SET event_type = 'UnsupportedCompensationEvent.v3'
                 WHERE event_id = ?
                """, EVENT);

        assertThat(worker("worker-unsupported", ignored -> { }).handoffOnce()).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT delivery_state, delivery_attempts
                  FROM hris_performance.prf_outbox_events
                 WHERE event_id = ?
                """, EVENT))
                .containsEntry("delivery_state", "QUARANTINED")
                .containsEntry("delivery_attempts", 1);
        assertThat(worker("worker-successor", ignored -> { }).handoffOnce()).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT delivery_state
                  FROM hris_performance.prf_outbox_events
                 WHERE event_id = ?
                """, String.class, SUCCESSOR_EVENT)).isEqualTo("PENDING");
    }

    @Test
    void replayReleaseCannotCrossDomainOrUseAMismatchedDescriptorPair() {
        jdbc.update("""
                UPDATE hris_performance.prf_outbox_events
                   SET delivery_state = 'QUARANTINED',
                       aggregate_type = 'PERFORMANCE_CYCLE'
                 WHERE event_id = ?
                """, EVENT);
        assertThat(repository.releaseQuarantined(
                TENANT, EVENT,
                CompensationPlanningEventHandoffWorker.supportedCycleEventNames(),
                CompensationPlanningEventHandoffWorker.supportedProposalEventNames()))
                .isEmpty();

        jdbc.update("""
                UPDATE hris_performance.prf_outbox_events
                   SET aggregate_type = 'COMPENSATION_CYCLE',
                       event_type = 'CompensationProposalDrafted.v2'
                 WHERE event_id = ?
                """, EVENT);
        assertThat(repository.releaseQuarantined(
                TENANT, EVENT,
                CompensationPlanningEventHandoffWorker.supportedCycleEventNames(),
                CompensationPlanningEventHandoffWorker.supportedProposalEventNames()))
                .isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT delivery_state
                  FROM hris_performance.prf_outbox_events
                 WHERE event_id = ?
                """, String.class, EVENT)).isEqualTo("QUARANTINED");
    }

    @Test
    void allEightNamedEventContractsAreAcceptedWithTheirExactAggregateAndTransition()
            throws Exception {
        seedStandardEvent(10, "CompensationBudgetAllocated.v2",
                "COMPENSATION_CYCLE", "DRAFT", "OPEN", 2L);
        seedStandardEvent(11, "CompensationCycleCancelled.v2",
                "COMPENSATION_CYCLE", "OPEN", "CANCELLED", 3L);
        seedStandardEvent(12, "CompensationCycleCreated.v2",
                "COMPENSATION_CYCLE", "NONE", "DRAFT", 1L);
        seedStandardEvent(13, "CompensationCycleRevised.v2",
                "COMPENSATION_CYCLE", "DRAFT", "DRAFT", 2L);
        seedStandardEvent(14, "CompensationPlanApprovalRecorded.v2",
                "COMPENSATION_CYCLE", "PROPOSAL_REVIEW", "APPROVED", 7L);
        seedStandardEvent(15, "CompensationProposalDrafted.v2",
                "COMPENSATION_PROPOSAL", "NONE", "DRAFT", 1L);
        seedStandardEvent(16, "CompensationProposalSubmitted.v2",
                "COMPENSATION_PROPOSAL", "DRAFT", "SUBMITTED", 2L);

        assertThat(worker("worker-contracts", ignored -> { }).handoffOnce())
                .isEqualTo(8);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM hris_performance.prf_outbox_events
                 WHERE delivery_state = 'DELIVERED'
                """, Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_domain_event_outbox",
                Integer.class)).isEqualTo(8);
    }

    @Test
    void standardEnvelopeContractDriftIsQuarantinedWithoutSharedPublication()
            throws Exception {
        markSeedSnapshotDelivered();

        SeededEvent wrongType = seedStandardEvent(
                30, "CompensationCycleCreated.v2",
                "COMPENSATION_CYCLE", "NONE", "DRAFT", 1L);
        jdbc.update("""
                UPDATE hris_performance.prf_outbox_events
                   SET aggregate_type = 'COMPENSATION_PROPOSAL'
                 WHERE event_id = ?
                """, wrongType.eventId());

        SeededEvent wrongTransition = seedStandardEvent(
                31, "CompensationCycleCancelled.v2",
                "COMPENSATION_CYCLE", "OPEN", "CANCELLED", 3L);
        mutatePayload(wrongTransition.eventId(),
                payload -> payload.put("toState", "PUBLISHED"));

        SeededEvent wrongIdentity = seedStandardEvent(
                32, "CompensationProposalSubmitted.v2",
                "COMPENSATION_PROPOSAL", "DRAFT", "SUBMITTED", 2L);
        mutatePayload(wrongIdentity.eventId(), payload -> payload.put(
                "aggregateId", uuid("payload-aggregate-drift").toString()));

        SeededEvent wrongVersion = seedStandardEvent(
                33, "CompensationCycleRevised.v2",
                "COMPENSATION_CYCLE", "DRAFT", "DRAFT", 2L);
        mutatePayload(wrongVersion.eventId(),
                payload -> payload.put("aggregateVersion", 3L));

        SeededEvent wrongTime = seedStandardEvent(
                34, "CompensationBudgetAllocated.v2",
                "COMPENSATION_CYCLE", "DRAFT", "OPEN", 2L);
        mutatePayload(wrongTime.eventId(), payload -> payload.put(
                "occurredAt", wrongTime.occurredAt().plusSeconds(1).toString()));

        assertThat(worker("worker-invalid-standard", ignored -> { }).handoffOnce())
                .isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM hris_performance.prf_outbox_events
                 WHERE delivery_state = 'QUARANTINED'
                """, Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_domain_event_outbox",
                Integer.class)).isZero();
    }

    @Test
    void everyXcon021CrossFieldInvariantQuarantinesWithoutSharedPublication()
            throws Exception {
        mutatePayload(EVENT, payload -> payload.put(
                "planId", uuid("different-plan").toString()));
        assertXconRejected("worker-invalid-xcon-plan");

        resetSeedSnapshotForValidation();
        mutatePayload(EVENT, payload -> {
            payload.put("planId", CYCLE.toString());
            payload.put("sourceVersion", 6L);
        });
        assertXconRejected("worker-invalid-xcon-version");

        resetSeedSnapshotForValidation();
        mutatePayload(EVENT, payload -> {
            payload.put("sourceVersion", 7L);
            payload.put("occurredAt", OCCURRED_AT.plusSeconds(1).toString());
        });
        assertXconRejected("worker-invalid-xcon-time");

        resetSeedSnapshotForValidation();
        mutatePayload(EVENT, payload -> {
            payload.put("occurredAt", OCCURRED_AT.toString());
            payload.put("fromState", "DRAFT");
        });
        assertXconRejected("worker-invalid-xcon-transition");

        resetSeedSnapshotForValidation();
        mutatePayload(EVENT, payload -> payload.put("fromState", "APPROVED"));
        jdbc.update("""
                UPDATE hris_performance.prf_outbox_events
                   SET aggregate_type = 'COMPENSATION_PROPOSAL'
                 WHERE event_id = ?
                """, EVENT);
        assertXconRejected("worker-invalid-xcon-aggregate");

        assertThat(jdbc.queryForMap("""
                SELECT delivery_state, delivery_attempts
                  FROM hris_performance.prf_outbox_events
                 WHERE event_id = ?
                """, EVENT))
                .containsEntry("delivery_state", "QUARANTINED")
                .containsEntry("delivery_attempts", 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_domain_event_outbox",
                Integer.class)).isZero();
    }

    @Test
    void quarantinedEventIsAuditedReleasedAndThenHandedOffSuccessfully() {
        CompensationPlanningEventHandoffWorker failing = worker(
                "worker-failing", 1,
                ignored -> {
                    throw new IllegalStateException("simulated durable handoff failure");
                });
        assertThat(failing.handoffOnce()).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT delivery_state, delivery_attempts
                  FROM hris_performance.prf_outbox_events
                 WHERE event_id = ?
                """, EVENT))
                .containsEntry("delivery_state", "QUARANTINED")
                .containsEntry("delivery_attempts", 1);

        CompensationPlanningEventHandoffReplayService replay =
                new CompensationPlanningEventHandoffReplayService(
                        repository, audit, transactionManager);
        PeopleRequestContext.set(
                700L, TENANT, null, Set.of("ADMIN"),
                Set.of("DATA.HRIS_COMPENSATION_PLAN:UPDATE"));
        try {
            assertThatThrownBy(() -> replay.release(
                    EVENT, CORRELATION, "generic admin must not replay"))
                    .isInstanceOf(SecurityException.class);
        } finally {
            PeopleRequestContext.clear();
        }
        PeopleRequestContext.set(
                700L, TENANT, null, Set.of("TENANT_ADMIN"),
                Set.of("DATA.HRIS_COMPENSATION_PLAN:UPDATE"));
        try {
            assertThat(replay.release(
                    EVENT, CORRELATION, "contract remediation verified"))
                    .isTrue();
        } finally {
            PeopleRequestContext.clear();
        }

        assertThat(jdbc.queryForMap("""
                SELECT delivery_state, delivery_attempts
                  FROM hris_performance.prf_outbox_events
                 WHERE event_id = ?
                """, EVENT))
                .containsEntry("delivery_state", "PENDING")
                .containsEntry("delivery_attempts", 0);
        JsonNode auditPayload = read(jdbc.queryForObject(
                """
                SELECT payload::text
                  FROM sys_audit_outbox
                 WHERE payload ->> 'action' =
                       'compensation.event-handoff.quarantine-released'
                """,
                String.class));
        assertThat(auditPayload.path("action").asText()).isEqualTo(
                "compensation.event-handoff.quarantine-released");
        assertThat(auditPayload.path("reason").asText()).isEqualTo(
                "contract remediation verified");
        assertThat(auditPayload.path("beforeState").path("deliveryAttempts").asInt())
                .isOne();
        assertThat(auditPayload.path("afterState").path("deliveryAttempts").asInt())
                .isZero();
        assertThat(jdbc.queryForMap("""
                SELECT direction, target_id, consumer_name, requested_by, reason
                  FROM sys_domain_event_replay_audit
                 WHERE target_id = ?
                """, EVENT.toString()))
                .containsEntry("direction", "OUTBOX")
                .containsEntry("target_id", EVENT.toString())
                .containsEntry(
                        "consumer_name", "hris-performance-compensation-handoff")
                .containsEntry("requested_by", "tenant:" + TENANT + ":user:700")
                .containsEntry("reason", "contract remediation verified");

        assertThat(worker("worker-replayed", ignored -> { }).handoffOnce()).isOne();
        assertThat(jdbc.queryForMap("""
                SELECT delivery_state, delivery_attempts
                  FROM hris_performance.prf_outbox_events
                 WHERE event_id = ?
                """, EVENT))
                .containsEntry("delivery_state", "DELIVERED")
                .containsEntry("delivery_attempts", 1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_domain_event_outbox WHERE event_id = ?",
                Integer.class,
                EVENT)).isOne();
    }

    private CompensationPlanningEventHandoffWorker worker(
            String workerId,
            java.util.function.Consumer<
                    CompensationPlanningEventHandoffRepository.ClaimedEvent> hook) {
        return worker(workerId, 5, hook);
    }

    private CompensationPlanningEventHandoffWorker worker(
            String workerId,
            int maximumAttempts,
            java.util.function.Consumer<
                    CompensationPlanningEventHandoffRepository.ClaimedEvent> hook) {
        return new CompensationPlanningEventHandoffWorker(
                repository,
                recorder,
                audit,
                contracts,
                mapper,
                transactionManager,
                true,
                workerId,
                10,
                Duration.ofSeconds(30),
                maximumAttempts,
                hook);
    }

    private void seedReceiptAndEvent() throws Exception {
        jdbc.update("""
                INSERT INTO hris_performance.prf_command_receipts (
                    command_receipt_id, tenant_id, idempotency_key, command_type,
                    originating_action, actor_id, subject_principal_public_id,
                    aggregate_id, expected_aggregate_version,
                    applied_aggregate_version, request_hash,
                    population_scope_digest, field_policy_revision, purpose_code,
                    authorization_revision, receipt_state, result_ref,
                    accepted_at, completed_at)
                VALUES (?, ?, 'handoff-test', 'PUBLISH', 'test.publish', 7, ?, ?, 7,
                        8, ?, ?, 1, 'hcm.compensation.plan.approve', 1,
                        'SUCCEEDED', ?, ?, ?)
                """,
                RECEIPT,
                TENANT,
                SUBJECT,
                CYCLE,
                "b".repeat(64),
                "c".repeat(64),
                SNAPSHOT,
                Timestamp.from(OCCURRED_AT),
                Timestamp.from(OCCURRED_AT));
        Xcon021ApprovedCompensationPlanSnapshotPublishedV2 payload =
                new Xcon021ApprovedCompensationPlanSnapshotPublishedV2(
                        CYCLE,
                        8L,
                        APPROVAL_RECEIPT,
                        7L,
                        CORRELATION,
                        CYCLE,
                        LocalDate.parse("2026-01-01"),
                        "APPROVED",
                        1,
                        OCCURRED_AT,
                        "a".repeat(64),
                        CYCLE,
                        SNAPSHOT,
                        1L,
                        7L,
                        "PUBLISHED");
        jdbc.update("""
                INSERT INTO hris_performance.prf_outbox_events (
                    event_id, tenant_id, command_receipt_id, aggregate_type,
                    aggregate_id, aggregate_version, event_type, payload,
                    delivery_state, delivery_attempts, available_at, created_at)
                VALUES (?, ?, ?, 'COMPENSATION_CYCLE', ?, 8, ?, CAST(? AS JSONB),
                        'PENDING', 0, ?, ?)
                """,
                EVENT,
                TENANT,
                RECEIPT,
                CYCLE,
                CompensationPlanningEventHandoffWorker.SNAPSHOT_PUBLISHED,
                mapper.writeValueAsString(payload),
                Timestamp.from(OCCURRED_AT),
                Timestamp.from(OCCURRED_AT));
    }

    private void seedOrderedCancellationEvents() throws Exception {
        ObjectNode predecessor = mapper.createObjectNode()
                .put("aggregateId", CYCLE.toString())
                .put("fromState", "OPEN")
                .put("toState", "OPEN")
                .put("aggregateVersion", 8L)
                .put("occurredAt", OCCURRED_AT.toString())
                .put("correlationId", CORRELATION.toString());
        jdbc.update("""
                UPDATE hris_performance.prf_outbox_events
                   SET event_type = 'CompensationBudgetAllocated.v2',
                       payload = CAST(? AS JSONB)
                 WHERE event_id = ?
                """, mapper.writeValueAsString(predecessor), EVENT);
        jdbc.update("""
                UPDATE hris_performance.prf_command_receipts
                   SET command_type = 'BUDGET',
                       originating_action = 'test.budget'
                 WHERE command_receipt_id = ?
                """, RECEIPT);
        jdbc.update("""
                INSERT INTO hris_performance.prf_command_receipts (
                    command_receipt_id, tenant_id, idempotency_key, command_type,
                    originating_action, actor_id, subject_principal_public_id,
                    aggregate_id, expected_aggregate_version,
                    applied_aggregate_version, request_hash,
                    population_scope_digest, field_policy_revision, purpose_code,
                    authorization_revision, receipt_state, result_ref,
                    accepted_at, completed_at)
                VALUES (?, ?, 'handoff-successor', 'CANCEL', 'test.cancel', 7, ?, ?, 8,
                        9, ?, ?, 1, 'hcm.compensation.plan.propose', 1,
                        'SUCCEEDED', ?, ?, ?)
                """,
                SUCCESSOR_RECEIPT,
                TENANT,
                SUBJECT,
                CYCLE,
                "d".repeat(64),
                "e".repeat(64),
                CYCLE,
                Timestamp.from(OCCURRED_AT.plusSeconds(1)),
                Timestamp.from(OCCURRED_AT.plusSeconds(1)));
        com.fasterxml.jackson.databind.node.ObjectNode payload = mapper.createObjectNode()
                .put("aggregateId", CYCLE.toString())
                .put("fromState", "OPEN")
                .put("toState", "CANCELLED")
                .put("aggregateVersion", 9L)
                .put("occurredAt", OCCURRED_AT.plusSeconds(1).toString())
                .put("correlationId", CORRELATION.toString());
        jdbc.update("""
                INSERT INTO hris_performance.prf_outbox_events (
                    event_id, tenant_id, command_receipt_id, aggregate_type,
                    aggregate_id, aggregate_version, event_type, payload,
                    delivery_state, delivery_attempts, available_at, created_at)
                VALUES (?, ?, ?, 'COMPENSATION_CYCLE', ?, 9,
                        'CompensationCycleCancelled.v2', CAST(? AS JSONB),
                        'PENDING', 0, ?, ?)
                """,
                SUCCESSOR_EVENT,
                TENANT,
                SUCCESSOR_RECEIPT,
                CYCLE,
                mapper.writeValueAsString(payload),
                Timestamp.from(OCCURRED_AT.plusSeconds(1)),
                Timestamp.from(OCCURRED_AT.plusSeconds(1)));
    }

    private SeededEvent seedStandardEvent(
            int ordinal,
            String eventName,
            String aggregateType,
            String fromState,
            String toState,
            long aggregateVersion) throws Exception {
        UUID eventId = uuid("event-" + ordinal);
        UUID receiptId = uuid("receipt-" + ordinal);
        UUID aggregateId = uuid("aggregate-" + ordinal);
        Instant occurredAt = OCCURRED_AT.plusSeconds(ordinal);
        jdbc.update("""
                INSERT INTO hris_performance.prf_command_receipts (
                    command_receipt_id, tenant_id, idempotency_key, command_type,
                    originating_action, actor_id, subject_principal_public_id,
                    aggregate_id, expected_aggregate_version,
                    applied_aggregate_version, request_hash,
                    population_scope_digest, field_policy_revision, purpose_code,
                    authorization_revision, receipt_state, result_ref,
                    accepted_at, completed_at)
                VALUES (?, ?, ?, 'TEST', ?, 7, ?, ?, ?,
                        ?, ?, ?, 1, 'hcm.compensation.plan.propose', 1,
                        'SUCCEEDED', ?, ?, ?)
                """,
                receiptId,
                TENANT,
                "handoff-contract-" + ordinal,
                "test.contract." + ordinal,
                SUBJECT,
                aggregateId,
                aggregateVersion - 1L,
                aggregateVersion,
                "a".repeat(64),
                "b".repeat(64),
                aggregateId,
                Timestamp.from(occurredAt),
                Timestamp.from(occurredAt));
        ObjectNode payload = mapper.createObjectNode()
                .put("aggregateId", aggregateId.toString())
                .put("fromState", fromState)
                .put("toState", toState)
                .put("aggregateVersion", aggregateVersion)
                .put("occurredAt", occurredAt.toString())
                .put("correlationId", uuid("correlation-" + ordinal).toString());
        jdbc.update("""
                INSERT INTO hris_performance.prf_outbox_events (
                    event_id, tenant_id, command_receipt_id, aggregate_type,
                    aggregate_id, aggregate_version, event_type, payload,
                    delivery_state, delivery_attempts, available_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB),
                        'PENDING', 0, ?, ?)
                """,
                eventId,
                TENANT,
                receiptId,
                aggregateType,
                aggregateId,
                aggregateVersion,
                eventName,
                mapper.writeValueAsString(payload),
                Timestamp.from(occurredAt),
                Timestamp.from(occurredAt));
        return new SeededEvent(eventId, aggregateId, aggregateVersion, occurredAt);
    }

    private void mutatePayload(UUID eventId, Consumer<ObjectNode> mutation)
            throws Exception {
        String current = jdbc.queryForObject("""
                SELECT payload::text
                  FROM hris_performance.prf_outbox_events
                 WHERE event_id = ?
                """, String.class, eventId);
        ObjectNode payload = (ObjectNode) mapper.readTree(current);
        mutation.accept(payload);
        jdbc.update("""
                UPDATE hris_performance.prf_outbox_events
                   SET payload = CAST(? AS JSONB)
                 WHERE event_id = ?
                """, mapper.writeValueAsString(payload), eventId);
    }

    private void markSeedSnapshotDelivered() {
        jdbc.update("""
                UPDATE hris_performance.prf_outbox_events
                   SET delivery_state = 'DELIVERED'
                 WHERE event_id = ?
                """, EVENT);
    }

    private void assertXconRejected(String workerId) {
        assertThat(worker(workerId, ignored -> { }).handoffOnce()).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT delivery_state
                  FROM hris_performance.prf_outbox_events
                 WHERE event_id = ?
                """, String.class, EVENT)).isEqualTo("QUARANTINED");
    }

    private void resetSeedSnapshotForValidation() {
        jdbc.update("""
                UPDATE hris_performance.prf_outbox_events
                   SET delivery_state = 'PENDING',
                       delivery_attempts = 0,
                       available_at = CURRENT_TIMESTAMP
                 WHERE event_id = ?
                """, EVENT);
    }

    private void createAuditOutbox() {
        jdbc.execute("DROP TABLE IF EXISTS sys_audit_outbox");
        jdbc.execute("""
                CREATE TABLE sys_audit_outbox (
                    outbox_id UUID PRIMARY KEY,
                    event_id UUID NOT NULL UNIQUE,
                    tenant_id BIGINT NOT NULL,
                    payload JSONB NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    attempt_count INTEGER NOT NULL,
                    available_at TIMESTAMPTZ NOT NULL,
                    locked_by VARCHAR(255),
                    locked_until TIMESTAMPTZ,
                    published_at TIMESTAMPTZ,
                    last_error VARCHAR(500),
                    created_at TIMESTAMPTZ NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)
                """);
    }

    private static UUID uuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private void expireVisibilityLease() {
        jdbc.update("""
                UPDATE hris_performance.prf_outbox_events
                   SET available_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                 WHERE event_id = ?
                """, EVENT);
    }

    private JsonNode read(String value) {
        try {
            return mapper.readTree(value);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private DataSource dataSource() {
        PGSimpleDataSource value = new PGSimpleDataSource();
        value.setUrl(POSTGRES.getJdbcUrl());
        value.setUser(POSTGRES.getUsername());
        value.setPassword(POSTGRES.getPassword());
        return value;
    }

    private record SeededEvent(
            UUID eventId,
            UUID aggregateId,
            long aggregateVersion,
            Instant occurredAt) {
    }
}
