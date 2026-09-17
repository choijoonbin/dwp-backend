package com.dwp.services.notification.integration;

import com.dwp.services.notification.domain.DirectNotificationMaterializer;
import com.dwp.services.notification.domain.NotificationAttentionAdmissionRepository;
import com.dwp.services.notification.domain.NotificationAttentionAdmissionService;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceRepository;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceRuntime;
import com.dwp.services.notification.domain.NotificationDeliveryAdmissionRepository;
import com.dwp.services.notification.domain.NotificationDeliveryAdmissionService;
import com.dwp.services.notification.domain.NotificationEffectivePolicyRepository;
import com.dwp.services.notification.domain.NotificationMaterializationRepository;
import com.dwp.services.notification.domain.NotificationMaterializationTransactions;
import com.dwp.services.notification.domain.NotificationModels.MaterializationResult;
import com.dwp.services.notification.domain.NotificationProducerOwnershipPolicy;
import com.dwp.services.notification.domain.NotificationRecipientEntitlementAdmission;
import com.dwp.services.notification.domain.NotificationRuntimeAdmissionRepository;
import com.dwp.services.notification.domain.NotificationTemplateModels.TemplateDraftRequest;
import com.dwp.services.notification.domain.NotificationTemplateRepository;
import com.dwp.services.notification.operations.NotificationRetentionRepository;
import com.dwp.services.notification.operations.NotificationRetentionService;
import com.dwp.services.notification.realtime.NotificationChangePublisher;
import com.dwp.services.notification.realtime.NotificationRedisChannels;
import com.dwp.services.notification.realtime.NotificationRedisSignalCodec;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static com.dwp.services.notification.integration.ApprovalSlaNotificationContractTest.JSON;
import static com.dwp.services.notification.integration.ApprovalSlaNotificationContractTest.encode;
import static com.dwp.services.notification.integration.ApprovalSlaNotificationContractTest.event;
import static com.dwp.services.notification.integration.ApprovalSlaNotificationContractTest.record;
import static com.dwp.services.notification.integration.ApprovalSlaRecipientAuthorityTest.Frame;
import static com.dwp.services.notification.integration.ApprovalSlaRecipientAuthorityTest.MutableClock;
import static com.dwp.services.notification.integration.ApprovalSlaRecipientAuthorityTest.NOW;
import static com.dwp.services.notification.integration.ApprovalSlaRecipientAuthorityTest.SourceServer;
import static com.dwp.services.notification.integration.ApprovalSlaRecipientAuthorityTest.rehash;
import static com.dwp.services.notification.integration.ApprovalSlaRecipientAuthorityTest.seat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Native PG/retention/after-commit protocol; outbound Redis and signed SYSTEM HTTP are test transports. */
@EnabledIfEnvironmentVariable(named = "DWP_NOTIFICATION_SLA_TEST_DB_URL", matches = ".+")
class ApprovalSlaMaterializationPostgresIntegrationTest {
    private static final List<String> NATIVE_TABLES = List.of("ntf_notification_intents", "ntf_notifications",
            "ntf_user_notifications", "ntf_user_counters", "ntf_delivery_admission_receipts", "ntf_outbox_events");
    private JdbcTemplate jdbc;
    private JdbcTemplate admin;
    private DataSourceTransactionManager manager;
    private NotificationDatabaseScope scope;
    private ApprovalSlaDeliveryJournal journal;
    private DirectNotificationMaterializer materializer;
    private StringRedisTemplate redis;
    private long tenant;
    private SourceServer server;

    @BeforeAll
    static void migrateAllRealVersionsWithTheGuardedDedicatedRole() {
        ApprovalSlaDeliveryJournalPostgresIntegrationTest.migrateOnlyDisposablePrefixedDatabaseWithDedicatedRuntimeRole();
        String url = System.getenv("DWP_NOTIFICATION_SLA_TEST_DB_URL");
        var source = new DriverManagerDataSource(
                url,
                System.getenv().getOrDefault("DWP_NOTIFICATION_SLA_TEST_DB_USERNAME", "postgres"),
                System.getenv().getOrDefault("DWP_NOTIFICATION_SLA_TEST_DB_PASSWORD", "postgres"));
        var migration = Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration")
                .placeholders(Map.of("notificationRuntimeRole", "ntf_sla_test_runtime"))
                .load();
        migration.migrate();
        assertThat(migration.info().current().getVersion().getVersion()).isEqualTo("33");
    }

