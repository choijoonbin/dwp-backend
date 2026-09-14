package com.dwp.services.notification.integration;

import com.dwp.services.notification.integration.ApprovalSlaDeliveryJournal.Lease;
import com.dwp.services.notification.integration.ApprovalSlaDeliveryJournal.LeaseUnavailableException;
import com.dwp.services.notification.integration.ApprovalSlaDeliveryJournal.Outcome;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static com.dwp.services.notification.integration.ApprovalSlaNotificationContractTest.HASH;
import static com.dwp.services.notification.integration.ApprovalSlaNotificationContractTest.TENANT;
import static com.dwp.services.notification.integration.ApprovalSlaNotificationContractTest.encode;
import static com.dwp.services.notification.integration.ApprovalSlaNotificationContractTest.event;
import static com.dwp.services.notification.integration.ApprovalSlaNotificationContractTest.plan;
import static com.dwp.services.notification.integration.ApprovalSlaNotificationContractTest.record;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real V1..27/RLS primitive evidence only, not native child-notification or consumer atomicity. */
@EnabledIfEnvironmentVariable(named = "DWP_NOTIFICATION_SLA_TEST_DB_URL", matches = ".+")
class ApprovalSlaDeliveryJournalPostgresIntegrationTest {
    private static final String RUNTIME_ROLE = "ntf_sla_test_runtime";
    private static final Duration LEASE_TIME = Duration.ofSeconds(30);
    private static JdbcTemplate admin;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static NotificationDatabaseScope scope;
    private static ApprovalSlaDeliveryJournal journal;

