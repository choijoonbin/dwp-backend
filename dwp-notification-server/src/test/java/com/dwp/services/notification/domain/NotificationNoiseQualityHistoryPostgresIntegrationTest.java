package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationModels.DirectMaterializationRequest;
import com.dwp.services.notification.domain.NotificationNoiseQualityModels.NoiseAggregateRow;
import com.dwp.services.notification.domain.NotificationNoiseQualityModels.NoiseQuality;
import com.dwp.services.notification.integration.NotificationRecipientEntitlementDirectory;
import com.dwp.services.notification.operations.NotificationRetentionRepository;
import com.dwp.services.notification.operations.NotificationRetentionService;
import com.dwp.services.notification.realtime.NotificationChangePublisher;
import com.dwp.services.notification.realtime.NotificationRedisChannels;
import com.dwp.services.notification.realtime.NotificationRedisSignalCodec;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "DWP_NOTIFICATION_CONTEXT_TEST_DB_URL", matches = ".+")
class NotificationNoiseQualityHistoryPostgresIntegrationTest {

    private static final String RUNTIME_USER = "ntf_context_test_runtime";
    private static final String RUNTIME_PASSWORD = "context-test-only";

    private JdbcTemplate admin;
    private NamedParameterJdbcTemplate named;
    private DataSourceTransactionManager transactions;
    private NotificationDatabaseScope scope;
    private NotificationMaterializationRepository materializationRepository;
    private NotificationDeliveryAdmissionService admissionService;
    private DirectNotificationMaterializer materializer;
    private NotificationNoiseQualityRepository qualityRepository;
    private NotificationNoiseQualityService qualityService;
    private NotificationCommandRepository commandRepository;
    private long tenantId;

    @BeforeAll
    static void migrateDisposableDatabase() {
        DriverManagerDataSource source = adminSource();
        JdbcTemplate admin = new JdbcTemplate(source);
        assertThat(admin.queryForObject("SELECT current_database()", String.class))
                .startsWith("dwp_notification_context_test");
        admin.execute("""
                DO $$ BEGIN
                    IF NOT EXISTS (
                        SELECT 1 FROM pg_roles WHERE rolname = 'ntf_context_test_runtime'
                    ) THEN
                        CREATE ROLE ntf_context_test_runtime LOGIN PASSWORD 'context-test-only'
                            NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
                    END IF;
                END $$
                """);
        Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration")
                .placeholders(Map.of("notificationRuntimeRole", RUNTIME_USER))
                .load()
                .migrate();
    }

    @BeforeEach
    void wireRealTransactionPaths() {
        DriverManagerDataSource adminSource = adminSource();
        admin = new JdbcTemplate(adminSource);
        DriverManagerDataSource runtimeSource = new DriverManagerDataSource(
                required("DWP_NOTIFICATION_CONTEXT_TEST_DB_URL"),
                RUNTIME_USER,
                RUNTIME_PASSWORD);
        JdbcTemplate runtime = new JdbcTemplate(runtimeSource);
        named = new NamedParameterJdbcTemplate(runtimeSource);
        transactions = new DataSourceTransactionManager(runtimeSource);
        scope = new NotificationDatabaseScope(runtime);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        NotificationChangePublisher publisher = new NotificationChangePublisher(
                mock(StringRedisTemplate.class),
                new NotificationRedisChannels("notification.quality.test", 8),
                new NotificationRedisSignalCodec(mapper),
                false);
        NotificationAttentionGovernanceRuntime governance =
                new NotificationAttentionGovernanceRuntime(
                        new NotificationAttentionGovernanceRepository(named, mapper),
                        500,
                        10);
        NotificationAttentionAdmissionService attention =
                new NotificationAttentionAdmissionService(
                        new NotificationAttentionAdmissionRepository(named),
                        new NotificationEffectivePolicyRepository(named),
                        governance);
        NotificationDeliveryAdmissionRepository admissionRepository =
                new NotificationDeliveryAdmissionRepository(named);
        admissionService = new NotificationDeliveryAdmissionService(
                admissionRepository, Duration.ofHours(1));
        materializationRepository = new NotificationMaterializationRepository(
                named,
                mapper,
                admissionService,
                new NotificationRuntimeAdmissionRepository(named),
                attention);
        NotificationRetentionService retention = new NotificationRetentionService(
                scope,
                new NotificationRetentionRepository(named),
                publisher,
                Duration.ofDays(30),
                Duration.ofDays(7),
                Duration.ofDays(180),
                100);
        NotificationMaterializationTransactions materializationTransactions =
                new NotificationMaterializationTransactions(
                        transactions,
                        scope,
                        materializationRepository,
                        retention,
                        publisher,
                        runtime);
        NotificationRecipientEntitlementAdmission entitlements =
                new NotificationRecipientEntitlementAdmission(
                        (requestedTenant, userId) -> Optional.of(
                                new NotificationRecipientEntitlementDirectory.Subject(
                                        requestedTenant,
                                        userId,
                                        "ACTIVE",
                                        "TENANT",
                                        List.of("APP.MESSAGING:VIEW"))),
                        "approvals=APP.APPROVALS:VIEW,hcm=APP.HCM:VIEW,"
                                + "messaging=APP.MESSAGING:VIEW,space=APP.SPACES:VIEW,"
                                + "meetings=APP.MEETINGS:VIEW,workplace=APP.WORKPLACE:VIEW,"
                                + "mail=APP.MAIL:VIEW");
        materializer = new DirectNotificationMaterializer(
                materializationTransactions,
                new NotificationProducerOwnershipPolicy(
                        "dwp-messaging-server=messaging"),
                entitlements,
                mapper);
        qualityRepository = new NotificationNoiseQualityRepository(named);
        qualityService = new NotificationNoiseQualityService(
                scope, qualityRepository, governance);
        commandRepository = new NotificationCommandRepository(
                named, mapper, new NotificationIdempotencyRepository(named, mapper));
        tenantId = nextTenant();
    }