    @BeforeEach
    void wireRealNativeServicesWithOnlyTheOutboundRedisTransportObserved() {
        String url = System.getenv("DWP_NOTIFICATION_SLA_TEST_DB_URL");
        admin = new JdbcTemplate(new DriverManagerDataSource(url,
                System.getenv().getOrDefault("DWP_NOTIFICATION_SLA_TEST_DB_USERNAME", "postgres"),
                System.getenv().getOrDefault("DWP_NOTIFICATION_SLA_TEST_DB_PASSWORD", "postgres")));
        assertThat(admin.queryForObject("SELECT current_database()", String.class)).startsWith("dwp_notification_sla_test");
        var runtime = new DriverManagerDataSource(url, "ntf_sla_test_runtime", "sla-test-only");
        jdbc = new JdbcTemplate(runtime);
        manager = new DataSourceTransactionManager(runtime);
        scope = new NotificationDatabaseScope(jdbc);
        journal = new ApprovalSlaDeliveryJournal(jdbc);
        var mapper = new ObjectMapper().findAndRegisterModules();
        var named = new NamedParameterJdbcTemplate(runtime);
        redis = mock(StringRedisTemplate.class);
        var publisher = new NotificationChangePublisher(redis, new NotificationRedisChannels("notification.sla.sidecar", 32),
                new NotificationRedisSignalCodec(mapper), true);
        var retention = new NotificationRetentionService(scope, new NotificationRetentionRepository(named), publisher,
                Duration.ofDays(7), Duration.ofDays(7), 100);
        var admission = new NotificationDeliveryAdmissionService(new NotificationDeliveryAdmissionRepository(named), Duration.ofHours(1));
        var repository = new NotificationMaterializationRepository(
                named,
                mapper,
                admission,
                new NotificationRuntimeAdmissionRepository(named),
                new NotificationAttentionAdmissionService(
                        new NotificationAttentionAdmissionRepository(named),
                        new NotificationEffectivePolicyRepository(named),
                        new NotificationAttentionGovernanceRuntime(
                                new NotificationAttentionGovernanceRepository(named, mapper),
                                500,
                                10)));
        var nativeTransactions = new NotificationMaterializationTransactions(manager, scope, repository, retention, publisher, jdbc);
        var legacyEntitlements = new NotificationRecipientEntitlementAdmission((id, user) -> {
            throw new AssertionError("SLA private Verified must not borrow the generic recipient directory");
        }, "approvals=APP.APPROVALS:VIEW,hcm=APP.HCM:VIEW,messaging=APP.MESSAGING:VIEW,space=APP.SPACES:VIEW,meetings=APP.MEETINGS:VIEW,workplace=APP.WORKPLACE:VIEW,mail=APP.MAIL:VIEW");
        materializer = new DirectNotificationMaterializer(nativeTransactions,
                new NotificationProducerOwnershipPolicy("dwp-approval-server=approvals"), legacyEntitlements, mapper);
        tenant = ThreadLocalRandom.current().nextLong(100_000L, 1_000_000_000_000L);
    }

    @AfterEach
    void closeOnlyOwnedHttpTransportAndKeepCommittedJournalRowsImmutableUntilDbDisposal() {
        if (server != null) { try { server.assertHealthy(); } finally { server.close(); } }
    }

