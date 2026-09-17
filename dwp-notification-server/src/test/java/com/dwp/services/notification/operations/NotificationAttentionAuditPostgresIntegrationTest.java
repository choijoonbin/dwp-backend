package com.dwp.services.notification.operations;

import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import com.dwp.services.notification.security.NotificationRuntimeDatabaseGuard;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Opt-in PostgreSQL proof of the attention-audit relay and database privilege fence. */
@EnabledIfEnvironmentVariable(named = "DWP_NOTIFICATION_ATTENTION_TEST_DB_URL", matches = ".+")
class NotificationAttentionAuditPostgresIntegrationTest {

    private static final String RUNTIME_USER = "ntf_attention_test_runtime";
    private static final String RUNTIME_PASSWORD = "attention-test-only";

    private JdbcTemplate admin;
    private JdbcTemplate runtime;
    private NotificationDatabaseScope scope;
    private TransactionTemplate transactions;
    private NotificationAttentionAuditRelayTransaction relay;
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
    void wireRuntime() {
        admin = new JdbcTemplate(adminSource());
        DriverManagerDataSource runtimeSource = new DriverManagerDataSource(
                required("DWP_NOTIFICATION_ATTENTION_TEST_DB_URL"),
                RUNTIME_USER,
                RUNTIME_PASSWORD);
        runtime = new JdbcTemplate(runtimeSource);
        scope = new NotificationDatabaseScope(runtime);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(runtimeSource));
        NotificationAttentionAuditOutboxRepository repository =
                new NotificationAttentionAuditOutboxRepository(
                        new NamedParameterJdbcTemplate(runtimeSource));
        relay = new NotificationAttentionAuditRelayTransaction(scope, repository, runtime);
        tenantId = ThreadLocalRandom.current().nextLong(100_000L, 1_000_000_000_000L);
    }

    @Test
    void runtimeGuardAndRelayUseOnlyTheDedicatedLeastPrivilegeRole() {
        UUID eventId = insertUserEvent(tenantId);

        assertThatCode(() -> new NotificationRuntimeDatabaseGuard(runtime, RUNTIME_USER)
                .run(null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> userTransaction(() -> runtime.update(
                "DELETE FROM ntf_attention_rule_audit_outbox WHERE event_id=?", eventId)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> workerTransaction(() -> runtime.update(
                "DELETE FROM ntf_attention_rule_audit_outbox WHERE event_id=?", eventId)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> relayTransaction(() -> runtime.update("""
                UPDATE ntf_attention_rule_audit_outbox
                   SET event_type='RULE_DELETED'
                 WHERE event_id=?
                """, eventId)))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> relayTransaction(() -> runtime.update(
                "DELETE FROM ntf_attention_rule_audit_outbox WHERE event_id=?", eventId)))
                .isInstanceOf(DataAccessException.class);

        List<NotificationAttentionAuditOutboxRepository.AttentionAuditEvent> leased = relayLease(
                tenantId,
                "integration-owner",
                Instant.parse("2026-09-17T00:00:00Z"),
                Instant.parse("2026-09-17T00:00:30Z"),
                10);
        assertThat(leased).singleElement().satisfies(event -> {
            assertThat(event.eventId()).isEqualTo(eventId);
            assertThat(event.attemptCount()).isOne();
        });
        assertThat(relayMarkPublished(
                tenantId,
                eventId,
                "integration-owner",
                Instant.parse("2026-09-17T00:00:01Z"))).isTrue();
        assertThat(admin.queryForObject("""
                SELECT published_at IS NOT NULL
                  FROM ntf_attention_rule_audit_outbox
                 WHERE event_id=?
                """, Boolean.class, eventId)).isTrue();
    }

    @Test
    void tenantLeaseIsIsolatedAndPublishedRetentionIsBounded() {
        UUID selected = insertUserEvent(tenantId);
        UUID otherTenant = insertUserEvent(tenantId + 1);

        assertThat(relayLease(
                tenantId,
                "tenant-owner",
                Instant.parse("2026-09-17T00:00:00Z"),
                Instant.parse("2026-09-17T00:00:30Z"),
                10))
                .extracting(NotificationAttentionAuditOutboxRepository.AttentionAuditEvent::eventId)
                .containsExactly(selected)
                .doesNotContain(otherTenant);

        UUID expiredPublished = UUID.randomUUID();
        admin.update("""
                INSERT INTO ntf_attention_rule_audit_outbox (
                    event_id, tenant_id, user_id, subject_type, subject_id,
                    event_type, scope_kind, effect, subject_version,
                    occurred_at, published_at)
                VALUES (?, ?, ?, 'ATTENTION_RULE', ?, 'RULE_CREATED',
                        'ACTOR', 'PRIORITIZE', 1,
                        CURRENT_TIMESTAMP - INTERVAL '41 days',
                        CURRENT_TIMESTAMP - INTERVAL '40 days')
                """, expiredPublished, tenantId, 17L, UUID.randomUUID());

        assertThat(relayCleanupPublished(
                tenantId,
                Instant.now().minusSeconds(30L * 24 * 60 * 60),
                10)).isOne();
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM ntf_attention_rule_audit_outbox WHERE event_id=?
                """, Integer.class, expiredPublished)).isZero();
        assertThat(admin.queryForObject("""
                SELECT COUNT(*) FROM ntf_attention_rule_audit_outbox WHERE event_id=?
                """, Integer.class, selected)).isOne();
    }

    private UUID insertUserEvent(long requestedTenant) {
        UUID eventId = UUID.randomUUID();
        NotificationRequestContext.Actor actor = new NotificationRequestContext.Actor(
                requestedTenant, 17L, Set.of(), Set.of(), false, "dwp-gateway");
        transactions.executeWithoutResult(status -> {
            scope.applyUser(actor);
            runtime.update("""
                    INSERT INTO ntf_attention_rule_audit_outbox (
                        event_id, tenant_id, user_id, subject_type, subject_id,
                        event_type, scope_kind, effect, subject_version, occurred_at)
                    VALUES (?, ?, ?, 'ATTENTION_RULE', ?, 'RULE_CREATED',
                            'ACTOR', 'PRIORITIZE', 1,
                            TIMESTAMPTZ '2026-09-16 23:59:59+00')
                    """, eventId, requestedTenant, actor.userId(), UUID.randomUUID());
        });
        return eventId;
    }

    private void userTransaction(Runnable action) {
        transactions.executeWithoutResult(status -> {
            scope.applyUser(new NotificationRequestContext.Actor(
                    tenantId, 17L, Set.of(), Set.of(), false, "dwp-gateway"));
            action.run();
        });
    }

    private void workerTransaction(Runnable action) {
        transactions.executeWithoutResult(status -> {
            scope.applyWorker(tenantId);
            action.run();
        });
    }

    private void relayTransaction(Runnable action) {
        transactions.executeWithoutResult(status -> {
            scope.applyWorker(tenantId);
            runtime.execute("SET LOCAL ROLE dwp_notification_attention_audit_relay");
            action.run();
        });
    }

    private List<NotificationAttentionAuditOutboxRepository.AttentionAuditEvent> relayLease(
            long requestedTenant,
            String owner,
            Instant now,
            Instant leaseUntil,
            int limit) {
        return transactions.execute(status -> relay.lease(
                requestedTenant, owner, now, leaseUntil, limit));
    }

    private boolean relayMarkPublished(
            long requestedTenant,
            UUID eventId,
            String owner,
            Instant publishedAt) {
        return Boolean.TRUE.equals(transactions.execute(status -> relay.markPublished(
                requestedTenant, eventId, owner, publishedAt)));
    }

    private int relayCleanupPublished(long requestedTenant, Instant cutoff, int limit) {
        Integer result = transactions.execute(status -> relay.cleanupPublished(
                requestedTenant, cutoff, limit));
        return result == null ? 0 : result;
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
}
