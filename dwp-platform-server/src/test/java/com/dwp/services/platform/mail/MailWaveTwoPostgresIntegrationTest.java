package com.dwp.services.platform.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class MailWaveTwoPostgresIntegrationTest {

    @Container
    private final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc;
    private AdminMailCompletionRepository admin;
    private MailSyncJobRepository jobs;

    @BeforeEach
    void migrate() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(postgres.getJdbcUrl());
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
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
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        admin = new AdminMailCompletionRepository(jdbc, objectMapper);
        jobs = new MailSyncJobRepository(jdbc, objectMapper);
    }

    @Test
    void leaseCursorResetEventCatchupAndSystemPrincipalAreDatabaseFenced() {
        List<Fixture> fixtures = fixtures();
        assertThat(fixtures).hasSizeGreaterThanOrEqualTo(2);
        Fixture fixture = fixtures.getFirst();

        UUID operationId = operation(fixture, fixture.ownerUserId(), "MANUAL", "a".repeat(64));
        jobs.enqueue(fixture.tenantId(), operationId, fixture.connectionId(),
                fixture.ownerUserId(), fixture.connectionVersion(), "MANUAL", 3);

        MailSyncJob first = jobs.claim("worker-a", 1, 30).getFirst();
        assertThat(first.attemptCount()).isOne();
        assertThat(jobs.claim("worker-b", 1, 30)).isEmpty();
        assertThat(jobs.completeFailure(
                first, "worker-a", "SYNC_RATE_LIMITED", false,
                "RATE_LIMIT", OffsetDateTime.now(ZoneOffset.UTC), Map.of()).terminal())
                .isFalse();
        jdbc.update("""
                UPDATE mail_sync_jobs SET next_attempt_at = CURRENT_TIMESTAMP
                 WHERE job_id = ?
                """, first.jobId());
        MailSyncJob retry = jobs.claim("worker-b", 1, 30).getFirst();
        assertThat(retry.attemptCount()).isEqualTo(2);
        MailSyncExecutionResult continuation =
                new MailSyncExecutionResult(1, 20, 3, 1, 0, true);
        assertThat(jobs.completeContinuation(retry, "worker-b", continuation)).isTrue();
        assertThat(jdbc.queryForMap("""
                SELECT job_state, attempt_count, lease_owner
                  FROM mail_sync_jobs WHERE job_id = ?
                """, retry.jobId()))
                .containsEntry("job_state", "RETRY_WAIT")
                .containsEntry("attempt_count", 0)
                .containsEntry("lease_owner", null);
        MailSyncJob continued =
                jobs.claim("worker-c", 1, 30).getFirst();
        assertThat(continued.attemptCount()).isOne();
        assertThat(jobs.completeSuccess(
                continued, "worker-c",
                new MailSyncExecutionResult(1, 1, 0, 0, 0))).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT operation_state FROM mail_connection_operations
                 WHERE operation_id = ?
                """, String.class, operationId)).isEqualTo("SUCCEEDED");

        Fixture currentFixture = fixture.withConnectionVersion(
                fixture.connectionVersion() + 1);
        UUID expiredOperation = operation(
                currentFixture, fixture.ownerUserId(), "MANUAL", "e".repeat(64));
        jobs.enqueue(fixture.tenantId(), expiredOperation, fixture.connectionId(),
                fixture.ownerUserId(), currentFixture.connectionVersion(), "MANUAL", 1);
        MailSyncJob expired =
                jobs.claim("worker-expired", 1, 30).getFirst();
        jdbc.update("""
                UPDATE mail_sync_jobs
                   SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                 WHERE job_id = ?
                """, expired.jobId());
        assertThat(jobs.releaseExpiredLeases())
                .isEqualTo(new MailSyncLeaseRecovery(0, 1));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM mail_audit_events
                 WHERE tenant_id = ? AND actor_user_id = ?
                   AND action = 'mail.connection.sync'
                   AND target_id = ?
                   AND after_snapshot ->> 'errorCode' = 'SYNC_WORKER_LEASE_EXPIRED'
                """, Integer.class, fixture.tenantId(), fixture.ownerUserId(),
                fixture.connectionId().toString())).isOne();

        AdminMailCompletionRepository.SyncAccountRow account =
                admin.syncAccount(fixture.tenantId(), fixture.accountId()).orElseThrow();
        String advanced = "cursor:generation:" + UUID.randomUUID();
        assertThat(admin.updateAccountSyncCursor(
                fixture.tenantId(), fixture.accountId(), account.cursor(),
                account.cursorGeneration(), advanced, fixture.ownerUserId())).isOne();
        assertThat(admin.updateAccountSyncCursor(
                fixture.tenantId(), fixture.accountId(), advanced,
                account.cursorGeneration(), "stale-write", fixture.ownerUserId())).isZero();

        UUID resetOperation = operation(
                currentFixture, fixture.ownerUserId(), "MANUAL", "b".repeat(64));
        jobs.enqueue(fixture.tenantId(), resetOperation, fixture.connectionId(),
                fixture.ownerUserId(), currentFixture.connectionVersion(), "MANUAL", 3);
        MailSyncJob resetJob = jobs.claim("worker-reset", 1, 30).getFirst();
        AdminMailCompletionRepository.SyncAccountRow beforeReset =
                admin.syncAccount(fixture.tenantId(), fixture.accountId()).orElseThrow();
        AdminMailCompletionRepository.AccountRow expected =
                new AdminMailCompletionRepository.AccountRow(
                        beforeReset.id(), beforeReset.email(), "provider-account",
                        beforeReset.cursor(), beforeReset.cursorGeneration());
        String fullSyncSeed = "provider:full-sync:" + UUID.randomUUID();
        MailSyncJobRepository.ResetTransition reset = jobs.resetCursorAndEnqueueRecovery(
                resetJob, expected, fullSyncSeed);
        MailSyncJobRepository.ResetTransition replay = jobs.resetCursorAndEnqueueRecovery(
                resetJob, expected, fullSyncSeed);
        assertThat(replay.resetId()).isEqualTo(reset.resetId());
        assertThat(jobs.completeFailure(
                resetJob, "worker-reset", "SYNC_CURSOR_RESET_REQUIRED", true,
                "CURSOR_RESET", null, reset.evidence()))
                .isEqualTo(new MailSyncJobRepository.FailureDisposition(true, true));
        assertThat(jdbc.queryForMap("""
                SELECT job_state, lease_owner, cursor_reset_receipt IS NOT NULL has_receipt
                  FROM mail_sync_jobs WHERE job_id = ?
                """, resetJob.jobId()))
                .containsEntry("job_state", "DEAD_LETTER")
                .containsEntry("lease_owner", null)
                .containsEntry("has_receipt", true);
        assertThat(jdbc.queryForObject("""
                SELECT operation_state FROM mail_connection_operations
                 WHERE operation_id = ?
                """, String.class, resetOperation)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("""
                SELECT synchronization_cursor_generation FROM mail_accounts
                 WHERE tenant_id = ? AND account_id = ?
                """, Long.class, fixture.tenantId(), fixture.accountId()))
                .isEqualTo(beforeReset.cursorGeneration() + 1);
        Map<String, Object> resetRow = jdbc.queryForMap("""
                SELECT prior_cursor_hash, reset_cursor_hash, prior_generation,
                       reset_generation
                  FROM mail_sync_cursor_reset_receipts WHERE reset_id = ?
                """, reset.resetId());
        assertThat(resetRow.toString()).doesNotContain(fullSyncSeed);
        if (beforeReset.cursor() != null) {
            assertThat(resetRow.toString()).doesNotContain(beforeReset.cursor());
        }
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM mail_sync_jobs
                 WHERE operation_id = ? AND trigger_kind = 'RECOVERY'
                """, Integer.class, reset.recoveryOperationId())).isOne();

        verifyEventIsolation(fixtures.get(0), fixtures.get(1));
        verifyWebhookSystemPrincipal(fixture);
        verifySubscriptionLeaseAndHashOnlyReceipt(fixture);
        verifySchemaContracts(fixture);
    }

    private void verifySubscriptionLeaseAndHashOnlyReceipt(Fixture fixture) {
        MailSubscriptionLedger subscriptions = new MailSubscriptionLedger(
                jdbc, new ObjectMapper().findAndRegisterModules());
        UUID subscriptionId = subscriptions.schedule(
                fixture.tenantId(), fixture.connectionId(), fixture.accountId());
        MailSubscriptionLedger.Renewal renewal = subscriptions
                .claim("subscription-worker-a", 1, 30).getFirst();
        assertThat(renewal.subscriptionId()).isEqualTo(subscriptionId);
        assertThat(renewal.accountId()).isEqualTo(fixture.accountId());
        assertThat(subscriptions.claim("subscription-worker-b", 1, 30)).isEmpty();
        OffsetDateTime expires = OffsetDateTime.now(ZoneOffset.UTC).plusHours(2);
        assertThat(subscriptions.completeSuccess(
                renewal, "subscription-worker-a", "e".repeat(64), null,
                expires, expires.minusMinutes(30),
                Map.of("provider", fixture.providerType(), "status", "VERIFIED"))).isTrue();
        Map<String, Object> stored = jdbc.queryForMap("""
                SELECT subscription_state, provider_subscription_reference_hash,
                       client_state_hash, lease_owner, actor_user_id
                  FROM mail_provider_subscriptions WHERE subscription_id = ?
                """, subscriptionId);
        assertThat(stored)
                .containsEntry("subscription_state", "ACTIVE")
                .containsEntry("provider_subscription_reference_hash", "e".repeat(64))
                .containsEntry("client_state_hash", null)
                .containsEntry("lease_owner", null);
        assertThat(((Number) stored.get("actor_user_id")).longValue()).isNegative();

        jdbc.update("""
                UPDATE mail_provider_subscriptions
                   SET renewal_due_at = CURRENT_TIMESTAMP,
                       next_renewal_attempt_at = CURRENT_TIMESTAMP
                 WHERE subscription_id = ?
                """, subscriptionId);
        MailSubscriptionLedger.Renewal terminalRenewal = subscriptions
                .claim("subscription-worker-terminal", 1, 30).getFirst();
        assertThat(subscriptions.completeFailure(
                terminalRenewal, "subscription-worker-terminal",
                "SUBSCRIPTION_AUTH_REVOKED", "AUTHENTICATION", null, true))
                .isEqualTo(new MailSubscriptionLedger.FailureDisposition(true, true));
        assertThat(subscriptions.claim("subscription-worker-replay", 1, 30)).isEmpty();
    }

    private void verifyEventIsolation(Fixture first, Fixture second) {
        assertThat(second.tenantId()).isEqualTo(first.tenantId());
        assertThat(second.ownerUserId()).isNotEqualTo(first.ownerUserId());
        UUID firstEvent = UUID.randomUUID();
        UUID secondEvent = UUID.randomUUID();
        UUID proposalEvent = UUID.randomUUID();
        UUID messageEvent = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();
        UUID messageId = jdbc.queryForObject("""
                SELECT message_id FROM mail_messages
                 WHERE tenant_id = ? AND thread_id = ?
                 ORDER BY sent_at, message_id LIMIT 1
                """, UUID.class, first.tenantId(), first.threadId());
        jdbc.update("""
                INSERT INTO mail_action_proposals (
                    proposal_id, tenant_id, account_id, thread_id,
                    proposal_type, proposal_status, title, summary,
                    evidence, proposed_payload, confidence, risk_level,
                    required_resource_key, required_permission_code,
                    target_route, expires_at, action_contract_version,
                    created_by, updated_by)
                VALUES (?, ?, ?, ?, 'DRAFT_REPLY', 'PROPOSED',
                        'SSE proposal', 'SSE proposal visibility',
                        '[{"messageId":"source"}]'::jsonb,
                        '{"requiresConfirmation":true}'::jsonb, 0.5, 'LOW',
                        'APP.MAIL', 'CREATE', '/mail/inbox',
                        CURRENT_TIMESTAMP + INTERVAL '1 day', 1, ?, ?)
                """, proposalId, first.tenantId(), first.accountId(), first.threadId(),
                first.ownerUserId(), first.ownerUserId());
        jdbc.update("""
                INSERT INTO mail_domain_events (
                    domain_event_id, tenant_id, aggregate_type, aggregate_id,
                    event_type, payload)
                VALUES (?, ?, 'MAIL_THREAD', ?, 'mail.message.delivered', '{}'::jsonb),
                       (?, ?, 'MAIL_THREAD', ?, 'mail.thread.updated', '{}'::jsonb),
                       (?, ?, 'MAIL_ACTION_PROPOSAL', ?,
                        'mail.proposal.produced', '{}'::jsonb),
                       (?, ?, 'MAIL_MESSAGE', ?, 'mail.message.received', '{}'::jsonb)
                """, firstEvent, first.tenantId(), first.threadId(),
                secondEvent, second.tenantId(), second.threadId(),
                proposalEvent, first.tenantId(), proposalId,
                messageEvent, first.tenantId(), messageId);
        long after = jdbc.queryForObject("""
                SELECT LEAST(
                    (SELECT event_sequence FROM mail_domain_events WHERE domain_event_id = ?),
                    (SELECT event_sequence FROM mail_domain_events WHERE domain_event_id = ?)) - 1
                """, Long.class, firstEvent, secondEvent);
        MailEventStreamRepository stream = new MailEventStreamRepository(jdbc);
        List<MailEventStreamRepository.EventEnvelope> visible = stream.catchUp(
                first.tenantId(), first.ownerUserId(), after, 100);
        assertThat(visible).extracting(MailEventStreamRepository.EventEnvelope::threadId)
                .contains(first.threadId())
                .doesNotContain(second.threadId());
        assertThat(visible).extracting(MailEventStreamRepository.EventEnvelope::type)
                .contains("mail.message.delivered", "mail.proposal.produced",
                        "mail.message.received");
        assertThat(visible).allSatisfy(event -> {
            assertThat(event.sequence()).isPositive();
            assertThat(event.occurredAt()).isNotNull();
        });
    }

    private void verifyWebhookSystemPrincipal(Fixture fixture) {
        MailWebhookVerificationPort.Verification verification =
                new MailWebhookVerificationPort.Verification(
                        true, "DWP_SANDBOX", fixture.tenantId(), fixture.connectionId(),
                        "c".repeat(64), "d".repeat(64),
                        OffsetDateTime.now(ZoneOffset.UTC), "webhook-corr", null);
        MailSyncJobRepository.WebhookEnqueue enqueued =
                jobs.enqueueVerifiedWebhook(verification, 3);
        Map<String, Object> operation = jdbc.queryForMap("""
                SELECT operation.actor_user_id, operation.request_payload::text,
                       principal.principal_key
                  FROM mail_connection_operations operation
                  JOIN mail_system_principals principal
                    ON principal.tenant_id = operation.tenant_id
                   AND principal.actor_id = operation.actor_user_id
                 WHERE operation.operation_id = ?
                """, enqueued.operationId());
        assertThat(((Number) operation.get("actor_user_id")).longValue()).isNegative();
        assertThat(operation.get("principal_key")).isEqualTo("MAIL_WEBHOOK");
        assertThat(operation.get("request_payload").toString())
                .contains("VERIFIED_WEBHOOK")
                .doesNotContain("token", "client-state");
    }

    private void verifySchemaContracts(Fixture fixture) {
        assertThat(jdbc.queryForList("""
                SELECT code FROM sys_code_values
                 WHERE code_set_key = 'PLATFORM.MAIL_SYNC_JOBS.JOB_STATE'
                   AND lifecycle_state = 'ACTIVE' ORDER BY code
                """, String.class)).containsExactly(
                "DEAD_LETTER", "FAILED", "LEASED", "QUEUED",
                "RETRY_WAIT", "SUCCEEDED");
        assertThat(jdbc.queryForList("""
                SELECT code FROM sys_code_values
                 WHERE code_set_key =
                       'PLATFORM.MAIL_PROVIDER_SUBSCRIPTIONS.SUBSCRIPTION_STATE'
                   AND lifecycle_state = 'ACTIVE' ORDER BY code
                """, String.class)).containsExactly(
                "ACTIVE", "EXPIRED", "FAILED", "PENDING", "RENEWAL_DUE",
                "RENEWING", "REVOKED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sys_code_sets code_set
                  JOIN sys_code_bindings binding
                    ON binding.code_set_key = code_set.code_set_key
                 WHERE code_set.code_set_key = 'PLATFORM.MAIL_SYNC_JOBS.JOB_STATE'
                   AND code_set.source_reference = 'mail_sync_jobs.job_state'
                   AND code_set.validation_source = 'CHECK'
                   AND binding.source_reference = 'mail_sync_jobs.job_state'
                   AND binding.enforcement_type = 'CHECK'
                   AND code_set.lifecycle_state = 'ACTIVE'
                   AND binding.lifecycle_state = 'ACTIVE'
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM pg_indexes
                 WHERE schemaname = current_schema()
                   AND indexname = 'idx_mail_domain_event_tenant_sequence'
                """, Integer.class)).isOne();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO mail_provider_subscriptions (
                    tenant_id, connection_id, account_id, actor_user_id,
                    provider_subscription_reference_hash, client_state_hash,
                    subscription_state, expires_at, renewal_due_at)
                VALUES (?, ?, ?, ?, 'raw-provider-reference', 'raw-client-state',
                        'REVOKED', CURRENT_TIMESTAMP + INTERVAL '1 hour',
                        CURRENT_TIMESTAMP)
                """, fixture.tenantId(), fixture.connectionId(), fixture.accountId(),
                jdbc.queryForObject("""
                        SELECT actor_id FROM mail_system_principals
                         WHERE tenant_id = ?
                           AND principal_key = 'MAIL_SUBSCRIPTION_RENEWAL'
                        """, Long.class, fixture.tenantId())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID operation(
            Fixture fixture,
            long actorId,
            String scope,
            String fingerprint) {
        UUID operationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO mail_connection_operations (
                    operation_id, tenant_id, connection_id, actor_user_id,
                    operation_kind, operation_scope, request_payload,
                    idempotency_key, request_fingerprint, correlation_id)
                VALUES (?, ?, ?, ?, 'SYNC', ?, '{}'::jsonb, ?, ?, 'test-sync')
                """, operationId, fixture.tenantId(), fixture.connectionId(),
                actorId, scope, UUID.randomUUID(), fingerprint);
        return operationId;
    }

    private List<Fixture> fixtures() {
        return jdbc.query("""
                WITH eligible_tenant AS (
                    SELECT account.tenant_id
                      FROM mail_accounts account
                     WHERE account.account_kind = 'PERSONAL'
                       AND account.owner_user_id IS NOT NULL
                     GROUP BY account.tenant_id
                    HAVING count(DISTINCT account.owner_user_id) >= 2
                     ORDER BY account.tenant_id LIMIT 1
                ), ranked AS (
                    SELECT account.tenant_id, account.account_id,
                           account.owner_user_id, account.email_address,
                           account.provider_account_ref,
                           account.synchronization_cursor,
                           account.synchronization_cursor_generation,
                           connection.connection_id, connection.provider_type,
                           connection.version connection_version,
                           thread.thread_id,
                           row_number() OVER (
                               PARTITION BY account.owner_user_id
                               ORDER BY thread.thread_id) row_number
                      FROM eligible_tenant eligible
                      JOIN mail_accounts account ON account.tenant_id = eligible.tenant_id
                      JOIN mail_provider_connections connection
                        ON connection.tenant_id = account.tenant_id
                       AND connection.connection_id = account.connection_id
                      JOIN mail_threads thread
                        ON thread.tenant_id = account.tenant_id
                       AND thread.account_id = account.account_id
                      JOIN mail_messages seeded_message
                        ON seeded_message.tenant_id = thread.tenant_id
                       AND seeded_message.thread_id = thread.thread_id
                     WHERE account.account_kind = 'PERSONAL'
                       AND account.connection_state = 'ACTIVE'
                       AND account.owner_user_id IS NOT NULL
                )
                SELECT * FROM ranked WHERE row_number = 1
                 ORDER BY owner_user_id LIMIT 2
                """, (result, ignored) -> new Fixture(
                result.getLong("tenant_id"),
                result.getObject("connection_id", UUID.class),
                result.getString("provider_type"),
                result.getLong("connection_version"),
                result.getObject("account_id", UUID.class),
                result.getLong("owner_user_id"),
                result.getString("email_address"),
                result.getString("provider_account_ref"),
                result.getString("synchronization_cursor"),
                result.getLong("synchronization_cursor_generation"),
                result.getObject("thread_id", UUID.class)));
    }

    private record Fixture(
            long tenantId,
            UUID connectionId,
            String providerType,
            long connectionVersion,
            UUID accountId,
            long ownerUserId,
            String email,
            String providerAccountReference,
            String cursor,
            long cursorGeneration,
            UUID threadId) {

        Fixture withConnectionVersion(long version) {
            return new Fixture(tenantId, connectionId, providerType, version,
                    accountId, ownerUserId, email, providerAccountReference,
                    cursor, cursorGeneration, threadId);
        }
    }
}