    @Test
    void commits100NativeChildrenExpiryCountersAndJournalInOneTxPublishingOnlyAfterCommit() throws Exception {
        var plan = ownPlan(100);
        var frame = new Frame(plan, new MutableClock(NOW));
        var current = frame.verify(frame.claims());
        worker(() -> {
            var lease = journal.claim(plan, UUID.randomUUID(), Duration.ofSeconds(30));
            var results = materializer.materializeApprovalSlaWithinWorkerTransaction(plan.actor(), plan,
                    plan.chunk(0).stream().map(plan::request).toList(), current);
            assertThat(results).hasSize(100).allSatisfy(result -> {
                assertThat(result.intentId()).isNotNull(); assertThat(result.notificationId()).isNotNull();
                assertThat(result.recipientCount()).isEqualTo(1);
            });
            assertPreparedNativeRows(100);
            verifyNoInteractions(redis);
            journal.completeChunk(lease, 0, outcomes(plan, results), current.stableDigest());
            current.requireCurrent(plan); journal.finish(lease); return true;
        });
        assertNativeRows(100);
        assertThat(eventCount("ntf_approval_sla_delivery_recipients", plan.eventId())).isEqualTo(100);
        assertThat(worker(() -> journal.finished(plan))).isTrue();
        verify(redis, times(100)).convertAndSend(anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXPIRED", "CHANGED"})
    void authorityExpiryOrChangedSignedSourceRollsBack100ActualChildrenAndEveryQueuedPublish(String failure) throws Exception {
        var plan = ownPlan(100);
        var clock = new MutableClock(NOW);
        var frame = new Frame(plan, clock);
        var current = frame.verify(frame.claims());
        var changedClaims = frame.claims(); seat(changedClaims, 0).put("eligible", false).put("reason", "REVOKED"); rehash(changedClaims);
        var changed = frame.verify(changedClaims);
        assertThatThrownBy(() -> worker(() -> {
            var lease = journal.claim(plan, UUID.randomUUID(), Duration.ofSeconds(30));
            var results = materializer.materializeApprovalSlaWithinWorkerTransaction(plan.actor(), plan,
                    plan.chunk(0).stream().map(plan::request).toList(), current);
            journal.completeChunk(lease, 0, outcomes(plan, results), current.stableDigest());
            assertPreparedNativeRows(100);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ntf_approval_sla_delivery_recipients WHERE tenant_id=?",
                    Integer.class, tenant)).isEqualTo(100);
            verifyNoInteractions(redis);
            if (failure.equals("EXPIRED")) { clock.advance(31); current.requireCurrent(plan); }
            else current.requireSameCurrent(changed);
            journal.finish(lease); return true;
        })).isInstanceOf(IllegalArgumentException.class);
        assertRolledBack(plan);
    }

    @Test
    void slaNativePathStillHonorsRealUserMutedRoutingRatherThanBypassingPolicies() throws Exception {
        var plan = ownPlan(2);
        var frame = new Frame(plan, new MutableClock(NOW));
        var current = frame.verify(frame.claims());
        admin.update("""
                INSERT INTO ntf_user_subscription_rules(rule_id,tenant_id,user_id,app_key,type_key,delivery_mode)
                VALUES (?,?,1,'approvals','APPROVAL.SLA_WARNING','MUTED')
                """, UUID.randomUUID(), tenant);
        var results = worker(() -> materializer.materializeApprovalSlaWithinWorkerTransaction(plan.actor(), plan,
                plan.chunk(0).stream().map(plan::request).toList(), current));
        assertThat(results).extracting(MaterializationResult::recipientCount).containsExactly(0, 1);
        assertThat(rowCount(admin, "ntf_user_notifications")).isEqualTo(1);
        verify(redis, times(1)).convertAndSend(anyString(), anyString());
    }