    @Test
    void recordsAdmittedSuppressedRateLimitedAndThreadMuteDecisionsExactlyOnce() {
        long userId = 910001L;
        DirectMaterializationRequest admitted = request(
                userId, UUID.randomUUID(), "thread:admitted", false, Instant.now());
        assertThat(materializer.materialize(worker(), admitted, "admitted").recipientCount())
                .isOne();
        assertDecision("ADMITTED", 1);

        tenantId = nextTenant();
        publishTypeRateLimit(1);
        assertThat(materializer.materialize(worker(), request(
                userId, UUID.randomUUID(), "thread:rate-1", false, Instant.now()),
                "rate-first").recipientCount()).isOne();
        assertThat(materializer.materialize(worker(), request(
                userId, UUID.randomUUID(), "thread:rate-2", false, Instant.now()),
                "rate-second").recipientCount()).isZero();
        assertDecision("ADMITTED", 1);
        assertDecision("RATE_LIMITED", 1);

        tenantId = nextTenant();
        activeSuppression();
        var suppressed = materializer.materialize(worker(), request(
                userId, UUID.randomUUID(), "thread:suppressed", false, Instant.now()),
                "suppressed");
        assertThat(suppressed.recipientCount()).isZero();
        assertThat(projectionCount(userId)).isZero();
        assertDecision("SUPPRESSED", 1);

        tenantId = nextTenant();
        String mutedThread = "thread:mute-history";
        attentionRule(userId, "THREAD", mutedThread, "MUTE");
        DirectMaterializationRequest muted = request(
                userId, UUID.randomUUID(), mutedThread, false, Instant.now());
        var mutedResult = materializer.materialize(worker(), muted, "thread-mute");
        assertThat(mutedResult.notificationId()).isNull();
        assertThat(projectionCount(userId)).isZero();
        assertThat(admin.queryForObject("""
                SELECT count(*)
                  FROM ntf_notification_quality_facts
                 WHERE tenant_id=?
                   AND decision='SUPPRESSED'
                   AND attention_effect='MUTE'
                   AND attention_scope_kind='THREAD'
                """, Long.class, tenantId)).isOne();

        admin.update("""
                UPDATE ntf_user_attention_rules
                   SET enabled=FALSE, updated_at=CURRENT_TIMESTAMP
                 WHERE tenant_id=? AND user_id=?
                """, tenantId, userId);
        NoiseAggregateRow historical = aggregates(Duration.ofDays(1)).getFirst();
        assertThat(historical.mutingRecipients()).isOne();
        assertThat(materializer.materialize(worker(), muted, "thread-mute-replay").duplicate())
                .isTrue();
        assertThat(factCount()).isOne();
    }

