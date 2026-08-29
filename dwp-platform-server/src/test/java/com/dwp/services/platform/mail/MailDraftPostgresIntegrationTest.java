package com.dwp.services.platform.mail;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class MailDraftPostgresIntegrationTest {

    @Container
    private final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void partialDraftPersistsWithoutRecipientAndHonorsCreateAndSaveIdempotency() {
        String schema = "mail_partial_draft";
        migrate(schema);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource(schema));
        MailDraftRepository drafts = new MailDraftRepository(jdbc);
        MailDraftCommandReceiptRepository receipts =
                new MailDraftCommandReceiptRepository(jdbc);
        MailDraftCommandFingerprint fingerprints = new MailDraftCommandFingerprint();
        Owner owner = owner(jdbc);
        UUID createKey = UUID.randomUUID();
        var create = new MailDtos.DraftSaveRequest(
                null, null, "회의 준비", null, createKey, null);
        String createFingerprint = fingerprints.create(create);
        assertThat(receipts.reserve(
                owner.tenantId(), owner.userId(),
                MailDraftCommandReceiptRepository.CommandType.CREATE,
                createKey, createFingerprint).inserted()).isTrue();

        MailDraftRepository.CreateResult created = drafts.create(
                owner.tenantId(), owner.userId(), create);
        assertThat(created).isNotNull();
        assertThat(created.created()).isTrue();
        assertThat(drafts.create(owner.tenantId(), owner.userId(), create))
                .isEqualTo(new MailDraftRepository.CreateResult(created.threadId(), false));
        receipts.complete(
                owner.tenantId(), owner.userId(),
                MailDraftCommandReceiptRepository.CommandType.CREATE,
                createKey, createFingerprint, created.threadId(), 0L);
        MailDraftCommandReceiptRepository.Receipt createReplay = receipts.reserve(
                owner.tenantId(), owner.userId(),
                MailDraftCommandReceiptRepository.CommandType.CREATE,
                createKey, createFingerprint);
        assertThat(createReplay.completed()).isTrue();
        assertThat(createReplay.threadId()).isEqualTo(created.threadId());

        Map<String, Object> initial = jdbc.queryForMap("""
                SELECT thread.subject, thread.preview, thread.participants::text,
                       thread.workflow_state, thread.version,
                       message.recipients::text, message.body_content
                  FROM mail_threads thread
                  JOIN mail_messages message
                    ON message.tenant_id = thread.tenant_id
                   AND message.thread_id = thread.thread_id
                 WHERE thread.tenant_id = ? AND thread.thread_id = ?
                """, owner.tenantId(), created.threadId());
        assertThat(initial)
                .containsEntry("subject", "회의 준비")
                .containsEntry("preview", "")
                .containsEntry("participants", "[]")
                .containsEntry("workflow_state", "DRAFT")
                .containsEntry("version", 0L)
                .containsEntry("recipients", "[]")
                .containsEntry("body_content", "");

        UUID saveKey = UUID.randomUUID();
        var save = new MailDtos.DraftSaveRequest(
                null, null, null, "부분 본문", saveKey, 0L);
        String saveFingerprint = fingerprints.save(created.threadId(), save);
        assertThat(receipts.reserve(
                owner.tenantId(), owner.userId(),
                MailDraftCommandReceiptRepository.CommandType.SAVE,
                saveKey, saveFingerprint).inserted()).isTrue();
        assertThat(drafts.save(
                owner.tenantId(), owner.userId(), created.threadId(), save)).isOne();
        receipts.complete(
                owner.tenantId(), owner.userId(),
                MailDraftCommandReceiptRepository.CommandType.SAVE,
                saveKey, saveFingerprint, created.threadId(), 1L);
        assertThat(receipts.reserve(
                owner.tenantId(), owner.userId(),
                MailDraftCommandReceiptRepository.CommandType.SAVE,
                saveKey, saveFingerprint).completed()).isTrue();
        assertThat(drafts.save(
                owner.tenantId(), owner.userId(), created.threadId(), save)).isZero();

        var drift = new MailDtos.DraftSaveRequest(
                null, null, null, "다른 본문", saveKey, 0L);
        MailDraftCommandReceiptRepository.Receipt driftReceipt = receipts.reserve(
                owner.tenantId(), owner.userId(),
                MailDraftCommandReceiptRepository.CommandType.SAVE,
                saveKey, fingerprints.save(created.threadId(), drift));
        assertThat(driftReceipt.requestFingerprint()).isEqualTo(saveFingerprint);
        assertThat(driftReceipt.inserted()).isFalse();

        Map<String, Object> saved = jdbc.queryForMap("""
                SELECT thread.subject, thread.preview, thread.participants::text,
                       thread.version, message.recipients::text, message.body_content
                  FROM mail_threads thread
                  JOIN mail_messages message
                    ON message.tenant_id = thread.tenant_id
                   AND message.thread_id = thread.thread_id
                 WHERE thread.tenant_id = ? AND thread.thread_id = ?
                """, owner.tenantId(), created.threadId());
        assertThat(saved)
                .containsEntry("subject", "")
                .containsEntry("preview", "부분 본문")
                .containsEntry("participants", "[]")
                .containsEntry("version", 1L)
                .containsEntry("recipients", "[]")
                .containsEntry("body_content", "부분 본문");
    }

    @Test
    void commandReceiptSerializesConcurrentReplayAndRollsBackFailedReservation()
            throws Exception {
        String schema = "mail_draft_receipt";
        migrate(schema);
        PGSimpleDataSource source = dataSource(schema);
        MailDraftCommandReceiptRepository receipts =
                new MailDraftCommandReceiptRepository(new JdbcTemplate(source));
        TransactionTemplate transaction =
                new TransactionTemplate(new DataSourceTransactionManager(source));
        Owner owner = owner(new JdbcTemplate(source));
        UUID key = UUID.randomUUID();
        String fingerprint = "a".repeat(64);
        CountDownLatch reserved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> transaction.execute(status -> {
                MailDraftCommandReceiptRepository.Receipt receipt = receipts.reserve(
                        owner.tenantId(), owner.userId(),
                        MailDraftCommandReceiptRepository.CommandType.SAVE,
                        key, fingerprint);
                reserved.countDown();
                await(release);
                receipts.complete(
                        owner.tenantId(), owner.userId(),
                        MailDraftCommandReceiptRepository.CommandType.SAVE,
                        key, fingerprint, null, 4L);
                return receipt;
            }));
            assertThat(reserved.await(10, TimeUnit.SECONDS)).isTrue();
            var replay = workers.submit(() -> transaction.execute(status -> receipts.reserve(
                    owner.tenantId(), owner.userId(),
                    MailDraftCommandReceiptRepository.CommandType.SAVE,
                    key, fingerprint)));
            release.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS).inserted()).isTrue();
            MailDraftCommandReceiptRepository.Receipt replayed =
                    replay.get(10, TimeUnit.SECONDS);
            assertThat(replayed.inserted()).isFalse();
            assertThat(replayed.completed()).isTrue();
            assertThat(replayed.appliedVersion()).isEqualTo(4L);
        }

        UUID rollbackKey = UUID.randomUUID();
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            receipts.reserve(
                    owner.tenantId(), owner.userId(),
                    MailDraftCommandReceiptRepository.CommandType.CREATE,
                    rollbackKey, "b".repeat(64));
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        MailDraftCommandReceiptRepository.Receipt retried = transaction.execute(status ->
                receipts.reserve(
                        owner.tenantId(), owner.userId(),
                        MailDraftCommandReceiptRepository.CommandType.CREATE,
                        rollbackKey, "b".repeat(64)));
        assertThat(retried).isNotNull();
        assertThat(retried.inserted()).isTrue();
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent receipt test.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for receipt test.", exception);
        }
    }

    private Owner owner(JdbcTemplate jdbc) {
        return jdbc.query("""
                SELECT tenant_id, owner_user_id
                  FROM mail_accounts
                 WHERE account_kind = 'PERSONAL'
                   AND owner_user_id IS NOT NULL
                   AND is_default = TRUE
                   AND connection_state = 'ACTIVE'
                 ORDER BY tenant_id, owner_user_id
                 LIMIT 1
                """, result -> {
            if (!result.next()) throw new IllegalStateException("Personal mail fixture is missing.");
            return new Owner(result.getLong("tenant_id"), result.getLong("owner_user_id"));
        });
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

    private record Owner(Long tenantId, Long userId) {
    }
}