    @Test
    void exactThreadMuteSuppressesEveryProjectionAndReplayRemainsWriteFree() throws Exception {
        var plan = ownPlan(1);
        var frame = new Frame(plan, new MutableClock(NOW));
        var current = frame.verify(frame.claims());
        var request = plan.request(plan.chunk(0).getFirst());
        long recipient = request.recipientUserIds().getFirst();
        UUID ruleId = UUID.randomUUID();
        admin.update("""
                INSERT INTO ntf_user_attention_rules (
                    rule_id, tenant_id, user_id, scope_kind, scope_key,
                    scope_key_hash, effect, source)
                VALUES (?, ?, ?, 'THREAD', ?, ?, 'MUTE', 'USER')
                """, ruleId, tenant, recipient, request.threadKey(),
                ApprovalSlaNotificationContractTest.digest(request.threadKey()));

        var first = worker(() -> materializer.materializeApprovalSlaWithinWorkerTransaction(
                plan.actor(), plan, List.of(request), current)).getFirst();

        assertThat(first.notificationId()).isNull();
        assertThat(first.recipientCount()).isZero();
        assertThat(first.duplicate()).isFalse();
        assertThat(rowCount(admin, "ntf_notification_intents")).isEqualTo(1);
        for (String table : List.of(
                "ntf_notifications", "ntf_user_notifications",
                "ntf_user_counters", "ntf_outbox_events")) {
            assertThat(rowCount(admin, table)).as("Mute leaves no projection: " + table).isZero();
        }
        assertThat(rowCount(admin, "ntf_delivery_admission_receipts")).isEqualTo(1);
        assertThat(admin.queryForMap("""
                SELECT decision, reason_code, attention_rule_id,
                       attention_scope_kind, attention_effect, attention_rule_revision
                  FROM ntf_delivery_admission_receipts
                 WHERE tenant_id = ?
                """, tenant))
                .containsEntry("decision", "SUPPRESSED")
                .containsEntry("reason_code", "USER_ATTENTION_MUTE")
                .containsEntry("attention_rule_id", ruleId)
                .containsEntry("attention_scope_kind", "THREAD")
                .containsEntry("attention_effect", "MUTE")
                .containsEntry("attention_rule_revision", 1L);
        verifyNoInteractions(redis);

        var replay = worker(() -> materializer.materializeApprovalSlaWithinWorkerTransaction(
                plan.actor(), plan, List.of(request), current)).getFirst();

        assertThat(replay.notificationId()).isNull();
        assertThat(replay.recipientCount()).isZero();
        assertThat(replay.duplicate()).isTrue();
        assertThat(rowCount(admin, "ntf_notification_intents")).isEqualTo(1);
        assertThat(rowCount(admin, "ntf_delivery_admission_receipts")).isEqualTo(1);
        verifyNoInteractions(redis);
    }