    @Test
    void oldProjectionNewActivityAndCompletionUseDecisionTimeFacts() {
        long userId = 910002L;
        String thread = "thread:old-projection";
        Instant now = Instant.now();
        var first = materializer.materialize(worker(), request(
                userId, UUID.randomUUID(), thread, true, now.minusSeconds(30)), "old-first");
        admin.update("""
                UPDATE ntf_user_notifications
                   SET created_at=CURRENT_TIMESTAMP - INTERVAL '60 days'
                 WHERE tenant_id=? AND user_id=? AND notification_id=?
                """, tenantId, userId, first.notificationId());

        var second = materializer.materialize(worker(), request(
                userId, UUID.randomUUID(), thread, true, now), "old-second");
        assertThat(second.notificationId()).isEqualTo(first.notificationId());
        NoiseAggregateRow beforeCompletion = aggregates(Duration.ofHours(1)).getFirst();
        assertThat(beforeCompletion.volume()).isEqualTo(2);
        assertThat(beforeCompletion.deduplicationEligibleOccurrences()).isEqualTo(2);
        assertThat(beforeCompletion.deduplicatedOccurrences()).isOne();
        assertThat(beforeCompletion.actionable()).isOne();

        NotificationRequestContext.Actor user = user(userId);
        var completed = userTransaction(() -> commandRepository.mutate(
                user,
                first.notificationId(),
                "COMPLETE",
                2,
                null,
                "quality-complete-" + first.notificationId()));
        assertThat(completed.changed()).isTrue();
        var replay = userTransaction(() -> commandRepository.mutate(
                user,
                first.notificationId(),
                "COMPLETE",
                2,
                null,
                "quality-complete-" + first.notificationId()));
        assertThat(replay.replayed()).isTrue();
        userTransaction(() -> commandRepository.mutate(
                user,
                first.notificationId(),
                "RESTORE",
                3,
                null,
                "quality-restore-" + first.notificationId()));

        assertThat(admin.queryForObject("""
                SELECT count(*) FROM ntf_notification_quality_completion_facts
                 WHERE tenant_id=? AND user_id=?
                """, Long.class, tenantId, userId)).isOne();
        assertThat(aggregates(Duration.ofHours(1)).getFirst().completedActions()).isOne();
    }