    @BeforeAll
    static void migrateOnlyDisposablePrefixedDatabaseWithDedicatedRuntimeRole() {
        String url = System.getenv("DWP_NOTIFICATION_SLA_TEST_DB_URL");
        String password = System.getenv().getOrDefault("DWP_NOTIFICATION_SLA_TEST_DB_PASSWORD", "postgres");
        var source = new DriverManagerDataSource(url,
                System.getenv().getOrDefault("DWP_NOTIFICATION_SLA_TEST_DB_USERNAME", "postgres"), password);
        admin = new JdbcTemplate(source);
        assertThat(admin.queryForObject("SELECT current_database()", String.class))
                .as("Do not migrate or provision roles on an application database")
                .startsWith("dwp_notification_sla_test");
        admin.execute("""
                DO $$ BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='ntf_sla_test_runtime') THEN
                        CREATE ROLE ntf_sla_test_runtime LOGIN PASSWORD 'sla-test-only' NOSUPERUSER
                            NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
                    END IF;
                END $$
                """);
        assertThat(admin.queryForObject("""
                SELECT NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)
                  FROM pg_roles WHERE rolname=?
                """, Boolean.class, RUNTIME_ROLE)).as("Reject unsafe preexisting runtime roles before migrating").isTrue();
        var migration = Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .target("27").placeholders(Map.of("notificationRuntimeRole", RUNTIME_ROLE)).load();
        migration.migrate();
        assertThat(migration.info().current().getVersion().getVersion()).isEqualTo("27");
        assertThat(java.util.Arrays.stream(migration.info().applied()).filter(info -> info.getVersion() != null)
                .map(info -> info.getVersion().getVersion()).toList())
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 27).mapToObj(Integer::toString).toList());
        admin.execute("CREATE TABLE IF NOT EXISTS ntf_sla_test_external_markers (marker_id UUID PRIMARY KEY, event_id UUID NOT NULL)");
        admin.execute("GRANT SELECT, INSERT ON ntf_sla_test_external_markers TO dwp_notification_worker");
        var runtime = new DriverManagerDataSource(url, RUNTIME_ROLE, "sla-test-only");
        jdbc = new JdbcTemplate(runtime);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(runtime));
        scope = new NotificationDatabaseScope(jdbc);
        journal = new ApprovalSlaDeliveryJournal(jdbc);
        assertThat(jdbc.queryForObject("SELECT session_user", String.class)).isEqualTo(RUNTIME_ROLE);
        assertThat(admin.queryForObject("""
                SELECT NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)
                  FROM pg_roles WHERE rolname=?
                """, Boolean.class, RUNTIME_ROLE)).isTrue();
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM pg_class WHERE relname LIKE 'ntf_approval_sla_%'
                  AND relowner=(SELECT oid FROM pg_roles WHERE rolname=?)
                """, Integer.class, RUNTIME_ROLE)).isZero();
    }

    @AfterEach
    void removeOnlyFixturesFromTheDedicatedDatabase() {
        // Committed journal rows stay immutable until disposal; each test has unique event IDs.
        admin.update("DELETE FROM ntf_sla_test_external_markers");
    }

    @Test
    void requiresExistingWorkerTransactionAndExactTenant() throws Exception {
        var plan = plan(1);
        assertThatThrownBy(() -> journal.claim(plan, UUID.randomUUID(), LEASE_TIME))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("existing worker transaction");
        assertThatThrownBy(() -> transactions.execute(status -> {
            scope.applyUser(new NotificationRequestContext.Actor(TENANT, 1L, java.util.Set.of(),
                    java.util.Set.of(), false, "dwp-gateway"));
            return journal.claim(plan, UUID.randomUUID(), LEASE_TIME);
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("worker scope");
        assertThatThrownBy(() -> worker(TENANT + 1, () -> journal.claim(plan, UUID.randomUUID(), LEASE_TIME)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("worker scope");
        assertThat(count("ntf_approval_sla_deliveries", plan.eventId())).isZero();
    }

    @Test
    void materializes1000DispositionsIn10ChunksWithReplayAndNoDroppedSeats() throws Exception {
        var plan = plan(1000);
        Lease lease = claim(plan);
        for (int index = 0; index < 10; index++) {
            int chunk = index;
            var outcomes = outcomes(plan, chunk);
            assertThat(worker(TENANT, () -> journal.hasChunk(lease, chunk))).isFalse();
            assertThat(worker(TENANT, () -> journal.completeChunk(lease, chunk, outcomes, HASH))).isTrue();
            assertThat(worker(TENANT, () -> journal.completeChunk(lease, chunk, outcomes, HASH))).isFalse();
            assertThat(worker(TENANT, () -> journal.hasChunk(lease, chunk))).isTrue();
        }
        worker(TENANT, () -> { journal.finish(lease); return true; });
        assertThat(count("ntf_approval_sla_delivery_chunks", plan.eventId())).isEqualTo(10);
        assertThat(count("ntf_approval_sla_delivery_recipients", plan.eventId())).isEqualTo(1000);
        var rows = admin.queryForList("""
                SELECT user_id,person_public_id,task_id,task_version,child_event_id,authority_eligible,intent_id,notification_id
                  FROM ntf_approval_sla_delivery_recipients WHERE event_id=? ORDER BY user_id
                """, plan.eventId());
        for (int index = 0; index < 1000; index++) {
            var seat = plan.recipients().get(index);
            var row = rows.get(index);
            assertThat(row).containsEntry("user_id", seat.userId()).containsEntry("task_id", seat.taskId())
                    .containsEntry("person_public_id", seat.personPublicId()).containsEntry("task_version", seat.taskVersion())
                    .containsEntry("child_event_id", plan.childEventId(seat));
            if (seat.userId() % 3 == 0) {
                assertThat(row).containsEntry("authority_eligible", false)
                        .containsEntry("intent_id", null).containsEntry("notification_id", null);
            }
        }
        assertThat(rows).extracting(row -> row.get("user_id"))
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 1000).mapToObj(Long::valueOf).toList());
        assertThat(worker(TENANT, () -> journal.claim(plan, UUID.randomUUID(), LEASE_TIME)).finished()).isTrue();
    }

    @Test
    void rejects1001EvenIfPlanWasConstructedOutsideTheContract() throws Exception {
        var original = plan(1000);
        var recipients = new ArrayList<>(original.recipients());
        recipients.add(new ApprovalSlaNotificationPlan.Recipient(1001, UUID.randomUUID(), UUID.randomUUID(), 0));
        var oversized = withRecipients(original, recipients);
        assertThatThrownBy(() -> claim(oversized)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Incomplete SLA audience");
        assertThat(count("ntf_approval_sla_deliveries", original.eventId())).isZero();
    }

    @Test
    void whitespaceCanonicalReplayStillRejectsChangedOriginalByteSha() throws Exception {
        UUID eventId = UUID.randomUUID();
        var body = event(1);
        String raw = encode(body);
        var original = ApprovalSlaNotificationContract.translate(record(eventId, raw));
        var spaced = ApprovalSlaNotificationContract.translate(record(eventId,
                ApprovalSlaNotificationContractTest.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(body)));
        assertThat(spaced.envelopeSha256()).isEqualTo(original.envelopeSha256());
        assertThat(spaced.originalEnvelopeSha256()).isNotEqualTo(original.originalEnvelopeSha256());
        Lease lease = claim(original);
        assertThatThrownBy(() -> worker(TENANT, () -> journal.claim(spaced, lease.owner(), LEASE_TIME)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Original SLA delivery bindings changed");
        assertThat(admin.queryForObject("SELECT original_envelope_sha256 FROM ntf_approval_sla_deliveries WHERE event_id=?",
                String.class, eventId)).isEqualTo(original.originalEnvelopeSha256());
        assertThat(worker(TENANT, () -> journal.claim(original, lease.owner(), LEASE_TIME)).epoch())
                .isEqualTo(lease.epoch() + 1);
    }

    @Test
    void originalCanonicalAudienceAndSourcePinFingerprintsRemainBoundInTheDatabase() throws Exception {
        var original = plan(1);
        Lease lease = claim(original);
        for (int changed = 0; changed < 4; changed++) {
            String other = "b".repeat(64);
            var rebound = new ApprovalSlaNotificationPlan(original.eventId(), original.actor(), original.eventType(),
                    original.canonicalEnvelope(), changed == 0 ? other : original.envelopeSha256(),
                    changed == 1 ? other : original.originalEnvelopeSha256(),
                    changed == 2 ? other : original.recipientSnapshotSha256(),
                    changed == 3 ? other : original.sourcePinsSha256(), original.requestId(), original.occurredAt(),
                    original.pins(), original.recipients());
            assertThatThrownBy(() -> worker(TENANT, () -> journal.claim(rebound, lease.owner(), LEASE_TIME)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Original SLA delivery bindings changed");
        }
        assertThatThrownBy(() -> worker(TENANT, () -> jdbc.update(
                "UPDATE ntf_approval_sla_deliveries SET source_pins_sha256=? WHERE event_id=?", "b".repeat(64), original.eventId())))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
        assertThat(admin.queryForObject("SELECT lease_epoch FROM ntf_approval_sla_deliveries WHERE event_id=?",
                Long.class, original.eventId())).isEqualTo(lease.epoch());
    }

    @Test
    void rejectsTruncatedReorderedReboundAndInvalidDispositionsBeforeAnyChunkDml() throws Exception {
        var plan = plan(3);
        Lease lease = claim(plan);
        var good = outcomes(plan, 0);
        var reordered = new ArrayList<>(good); Collections.swap(reordered, 0, 1);
        var rebound = new ArrayList<>(good);
        var seat = good.getFirst().recipient();
        rebound.set(0, new Outcome(new ApprovalSlaNotificationPlan.Recipient(seat.userId(), seat.personPublicId(),
                UUID.randomUUID(), seat.taskVersion()), true, UUID.randomUUID(), null));
        var noIntent = new ArrayList<>(good); noIntent.set(0, new Outcome(seat, true, null, null));
        var ineligible = new ArrayList<>(good); ineligible.set(0, new Outcome(seat, false, UUID.randomUUID(), null));
        for (List<Outcome> bad : List.of(good.subList(0, 2), reordered, rebound, noIntent, ineligible)) {
            assertThatThrownBy(() -> worker(TENANT, () -> journal.completeChunk(lease, 0, bad, HASH)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(count("ntf_approval_sla_delivery_chunks", plan.eventId())).isZero();
            assertThat(count("ntf_approval_sla_delivery_recipients", plan.eventId())).isZero();
        }
        assertThatThrownBy(() -> worker(TENANT, () -> journal.completeChunk(lease, 1, good, HASH)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> worker(TENANT, () -> journal.completeChunk(lease, 0, good, "BAD")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void committedChildUuidAndChunkOutcomeCannotBeReboundOnReplay() throws Exception {
        var plan = plan(2);
        Lease lease = claim(plan);
        var good = outcomes(plan, 0);
        assertThat(worker(TENANT, () -> journal.completeChunk(lease, 0, good, HASH))).isTrue();
        var changed = new ArrayList<>(good);
        changed.set(0, new Outcome(good.getFirst().recipient(), true, UUID.randomUUID(), UUID.randomUUID()));
        assertThatThrownBy(() -> worker(TENANT, () -> journal.completeChunk(lease, 0, changed, HASH)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Completed SLA chunk changed");
        assertThat(worker(TENANT, () -> journal.completeChunk(lease, 0, good, HASH))).isFalse();
        assertThat(admin.queryForList("SELECT child_event_id FROM ntf_approval_sla_delivery_recipients WHERE event_id=? ORDER BY user_id",
                UUID.class, plan.eventId())).containsExactlyElementsOf(plan.recipients().stream().map(plan::childEventId).toList());
        assertThat(count("ntf_approval_sla_delivery_recipients", plan.eventId())).isEqualTo(2);
    }

    @Test
    void concurrentClaimUsesIndependentConnectionsAndOnlyOneLiveOwnerWins() throws Exception {
        var plan = plan(1);
        UUID firstOwner = UUID.randomUUID(), secondOwner = UUID.randomUUID();
        var locked = new CountDownLatch(1);
        var competing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> worker(TENANT, () -> {
                Lease lease = journal.claim(plan, firstOwner, LEASE_TIME);
                locked.countDown(); await(release); return lease;
            }));
            await(locked);
            var second = pool.submit(() -> {
                competing.countDown();
                return worker(TENANT, () -> journal.claim(plan, secondOwner, LEASE_TIME));
            });
            await(competing);
            try { awaitDatabaseLock(); assertThat(second.isDone()).isFalse(); }
            finally { release.countDown(); }
            assertThat(first.get(10, TimeUnit.SECONDS).owner()).isEqualTo(firstOwner);
            assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(LeaseUnavailableException.class);
        } finally { release.countDown(); }
        assertThat(count("ntf_approval_sla_deliveries", plan.eventId())).isEqualTo(1);
    }

    @Test
    void dbExpiredLeaseCanBeReclaimedAndOldEpochCannotWriteOrFinish() throws Exception {
        var plan = plan(1);
        Lease old = claim(plan);
        admin.update("UPDATE ntf_approval_sla_deliveries SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE event_id=?",
                plan.eventId());
        assertThatThrownBy(() -> worker(TENANT, () -> journal.hasChunk(old, 0))).isInstanceOf(LeaseUnavailableException.class);
        Lease current = worker(TENANT, () -> journal.claim(plan, UUID.randomUUID(), LEASE_TIME));
        assertThat(current.epoch()).isEqualTo(old.epoch() + 1);
        assertThatThrownBy(() -> worker(TENANT, () -> journal.completeChunk(old, 0, outcomes(plan, 0), HASH)))
                .isInstanceOf(LeaseUnavailableException.class);
        assertThatThrownBy(() -> worker(TENANT, () -> { journal.finish(old); return true; }))
                .isInstanceOf(LeaseUnavailableException.class);
        assertThat(count("ntf_approval_sla_delivery_chunks", plan.eventId())).isZero();
        assertThat(worker(TENANT, () -> journal.completeChunk(current, 0, outcomes(plan, 0), HASH))).isTrue();
    }

    @Test
    void actualDatabaseClockExpiryDuringTransactionRollsBackChunkAndExternalMarker() throws Exception {
        var plan = plan(1);
        Lease lease = worker(TENANT, () -> journal.claim(plan, UUID.randomUUID(), Duration.ofMillis(300)));
        assertThatThrownBy(() -> worker(TENANT, () -> {
            jdbc.update("INSERT INTO ntf_sla_test_external_markers VALUES (?,?)", UUID.randomUUID(), plan.eventId());
            journal.completeChunk(lease, 0, outcomes(plan, 0), HASH);
            jdbc.execute("SELECT pg_sleep(0.4)");
            journal.finish(lease);
            return true;
        })).isInstanceOf(LeaseUnavailableException.class);
        assertThat(count("ntf_approval_sla_delivery_chunks", plan.eventId())).isZero();
        assertThat(count("ntf_approval_sla_delivery_recipients", plan.eventId())).isZero();
        assertThat(count("ntf_sla_test_external_markers", plan.eventId())).isZero();
    }

    @Test
    void omittedChunkCannotFinishAndCompletedDeliveryIsImmutable() throws Exception {
        var plan = plan(101);
        Lease lease = claim(plan);
        worker(TENANT, () -> journal.completeChunk(lease, 0, outcomes(plan, 0), HASH));
        assertThatThrownBy(() -> worker(TENANT, () -> { journal.finish(lease); return true; }))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unfinished chunks");
        assertThat(admin.queryForObject("SELECT finished_at IS NULL FROM ntf_approval_sla_deliveries WHERE event_id=?",
                Boolean.class, plan.eventId())).isTrue();
        worker(TENANT, () -> { journal.completeChunk(lease, 1, outcomes(plan, 1), HASH); journal.finish(lease); return true; });
        assertThatThrownBy(() -> worker(TENANT, () -> journal.completeChunk(lease, 1, outcomes(plan, 1), HASH)))
                .isInstanceOf(LeaseUnavailableException.class);
        assertThatThrownBy(() -> worker(TENANT, () -> jdbc.update(
                "UPDATE ntf_approval_sla_deliveries SET lease_owner=? WHERE event_id=?", UUID.randomUUID(), plan.eventId())))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
        assertThatThrownBy(() -> worker(TENANT, () -> jdbc.update(
                "UPDATE ntf_approval_sla_delivery_chunks SET outcome_sha256=? WHERE event_id=?", "b".repeat(64), plan.eventId())))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> worker(TENANT, () -> jdbc.update(
                "DELETE FROM ntf_approval_sla_deliveries WHERE event_id=?", plan.eventId())))
                .isInstanceOf(DataAccessException.class);
        for (String table : List.of("ntf_approval_sla_delivery_chunks", "ntf_approval_sla_delivery_recipients")) {
            // Owner privileges bypass grants, not the immutable UPDATE/DELETE defense.
            assertThatThrownBy(() -> admin.update("DELETE FROM " + table + " WHERE event_id=?", plan.eventId()))
                    .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("immutable");
            assertThatThrownBy(() -> admin.update("UPDATE " + table
                    + " SET chunk_index=chunk_index+1 WHERE event_id=?", plan.eventId()))
                    .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("immutable");
        }
        assertThat(count("ntf_approval_sla_delivery_recipients", plan.eventId())).isEqualTo(101);
    }

    @Test
    void sameWorkerTxRollsBackJournalChunkRecipientsAndExternalDmlMarkerTogether() throws Exception {
        var plan = plan(2);
        Lease lease = claim(plan);
        assertThatThrownBy(() -> worker(TENANT, () -> {
            jdbc.update("INSERT INTO ntf_sla_test_external_markers VALUES (?,?)", UUID.randomUUID(), plan.eventId());
            journal.completeChunk(lease, 0, outcomes(plan, 0), HASH);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ntf_approval_sla_delivery_recipients WHERE event_id=?",
                    Integer.class, plan.eventId())).isEqualTo(2);
            throw new IllegalStateException("external child primitive failure");
        })).isInstanceOf(IllegalStateException.class).hasMessage("external child primitive failure");
        assertThat(count("ntf_approval_sla_delivery_chunks", plan.eventId())).isZero();
        assertThat(count("ntf_approval_sla_delivery_recipients", plan.eventId())).isZero();
        assertThat(count("ntf_sla_test_external_markers", plan.eventId())).isZero();
        assertThat(count("ntf_approval_sla_deliveries", plan.eventId())).isEqualTo(1);
        worker(TENANT, () -> journal.completeChunk(lease, 0, outcomes(plan, 0), HASH));
        assertThat(count("ntf_approval_sla_delivery_recipients", plan.eventId())).isEqualTo(2);
    }

    @Test
    void forcedRlsDeniesCrossTenantRowsAndWritesForAllThreeJournalTables() throws Exception {
        var plan = plan(1);
        Lease lease = claim(plan);
        worker(TENANT, () -> journal.completeChunk(lease, 0, outcomes(plan, 0), HASH));
        for (String table : journalTables()) {
            assertThat(admin.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE relname=?",
                    Boolean.class, table)).isTrue();
            assertThat(worker(TENANT + 1, () -> jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class))).isZero();
            String frozenRow = admin.queryForObject("SELECT row_to_json(r)::text FROM " + table + " r WHERE event_id=?",
                    String.class, plan.eventId());
            assertThatThrownBy(() -> worker(TENANT + 1, () -> jdbc.update("INSERT INTO " + table
                    + " SELECT * FROM json_populate_record(NULL::" + table + ",?::json)", frozenRow)))
                    .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("row-level security");
        }
    }

    @Test
    void apiCannotBorrowWorkerSettingOrSeeJournalRowsEvenWithTestOnlySelectGrant() throws Exception {
        var plan = plan(1);
        Lease lease = claim(plan);
        worker(TENANT, () -> journal.completeChunk(lease, 0, outcomes(plan, 0), HASH));
        for (String table : journalTables()) {
            assertThat(admin.queryForObject("SELECT has_table_privilege('dwp_notification_api',?,'SELECT')",
                    Boolean.class, table)).isFalse();
            // Temporary read grant isolates row policy from the default API privilege denial.
            admin.execute("GRANT SELECT ON " + table + " TO dwp_notification_api");
            try {
                Integer visible = transactions.execute(status -> {
                    scope.applyUser(new NotificationRequestContext.Actor(TENANT, 1L, java.util.Set.of(),
                            java.util.Set.of(), false, "dwp-gateway"));
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isZero();
                    jdbc.queryForObject("SELECT set_config('dwp.notification_scope','WORKER',true)", String.class);
                    assertThat(jdbc.queryForObject("SELECT ntf_is_worker()", Boolean.class)).isFalse();
                    return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
                });
                assertThat(visible).isZero();
            } finally { admin.execute("REVOKE SELECT ON " + table + " FROM dwp_notification_api"); }
        }
    }

    @Test
    void v27RevokesInheritedDefaultPrivilegesAndKeepsOnlyAppendOnlyChildRows() throws Exception {
        for (String table : journalTables()) {
            assertThat(admin.queryForObject("SELECT has_table_privilege('dwp_notification_worker',?,'DELETE')",
                    Boolean.class, table)).as("Default future-table DELETE must be explicitly revoked: " + table).isFalse();
            if (!table.equals("ntf_approval_sla_deliveries")) {
                assertThat(admin.queryForObject("SELECT has_table_privilege('dwp_notification_worker',?,'UPDATE')",
                        Boolean.class, table)).as("Completed child rows are append-only: " + table).isFalse();
            }
        }
    }

    @Test
    void actualWorkerDeleteOfParentChunkAndRecipientIsPermissionDeniedWithoutRemovingRows() throws Exception {
        var plan = plan(2);
        Lease lease = claim(plan);
        worker(TENANT, () -> journal.completeChunk(lease, 0, outcomes(plan, 0), HASH));
        for (String table : journalTables()) {
            long before = count(table, plan.eventId());
            assertThatThrownBy(() -> worker(TENANT, () -> jdbc.update("DELETE FROM " + table + " WHERE event_id=?", plan.eventId())))
                    .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
            assertThat(count(table, plan.eventId())).isEqualTo(before).isPositive();
        }
    }

    @Test
    void v27PublishesOnlyTheTwoNewSlaContractsAndTheirFourInAppLocaleTemplates() {
        var types = admin.queryForList("""
                SELECT t.type_key,v.source_event_type,v.lifecycle_state,v.contract_payload->>'userConfigurable' AS configurable
                  FROM ntf_notification_types t JOIN ntf_notification_type_versions v USING (type_id)
                 WHERE t.type_key IN ('APPROVAL.SLA_WARNING','APPROVAL.SLA_BREACHED') ORDER BY t.type_key
                """);
        assertThat(types).hasSize(2);
        assertThat(types).extracting(row -> row.get("source_event_type"))
                .containsExactly("Approval.Quorum.SlaBreached", "Approval.Quorum.SlaWarning");
        assertThat(types).allSatisfy(row -> assertThat(row)
                .containsEntry("lifecycle_state", "ACTIVE").containsEntry("configurable", "true"));
        var templates = admin.queryForList("""
                SELECT t.type_key,p.channel,p.locale,p.state FROM ntf_notification_types t
                  JOIN ntf_notification_type_versions v USING(type_id)
                  JOIN ntf_template_versions p USING(type_version_id)
                 WHERE t.type_key IN ('APPROVAL.SLA_WARNING','APPROVAL.SLA_BREACHED') ORDER BY t.type_key,p.locale
                """);
        assertThat(templates).hasSize(4);
        for (String key : List.of("APPROVAL.SLA_WARNING", "APPROVAL.SLA_BREACHED")) {
            var local = templates.stream().filter(row -> key.equals(row.get("type_key"))).toList();
            assertThat(local).extracting(row -> row.get("locale")).containsExactly("en-US", "ko-KR");
            assertThat(local).allSatisfy(row -> assertThat(row).containsEntry("channel", "IN_APP").containsEntry("state", "PUBLISHED"));
        }
    }

    private static List<String> journalTables() {
        return List.of("ntf_approval_sla_deliveries", "ntf_approval_sla_delivery_chunks", "ntf_approval_sla_delivery_recipients");
    }
    private static Lease claim(ApprovalSlaNotificationPlan plan) {
        return worker(plan.actor().tenantId(), () -> journal.claim(plan, UUID.randomUUID(), LEASE_TIME));
    }
    private static <T> T worker(long tenant, Supplier<T> action) {
        return transactions.execute(status -> {
            scope.applyWorker(tenant);
            jdbc.execute("SET LOCAL statement_timeout='5s'");
            return action.get();
        });
    }
    private static long count(String table, UUID event) {
        return admin.queryForObject("SELECT count(*) FROM " + table + " WHERE event_id=?", Long.class, event);
    }
    private static List<Outcome> outcomes(ApprovalSlaNotificationPlan plan, int index) {
        return plan.chunk(index).stream().map(seat -> {
            UUID child = plan.childEventId(seat);
            boolean eligible = seat.userId() % 3 != 0;
            return new Outcome(seat, eligible,
                    eligible ? ApprovalSlaNotificationContractTest.opaque("intent-" + child) : null,
                    eligible ? ApprovalSlaNotificationContractTest.opaque("notification-" + child) : null);
        }).toList();
    }
    private static ApprovalSlaNotificationPlan withRecipients(ApprovalSlaNotificationPlan plan,
            List<ApprovalSlaNotificationPlan.Recipient> recipients) {
        return new ApprovalSlaNotificationPlan(plan.eventId(), plan.actor(), plan.eventType(), plan.canonicalEnvelope(),
                plan.envelopeSha256(), plan.originalEnvelopeSha256(), plan.recipientSnapshotSha256(), plan.sourcePinsSha256(),
                plan.requestId(), plan.occurredAt(), plan.pins(), recipients);
    }
    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).as("Independent worker synchronization").isTrue(); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
    private static void awaitDatabaseLock() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (admin.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE usename=? AND wait_event_type='Lock'",
                    Integer.class, RUNTIME_ROLE) > 0) return;
            try { Thread.sleep(10); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
        }
        throw new AssertionError("Competing worker must actually block on a PostgreSQL lock");
    }
}
