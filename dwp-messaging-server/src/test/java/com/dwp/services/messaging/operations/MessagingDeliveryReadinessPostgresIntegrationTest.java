package com.dwp.services.messaging.operations;

import com.dwp.core.event.DomainEventOutboxRelay;
import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.core.event.DomainEventOutboxRepository;
import com.dwp.core.event.DomainEventRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "DWP_MESSAGING_INTEGRATION_DB_URL", matches = ".+")
class MessagingDeliveryReadinessPostgresIntegrationTest {

    private static final long TENANT = 98_761;
    private static final long OTHER_TENANT = 98_762;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void migrate() {
        var source = new DriverManagerDataSource(
                System.getenv("DWP_MESSAGING_INTEGRATION_DB_URL"),
                System.getenv().getOrDefault("DWP_MESSAGING_INTEGRATION_DB_USERNAME", "postgres"),
                System.getenv().getOrDefault("DWP_MESSAGING_INTEGRATION_DB_PASSWORD", "postgres"));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @BeforeEach
    void clean() {
        jdbc.update(
                "DELETE FROM sys_domain_event_outbox WHERE tenant_id IN (?, ?)",
                TENANT, OTHER_TENANT);
        jdbc.update("DELETE FROM msg_realtime_events WHERE tenant_id IN (?, ?)", TENANT, OTHER_TENANT);
    }

    @Test
    void probesTheTenantLogAndScopesOutboxEvidenceByTenantAndMessagingSource() {
        DomainEventOutboxRelay relay = mock(DomainEventOutboxRelay.class);
        when(relay.isRunning()).thenReturn(true);
        MessagingDeliveryReadinessOperations operations = new MessagingDeliveryReadinessOperations(
                jdbc, relay, true, Duration.ofMinutes(5), 100, Clock.systemUTC());
        outbox(TENANT, "urn:dwp:messaging", "PENDING");
        outbox(OTHER_TENANT, "urn:dwp:messaging", "DEAD");
        outbox(TENANT, "urn:dwp:another-service", "DEAD");

        MessagingDeliveryReadinessOperations.DeliveryEvidence evidence =
                operations.evidence(TENANT);

        assertThat(evidence.durableLog().effective()).isTrue();
        assertThat(evidence.notificationOutbox().state()).isEqualTo("READY");
        assertThat(evidence.notificationOutbox().pendingWorkCount()).isOne();

        outbox(TENANT, "urn:dwp:messaging", "DEAD");
        MessagingDeliveryReadinessOperations.NotificationOutboxStatus blocked =
                operations.evidence(TENANT).notificationOutbox();
        assertThat(blocked.state()).isEqualTo("BLOCKED");
        assertThat(blocked.reasonCode()).isEqualTo("NOTIFICATION_OUTBOX_DEAD_LETTERS");
        assertThat(blocked.pendingWorkCount()).isEqualTo(2);
    }

    @Test
    void messagingNotificationOutboxAppendRollsBackWithItsOwningTransaction() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        DomainEventContractRegistry contracts = new DomainEventContractRegistry();
        contracts.register("messaging.message.sent.v1", 1, 1);
        DomainEventRecorder recorder = new DomainEventRecorder(
                new DomainEventOutboxRepository(
                        new NamedParameterJdbcTemplate(jdbc), objectMapper),
                contracts,
                objectMapper);
        DomainEventEnvelope rolledBack = event(objectMapper, "rollback");

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            recorder.record(rolledBack);
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(outboxCount(rolledBack.id())).isZero();

        DomainEventEnvelope committed = event(objectMapper, "commit");
        transactions.executeWithoutResult(status -> recorder.record(committed));
        assertThat(outboxCount(committed.id())).isOne();
    }

    private void outbox(long tenantId, String source, String status) {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sys_domain_event_outbox (
                    outbox_id, event_id, spec_version, event_source, event_type,
                    schema_version, tenant_id, aggregate_type, aggregate_id,
                    aggregate_sequence, correlation_id, payload, payload_sha256,
                    status, attempt_count, available_at, created_at, updated_at,
                    dead_lettered_at)
                VALUES (?, ?, '1.0', ?, 'messaging.message.sent.v1',
                        1, ?, 'MESSAGING_CONVERSATION', ?, 1, ?, '{}'::jsonb, ?,
                        ?, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        CASE WHEN ? = 'DEAD' THEN CURRENT_TIMESTAMP ELSE NULL END)
                """, UUID.randomUUID(), eventId, source, tenantId,
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), "a".repeat(64),
                status, status);
    }

    private DomainEventEnvelope event(ObjectMapper objectMapper, String suffix) {
        return DomainEventEnvelope.create(
                "urn:dwp:messaging",
                "messaging.message.sent.v1",
                1,
                TENANT,
                "MESSAGING_CONVERSATION",
                UUID.randomUUID().toString(),
                1,
                "readiness-" + suffix + '-' + UUID.randomUUID(),
                null,
                null,
                objectMapper.createObjectNode().put("test", suffix));
    }

    private long outboxCount(UUID eventId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_domain_event_outbox WHERE event_id = ?",
                Long.class, eventId);
        return count == null ? 0 : count;
    }
}
