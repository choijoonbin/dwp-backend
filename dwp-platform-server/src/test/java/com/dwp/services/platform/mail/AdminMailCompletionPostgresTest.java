package com.dwp.services.platform.mail;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventOutboxRepository;
import com.dwp.core.event.DomainEventRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.dwp.services.platform.mail.MailWorkspaceDtos.LegalHoldRequest;
import static com.dwp.services.platform.mail.MailWorkspaceDtos.PurgeApprovalRequest;
import static com.dwp.services.platform.mail.MailWorkspaceDtos.PurgeExecuteRequest;
import static com.dwp.services.platform.mail.MailWorkspaceDtos.PurgeJob;
import static com.dwp.services.platform.mail.MailWorkspaceDtos.PurgePreviewRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class AdminMailCompletionPostgresTest {

    @Container
    private final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void actionGrantIsRecheckedAlongsideLegacyMembership() {
        String schema = "mail_admin_access_matrix";
        migrate(schema);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource(schema));
        MailQueryRepository queries = new MailQueryRepository(
                jdbc, new MailJsonCodec(new ObjectMapper().findAndRegisterModules()));
        AccessFixture fixture = accessFixture(jdbc);

        assertThat(queries.thread(
                fixture.tenantId(), fixture.userId(), fixture.threadId())).isPresent();
        jdbc.update("""
                UPDATE mail_shared_inbox_access_grants
                   SET can_read = TRUE, can_send_as = FALSE,
                       can_send_on_behalf = FALSE, can_assign = FALSE, can_manage = FALSE,
                       expires_at = NULL, member_state = 'ACTIVE'
                 WHERE tenant_id = ? AND shared_inbox_id = ? AND user_id = ?
                """, fixture.tenantId(), fixture.inboxId(), fixture.userId());

        assertThat(queries.hasSharedInboxPermission(
                fixture.tenantId(), fixture.inboxId(), fixture.userId(),
                MailQueryRepository.SharedInboxPermission.READ)).isTrue();
        assertThat(queries.hasSharedInboxPermission(
                fixture.tenantId(), fixture.inboxId(), fixture.userId(),
                MailQueryRepository.SharedInboxPermission.SEND)).isFalse();

        jdbc.update("""
                UPDATE mail_shared_inbox_access_grants SET can_send_on_behalf = TRUE
                 WHERE tenant_id = ? AND shared_inbox_id = ? AND user_id = ?
                """, fixture.tenantId(), fixture.inboxId(), fixture.userId());
        assertThat(queries.hasSharedInboxPermission(
                fixture.tenantId(), fixture.inboxId(), fixture.userId(),
                MailQueryRepository.SharedInboxPermission.SEND)).isTrue();

        jdbc.update("""
                UPDATE mail_shared_inbox_access_grants SET can_read = FALSE
                 WHERE tenant_id = ? AND shared_inbox_id = ? AND user_id = ?
                """, fixture.tenantId(), fixture.inboxId(), fixture.userId());
        assertThat(queries.thread(
                fixture.tenantId(), fixture.userId(), fixture.threadId())).isEmpty();
    }

    @Test
    void purgeDeletesOnlyAfterFreshFingerprintAndTwoDistinctApprovals() {
        String schema = "mail_admin_purge_execution";
        migrate(schema);
        PGSimpleDataSource source = dataSource(schema);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AdminMailCompletionRepository repository =
                new AdminMailCompletionRepository(jdbc, objectMapper);
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(source);
        AdminMailCompletionService service = new AdminMailCompletionService(
                repository,
                new MailConnectorRegistry(List.of(new DwpSandboxMailConnector())),
                objectMapper);
        AdminMailPurgeWorker worker = purgeWorker(
                repository, transactions, source, objectMapper, 5);
        PurgeFixture fixture = purgeFixture(jdbc);
        long policyVersion = jdbc.queryForObject("""
                SELECT version FROM mail_tenant_policies WHERE tenant_id = ?
                """, Long.class, fixture.tenantId());

        var preview = service.previewPurge(
                fixture.tenantId(), 7001,
                new PurgePreviewRequest(
                        Map.of("tenant", true), List.of("THREADS", "MESSAGES"),
                        OffsetDateTime.now(ZoneOffset.UTC), UUID.randomUUID(), policyVersion));
        assertThat(preview.eligibleCount()).isGreaterThanOrEqualTo(2);

        service.approvePurge(
                fixture.tenantId(), 7002, preview.candidateSnapshotId(),
                new PurgeApprovalRequest("APPROVE", UUID.randomUUID(), policyVersion));
        var second = service.approvePurge(
                fixture.tenantId(), 7003, preview.candidateSnapshotId(),
                new PurgeApprovalRequest("APPROVE", UUID.randomUUID(), policyVersion));
        assertThat(second.distinctApproverCount()).isEqualTo(2);

        var accepted = service.executePurge(
                fixture.tenantId(), 7001, preview.candidateSnapshotId(), "purge-correlation",
                new PurgeExecuteRequest(
                        UUID.randomUUID(), policyVersion, preview.fingerprint()));

        assertThat(accepted.state()).isEqualTo("ACCEPTED");
        worker.executePending();
        var pendingPublication = service.purgeJob(fixture.tenantId(), accepted.jobId());
        assertThat(pendingPublication.state()).isEqualTo("PARTIAL");
        assertThat(pendingPublication.errorCode())
                .isEqualTo("PURGE_EVENT_PUBLICATION_PENDING");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_domain_event_outbox
                 WHERE tenant_id = ? AND aggregate_type = 'MAIL_PURGE_JOB'
                   AND aggregate_id = ? AND event_type = 'mail.purge.completed.v1'
                   AND status = 'PENDING'
                """, Integer.class, fixture.tenantId(), accepted.jobId().toString())).isOne();
        publishPurgeEvent(jdbc, fixture.tenantId(), accepted.jobId());
        worker.executePending();
        var job = service.purgeJob(fixture.tenantId(), accepted.jobId());
        assertThat(job.state()).isEqualTo("SUCCEEDED");
        assertThat(job.verificationState()).isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM mail_threads
                 WHERE tenant_id = ? AND thread_id = ?
                """, Integer.class, fixture.tenantId(), fixture.threadId())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM sys_tenant_media_cleanup_outbox
                 WHERE tenant_id = ? AND storage_key = 'mail/purge/unique.txt'
                   AND cleanup_reason = 'MAIL_RETENTION_PURGE'
                   AND cleanup_status = 'PENDING'
                """, Integer.class, fixture.tenantId())).isOne();
    }

    @Test
    void purgePreservesRestrictEvidenceAndDeletesTheRemainingEligibleSet() {
        String schema = "mail_admin_purge_evidence";
        migrateThroughMailAdmin(schema);
        PGSimpleDataSource source = dataSource(schema);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AdminMailCompletionRepository repository =
                new AdminMailCompletionRepository(jdbc, objectMapper);
        AdminMailCompletionService service = service(repository, objectMapper);
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(source);
        AdminMailPurgeWorker worker = purgeWorker(
                repository, transactions, source, objectMapper, 5);
        PurgeFixture eligible = purgeFixture(jdbc);
        PurgeFixture snapshotEvidence = additionalPurgeFixture(
                jdbc, eligible, "Purge snapshot evidence");
        PurgeFixture snapshotMessageEvidence = additionalPurgeFixture(
                jdbc, eligible, "Purge snapshot message evidence");
        PurgeFixture historyEvidence = additionalPurgeFixture(
                jdbc, eligible, "Purge history evidence");
        PurgeFixture receiptEvidence = additionalPurgeFixture(
                jdbc, eligible, "Purge draft receipt evidence");
        PurgeFixture backfillEvidence = additionalPurgeFixture(
                jdbc, eligible, "Purge backfill evidence");
        addImmutableEvidence(
                jdbc, snapshotEvidence, snapshotMessageEvidence,
                historyEvidence, receiptEvidence, backfillEvidence);
        long policyVersion = policyVersion(jdbc, eligible.tenantId());

        var preview = approvedPreview(service, eligible.tenantId(), policyVersion);
        AdminMailCompletionRepository.PurgePreviewRow storedPreview = repository
                .purgePreview(eligible.tenantId(), preview.candidateSnapshotId()).orElseThrow();
        AdminMailCompletionRepository.CandidateSet candidates = repository
                .purgeCandidates(eligible.tenantId(), storedPreview.before());

        assertThat(preview.totalCandidates()).isEqualTo(
                candidates.totalThreadCount() + candidates.totalMessageCount());
        assertThat(preview.heldCount()).isEqualTo(
                candidates.blockedThreadCount() + candidates.blockedMessageCount());
        assertThat(preview.eligibleCount()).isEqualTo(
                candidates.eligibleThreadCount() + candidates.eligibleMessageCount());
        assertThat(candidates.blockedThreadIds()).containsExactlyInAnyOrder(
                snapshotEvidence.threadId(), snapshotMessageEvidence.threadId(),
                historyEvidence.threadId(), receiptEvidence.threadId(),
                backfillEvidence.threadId());

        PurgeJob accepted = service.executePurge(
                eligible.tenantId(), 7001, preview.candidateSnapshotId(),
                "purge-evidence-correlation",
                new PurgeExecuteRequest(
                        UUID.randomUUID(), policyVersion, preview.fingerprint()));

        worker.executePending();
        publishPurgeEvent(jdbc, eligible.tenantId(), accepted.jobId());
        worker.executePending();
        PurgeJob job = service.purgeJob(eligible.tenantId(), accepted.jobId());
        assertThat(job.state()).isEqualTo("SUCCEEDED");
        assertThat(job.verificationState()).isEqualTo("VERIFIED");
        assertThat(job.stepResults()).anySatisfy(step -> {
            assertThat(step).containsEntry("step", "PRESERVE_IMMUTABLE_EVIDENCE")
                    .containsEntry("state", "SUCCEEDED");
            assertThat(((Number) step.get("blockedThreads")).intValue()).isEqualTo(5);
            assertThat(((Number) step.get("blockedMessages")).intValue()).isEqualTo(5);
        });
        assertThat(threadExists(jdbc, eligible)).isFalse();
        assertThat(List.of(
                snapshotEvidence, snapshotMessageEvidence, historyEvidence,
                receiptEvidence, backfillEvidence))
                .allSatisfy(fixture -> assertThat(threadExists(jdbc, fixture)).isTrue());
    }

    @Test
    void failedDeletePersistsTheFailedJobAfterPostgresConstraintAbort() {
        String schema = "mail_admin_purge_failure_receipt";
        migrateThroughMailAdmin(schema);
        PGSimpleDataSource source = dataSource(schema);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AdminMailCompletionRepository repository =
                new AdminMailCompletionRepository(jdbc, objectMapper);
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(source);
        AdminMailCompletionService service = new AdminMailCompletionService(
                repository,
                new MailConnectorRegistry(List.of(new DwpSandboxMailConnector())),
                objectMapper, null, null,
                new AdminMailPurgeTransactions(repository, transactions));
        PurgeFixture fixture = purgeFixture(jdbc);
        jdbc.execute("""
                CREATE TABLE test_mail_purge_restrict (
                    thread_id UUID PRIMARY KEY
                        REFERENCES mail_threads(thread_id) ON DELETE RESTRICT)
                """);
        jdbc.update("INSERT INTO test_mail_purge_restrict (thread_id) VALUES (?)",
                fixture.threadId());
        long policyVersion = policyVersion(jdbc, fixture.tenantId());
        var preview = approvedPreview(service, fixture.tenantId(), policyVersion);

        PurgeJob accepted = new TransactionTemplate(transactions).execute(status ->
                service.executePurge(
                        fixture.tenantId(), 7301, preview.candidateSnapshotId(),
                        "purge-failure-correlation",
                        new PurgeExecuteRequest(
                                UUID.randomUUID(), policyVersion, preview.fingerprint())));

        assertThat(accepted).isNotNull();
        assertThat(accepted.state()).isEqualTo("ACCEPTED");
        purgeWorker(repository, transactions, source, objectMapper, 1).executePending();
        PurgeJob job = service.purgeJob(fixture.tenantId(), accepted.jobId());
        assertThat(job.state()).isEqualTo("FAILED");
        assertThat(job.verificationState()).isEqualTo("UNKNOWN");
        assertThat(job.errorCode()).isEqualTo("LOCAL_DELETE_FAILED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM mail_purge_jobs
                 WHERE tenant_id = ? AND job_id = ?
                   AND job_state = 'FAILED'
                   AND verification_state = 'UNKNOWN'
                   AND error_code = 'LOCAL_DELETE_FAILED'
                """, Integer.class, fixture.tenantId(), job.jobId())).isOne();
        assertThat(threadExists(jdbc, fixture)).isTrue();
    }

    @Test
    void legalHoldCommitIsFencedAheadOfPurgeDeletion() throws Exception {
        String schema = "mail_admin_hold_purge_fence";
        migrateThroughMailAdmin(schema);
        PGSimpleDataSource source = dataSource(schema);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        CountDownLatch purgeLockAttempted = new CountDownLatch(1);
        AdminMailCompletionRepository repository = new AdminMailCompletionRepository(
                jdbc, objectMapper) {
            @Override
            void lockRetentionLifecycle(long tenantId) {
                if (Thread.currentThread().getName().startsWith("purge-test")) {
                    purgeLockAttempted.countDown();
                }
                super.lockRetentionLifecycle(tenantId);
            }
        };
        AdminMailCompletionService service = service(repository, objectMapper);
        PurgeFixture fixture = purgeFixture(jdbc);
        long policyVersion = policyVersion(jdbc, fixture.tenantId());
        var preview = approvedPreview(service, fixture.tenantId(), policyVersion);
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(source);
        CountDownLatch holdWritten = new CountDownLatch(1);
        CountDownLatch releaseHoldCommit = new CountDownLatch(1);
        ExecutorService holdExecutor = namedExecutor("hold-test");
        ExecutorService purgeExecutor = namedExecutor("purge-test");
        Future<?> hold = holdExecutor.submit(() -> new TransactionTemplate(transactions)
                .executeWithoutResult(status -> {
                    service.createLegalHold(
                            fixture.tenantId(), 7101, "hold-correlation",
                            new LegalHoldRequest(
                                    "Investigation hold", "CASE-SAFE-1",
                                    Map.of("tenant", true),
                                    OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1),
                                    null, UUID.randomUUID(), null));
                    holdWritten.countDown();
                    await(releaseHoldCommit);
                }));
        try {
            Future<Throwable> purge;
            try {
                assertThat(holdWritten.await(10, TimeUnit.SECONDS)).isTrue();
                purge = purgeExecutor.submit(() -> {
                    try {
                        new TransactionTemplate(transactions).execute(
                                status -> service.executePurge(
                                        fixture.tenantId(), 7102,
                                        preview.candidateSnapshotId(),
                                        "purge-correlation",
                                        new PurgeExecuteRequest(
                                                UUID.randomUUID(), policyVersion,
                                                preview.fingerprint())));
                        return null;
                    } catch (Throwable failure) {
                        return failure;
                    }
                });
                assertThat(purgeLockAttempted.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> purge.get(250, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                releaseHoldCommit.countDown();
            }
            hold.get(10, TimeUnit.SECONDS);
            Throwable failure = purge.get(10, TimeUnit.SECONDS);
            assertThat(failure).isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("ACTIVE_LEGAL_HOLD_BLOCKS_PURGE");
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM mail_threads
                     WHERE tenant_id = ? AND thread_id = ?
                    """, Integer.class, fixture.tenantId(), fixture.threadId())).isOne();
        } finally {
            releaseHoldCommit.countDown();
            holdExecutor.shutdownNow();
            purgeExecutor.shutdownNow();
        }
    }

    @Test
    void concurrentPurgeCommandsExecuteOneSnapshotOnlyOnce() throws Exception {
        String schema = "mail_admin_concurrent_purge_fence";
        migrateThroughMailAdmin(schema);
        PGSimpleDataSource source = dataSource(schema);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AdminMailCompletionRepository repository =
                new AdminMailCompletionRepository(jdbc, objectMapper);
        AdminMailCompletionService service = service(repository, objectMapper);
        PurgeFixture fixture = purgeFixture(jdbc);
        long policyVersion = policyVersion(jdbc, fixture.tenantId());
        var preview = approvedPreview(service, fixture.tenantId(), policyVersion);
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(source);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = executor.submit(() -> executePurge(
                    transactions, service, fixture.tenantId(), 7201, preview, policyVersion, start));
            Future<Object> second = executor.submit(() -> executePurge(
                    transactions, service, fixture.tenantId(), 7202, preview, policyVersion, start));
            start.countDown();

            List<Object> results = List.of(
                    first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(results).filteredOn(PurgeJob.class::isInstance).hasSize(1);
            assertThat(results).filteredOn(ResponseStatusException.class::isInstance).hasSize(1);
            assertThat(results.stream()
                    .filter(ResponseStatusException.class::isInstance)
                    .map(ResponseStatusException.class::cast)
                    .findFirst().orElseThrow())
                    .hasMessageContaining("PURGE_SNAPSHOT_ALREADY_EXECUTED");
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM mail_purge_jobs
                     WHERE tenant_id = ? AND candidate_snapshot_id = ?
                    """, Integer.class, fixture.tenantId(), preview.candidateSnapshotId())).isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    private AdminMailCompletionService service(
            AdminMailCompletionRepository repository, ObjectMapper objectMapper) {
        return new AdminMailCompletionService(
                repository,
                new MailConnectorRegistry(List.of(new DwpSandboxMailConnector())),
                objectMapper);
    }

    private AdminMailPurgeWorker purgeWorker(
            AdminMailCompletionRepository repository,
            DataSourceTransactionManager transactions,
            PGSimpleDataSource source,
            ObjectMapper objectMapper,
            int maximumAttempts) {
        DomainEventContractRegistry contracts = new DomainEventContractRegistry();
        DomainEventRecorder recorder = new DomainEventRecorder(
                new DomainEventOutboxRepository(
                        new NamedParameterJdbcTemplate(source), objectMapper),
                contracts,
                objectMapper);
        return new AdminMailPurgeWorker(
                repository,
                new AdminMailPurgeTransactions(repository, transactions),
                (tenantId, actorId, jobId) -> new MailPurgeExecutionAuthority.Decision(
                        MailPurgeExecutionAuthority.State.ALLOWED,
                        "test-authority:" + actorId,
                        "test-revision",
                        "TEST_AUTHORITY_ALLOWED"),
                new MailPurgeDomainEvents(recorder, contracts, objectMapper),
                true, 5, 30, maximumAttempts, "mail-purge-test");
    }

    private void publishPurgeEvent(JdbcTemplate jdbc, long tenantId, UUID jobId) {
        assertThat(jdbc.update("""
                UPDATE sys_domain_event_outbox
                   SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND aggregate_type = 'MAIL_PURGE_JOB'
                   AND aggregate_id = ? AND event_type = 'mail.purge.completed.v1'
                   AND status = 'PENDING'
                """, tenantId, jobId.toString())).isOne();
    }

    private MailWorkspaceDtos.PurgePreview approvedPreview(
            AdminMailCompletionService service, long tenantId, long policyVersion) {
        var preview = service.previewPurge(
                tenantId, 7001,
                new PurgePreviewRequest(
                        Map.of("tenant", true), List.of("THREADS", "MESSAGES"),
                        OffsetDateTime.now(ZoneOffset.UTC), UUID.randomUUID(), policyVersion));
        service.approvePurge(
                tenantId, 7002, preview.candidateSnapshotId(),
                new PurgeApprovalRequest("APPROVE", UUID.randomUUID(), policyVersion));
        service.approvePurge(
                tenantId, 7003, preview.candidateSnapshotId(),
                new PurgeApprovalRequest("APPROVE", UUID.randomUUID(), policyVersion));
        return preview;
    }

    private Object executePurge(
            DataSourceTransactionManager transactions,
            AdminMailCompletionService service,
            long tenantId,
            long actorId,
            MailWorkspaceDtos.PurgePreview preview,
            long policyVersion,
            CountDownLatch start) {
        await(start);
        try {
            return new TransactionTemplate(transactions).execute(status -> service.executePurge(
                    tenantId, actorId, preview.candidateSnapshotId(), "purge-" + actorId,
                    new PurgeExecuteRequest(
                            UUID.randomUUID(), policyVersion, preview.fingerprint())));
        } catch (Throwable failure) {
            return failure;
        }
    }

    private long policyVersion(JdbcTemplate jdbc, long tenantId) {
        return jdbc.queryForObject("""
                SELECT version FROM mail_tenant_policies WHERE tenant_id = ?
                """, Long.class, tenantId);
    }

    private ExecutorService namedExecutor(String name) {
        return Executors.newSingleThreadExecutor(task -> new Thread(task, name));
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent mail test");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during concurrent mail test", interrupted);
        }
    }

    private AccessFixture accessFixture(JdbcTemplate jdbc) {
        return jdbc.query("""
                SELECT thread.tenant_id, member.user_id, thread.thread_id,
                       thread.shared_inbox_id
                  FROM mail_threads thread
                  JOIN mail_shared_inbox_members member
                    ON member.tenant_id = thread.tenant_id
                   AND member.shared_inbox_id = thread.shared_inbox_id
                   AND member.lifecycle_state = 'ACTIVE'
                  JOIN mail_shared_inbox_access_grants access_grant
                    ON access_grant.tenant_id = member.tenant_id
                   AND access_grant.shared_inbox_id = member.shared_inbox_id
                   AND access_grant.user_id = member.user_id
                 WHERE thread.shared_inbox_id IS NOT NULL
                 ORDER BY thread.thread_id, member.user_id LIMIT 1
                """, result -> {
            if (!result.next()) throw new IllegalStateException("Shared mail fixture missing");
            return new AccessFixture(
                    result.getLong("tenant_id"), result.getLong("user_id"),
                    result.getObject("thread_id", UUID.class),
                    result.getObject("shared_inbox_id", UUID.class));
        });
    }

    private PurgeFixture purgeFixture(JdbcTemplate jdbc) {
        return jdbc.query("""
                SELECT account.tenant_id, account.owner_user_id,
                       account.account_id, folder.folder_id
                  FROM mail_accounts account
                  JOIN mail_provider_connections connection
                    ON connection.tenant_id = account.tenant_id
                   AND connection.connection_id = account.connection_id
                   AND connection.provider_type = 'DWP_SANDBOX'
                  JOIN mail_folders folder
                    ON folder.tenant_id = account.tenant_id
                   AND folder.account_id = account.account_id
                   AND folder.lifecycle_state = 'ACTIVE'
                 WHERE account.account_kind = 'PERSONAL'
                 ORDER BY account.tenant_id, account.account_id, folder.sort_order
                 LIMIT 1
                """, result -> {
            if (!result.next()) throw new IllegalStateException("Sandbox account fixture missing");
            long tenantId = result.getLong("tenant_id");
            long ownerUserId = result.getLong("owner_user_id");
            UUID accountId = result.getObject("account_id", UUID.class);
            UUID folderId = result.getObject("folder_id", UUID.class);
            PurgeFixture fixture = insertPurgeThread(
                    jdbc, tenantId, ownerUserId, accountId, folderId, "Purge fixture");
            jdbc.update("""
                    INSERT INTO mail_compose_attachments (
                        tenant_id, uploader_user_id, thread_id, storage_reference,
                        file_name, content_type, size_bytes, checksum_sha256,
                        scan_state, scan_evidence)
                    VALUES (?, 7001, ?, 'mail/purge/unique.txt', 'unique.txt',
                            'text/plain', 4, ?, 'READY', 'fixture:clean')
                    """, tenantId, fixture.threadId(), "a".repeat(64));
            return fixture;
        });
    }

    private PurgeFixture additionalPurgeFixture(
            JdbcTemplate jdbc, PurgeFixture base, String subject) {
        return insertPurgeThread(
                jdbc, base.tenantId(), base.ownerUserId(), base.accountId(),
                base.folderId(), subject);
    }

    private PurgeFixture insertPurgeThread(
            JdbcTemplate jdbc,
            long tenantId,
            long ownerUserId,
            UUID accountId,
            UUID folderId,
            String subject) {
        UUID threadId = jdbc.queryForObject("""
                INSERT INTO mail_threads (
                    tenant_id, account_id, folder_id, subject, preview, participants,
                    latest_message_at, workflow_state, trashed_at, message_count, created_by,
                    updated_by, updated_at)
                VALUES (?, ?, ?, ?, ?, '[]'::jsonb,
                        CURRENT_TIMESTAMP - INTERVAL '500 days', 'TRASHED',
                        CURRENT_TIMESTAMP - INTERVAL '500 days', 1, 7001,
                        7001, CURRENT_TIMESTAMP - INTERVAL '500 days')
                RETURNING thread_id
                """, UUID.class, tenantId, accountId, folderId, subject, subject);
        UUID messageId = jdbc.queryForObject("""
                INSERT INTO mail_messages (
                    tenant_id, thread_id, sender_email, sender_name, recipients,
                    message_direction, body_format, body_content, sent_at, created_by)
                VALUES (?, ?, 'purge@example.test', 'Purge fixture', '[]'::jsonb,
                        'INBOUND', 'TEXT', 'Purge fixture body',
                        CURRENT_TIMESTAMP - INTERVAL '500 days', 7001)
                RETURNING message_id
                """, UUID.class, tenantId, threadId);
        return new PurgeFixture(
                tenantId, ownerUserId, accountId, folderId, threadId, messageId);
    }

    private void addImmutableEvidence(
            JdbcTemplate jdbc,
            PurgeFixture snapshotEvidence,
            PurgeFixture snapshotMessageEvidence,
            PurgeFixture historyEvidence,
            PurgeFixture receiptEvidence,
            PurgeFixture backfillEvidence) {
        UUID groupId = jdbc.queryForObject("""
                INSERT INTO mail_contact_groups (
                    tenant_id, owner_user_id, create_request_id, display_name,
                    created_by, updated_by)
                VALUES (?, ?, ?, 'Purge evidence group', ?, ?)
                RETURNING group_id
                """, UUID.class, snapshotEvidence.tenantId(), snapshotEvidence.ownerUserId(),
                UUID.randomUUID(), snapshotEvidence.ownerUserId(),
                snapshotEvidence.ownerUserId());
        UUID deliveryId = jdbc.queryForObject("""
                INSERT INTO mail_delivery_outbox (
                    tenant_id, thread_id, message_id, idempotency_key, created_by)
                VALUES (?, ?, ?, ?, ?)
                RETURNING delivery_id
                """, UUID.class, snapshotEvidence.tenantId(), snapshotEvidence.threadId(),
                snapshotMessageEvidence.messageId(), UUID.randomUUID(),
                snapshotEvidence.ownerUserId());
        jdbc.update("""
                INSERT INTO mail_group_recipient_snapshots (
                    delivery_id, tenant_id, owner_user_id, thread_id, message_id,
                    group_id, group_version, recipient_count, recipients, recipients_sha256)
                VALUES (?, ?, ?, ?, ?, ?, 0, 1,
                        '[{"email":"recipient@example.test"}]'::jsonb, ?)
                """, deliveryId, snapshotEvidence.tenantId(),
                snapshotEvidence.ownerUserId(), snapshotEvidence.threadId(),
                snapshotMessageEvidence.messageId(), groupId, "b".repeat(64));
        jdbc.update("""
                INSERT INTO mail_group_send_history (
                    tenant_id, owner_user_id, group_id, group_version,
                    recipient_mode, recipient_count, thread_id, receipt_state)
                VALUES (?, ?, ?, 0, 'TO', 1, ?, 'ACCEPTED')
                """, historyEvidence.tenantId(), historyEvidence.ownerUserId(),
                groupId, historyEvidence.threadId());
        jdbc.update("""
                INSERT INTO mail_draft_command_receipts (
                    tenant_id, actor_user_id, command_type, idempotency_key,
                    request_fingerprint, thread_id, applied_version,
                    command_status, completed_at)
                VALUES (?, ?, 'CREATE', ?, ?, ?, 0, 'COMPLETED', CURRENT_TIMESTAMP)
                """, receiptEvidence.tenantId(), receiptEvidence.ownerUserId(),
                UUID.randomUUID(), "c".repeat(64), receiptEvidence.threadId());

        Map<String, Object> rule = jdbc.queryForMap("""
                SELECT rule_id, version FROM mail_rules
                 WHERE tenant_id = ? AND account_id = ?
                 ORDER BY priority, rule_id LIMIT 1
                """, backfillEvidence.tenantId(), backfillEvidence.accountId());
        UUID executionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO mail_rule_backfill_executions (
                    execution_id, tenant_id, account_id, owner_user_id,
                    request_id, request_fingerprint, preview_fingerprint,
                    generation, lease_token, lease_expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, CURRENT_TIMESTAMP + INTERVAL '5 minutes')
                """, executionId, backfillEvidence.tenantId(),
                backfillEvidence.accountId(), backfillEvidence.ownerUserId(),
                UUID.randomUUID(), "d".repeat(64), "e".repeat(64), UUID.randomUUID());
        jdbc.update("""
                INSERT INTO mail_rule_backfill_applications (
                    execution_id, tenant_id, account_id, thread_id, rule_id,
                    rule_version, before_thread_version, after_thread_version, changed)
                VALUES (?, ?, ?, ?, ?, ?, 0, 0, FALSE)
                """, executionId, backfillEvidence.tenantId(),
                backfillEvidence.accountId(), backfillEvidence.threadId(),
                rule.get("rule_id"), ((Number) rule.get("version")).longValue());
    }

    private boolean threadExists(JdbcTemplate jdbc, PurgeFixture fixture) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM mail_threads
                 WHERE tenant_id = ? AND thread_id = ?
                """, Integer.class, fixture.tenantId(), fixture.threadId()) == 1;
    }

    private void migrate(String schema) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource())
                .schemas(schema)
                .defaultSchema(schema)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    private void migrateThroughMailAdmin(String schema) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource())
                .schemas(schema)
                .defaultSchema(schema)
                .locations(
                        "filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .target("305")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    private PGSimpleDataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(postgres.getJdbcUrl());
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        return source;
    }

    private PGSimpleDataSource dataSource(String schema) {
        PGSimpleDataSource source = dataSource();
        source.setCurrentSchema(schema);
        return source;
    }

    private record AccessFixture(long tenantId, long userId, UUID threadId, UUID inboxId) { }
    private record PurgeFixture(
            long tenantId,
            long ownerUserId,
            UUID accountId,
            UUID folderId,
            UUID threadId,
            UUID messageId) { }
}
