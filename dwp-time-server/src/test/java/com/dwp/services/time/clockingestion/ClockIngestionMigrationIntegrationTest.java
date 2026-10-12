package com.dwp.services.time.clockingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.HcmEligibilityScopeKey;
import com.dwp.services.time.clockingestion.ClockIngestionModels.CaptureMode;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockAction;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockEventType;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockExceptionProjectionQuery;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockProjectionQuery;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockSourceType;
import com.dwp.services.time.clockingestion.ClockIngestionModels.DstResolution;
import com.dwp.services.time.clockingestion.ClockIngestionModels.InstantAuthority;
import com.dwp.services.time.clockingestion.ClockIngestionModels.RecordClockCommand;
import com.dwp.services.time.clockingestion.ClockIngestionModels.TrustLevel;
import com.dwp.services.time.clockingestion.ClockIngestionModels.VerifiedClockRequest;
import com.dwp.services.time.clockingestion.ClockIngestionModels.VoidClockCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClockIngestionMigrationIntegrationTest {

    private static final String RUNTIME_ROLE = "tim_clock_runtime";
    private static final String RUNTIME_PASSWORD = "tim-clock-runtime-test";
    private static final String PUBLISHER_ROLE = "tim_clock_publisher";
    private static final String PUBLISHER_PASSWORD = "tim-clock-publisher-test";

    private PostgreSQLContainer<?> postgres;
    private DataSource adminDataSource;
    private JdbcTemplate admin;
    private Flyway flyway;

    @BeforeAll
    void migrateCleanDatabase() {
        assertThat(DockerClientFactory.instance().isDockerAvailable())
                .as("Docker is required for the hermetic TIM clock migration gate")
                .isTrue();
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        adminDataSource = dataSource(postgres.getUsername(), postgres.getPassword());
        admin = new JdbcTemplate(adminDataSource);
        admin.execute("CREATE ROLE " + RUNTIME_ROLE
                + " LOGIN PASSWORD '" + RUNTIME_PASSWORD
                + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
        admin.execute("CREATE ROLE " + PUBLISHER_ROLE
                + " LOGIN PASSWORD '" + PUBLISHER_PASSWORD
                + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
        flyway = Flyway.configure()
                .dataSource(adminDataSource)
                .locations("classpath:db/migration")
                .schemas("public")
                .defaultSchema("public")
                .table("flyway_schema_history")
                .baselineOnMigrate(false)
                .validateOnMigrate(true)
                .placeholderReplacement(true)
                .placeholders(Map.of(
                        "timeRuntimeRole", RUNTIME_ROLE,
                        "timeProjectionPublisherRole", PUBLISHER_ROLE))
                .load();
        // Flyway may count its schema-creation action in this result; the history assertion below
        // pins the leased version and filename exactly.
        assertThat(flyway.migrate().migrationsExecuted).isGreaterThanOrEqualTo(8);
    }

    @AfterAll
    void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @Test
    void cleanUpgradeIsRepeatableAndV8IsExactlyLeased() {
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(admin.queryForObject(
                "SELECT count(*) FROM flyway_schema_history "
                        + "WHERE success AND version='8' "
                        + "AND script='V8__tim_create_clock_ingestion.sql'",
                Integer.class)).isOne();
    }

    @Test
    void clockTablesAreForcedRlsAndRuntimeCannotRewriteEvidence() {
        assertThat(admin.queryForObject("""
                SELECT count(*)
                  FROM pg_class
                 WHERE relnamespace = 'public'::regnamespace
                   AND relname IN (
                       'tme_clock_command_receipts',
                       'tme_clock_events',
                       'tme_clock_outbox_events')
                   AND relkind = 'r'
                   AND relrowsecurity
                   AND relforcerowsecurity
                """, Integer.class)).isEqualTo(3);
        assertThat(admin.queryForObject("""
                SELECT has_table_privilege(?, 'public.tme_clock_events', 'INSERT')
                   AND has_table_privilege(?, 'public.tme_clock_events', 'SELECT')
                   AND NOT has_table_privilege(?, 'public.tme_clock_events', 'UPDATE')
                   AND NOT has_table_privilege(?, 'public.tme_clock_events', 'DELETE')
                   AND NOT has_table_privilege(
                       ?, 'public.tme_clock_command_receipts', 'UPDATE')
                """, Boolean.class,
                RUNTIME_ROLE, RUNTIME_ROLE, RUNTIME_ROLE, RUNTIME_ROLE, RUNTIME_ROLE)).isTrue();
        assertThat(admin.queryForObject("""
                SELECT has_column_privilege(
                           ?, 'public.tme_clock_outbox_events', 'published_at', 'UPDATE')
                   AND has_column_privilege(
                           ?, 'public.tme_clock_outbox_events', 'publish_attempts', 'UPDATE')
                   AND NOT has_table_privilege(
                           ?, 'public.tme_clock_outbox_events', 'INSERT')
                """, Boolean.class,
                PUBLISHER_ROLE, PUBLISHER_ROLE, PUBLISHER_ROLE)).isTrue();
        assertThat(admin.queryForObject("""
                SELECT count(*)
                  FROM pg_proc p
                  JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname = 'public'
                   AND p.proname IN (
                       'tme_guard_clock_source_order',
                       'tme_reject_clock_fact_mutation',
                       'tme_guard_clock_outbox_delivery')
                   AND has_function_privilege(?, p.oid, 'EXECUTE')
                """, Integer.class, RUNTIME_ROLE)).isZero();
    }

    @Test
    void delayedUniqueOfflineSequenceIsPreservedWhileDuplicatesAndOnlineRegressionFail() {
        long tenantId = 9_051L;
        UUID sourceId = UUID.fromString("90510000-0000-4000-8000-000000000001");
        UUID workerId = UUID.fromString("90510000-0000-4000-8000-000000000002");
        UUID assignmentId = UUID.fromString("90510000-0000-4000-8000-000000000003");
        insertFact(tenantId, sourceId, workerId, assignmentId, 2, "source-2", "ONLINE");
        insertFact(tenantId, sourceId, workerId, assignmentId, 4, "source-4", "ONLINE");

        insertFact(tenantId, sourceId, workerId, assignmentId, 3, "delayed-3", "OFFLINE");

        assertThat(admin.queryForObject("""
                SELECT count(*) FROM tme_clock_events
                 WHERE tenant_id = ? AND source_public_id = ?
                """, Integer.class, tenantId, sourceId)).isEqualTo(3);
        assertThatThrownBy(() -> insertFact(
                tenantId, sourceId, workerId, assignmentId, 3, "duplicate-3", "OFFLINE"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertFact(
                tenantId, sourceId, workerId, assignmentId, 1, "online-regression", "ONLINE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void jdbcQueriesUseOneKeysetSnapshotAndDeriveGapRecoveryFromPreservedFacts() {
        long tenantId = 9_052L;
        UUID sourceId = UUID.fromString("90520000-0000-4000-8000-000000000001");
        UUID workerId = UUID.fromString("90520000-0000-4000-8000-000000000002");
        UUID assignmentId = UUID.fromString("90520000-0000-4000-8000-000000000003");
        UUID firstId = insertFact(
                tenantId, sourceId, workerId, assignmentId, 1, "ordered-1", "ONLINE");
        UUID thirdId = insertFact(
                tenantId, sourceId, workerId, assignmentId, 3, "ordered-3", "ONLINE");
        UUID delayedId = insertFact(
                tenantId, sourceId, workerId, assignmentId, 2, "delayed-2", "OFFLINE");
        Instant asOf = Instant.parse("2026-10-12T03:00:00Z");
        JdbcClockEventRepository repository = new JdbcClockEventRepository(
                admin,
                new DataSourceTransactionManager(adminDataSource),
                new ObjectMapper().findAndRegisterModules());

        var firstPage = repository.queryClock(new ClockProjectionQuery(
                tenantId, Set.of(workerId), asOf, null, List.of(), 2,
                List.of("clockEvent:ASC"), null, null, null, null, null, "UTC",
                List.of(), null, List.of(), null));
        assertThat(firstPage.items()).extracting(candidate -> candidate.event().clockEventId())
                .containsExactly(firstId, delayedId);
        assertThat(firstPage.hasMore()).isTrue();

        UUID lateId = insertFact(
                tenantId, sourceId, workerId, assignmentId, 4, "late-after-snapshot", "ONLINE");
        var continuation = repository.queryClock(new ClockProjectionQuery(
                tenantId, Set.of(workerId), asOf, firstPage.snapshotHighWater(),
                firstPage.nextKey(), 2, List.of("clockEvent:ASC"), null, null, null,
                null, null, "UTC", List.of(), null, List.of(), null));
        assertThat(continuation.items()).extracting(candidate -> candidate.event().clockEventId())
                .containsExactly(thirdId)
                .doesNotContain(lateId);

        var exceptions = repository.queryExceptions(new ClockExceptionProjectionQuery(
                tenantId, Set.of(workerId), asOf, firstPage.snapshotHighWater(), List.of(),
                100, List.of("exception:ASC"), null, "UTC", List.of(), null,
                List.of(), List.of(), List.of(), List.of()));
        assertThat(exceptions.items())
                .anySatisfy(candidate -> {
                    assertThat(candidate.exceptionCode()).isEqualTo("SOURCE_SEQUENCE_GAP");
                    assertThat(candidate.recoveryState()).isEqualTo("RESOLVED");
                })
                .anySatisfy(candidate -> {
                    assertThat(candidate.exceptionCode())
                            .isEqualTo("OUT_OF_ORDER_OFFLINE_SEQUENCE");
                    assertThat(candidate.recoveryState()).isEqualTo("QUARANTINED");
                });
    }

    @Test
    void jdbcReplayReturnsOriginalRecordAndVoidReceiptsWhenServerTimeAdvances() {
        long tenantId = 9_053L;
        UUID subjectId = UUID.fromString("90530000-0000-4000-8000-000000000001");
        UUID populationId = UUID.fromString("90530000-0000-4000-8000-000000000002");
        UUID workerId = UUID.fromString("90530000-0000-4000-8000-000000000003");
        UUID assignmentId = UUID.fromString("90530000-0000-4000-8000-000000000004");
        UUID sourceId = UUID.fromString("90530000-0000-4000-8000-000000000005");
        UUID correlationId = UUID.fromString("90530000-0000-4000-8000-000000000006");
        MutableClock clock = new MutableClock(Instant.parse("2026-10-12T04:00:00Z"));
        JdbcClockEventRepository repository = new JdbcClockEventRepository(
                admin,
                new DataSourceTransactionManager(adminDataSource),
                new ObjectMapper().findAndRegisterModules());
        ClockIngestionService service = new ClockIngestionService(
                repository,
                new ClockEvidenceValidator(),
                clock,
                new ClockQueryCursorCodec(
                        "clock-integration-replay-secret",
                        Duration.ofMinutes(15),
                        new ObjectMapper().findAndRegisterModules(),
                        clock));
        VerifiedClockRequest authority = authority(
                tenantId, subjectId, populationId, workerId, assignmentId, sourceId,
                correlationId, clock.instant());
        RecordClockCommand command = command(sourceId, workerId, assignmentId);
        UUID recordKey = UUID.fromString("90530000-0000-4000-8000-000000000007");

        var record = service.record(authority, recordKey, command);
        clock.advance(Duration.ofMinutes(2));
        var recordReplay = service.record(authority, recordKey, command);
        assertThat(recordReplay.receiptId()).isEqualTo(record.receiptId());
        assertThat(recordReplay.createdAt()).isEqualTo(record.createdAt());
        assertThat(recordReplay.resultDigest()).isEqualTo(record.resultDigest());
        assertThat(recordReplay.replayed()).isTrue();

        UUID voidKey = UUID.fromString("90530000-0000-4000-8000-000000000008");
        var voided = service.voidEvent(
                authority, record.clockEventId(), voidKey,
                new VoidClockCommand("DEVICE_DUPLICATE"));
        clock.advance(Duration.ofMinutes(2));
        var voidReplay = service.voidEvent(
                authority, record.clockEventId(), voidKey,
                new VoidClockCommand("DEVICE_DUPLICATE"));
        assertThat(voidReplay.receiptId()).isEqualTo(voided.receiptId());
        assertThat(voidReplay.createdAt()).isEqualTo(voided.createdAt());
        assertThat(voidReplay.resultDigest()).isEqualTo(voided.resultDigest());
        assertThat(voidReplay.replayed()).isTrue();
        assertThatThrownBy(() -> service.voidEvent(
                authority, record.clockEventId(), voidKey,
                new VoidClockCommand("OPERATOR_CORRECTION")))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    private static VerifiedClockRequest authority(
            long tenantId,
            UUID subjectId,
            UUID populationId,
            UUID workerId,
            UUID assignmentId,
            UUID sourceId,
            UUID correlationId,
            Instant now) {
        return new VerifiedClockRequest(
                tenantId,
                72,
                subjectId,
                Set.of(ClockAction.VIEW, ClockAction.RECORD, ClockAction.CORRECT),
                populationId,
                9,
                11,
                13,
                workerId,
                assignmentId,
                7,
                HcmEligibilityScopeKey.derived(
                        tenantId, 72, "hcm.operations.clock-ingestion", "integration-replay",
                        "relationship-9", "population-9"),
                "a".repeat(64),
                "b".repeat(64),
                "c".repeat(64),
                "d".repeat(64),
                5,
                ClockIngestionService.CAPTURE_PURPOSE,
                17,
                "psr-" + "e".repeat(64),
                now.plus(Duration.ofHours(1)),
                correlationId,
                Set.of(workerId),
                "scope-replay-13",
                Set.of(),
                Set.of(),
                Set.of(sourceId),
                true);
    }

    private static RecordClockCommand command(
            UUID sourceId, UUID workerId, UUID assignmentId) {
        String tzdb = ClockEvidenceValidator.expectedTzdbVersion("UTC");
        RecordClockCommand unsealed = new RecordClockCommand(
                sourceId,
                "integration-source-1",
                1,
                ClockSourceType.API,
                null,
                CaptureMode.ONLINE,
                null,
                workerId,
                assignmentId,
                7,
                ClockEventType.IN,
                Instant.parse("2026-10-12T03:30:00Z"),
                LocalDateTime.parse("2026-10-12T03:30:00"),
                "UTC",
                0,
                DstResolution.EXACT_OFFSET_SUPPLIED,
                InstantAuthority.SOURCE_INSTANT,
                tzdb,
                ClockEvidenceValidator.expectedTimeZoneRuleVersion("UTC", tzdb),
                "0".repeat(64),
                TrustLevel.VERIFIED);
        return new RecordClockCommand(
                unsealed.sourcePublicId(), unsealed.sourceEventKey(), unsealed.sourceSequence(),
                unsealed.sourceType(), unsealed.devicePublicId(), unsealed.captureMode(),
                unsealed.offlineBatchPublicId(), unsealed.workerPublicId(),
                unsealed.peopleAssignmentPublicId(), unsealed.peopleAssignmentRevision(),
                unsealed.eventType(), unsealed.occurredAt(), unsealed.sourceLocalDateTime(),
                unsealed.sourceTimeZone(), unsealed.sourceUtcOffsetMinutes(),
                unsealed.dstResolution(), unsealed.instantAuthority(), unsealed.tzdbVersion(),
                unsealed.timeZoneRuleVersion(),
                ClockEvidenceValidator.provenanceDigest(unsealed), unsealed.trustLevel());
    }

    private UUID insertFact(
            long tenantId,
            UUID sourceId,
            UUID workerId,
            UUID assignmentId,
            long sequence,
            String sourceEventKey,
            String captureMode) {
        UUID eventId = UUID.randomUUID();
        UUID receiptId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        Instant receivedAt = Instant.parse("2026-10-12T02:00:00Z").plusSeconds(sequence);
        admin.update("""
                INSERT INTO tme_clock_command_receipts (
                    public_id, tenant_id, idempotency_key, command_type, actor_id,
                    subject_principal_public_id, population_public_id, population_revision,
                    grant_revision, membership_revision, population_scope_digest,
                    field_policy_revision, purpose_code, authorization_revision,
                    request_digest, result_event_public_id, result_digest, status,
                    correlation_id, created_at)
                VALUES (?, ?, ?, 'CLOCK_RECORD', 1, ?, ?, 1, 1, 1, ?, 1,
                        'TIME_CAPTURE', 1, ?, ?, ?, 'SUCCEEDED', ?, ?)
                """,
                receiptId, tenantId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "a".repeat(64), "b".repeat(64), eventId, "c".repeat(64),
                correlationId, Timestamp.from(receivedAt));
        admin.update("""
                INSERT INTO tme_clock_events (
                    public_id, tenant_id, command_receipt_public_id, source_public_id,
                    source_event_key, source_sequence, source_type, device_public_id,
                    capture_mode, offline_batch_public_id, worker_public_id,
                    people_assignment_public_id, people_assignment_revision, event_type,
                    occurred_at, received_at, source_local_datetime, source_time_zone,
                    source_utc_offset_minutes, dst_resolution, instant_authority,
                    tzdb_version, time_zone_rule_version, source_provenance_digest,
                    payload_digest, trust_level, voids_event_public_id,
                    correction_reason_code, correlation_id, recorded_by)
                VALUES (?, ?, ?, ?, ?, ?, 'API', NULL, ?, ?, ?, ?, 0, 'IN', ?, ?, ?,
                        'UTC', 0, 'EXACT_OFFSET_SUPPLIED', 'SOURCE_INSTANT', 'test',
                        'UTC@test', ?, ?, 'VERIFIED', NULL, NULL, ?, 1)
                """,
                eventId, tenantId, receiptId, sourceId, sourceEventKey, sequence, captureMode,
                captureMode.equals("OFFLINE") ? UUID.randomUUID() : null,
                workerId, assignmentId, Timestamp.from(receivedAt), Timestamp.from(receivedAt),
                LocalDateTime.ofInstant(receivedAt, java.time.ZoneOffset.UTC),
                "d".repeat(64), "e".repeat(64), correlationId);
        return eventId;
    }

    private DataSource dataSource(String username, String password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(postgres.getJdbcUrl());
        source.setUser(username);
        source.setPassword(password);
        return source;
    }

    private static final class MutableClock extends Clock {
        private Instant value;

        private MutableClock(Instant value) {
            this.value = value;
        }

        private void advance(Duration duration) {
            value = value.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return value;
        }
    }
}
