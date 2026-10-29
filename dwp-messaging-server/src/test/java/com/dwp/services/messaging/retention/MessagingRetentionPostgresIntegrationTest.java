package com.dwp.services.messaging.retention;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "DWP_MESSAGING_INTEGRATION_DB_URL", matches = ".+")
class MessagingRetentionPostgresIntegrationTest {

    private static final long TENANT_ID = 99_518;
    private static JdbcTemplate jdbc;
    private static MessagingRetentionRepository repository;
    private static TransactionTemplate transactions;

    private UUID conversationId;
    private UUID messageId;

    @BeforeAll
    static void migrate() {
        var source = new DriverManagerDataSource(
                System.getenv("DWP_MESSAGING_INTEGRATION_DB_URL"),
                System.getenv().getOrDefault(
                        "DWP_MESSAGING_INTEGRATION_DB_USERNAME", "postgres"),
                System.getenv().getOrDefault(
                        "DWP_MESSAGING_INTEGRATION_DB_PASSWORD", "postgres"));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        repository = new MessagingRetentionRepository(jdbc);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @BeforeEach
    void seed() {
        jdbc.update("""
                DELETE FROM msg_audit_events
                 WHERE tenant_id = ? AND object_type = 'MSG_RETENTION_RUN'
                """, TENANT_ID);
        jdbc.update("DELETE FROM msg_retention_attachment_deletions WHERE tenant_id = ?", TENANT_ID);
        jdbc.update("DELETE FROM msg_retention_runs WHERE tenant_id = ?", TENANT_ID);
        jdbc.update("DELETE FROM msg_retention_holds WHERE tenant_id = ?", TENANT_ID);
        jdbc.update("DELETE FROM msg_conversations WHERE tenant_id = ?", TENANT_ID);
        jdbc.update("""
                INSERT INTO msg_tenant_policies (tenant_id, retention_days)
                VALUES (?, 365)
                ON CONFLICT (tenant_id) DO UPDATE SET retention_days = EXCLUDED.retention_days
                """, TENANT_ID);
        jdbc.update("""
                INSERT INTO msg_people_snapshot (
                    tenant_id, user_id, email_address, display_name)
                VALUES (?, 100, 'retention@example.com', 'Retention Sender')
                ON CONFLICT (tenant_id, user_id) DO UPDATE
                    SET lifecycle_state = 'ACTIVE'
                """, TENANT_ID);
        conversationId = UUID.randomUUID();
        messageId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO msg_conversations (
                    conversation_id, tenant_id, conversation_key, conversation_type,
                    name, visibility, data_classification)
                VALUES (?, ?, ?, 'GROUP', 'Retention', 'PRIVATE', 'INTERNAL')
                """, conversationId, TENANT_ID, "retention:" + conversationId);
        jdbc.update("""
                INSERT INTO msg_conversation_members (
                    tenant_id, conversation_id, user_id, member_role)
                VALUES (?, ?, 100, 'OWNER')
                """, TENANT_ID, conversationId);
        jdbc.update("""
                INSERT INTO msg_messages (
                    message_id, tenant_id, conversation_id, sequence, sender_user_id,
                    sender_name, body, created_at)
                VALUES (?, ?, ?, 1, 100, 'Retention Sender', 'sensitive content',
                        CURRENT_TIMESTAMP - INTERVAL '400 days')
                """, messageId, TENANT_ID, conversationId);
    }

    @Test
    void activeHoldExcludesAndRecheckBlocksTheMessage() {
        jdbc.update("""
                INSERT INTO msg_retention_holds (
                    tenant_id, conversation_id, reason_reference, created_by)
                VALUES (?, ?, 'legal-case-1', 900)
                """, TENANT_ID, conversationId);
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(365);

        MessagingRetentionRepository.CandidateStats stats =
                repository.candidateStats(TENANT_ID, cutoff);
        Optional<MessagingRetentionRepository.LockedMessage> locked = transactions.execute(
                status -> repository.lockEligibleMessage(TENANT_ID, messageId, cutoff));

        assertThat(stats.candidateCount()).isEqualTo(1);
        assertThat(stats.heldCount()).isEqualTo(1);
        assertThat(repository.nextCandidates(TENANT_ID, cutoff, 10)).isEmpty();
        assertThat(locked).isEmpty();
    }

    @Test
    void contentPurgeAtomicallyStagesProviderDeletionAndPreservesStructure() {
        UUID attachmentId = UUID.randomUUID();
        String objectKey = TENANT_ID + "/" + attachmentId;
        jdbc.update("""
                INSERT INTO msg_attachments (
                    attachment_id, tenant_id, conversation_id, uploader_user_id, message_id,
                    original_filename, normalized_filename, file_extension,
                    declared_content_type, detected_content_type, size_bytes, object_key,
                    content_sha256, status, idempotency_key, request_hash,
                    upload_token_hash, upload_expires_at)
                VALUES (?, ?, ?, 100, ?, 'evidence.pdf', 'evidence.pdf', 'pdf',
                        'application/pdf', 'application/pdf', 10, ?, ?, 'CLEAN', ?, ?, ?,
                        CURRENT_TIMESTAMP + INTERVAL '10 minutes')
                """, attachmentId, TENANT_ID, conversationId, messageId, objectKey,
                "a".repeat(64), UUID.randomUUID(), "b".repeat(64), "c".repeat(64));
        UUID workerId = UUID.randomUUID();
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(365);
        UUID runId = transactions.execute(status -> repository.tryStartRun(
                TENANT_ID, "ENFORCING", cutoff, workerId, Duration.ofMinutes(15)))
                .orElseThrow();

        Integer changed = transactions.execute(status -> {
            assertThat(repository.lockEligibleMessage(TENANT_ID, messageId, cutoff)).isPresent();
            return repository.purgeMessageContent(TENANT_ID, runId, messageId);
        });

        assertThat(changed).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT body FROM msg_messages WHERE message_id = ?", String.class, messageId))
                .isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT retention_purged_at IS NOT NULL
                  FROM msg_messages WHERE message_id = ?
                """, Boolean.class, messageId)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM msg_attachments WHERE attachment_id = ?",
                Integer.class, attachmentId)).isZero();
        MessagingRetentionRepository.PendingAttachmentDeletion deletion =
                repository.pendingAttachmentDeletions(TENANT_ID, 10).getFirst();
        assertThat(deletion.objectKey()).isEqualTo(objectKey);
        repository.completeAttachmentDeletion(deletion.deletionId());
        assertThat(jdbc.queryForObject("""
                SELECT object_key FROM msg_retention_attachment_deletions
                 WHERE deletion_id = ?
                """, String.class, deletion.deletionId()))
                .isEqualTo("deleted/" + deletion.deletionId());

        Boolean finished = transactions.execute(status -> {
            boolean updated = repository.finishRun(
                    runId, workerId, "COMPLETED", 1, 1, 0, 0, null);
            repository.recordAudit(TENANT_ID, runId, "COMPLETED", 1, 1, 0, 0, null);
            return updated;
        });
        assertThat(finished).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM msg_audit_events
                 WHERE tenant_id = ? AND object_type = 'MSG_RETENTION_RUN'
                """, Integer.class, TENANT_ID)).isEqualTo(1);
    }

    @Test
    void continuityCleanupDeletesExpiredDraftAndRedactsTerminalScheduleWithoutLosingEvidence() {
        UUID draftId = UUID.randomUUID();
        UUID scheduledId = UUID.randomUUID();
        String originalRequestHash = "d".repeat(64);
        jdbc.update("""
                INSERT INTO msg_message_drafts (
                    draft_id, tenant_id, user_id, conversation_id, body,
                    mentioned_user_ids, updated_at)
                VALUES (?, ?, 100, ?, 'private draft', ARRAY[200]::BIGINT[],
                        CURRENT_TIMESTAMP - INTERVAL '400 days')
                """, draftId, TENANT_ID, conversationId);
        jdbc.update("""
                INSERT INTO msg_scheduled_messages (
                    scheduled_message_id, tenant_id, sender_user_id, conversation_id,
                    body, mentioned_user_ids, request_idempotency_key, send_idempotency_key,
                    request_hash, scheduled_for, display_time_zone, status,
                    next_attempt_at, delivered_message_id, delivered_at, failure_detail,
                    updated_at)
                VALUES (?, ?, 100, ?, 'scheduled secret', ARRAY[200]::BIGINT[], ?, ?, ?,
                        CURRENT_TIMESTAMP - INTERVAL '401 days', 'UTC', 'SENT',
                        CURRENT_TIMESTAMP - INTERVAL '401 days', ?,
                        CURRENT_TIMESTAMP - INTERVAL '400 days', 'private detail',
                        CURRENT_TIMESTAMP - INTERVAL '400 days')
                """, scheduledId, TENANT_ID, conversationId, UUID.randomUUID(),
                UUID.randomUUID(), originalRequestHash, messageId);
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(365);

        MessagingRetentionRepository.CandidateStats stats =
                repository.candidateStats(TENANT_ID, cutoff);
        MessagingRetentionRepository.ContinuityPurgeResult result = transactions.execute(
                status -> repository.purgeContinuityContent(TENANT_ID, cutoff, 100));

        assertThat(stats.candidateCount()).isEqualTo(3);
        assertThat(result.deletedDrafts()).isOne();
        assertThat(result.redactedSchedules()).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM msg_message_drafts WHERE draft_id = ?",
                Integer.class, draftId)).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT body, cardinality(mentioned_user_ids) AS mention_count,
                       failure_detail, payload_purged_at IS NOT NULL AS purged,
                       status, delivered_message_id, request_hash
                  FROM msg_scheduled_messages WHERE scheduled_message_id = ?
                """, scheduledId))
                .containsEntry("body", "")
                .containsEntry("mention_count", 0)
                .containsEntry("failure_detail", null)
                .containsEntry("purged", true)
                .containsEntry("status", "SENT")
                .containsEntry("delivered_message_id", messageId)
                .containsEntry(
                        "request_hash",
                        sha256("msg-scheduled-payload-purged:" + scheduledId));
        assertThat(jdbc.queryForObject("""
                SELECT request_hash FROM msg_scheduled_messages
                 WHERE scheduled_message_id = ?
                """, String.class, scheduledId)).isNotEqualTo(originalRequestHash);
    }

    @Test
    void continuityCleanupRespectsConversationHoldAtExecutionTime() {
        UUID draftId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO msg_message_drafts (
                    draft_id, tenant_id, user_id, conversation_id, body, updated_at)
                VALUES (?, ?, 100, ?, 'held draft',
                        CURRENT_TIMESTAMP - INTERVAL '400 days')
                """, draftId, TENANT_ID, conversationId);
        jdbc.update("""
                INSERT INTO msg_retention_holds (
                    tenant_id, conversation_id, reason_reference, created_by)
                VALUES (?, ?, 'legal-case-continuity', 900)
                """, TENANT_ID, conversationId);
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(365);

        MessagingRetentionRepository.ContinuityPurgeResult result = transactions.execute(
                status -> repository.purgeContinuityContent(TENANT_ID, cutoff, 100));

        assertThat(result.total()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT body FROM msg_message_drafts WHERE draft_id = ?",
                String.class, draftId)).isEqualTo("held draft");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", exception);
        }
    }
}
