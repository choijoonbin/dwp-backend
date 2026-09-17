package com.dwp.services.notification.domain;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceModels.DecisionRequest;
import com.dwp.services.notification.domain.NotificationAttentionGovernanceModels.Settings;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionPolicyDecision;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionRule;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionRuleCreateRequest;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionRuleUpdateRequest;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionScopeEvidence;
import com.dwp.services.notification.domain.NotificationAttentionModels.AttentionTypeTarget;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Opt-in PostgreSQL proof that governance publication serializes with rule writers. */
@EnabledIfEnvironmentVariable(named = "DWP_NOTIFICATION_ATTENTION_TEST_DB_URL", matches = ".+")
class NotificationAttentionGovernanceConcurrencyPostgresIntegrationTest {

    private static final String RUNTIME_USER = "ntf_attention_test_runtime";
    private static final String RUNTIME_PASSWORD = "attention-test-only";
    private static final long RULE_USER = 17L;
    private static final long AUTHOR = 19L;
    private static final long REVIEWER = 18L;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private JdbcTemplate admin;
    private DriverManagerDataSource adminSource;
    private TransactionTemplate transactions;
    private NotificationAttentionRuleService rules;
    private NotificationAttentionGovernanceService governance;
    private long tenantId;

    @BeforeAll
    static void migrateDisposableDatabase() {
        DriverManagerDataSource source = adminSource();
        JdbcTemplate admin = new JdbcTemplate(source);
        assertThat(admin.queryForObject("SELECT current_database()", String.class))
                .startsWith("dwp_notification_attention_test");
        admin.execute("""
                DO $$ BEGIN
                    IF NOT EXISTS (
                        SELECT 1 FROM pg_roles WHERE rolname = 'ntf_attention_test_runtime'
                    ) THEN
                        CREATE ROLE ntf_attention_test_runtime
                            LOGIN PASSWORD 'attention-test-only'
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
    void wireServices() {
        adminSource = adminSource();
        admin = new JdbcTemplate(adminSource);
        DriverManagerDataSource runtimeSource = new DriverManagerDataSource(
                required("DWP_NOTIFICATION_ATTENTION_TEST_DB_URL"),
                RUNTIME_USER,
                RUNTIME_PASSWORD);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(runtimeSource));
        JdbcTemplate runtimeJdbc = new JdbcTemplate(runtimeSource);
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(runtimeSource);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        NotificationDatabaseScope scope = new NotificationDatabaseScope(runtimeJdbc);
        NotificationIdempotencyRepository idempotency =
                new NotificationIdempotencyRepository(named, mapper);
        NotificationAttentionGovernanceRepository governanceRepository =
                new NotificationAttentionGovernanceRepository(named, mapper);
        NotificationAttentionGovernanceRuntime governanceRuntime =
                new NotificationAttentionGovernanceRuntime(governanceRepository, 500, 10);
        NotificationAttentionGovernanceLock governanceLock =
                new NotificationAttentionGovernanceLock(named);
        NotificationAttentionContextRepository contexts =
                mock(NotificationAttentionContextRepository.class);
        NotificationAttentionPolicyGuard policy = mock(NotificationAttentionPolicyGuard.class);
        when(contexts.evidence(any(), any())).thenReturn(new AttentionScopeEvidence(
                List.of(new AttentionTypeTarget(
                        "messaging", "MESSAGING.DIRECT_MESSAGE")),
                0L));
        when(policy.load(any())).thenReturn(List.of());
        when(policy.evaluate(anyList(), anyList(), anyString(), anyMap()))
                .thenReturn(AttentionPolicyDecision.allowed());
        NotificationAttentionRuleRepository ruleRepository =
                new NotificationAttentionRuleRepository(named, mapper, idempotency);
        rules = new NotificationAttentionRuleService(
                scope,
                ruleRepository,
                contexts,
                policy,
                governanceRuntime,
                governanceLock);
        governance = new NotificationAttentionGovernanceService(
                scope,
                governanceRepository,
                idempotency,
                mock(AuditOutboxRecorder.class),
                governanceLock);
        tenantId = ThreadLocalRandom.current().nextLong(100_000L, 1_000_000_000_000L);
    }

    @AfterEach
    void stopWorkers() {
        executor.shutdownNow();
    }

    @Test
    void publishedCapacityShrinkWinsBeforeCreateWithoutDeadlockOrStaleWrite()
            throws Exception {
        GovernanceFixture fixture = seedGovernance(
                settings(5, 5, List.of("#security-alert")),
                settings(1, 0, List.of()));
        AdvisoryBlocker blocker = blockTenantGovernance();
        Future<?> publish = executor.submit(() -> publish(fixture.draftId(), "publish-shrink"));
        awaitAdvisoryWaiters(1);
        Future<AttentionRule> create = executor.submit(() -> create(
                appTypeCreate("FOLLOW"), "create-after-shrink"));
        awaitAdvisoryWaiters(2);

        blocker.close();

        publish.get(Duration.ofSeconds(5).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThatThrownBy(create::get)
                .isInstanceOfSatisfying(ExecutionException.class, failure ->
                        assertThat(root(failure))
                                .isInstanceOfSatisfying(NotificationException.class, exception ->
                                        assertThat(exception.errorCode()).isEqualTo(
                                                NotificationErrorCode.ATTENTION_RULE_LIMIT_REACHED)));
        assertThat(ruleCount()).isZero();
        assertThat(activeGovernanceRevision()).isEqualTo(2L);
    }

    @Test
    void publishedTopicRevocationWinsBeforeUpdateAndKeepsTheOriginalVersion()
            throws Exception {
        GovernanceFixture fixture = seedGovernance(
                settings(5, 5, List.of("#security-alert")),
                settings(5, 5, List.of()));
        AttentionRule existing = create(topicCreate(), "create-topic-before-revocation");
        AdvisoryBlocker blocker = blockTenantGovernance();
        Future<?> publish = executor.submit(() -> publish(fixture.draftId(), "publish-revoke"));
        awaitAdvisoryWaiters(1);
        Future<AttentionRule> update = executor.submit(() -> update(
                existing.ruleId(), topicUpdate(existing.version()), "update-after-revoke"));
        awaitAdvisoryWaiters(2);

        blocker.close();

        publish.get(Duration.ofSeconds(5).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThatThrownBy(update::get)
                .isInstanceOfSatisfying(ExecutionException.class, failure ->
                        assertThat(root(failure))
                                .isInstanceOfSatisfying(NotificationException.class, exception ->
                                        assertThat(exception.errorCode()).isEqualTo(
                                                NotificationErrorCode.ATTENTION_POLICY_LOCKED)));
        assertThat(ruleVersion(existing.ruleId())).isEqualTo(1L);
        assertThat(activeGovernanceRevision()).isEqualTo(2L);
    }

    @Test
    void staleRuleVersionStillFailsInsideTheSerializedWriterTransaction() {
        seedGovernance(settings(5, 5, List.of("#security-alert")), null);
        AttentionRule created = create(appTypeCreate("PRIORITIZE"), "create-versioned");
        AttentionRule updated = update(
                created.ruleId(), appTypeUpdate("1", "PRIORITIZE"), "update-versioned");
        assertThat(updated.version()).isEqualTo("2");

        assertThatThrownBy(() -> update(
                created.ruleId(), appTypeUpdate("1", "PRIORITIZE"), "update-stale"))
                .isInstanceOfSatisfying(NotificationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(
                                NotificationErrorCode.NOTIFICATION_STALE_VERSION));
        assertThat(ruleVersion(created.ruleId())).isEqualTo(2L);
    }

    private AttentionRule create(AttentionRuleCreateRequest request, String key) {
        return inTransaction(() -> rules.create(ruleActor(), request, key));
    }

    private AttentionRule update(
            UUID ruleId,
            AttentionRuleUpdateRequest request,
            String key) {
        return inTransaction(() -> rules.update(ruleActor(), ruleId, request, key));
    }

    private void publish(UUID governanceId, String key) {
        inTransaction(() -> governance.publish(
                reviewer(), governanceId,
                new DecisionRequest("1", "Independent governance review completed"),
                key));
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transactions.execute(status -> action.get());
    }

    private GovernanceFixture seedGovernance(Settings active, Settings draft) {
        UUID activeId = UUID.randomUUID();
        insertGovernance(activeId, "PUBLISHED", active, 1L, 2L, null);
        if (draft == null) return new GovernanceFixture(activeId, null);
        UUID draftId = UUID.randomUUID();
        insertGovernance(draftId, "DRAFT", draft, 2L, 1L, activeId);
        return new GovernanceFixture(activeId, draftId);
    }

    private void insertGovernance(
            UUID id,
            String state,
            Settings settings,
            long revision,
            long version,
            UUID supersedes) {
        boolean published = "PUBLISHED".equals(state);
        admin.update("""
                INSERT INTO ntf_attention_governance_revisions (
                    governance_id, tenant_id, state,
                    max_active_user_rules, max_vip_rules, max_follow_rules,
                    approved_topic_allowlist, mandatory_policy_precedence,
                    minimum_analytics_cohort, independent_reviewer_required,
                    revision_number, version, change_reason,
                    created_by, approved_by, approved_at, updated_by,
                    decision_reason, supersedes_governance_id)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), TRUE, 10, TRUE,
                        ?, ?, 'Protect tenant attention governance',
                        ?, ?, CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END, ?,
                        CASE WHEN ? THEN 'Independent governance review completed' ELSE NULL END,
                        ?)
                """,
                id,
                tenantId,
                state,
                settings.maxActiveUserRules(),
                settings.maxVipRules(),
                settings.maxFollowRules(),
                jsonTopics(settings.approvedTopicAllowlist()),
                revision,
                version,
                AUTHOR,
                published ? REVIEWER : null,
                published,
                published ? REVIEWER : AUTHOR,
                published,
                supersedes);
    }

    private AdvisoryBlocker blockTenantGovernance() throws Exception {
        Connection connection = adminSource.getConnection();
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
            statement.setString(1, NotificationAttentionGovernanceLock.LOCK_NAMESPACE + tenantId);
            statement.execute();
        }
        return new AdvisoryBlocker(connection);
    }

    private void awaitAdvisoryWaiters(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Integer waiting = admin.queryForObject("""
                    SELECT COUNT(*) FROM pg_locks
                     WHERE locktype='advisory' AND NOT granted
                    """, Integer.class);
            if (waiting != null && waiting >= expected) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for advisory-lock queue size " + expected);
    }

    private long ruleCount() {
        return admin.queryForObject("""
                SELECT COUNT(*) FROM ntf_user_attention_rules WHERE tenant_id=?
                """, Long.class, tenantId);
    }

    private long ruleVersion(UUID ruleId) {
        return admin.queryForObject("""
                SELECT version FROM ntf_user_attention_rules
                 WHERE tenant_id=? AND rule_id=?
                """, Long.class, tenantId, ruleId);
    }

    private long activeGovernanceRevision() {
        return admin.queryForObject("""
                SELECT revision_number FROM ntf_attention_governance_revisions
                 WHERE tenant_id=? AND state='PUBLISHED'
                """, Long.class, tenantId);
    }

    private NotificationRequestContext.Actor ruleActor() {
        return actor(RULE_USER);
    }

    private NotificationRequestContext.Actor reviewer() {
        return actor(REVIEWER);
    }

    private NotificationRequestContext.Actor actor(long userId) {
        return new NotificationRequestContext.Actor(
                tenantId, userId, Set.of("TENANT_ADMIN"),
                Set.of("ADMIN.NOTIFICATION_POLICY:APPROVE"), false, "dwp-gateway");
    }

    private Settings settings(int active, int follow, List<String> topics) {
        return new Settings(active, active, follow, topics, true, 10, true);
    }

    private AttentionRuleCreateRequest appTypeCreate(String effect) {
        return new AttentionRuleCreateRequest(
                "APP_TYPE", "messaging:MESSAGING.DIRECT_MESSAGE",
                "Direct messages", effect, Map.of(), null, null, null, true);
    }

    private AttentionRuleUpdateRequest appTypeUpdate(String version, String effect) {
        return new AttentionRuleUpdateRequest(
                "APP_TYPE", "messaging:MESSAGING.DIRECT_MESSAGE",
                "Updated direct messages", effect, Map.of(), null, null, version, true);
    }

    private AttentionRuleCreateRequest topicCreate() {
        return new AttentionRuleCreateRequest(
                "TOPIC_TOKEN", "security-alert", "Security alerts",
                "FOLLOW", Map.of(), null, null, null, true);
    }

    private AttentionRuleUpdateRequest topicUpdate(String version) {
        return new AttentionRuleUpdateRequest(
                "TOPIC_TOKEN", "security-alert", "Updated security alerts",
                "FOLLOW", Map.of(), null, null, version, true);
    }

    private String jsonTopics(List<String> topics) {
        return topics.stream()
                .map(topic -> "\"" + topic + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private Throwable root(Throwable value) {
        Throwable current = value;
        while (current.getCause() != null) current = current.getCause();
        return current;
    }

    private static DriverManagerDataSource adminSource() {
        return new DriverManagerDataSource(
                required("DWP_NOTIFICATION_ATTENTION_TEST_DB_URL"),
                required("DWP_NOTIFICATION_ATTENTION_TEST_DB_USER"),
                required("DWP_NOTIFICATION_ATTENTION_TEST_DB_PASSWORD"));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for this integration test.");
        }
        return value;
    }

    private record GovernanceFixture(UUID activeId, UUID draftId) {
    }

    private static final class AdvisoryBlocker implements AutoCloseable {
        private final Connection connection;

        private AdvisoryBlocker(Connection connection) {
            this.connection = connection;
        }

        @Override
        public void close() throws SQLException {
            connection.commit();
            connection.close();
        }
    }
}