    @Test
    void publishedTemplateChangesDuringActualChildDmlRollbackTheEntireFrozenRenderingBatch() throws Exception {
        var plan = ownPlan(100);
        var frame = new Frame(plan, new MutableClock(NOW));
        var current = frame.verify(frame.claims());
        UUID template = UUID.fromString("12000000-0000-0000-0000-000000000101");
        UUID typeVersion = admin.queryForObject("SELECT type_version_id FROM ntf_template_versions WHERE template_version_id=?",
                UUID.class, template);
        var templates = new NotificationTemplateRepository(new NamedParameterJdbcTemplate(jdbc));
        String checksum = ApprovalSlaNotificationContractTest.digest("New published template");
        var draft = worker(() -> templates.createDraft(tenant, 2,
                new TemplateDraftRequest(typeVersion, "IN_APP", "ko-KR", "New published template", "Preview",
                        "Body", "Open request", "Verified concurrent publication", "0"), 1,
                checksum));
        admin.execute("CREATE FUNCTION ntf_sla_test_template_window() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.tenant_id=" + tenant + " THEN PERFORM pg_advisory_xact_lock(" + tenant + "); END IF; RETURN NEW; END $$");
        admin.execute("CREATE TRIGGER ntf_sla_test_template_window AFTER INSERT ON ntf_notifications "
                + "FOR EACH ROW EXECUTE FUNCTION ntf_sla_test_template_window()");
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (var blocker = admin.getDataSource().getConnection()) {
            try (var hold = blocker.prepareStatement("SELECT pg_advisory_lock(?)")) { hold.setLong(1, tenant); hold.execute(); }
            try {
                var pending = pool.submit(() -> worker(() -> materializer.materializeApprovalSlaWithinWorkerTransaction(
                        plan.actor(), plan, plan.chunk(0).stream().map(plan::request).toList(), current)));
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                boolean blocked = false;
                while (System.nanoTime() < deadline) {
                    if (admin.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE usename='ntf_sla_test_runtime' AND wait_event_type='Lock'",
                            Integer.class) > 0) { blocked=true; break; }
                    Thread.sleep(10);
                }
                assertThat(blocked).as("Actual child INSERT blocks after the initial template snapshot").isTrue();
                assertThat(worker(() -> templates.publish(tenant, 3, draft.revisionId(), 1,
                        "Independent checker publishes the frozen draft"))).isTrue();
                try (var unlock = blocker.prepareStatement("SELECT pg_advisory_unlock(?)")) { unlock.setLong(1, tenant); unlock.execute(); }
                assertThatThrownBy(() -> pending.get(15, java.util.concurrent.TimeUnit.SECONDS))
                        .hasCauseInstanceOf(IllegalArgumentException.class).rootCause().hasMessageContaining("contract changed");
                assertRolledBack(plan);
            } finally {
                try (var unlock = blocker.prepareStatement("SELECT pg_advisory_unlock(?)")) { unlock.setLong(1, tenant); unlock.execute(); }
                pool.shutdownNow();
            }
        } finally {
            pool.shutdownNow();
            admin.execute("DROP TRIGGER ntf_sla_test_template_window ON ntf_notifications");
            admin.execute("DROP FUNCTION ntf_sla_test_template_window()");
        }
    }

    @Test
    void actualConsumerProcesses1000SeatsIn10NativeChunksAndHistoricalReplayDoesNotCallHttpOrWriteAgain() throws Exception {
        var kafka = ownRecord(1000);
        var plan = ApprovalSlaNotificationContract.translate(kafka);
        server = new SourceServer(plan, new MutableClock(NOW), "GOOD");
        var listener = new ApprovalSlaNotificationKafkaListener(consumer());
        listener.receive(kafka);
        assertNativeRows(1000);
        assertThat(eventCount("ntf_approval_sla_delivery_chunks", plan.eventId())).isEqualTo(10);
        assertThat(eventCount("ntf_approval_sla_delivery_recipients", plan.eventId())).isEqualTo(1000);
        assertThat(worker(() -> journal.finished(plan))).isTrue();
        assertThat(server.calls.get()).isEqualTo(21);
        verify(redis, times(1000)).convertAndSend(anyString(), anyString());
        listener.receive(kafka);
        assertThat(server.calls.get()).isEqualTo(21);
        assertNativeRows(1000);
        verify(redis, times(1000)).convertAndSend(anyString(), anyString());
        var altered = ownEvent(1000);
        String whitespace = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(altered);
        var replayRecord = record(plan.eventId(), whitespace);
        replayRecord.headers().remove("dwp-tenant-id").add("dwp-tenant-id", Long.toString(tenant).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var changedRaw = ApprovalSlaNotificationContract.translate(replayRecord);
        assertThat(changedRaw.envelopeSha256()).isEqualTo(plan.envelopeSha256());
        assertThatThrownBy(() -> listener.receive(replayRecord)).isInstanceOf(IllegalArgumentException.class);
        assertThat(server.calls.get()).isEqualTo(21);
    }

    @Test
    void actualConsumerRejectsInitialHttpAuthorityBeforeReservingUntrustedKafkaIdentity() throws Exception {
        var plan = ownPlan(100);
        server = new SourceServer(plan, new MutableClock(NOW), "DENIED");
        assertThatThrownBy(() -> consumer().deliver(plan)).isInstanceOf(IllegalStateException.class);
        assertThat(server.calls.get()).isEqualTo(1);
        assertRolledBack(plan);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST_CHANGED", "POST_EXPIRED", "POST_503"})
    void actualConsumerSecondHttpSourceDriftRollsBackNativeChunkAndAfterCommitCallbacks(String mode) throws Exception {
        var plan = ownPlan(100);
        server = new SourceServer(plan, new MutableClock(NOW), mode);
        assertThatThrownBy(() -> consumer().deliver(plan)).isInstanceOf(RuntimeException.class);
        assertThat(server.calls.get()).isEqualTo(2);
        assertRolledBack(plan);
    }

    @Test
    void nativePrivateVerifiedCannotOpenItsOwnWorkerTransactionOrCrossTenant() throws Exception {
        var plan = ownPlan(1);
        var frame = new Frame(plan, new MutableClock(NOW));
        var current = frame.verify(frame.claims());
        var requests = plan.chunk(0).stream().map(plan::request).toList();
        assertThatThrownBy(() -> materializer.materializeApprovalSlaWithinWorkerTransaction(plan.actor(), plan, requests, current))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TransactionTemplate(manager).execute(status -> {
            scope.applyWorker(tenant + 1);
            return materializer.materializeApprovalSlaWithinWorkerTransaction(plan.actor(), plan, requests, current);
        })).isInstanceOf(IllegalStateException.class);
        assertRolledBack(plan);
    }

    private ApprovalSlaNotificationConsumer consumer() {
        return new ApprovalSlaNotificationConsumer(manager, scope, journal, server.client, materializer);
    }
    private ApprovalSlaNotificationPlan ownPlan(int count) throws Exception {
        return ApprovalSlaNotificationContract.translate(ownRecord(count));
    }
    private ConsumerRecord<String, String> ownRecord(int count) throws Exception {
        var input = record(UUID.randomUUID(), encode(ownEvent(count)));
        input.headers().remove("dwp-tenant-id").add("dwp-tenant-id",
                Long.toString(tenant).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return input;
    }
    private com.fasterxml.jackson.databind.node.ObjectNode ownEvent(int count) throws Exception {
        var event = event(count); event.put("tenantId", tenant); return event;
    }
    private <T> T worker(Supplier<T> action) {
        return new TransactionTemplate(manager).execute(status -> { scope.applyWorker(tenant); return action.get(); });
    }
    private List<ApprovalSlaDeliveryJournal.Outcome> outcomes(ApprovalSlaNotificationPlan plan, List<MaterializationResult> results) {
        return java.util.stream.IntStream.range(0, results.size()).mapToObj(index -> new ApprovalSlaDeliveryJournal.Outcome(
                plan.chunk(0).get(index), true, results.get(index).intentId(), results.get(index).notificationId())).toList();
    }
    private void assertPreparedNativeRows(int count) {
        for (String table : NATIVE_TABLES) assertThat(rowCount(jdbc, table)).as("Native DML prepared in caller TX: " + table).isEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ntf_notifications WHERE tenant_id=? AND expires_at IS NOT NULL",
                Integer.class, tenant)).isEqualTo(count);
    }
    private void assertNativeRows(int count) {
        for (String table : NATIVE_TABLES) assertThat(rowCount(admin, table)).as("Committed native DML: " + table).isEqualTo(count);
        assertThat(admin.queryForObject("SELECT count(*) FROM ntf_notifications WHERE tenant_id=? AND expires_at IS NOT NULL",
                Integer.class, tenant)).isEqualTo(count);
        assertThat(admin.queryForObject("SELECT sum(unread_count) FROM ntf_user_counters WHERE tenant_id=?", Long.class, tenant))
                .isEqualTo(count);
    }
    private void assertRolledBack(ApprovalSlaNotificationPlan plan) {
        for (String table : NATIVE_TABLES) assertThat(rowCount(admin, table)).as("Rolled back native DML: " + table).isZero();
        for (String table : List.of("ntf_approval_sla_deliveries", "ntf_approval_sla_delivery_chunks", "ntf_approval_sla_delivery_recipients"))
            assertThat(eventCount(table, plan.eventId())).as("Rolled back journal: " + table).isZero();
        assertThat(admin.queryForObject("SELECT count(*) FROM ntf_notifications WHERE tenant_id=? AND expires_at IS NOT NULL",
                Integer.class, tenant)).isZero();
        verifyNoInteractions(redis);
    }
    private long rowCount(JdbcTemplate query, String table) { return query.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id=?", Long.class, tenant); }
    private long eventCount(String table, UUID event) { return admin.queryForObject("SELECT count(*) FROM " + table + " WHERE tenant_id=? AND event_id=?", Long.class, tenant, event); }
}
