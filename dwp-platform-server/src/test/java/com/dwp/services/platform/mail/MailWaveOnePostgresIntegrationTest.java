package com.dwp.services.platform.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class MailWaveOnePostgresIntegrationTest {

    private static final String BODY_TOKEN = "orichalcumuniquebody";
    private static final String BCC_TOKEN = "shadowbccuniquex";

    @Container
    private final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private MailJsonCodec json;

    @BeforeEach
    void migrate() {
        PGSimpleDataSource source = dataSource();
        Flyway flyway = Flyway.configure()
                .dataSource(source)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        json = new MailJsonCodec(new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void incomingRulesSearchDiagnosticAndSchemaFencesOperateTogether() {
        Fixture fixture = fixture();
        MailIncomingRuleRepository runs = new MailIncomingRuleRepository(jdbc, json);
        MailMetrics metrics = new MailMetrics(new SimpleMeterRegistry());
        MailIncomingRuleTransactions incoming = new MailIncomingRuleTransactions(
                runs, new MailOrganizationCommandRepository(jdbc, json),
                new MailRuleEvaluator(), metrics);

        jdbc.update("""
                UPDATE mail_rules SET lifecycle_state = 'ARCHIVED', enabled = FALSE
                 WHERE tenant_id = ? AND account_id = ?
                """, fixture.tenantId(), fixture.accountId());
        jdbc.update("""
                UPDATE mail_threads
                   SET unread = TRUE, starred = FALSE, workflow_state = 'OPEN'
                 WHERE tenant_id = ? AND thread_id = ?
                """, fixture.tenantId(), fixture.threadId());
        jdbc.update("""
                UPDATE mail_messages
                   SET body_content = body_content || E'\\n' || ?
                 WHERE tenant_id = ? AND message_id = ?
                """, BODY_TOKEN, fixture.tenantId(), fixture.messageId());
        UUID firstRule = insertRule(fixture, 10, false, "MARK_READ");
        UUID secondRule = insertRule(fixture, 20, true, "STAR");

        assertThat(runs.enqueue(
                fixture.tenantId(), fixture.threadId(), fixture.messageId())).isEqualTo(2);
        assertThat(runs.enqueue(
                fixture.tenantId(), fixture.threadId(), fixture.messageId())).isZero();

        MailIncomingRuleRepository.IncomingRun first =
                runs.claim("worker-a", 10, 30).getFirst();
        assertThat(first.ruleId()).isEqualTo(firstRule);
        assertThat(first.attemptCount()).isOne();
        assertThat(runs.fail(first, "worker-a", 3, "INJECTED_FAILURE")).isOne();
        jdbc.update("""
                UPDATE mail_rule_runs SET next_attempt_at = CURRENT_TIMESTAMP
                 WHERE run_id = ?
                """, first.runId());

        MailIncomingRuleRepository.IncomingRun retry =
                runs.claim("worker-b", 10, 30).getFirst();
        assertThat(retry.runId()).isEqualTo(first.runId());
        assertThat(retry.attemptCount()).isEqualTo(2);
        transaction.executeWithoutResult(ignored -> incoming.execute(retry, "worker-b"));

        MailIncomingRuleRepository.IncomingRun second =
                runs.claim("worker-c", 10, 30).getFirst();
        assertThat(second.ruleId()).isEqualTo(secondRule);
        transaction.executeWithoutResult(ignored -> incoming.execute(second, "worker-c"));

        assertThat(jdbc.queryForMap("""
                SELECT unread, starred FROM mail_threads
                 WHERE tenant_id = ? AND thread_id = ?
                """, fixture.tenantId(), fixture.threadId()))
                .containsEntry("unread", false)
                .containsEntry("starred", true);
        assertThat(jdbc.queryForList("""
                SELECT run_status FROM mail_rule_runs
                 WHERE tenant_id = ? AND message_id = ? ORDER BY rule_priority
                """, String.class, fixture.tenantId(), fixture.messageId()))
                .containsExactly("SUCCEEDED", "SUCCEEDED");

        verifyBodySearchAndAuthorizationBoundary(fixture);
        verifyConnectionActivationReceipt(fixture);
        verifySchemaContractsAndMessageFence(fixture, first.runId());
    }

    private void verifyBodySearchAndAuthorizationBoundary(Fixture fixture) {
        MailQueryRepository queries = new MailQueryRepository(jdbc, json);

        assertThat(queries.threads(
                fixture.tenantId(), fixture.ownerUserId(), "", "", "",
                false, BODY_TOKEN, 0, 50))
                .extracting(MailDtos.ThreadSummary::threadId)
                .contains(fixture.threadId());
        assertThat(queries.threadCount(
                fixture.tenantId(), fixture.ownerUserId(), "", "", "",
                false, BODY_TOKEN)).isPositive();
        assertThat(queries.threads(
                fixture.tenantId(), fixture.ownerUserId() + 9_000_000L, "", "", "",
                false, BODY_TOKEN, 0, 50)).isEmpty();

        jdbc.update("""
                UPDATE mail_threads
                   SET participants = jsonb_build_array(jsonb_build_object(
                       'type', 'BCC', 'name', 'Hidden', 'email', ?))
                 WHERE tenant_id = ? AND thread_id = ?
                """, BCC_TOKEN + "@example.test", fixture.tenantId(), fixture.threadId());
        assertThat(queries.threads(
                fixture.tenantId(), fixture.ownerUserId(), "", "", "",
                false, BCC_TOKEN, 0, 50)).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM pg_indexes
                 WHERE schemaname = current_schema()
                   AND indexname = 'idx_mail_thread_search_document'
                   AND indexdef LIKE '%USING gin%'
                """, Integer.class)).isOne();
    }

    private void verifyConnectionActivationReceipt(Fixture fixture) {
        Connection connection = jdbc.query("""
                SELECT connection.connection_id, connection.version
                  FROM mail_accounts account
                  JOIN mail_provider_connections connection
                    ON connection.tenant_id = account.tenant_id
                   AND connection.connection_id = account.connection_id
                 WHERE account.tenant_id = ? AND account.account_id = ?
                """, (result, ignored) -> new Connection(
                result.getObject("connection_id", UUID.class), result.getLong("version")),
                fixture.tenantId(), fixture.accountId()).getFirst();
        UUID operationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO mail_connection_operations (
                    operation_id, tenant_id, connection_id, actor_user_id,
                    operation_kind, operation_scope, request_payload,
                    operation_state, idempotency_key, request_fingerprint,
                    evidence_generated_at, completed_at)
                VALUES (?, ?, ?, ?, 'DIAGNOSTIC', 'FULL',
                        jsonb_build_object(
                            'capability', 'AUTHENTICATION',
                            'confirmedExternalImpact', TRUE,
                            'version', ?::bigint),
                        'SUCCEEDED', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, operationId, fixture.tenantId(), connection.id(),
                fixture.ownerUserId(), connection.version(), UUID.randomUUID(), "a".repeat(64));
        MailQueryRepository queries = new MailQueryRepository(jdbc, json);
        OffsetDateTime notBefore = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10);
        assertThat(queries.hasFreshActivationDiagnostic(
                fixture.tenantId(), fixture.ownerUserId(), connection.id(),
                connection.version(), notBefore)).isTrue();

        jdbc.update("""
                UPDATE mail_connection_operations
                   SET evidence_generated_at = CURRENT_TIMESTAMP - INTERVAL '11 minutes'
                 WHERE operation_id = ?
                """, operationId);
        assertThat(queries.hasFreshActivationDiagnostic(
                fixture.tenantId(), fixture.ownerUserId(), connection.id(),
                connection.version(), notBefore)).isFalse();
    }

    private void verifySchemaContractsAndMessageFence(Fixture fixture, UUID runId) {
        assertThat(jdbc.queryForList("""
                SELECT code FROM sys_code_values
                 WHERE code_set_key = 'PLATFORM.MAIL_RULE_RUNS.RUN_STATUS'
                   AND lifecycle_state = 'ACTIVE'
                 ORDER BY code
                """, String.class)).containsExactly(
                "FAILED", "LEASED", "QUEUED", "RETRY_WAIT",
                "RUNNING", "SKIPPED", "SUCCEEDED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM sys_code_bindings
                 WHERE code_set_key = 'PLATFORM.MAIL_RULE_RUNS.RUN_STATUS'
                   AND source_reference = 'mail_rule_runs.run_status'
                   AND enforcement_type = 'CHECK'
                   AND lifecycle_state = 'ACTIVE'
                """, Integer.class)).isOne();

        UUID otherMessage = jdbc.queryForObject("""
                SELECT message.message_id
                  FROM mail_messages message
                 WHERE message.tenant_id = ? AND message.thread_id <> ?
                 ORDER BY message.message_id LIMIT 1
                """, UUID.class, fixture.tenantId(), fixture.threadId());
        assertThat(otherMessage).isNotNull();
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE mail_rule_runs SET message_id = ? WHERE run_id = ?
                """, otherMessage, runId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .rootCause()
                .hasMessageContaining("fk_mail_rule_run_message");
    }

    private UUID insertRule(
            Fixture fixture, int priority, boolean stopProcessing, String action) {
        UUID ruleId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO mail_rules (
                    rule_id, tenant_id, account_id, owner_user_id, display_name,
                    priority, match_mode, conditions, actions, stop_processing,
                    enabled, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, 'ALL',
                        jsonb_build_array(jsonb_build_object(
                            'field', 'BODY', 'operator', 'CONTAINS', 'value', ?)),
                        jsonb_build_array(jsonb_build_object('type', ?)),
                        ?, TRUE, ?, ?)
                """, ruleId, fixture.tenantId(), fixture.accountId(), fixture.ownerUserId(),
                "Incoming " + ruleId, priority, BODY_TOKEN, action, stopProcessing,
                fixture.ownerUserId(), fixture.ownerUserId());
        return ruleId;
    }

    private Fixture fixture() {
        return jdbc.query("""
                SELECT thread.tenant_id, account.owner_user_id, thread.account_id,
                       thread.thread_id, message.message_id
                  FROM mail_threads thread
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                   AND account.account_kind = 'PERSONAL'
                   AND account.owner_user_id IS NOT NULL
                   AND account.connection_state = 'ACTIVE'
                  JOIN mail_messages message
                    ON message.tenant_id = thread.tenant_id
                   AND message.thread_id = thread.thread_id
                   AND message.message_direction = 'INBOUND'
                 WHERE EXISTS (
                       SELECT 1 FROM mail_messages other
                        WHERE other.tenant_id = thread.tenant_id
                          AND other.thread_id <> thread.thread_id)
                 ORDER BY thread.tenant_id, account.owner_user_id, thread.thread_id
                 LIMIT 1
                """, (result, ignored) -> new Fixture(
                result.getLong("tenant_id"), result.getLong("owner_user_id"),
                result.getObject("account_id", UUID.class),
                result.getObject("thread_id", UUID.class),
                result.getObject("message_id", UUID.class))).getFirst();
    }

    private PGSimpleDataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(postgres.getJdbcUrl());
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        return source;
    }

    private record Fixture(
            long tenantId,
            long ownerUserId,
            UUID accountId,
            UUID threadId,
            UUID messageId) {
    }

    private record Connection(UUID id, long version) {
    }
}