    @Test
    void rollbackWritesNoFactAndSmallCohortsAndOtherTenantsStayWithheld() {
        long userId = 910003L;
        DirectMaterializationRequest request = request(
                userId, UUID.randomUUID(), "thread:rollback", false, Instant.now());
        TemplateContract contract = workerTransaction(() -> materializationRepository.contract(
                tenantId,
                request.typeKey(),
                request.sourceEventType(),
                request.sourceSchemaVersion(),
                request.locale()));

        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            scope.applyWorker(tenantId);
            assertThat(admissionService.admittedRecipient(
                    tenantId,
                    userId,
                    request,
                    contract,
                    Instant.now(),
                    NotificationAttentionDecision.none(),
                    false,
                    true)).isTrue();
            throw new RollbackProbe();
        })).isInstanceOf(RollbackProbe.class);
        assertThat(factCount()).isZero();
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM ntf_delivery_admission_receipts WHERE tenant_id=?
                """, Long.class, tenantId)).isZero();

        materializer.materialize(worker(), request(
                userId, UUID.randomUUID(), "thread:privacy", false, Instant.now()),
                "privacy");
        NoiseQuality redacted = workerTransaction(() -> qualityService.summary(user(userId)));
        assertThat(redacted.sufficientCohort()).isFalse();
        assertThat(redacted.observedCohortSize()).isNull();
        assertThat(redacted.noisyTypes()).isEmpty();

        long otherTenant = nextTenant();
        assertThat(workerTransaction(otherTenant, () -> qualityRepository.observedCohortSize(
                otherTenant, Instant.now().minus(Duration.ofDays(1)), null))).isZero();
    }

    private List<NoiseAggregateRow> aggregates(Duration window) {
        return workerTransaction(() -> qualityRepository.aggregates(
                tenantId,
                Instant.now().minus(window),
                1,
                100,
                null,
                null,
                null));
    }

    private void publishTypeRateLimit(int maximum) {
        UUID policyId = UUID.randomUUID();
        admin.update("""
                INSERT INTO ntf_routing_policies (
                    policy_id, tenant_id, scope_type, scope_key, version, state,
                    mandatory, quiet_hours_bypass, digest_mode,
                    created_by, approved_by, approved_at, change_reason)
                VALUES (?, ?, 'TYPE', 'MESSAGING.DIRECT_MESSAGE', 1, 'PUBLISHED',
                        FALSE, FALSE, 'IMMEDIATE', 1, 2, CURRENT_TIMESTAMP,
                        'Independent quality test approval')
                """, policyId, tenantId);
        admin.update("""
                INSERT INTO ntf_policy_channel_rules (
                    policy_channel_rule_id, tenant_id, policy_id, channel,
                    enabled, default_mode, user_overridable, max_per_window)
                VALUES (?, ?, ?, 'IN_APP', TRUE, 'IMMEDIATE', TRUE, ?)
                """, UUID.randomUUID(), tenantId, policyId, maximum);
    }

    private void activeSuppression() {
        admin.update("""
                INSERT INTO ntf_delivery_suppressions (
                    suppression_id, tenant_id, scope_type, scope_key, channel,
                    starts_at, expires_at, critical_bypass, reason, created_by)
                VALUES (?, ?, 'TYPE', 'MESSAGING.DIRECT_MESSAGE', 'IN_APP',
                        CURRENT_TIMESTAMP - INTERVAL '1 minute',
                        CURRENT_TIMESTAMP + INTERVAL '1 hour', FALSE,
                        'Quality history integration suppression', 1)
                """, UUID.randomUUID(), tenantId);
    }

    private void attentionRule(long userId, String kind, String key, String effect) {
        admin.update("""
                INSERT INTO ntf_user_attention_rules (
                    rule_id, tenant_id, user_id, scope_kind, scope_key,
                    scope_key_hash, effect, source)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'USER')
                """, UUID.randomUUID(), tenantId, userId, kind, key,
                NotificationStructuredContexts.attentionScopeHash(kind, key), effect);
    }

    private DirectMaterializationRequest request(
            long userId,
            UUID sourceEventId,
            String thread,
            boolean actionRequired,
            Instant occurredAt) {
        UUID messageId = UUID.randomUUID();
        return new DirectMaterializationRequest(
                sourceEventId,
                "messaging.message.sent.v1",
                1,
                "MESSAGING.DIRECT_MESSAGE",
                List.of(userId),
                thread,
                "ko-KR",
                "DIRECT",
                "user:sender",
                "message:" + messageId,
                "/messages/direct?message=" + messageId,
                occurredAt,
                null,
                actionRequired,
                Map.of(
                        "senderName", "Quality sender",
                        "conversationName", "Quality conversation",
                        "conversationId", UUID.randomUUID().toString(),
                        "messageId", messageId.toString(),
                        "messagePreview", "Content is never copied into quality facts"));
    }

    private NotificationRequestContext.Actor worker() {
        return new NotificationRequestContext.Actor(
                tenantId, null, Set.of(), Set.of(), true, "dwp-messaging-server");
    }

    private NotificationRequestContext.Actor user(long userId) {
        return new NotificationRequestContext.Actor(
                tenantId, userId, Set.of(), Set.of(), false, "dwp-gateway");
    }

    private long projectionCount(long userId) {
        return admin.queryForObject("""
                SELECT count(*) FROM ntf_user_notifications
                 WHERE tenant_id=? AND user_id=?
                """, Long.class, tenantId, userId);
    }

    private void assertDecision(String decision, long count) {
        assertThat(admin.queryForObject("""
                SELECT count(*) FROM ntf_notification_quality_facts
                 WHERE tenant_id=? AND decision=?
                """, Long.class, tenantId, decision)).isEqualTo(count);
    }

    private long factCount() {
        return admin.queryForObject("""
                SELECT count(*) FROM ntf_notification_quality_facts WHERE tenant_id=?
                """, Long.class, tenantId);
    }

    private <T> T workerTransaction(Supplier<T> work) {
        return workerTransaction(tenantId, work);
    }

    private <T> T workerTransaction(long scopedTenant, Supplier<T> work) {
        return new TransactionTemplate(transactions).execute(status -> {
            scope.applyWorker(scopedTenant);
            return work.get();
        });
    }

    private <T> T userTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactions).execute(status -> {
            scope.applyUser(user(910002L));
            return work.get();
        });
    }

    private static long nextTenant() {
        return ThreadLocalRandom.current().nextLong(100_000L, 1_000_000_000_000L);
    }

    private static DriverManagerDataSource adminSource() {
        return new DriverManagerDataSource(
                required("DWP_NOTIFICATION_CONTEXT_TEST_DB_URL"),
                System.getenv().getOrDefault(
                        "DWP_NOTIFICATION_CONTEXT_TEST_DB_USERNAME", "postgres"),
                System.getenv().getOrDefault(
                        "DWP_NOTIFICATION_CONTEXT_TEST_DB_PASSWORD", "postgres"));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required.");
        }
        return value;
    }

    private static final class RollbackProbe extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
