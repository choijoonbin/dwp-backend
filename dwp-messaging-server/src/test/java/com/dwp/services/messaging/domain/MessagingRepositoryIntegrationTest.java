package com.dwp.services.messaging.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.realtime.MessagingRealtimeEvent;
import com.dwp.services.messaging.realtime.MessagingRealtimeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "DWP_MESSAGING_INTEGRATION_DB_URL", matches = ".+")
class MessagingRepositoryIntegrationTest {

    private static final long TENANT_ID = 99;
    private static final long USER_ID = 7001;
    private static final long OTHER_USER_ID = 7002;

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void migrateDatabase() {
        String url = System.getenv("DWP_MESSAGING_INTEGRATION_DB_URL");
        String username = System.getenv().getOrDefault("DWP_MESSAGING_INTEGRATION_DB_USERNAME", "postgres");
        String password = System.getenv().getOrDefault("DWP_MESSAGING_INTEGRATION_DB_PASSWORD", "postgres");
        Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, username, password);
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void databaseRejectsReplyParentFromAnotherConversation() {
        UUID leftConversation = createConversation("integration:left");
        UUID rightConversation = createConversation("integration:right");
        UUID parentId = insertMessage(leftConversation, USER_ID, null);

        assertThatThrownBy(() -> insertMessage(rightConversation, USER_ID, parentId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void messageEditUsesCompareAndSetVersioning() {
        UUID conversationId = createConversation("integration:optimistic-message");
        UUID messageId = insertMessage(conversationId, USER_ID, null);
        MessagingInteractionCommandRepository repository =
                new MessagingInteractionCommandRepository(jdbc);

        assertThat(repository.editMessage(
                TENANT_ID, USER_ID, conversationId, messageId, "first edit", 0)).isOne();
        assertThat(repository.editMessage(
                TENANT_ID, USER_ID, conversationId, messageId, "stale edit", 0)).isZero();

        assertThat(jdbc.queryForObject(
                "SELECT body FROM msg_messages WHERE message_id = ?", String.class, messageId))
                .isEqualTo("first edit");
        assertThat(jdbc.queryForObject(
                "SELECT version FROM msg_messages WHERE message_id = ?", Long.class, messageId))
                .isEqualTo(1L);
    }

    @Test
    void conversationSettingsUseCompareAndSetVersioning() {
        UUID conversationId = createConversation("integration:optimistic-settings");
        jdbc.update("""
                INSERT INTO msg_conversation_members (
                    tenant_id, conversation_id, user_id, member_role, membership_source,
                    notification_level, lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, 'MEMBER', 'DIRECT', 'DEFAULT', 'ACTIVE', ?, ?)
                """, TENANT_ID, conversationId, USER_ID, USER_ID, USER_ID);
        MessagingInteractionCommandRepository repository =
                new MessagingInteractionCommandRepository(jdbc);
        MessagingDtos.ConversationSettingsRequest request =
                new MessagingDtos.ConversationSettingsRequest("MENTIONS", true, true, 0);

        assertThat(repository.updateSettings(TENANT_ID, USER_ID, conversationId, request)).isOne();
        assertThat(repository.updateSettings(TENANT_ID, USER_ID, conversationId, request)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT notification_level FROM msg_conversation_members
                 WHERE tenant_id = ? AND conversation_id = ? AND user_id = ?
                """, String.class, TENANT_ID, conversationId, USER_ID)).isEqualTo("MENTIONS");
    }

    @Test
    void sameIdempotencyKeyAndPayloadReturnsTheOriginalMessage() {
        UUID conversationId = createConversation("integration:idempotent-same");
        UUID key = UUID.randomUUID();
        MessagingCommandRepository.MessageInsertResult created = send(
                conversationId, key, "  같은 요청\r\n본문  ", null);
        MessagingCommandRepository.MessageInsertResult replayed = send(
                conversationId, key, "같은 요청\n본문", null);
        Optional<MessagingCommandRepository.MessageInsertResult> preflight =
                new MessagingCommandRepository(jdbc).replayMessage(
                        TENANT_ID, USER_ID, conversationId, key, "같은 요청\n본문", null);

        assertThat(created.created()).isTrue();
        assertThat(replayed.created()).isFalse();
        assertThat(replayed.messageId()).isEqualTo(created.messageId());
        assertThat(replayed.sequence()).isEqualTo(created.sequence());
        assertThat(preflight).isPresent();
        assertThat(preflight.orElseThrow().messageId()).isEqualTo(created.messageId());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM msg_messages
                 WHERE tenant_id = ? AND conversation_id = ?
                """, Long.class, TENANT_ID, conversationId)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM msg_idempotency_keys
                 WHERE tenant_id = ? AND user_id = ? AND operation = 'SEND_MESSAGE'
                   AND idempotency_key = ? AND result_message_id = ?
                """, Long.class, TENANT_ID, USER_ID, key, created.messageId())).isOne();
    }

    @Test
    void reusedIdempotencyKeyWithChangedPayloadIsAConflict() {
        UUID conversationId = createConversation("integration:idempotent-conflict");
        UUID key = UUID.randomUUID();
        send(conversationId, key, "original", null);

        assertThatThrownBy(() -> send(conversationId, key, "changed", null))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> {
                            assertThat(exception.getErrorCode())
                                    .isEqualTo(ErrorCode.RESOURCE_CONFLICT);
                            assertThat(exception.getErrorCode().getHttpStatus().value()).isEqualTo(409);
                        });
        assertThatThrownBy(() -> new MessagingCommandRepository(jdbc).replayMessage(
                TENANT_ID, USER_ID, conversationId, key, "original", UUID.randomUUID()))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM msg_messages
                 WHERE tenant_id = ? AND conversation_id = ?
                """, Long.class, TENANT_ID, conversationId)).isOne();
    }

    @Test
    void concurrentSameKeyRequestsConvergeOnOneMessage() throws Exception {
        UUID conversationId = createConversation("integration:idempotent-concurrent");
        UUID key = UUID.randomUUID();
        int writers = 6;
        CountDownLatch ready = new CountDownLatch(writers);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(writers)) {
            List<Future<MessagingCommandRepository.MessageInsertResult>> futures = new ArrayList<>();
            for (int index = 0; index < writers; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return send(conversationId, key, "one logical command", null);
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<MessagingCommandRepository.MessageInsertResult> results = new ArrayList<>();
            for (Future<MessagingCommandRepository.MessageInsertResult> future : futures) {
                results.add(future.get(15, TimeUnit.SECONDS));
            }
            assertThat(results).extracting(MessagingCommandRepository.MessageInsertResult::messageId)
                    .containsOnly(results.getFirst().messageId());
            assertThat(results).extracting(MessagingCommandRepository.MessageInsertResult::sequence)
                    .containsOnly(1L);
            assertThat(results).filteredOn(MessagingCommandRepository.MessageInsertResult::created)
                    .hasSize(1);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM msg_messages
                     WHERE tenant_id = ? AND conversation_id = ?
                    """, Long.class, TENANT_ID, conversationId)).isOne();
        }
    }

    @Test
    void concurrentWritersReceiveUniqueMonotonicConversationSequences() throws Exception {
        UUID conversationId = createConversation("integration:concurrent-sequence");
        int writers = 8;
        CountDownLatch ready = new CountDownLatch(writers);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(writers)) {
            List<Future<MessagingCommandRepository.MessageInsertResult>> futures = new ArrayList<>();
            for (int index = 0; index < writers; index++) {
                int messageIndex = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return send(conversationId, UUID.randomUUID(), "message-" + messageIndex, null);
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Long> sequences = new ArrayList<>();
            for (Future<MessagingCommandRepository.MessageInsertResult> future : futures) {
                sequences.add(future.get(15, TimeUnit.SECONDS).sequence());
            }
            sequences.sort(Comparator.naturalOrder());
            assertThat(sequences).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        }
    }

    @Test
    void readCursorNeverRegressesAndRejectsConversationOrTenantMismatch() {
        UUID conversationId = createConversation("integration:read-cursor");
        createMember(conversationId, USER_ID);
        MessagingCommandRepository.MessageInsertResult first =
                send(conversationId, UUID.randomUUID(), "first", null);
        MessagingCommandRepository.MessageInsertResult second =
                send(conversationId, UUID.randomUUID(), "second", null);
        MessagingCommandRepository commands = new MessagingCommandRepository(jdbc);

        MessagingCommandRepository.ReadCursorState advanced = transactions.execute(status -> commands.markRead(
                TENANT_ID, USER_ID, conversationId, second.messageId()).orElseThrow());
        MessagingCommandRepository.ReadCursorState older = transactions.execute(status -> commands.markRead(
                TENANT_ID, USER_ID, conversationId, first.messageId()).orElseThrow());

        assertThat(advanced).isNotNull();
        assertThat(advanced.advanced()).isTrue();
        assertThat(advanced.currentSequence()).isEqualTo(second.sequence());
        assertThat(advanced.currentVersion()).isEqualTo(1);
        assertThat(older).isNotNull();
        assertThat(older.advanced()).isFalse();
        assertThat(older.currentMessageId()).isEqualTo(second.messageId());
        assertThat(older.currentSequence()).isEqualTo(second.sequence());
        assertThat(older.currentVersion()).isEqualTo(advanced.currentVersion());
        assertThat(older.currentReadAt()).isEqualTo(advanced.currentReadAt());

        UUID otherConversation = createConversation("integration:read-cursor-other");
        Optional<MessagingCommandRepository.ReadCursorState> wrongConversation =
                transactions.execute(status -> commands.markRead(
                        TENANT_ID, USER_ID, otherConversation, second.messageId()));
        Optional<MessagingCommandRepository.ReadCursorState> wrongTenant =
                transactions.execute(status -> commands.markRead(
                        TENANT_ID + 1, USER_ID, conversationId, second.messageId()));
        assertThat(wrongConversation).isEmpty();
        assertThat(wrongTenant).isEmpty();
    }

    @Test
    void conversationAndTimelineDtosExposeSequenceBasedState() {
        UUID conversationId = createConversation("integration:sequence-query");
        createMember(conversationId, USER_ID);
        MessagingCommandRepository.MessageInsertResult first = sendAs(
                USER_ID, conversationId, UUID.randomUUID(), "first", null);
        MessagingCommandRepository.MessageInsertResult second = sendAs(
                OTHER_USER_ID, conversationId, UUID.randomUUID(), "second", null);
        MessagingMessageQueryRepository messageQueries = new MessagingMessageQueryRepository(jdbc);
        MessagingQueryRepository queries = new MessagingQueryRepository(jdbc, messageQueries);

        MessagingDtos.ConversationSummary unread = queries.conversation(
                TENANT_ID, USER_ID, conversationId).orElseThrow();
        assertThat(unread.unreadCount()).isOne();
        assertThat(unread.lastMessage().sequence()).isEqualTo(second.sequence());
        assertThat(messageQueries.timeline(TENANT_ID, conversationId, USER_ID, 20))
                .extracting(MessagingDtos.MessageSummary::sequence)
                .containsExactly(first.sequence(), second.sequence());

        transactions.executeWithoutResult(status -> new MessagingCommandRepository(jdbc).markRead(
                TENANT_ID, USER_ID, conversationId, second.messageId()).orElseThrow());
        MessagingDtos.ConversationSummary read = queries.conversation(
                TENANT_ID, USER_ID, conversationId).orElseThrow();
        assertThat(read.unreadCount()).isZero();
    }

    @Test
    void realtimeEventLogCommitsAtomicallyAndCarriesMessageSequence() {
        UUID conversationId = createConversation("integration:durable-event");
        MessagingCommandRepository.MessageInsertResult message =
                send(conversationId, UUID.randomUUID(), "durable", null);
        MessagingRealtimeRepository events = new MessagingRealtimeRepository(jdbc, new ObjectMapper());

        MessagingRealtimeEvent committed = transactions.execute(status -> events.append(
                TENANT_ID, null, conversationId, message.messageId(), USER_ID,
                "messaging.message.created", Map.of("source", "integration-test")));

        assertThat(committed).isNotNull();
        assertThat(committed.messageSequence()).isEqualTo(message.sequence());
        long committedCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM msg_realtime_events
                 WHERE tenant_id = ? AND conversation_id = ?
                """, Long.class, TENANT_ID, conversationId);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            events.append(
                    TENANT_ID, null, conversationId, message.messageId(), USER_ID,
                    "messaging.message.updated", Map.of("source", "rollback-test"));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM msg_realtime_events
                 WHERE tenant_id = ? AND conversation_id = ?
                """, Long.class, TENANT_ID, conversationId)).isEqualTo(committedCount);
    }

    private UUID createConversation(String keyPrefix) {
        UUID conversationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO msg_conversations (
                    conversation_id, tenant_id, conversation_key, conversation_type,
                    name, visibility, data_classification, lifecycle_state,
                    created_by, updated_by)
                VALUES (?, ?, ?, 'CHANNEL', 'Integration', 'PRIVATE', 'INTERNAL',
                        'ACTIVE', ?, ?)
                """, conversationId, TENANT_ID, keyPrefix + ':' + conversationId, USER_ID, USER_ID);
        return conversationId;
    }

    private void createMember(UUID conversationId, long userId) {
        jdbc.update("""
                INSERT INTO msg_conversation_members (
                    tenant_id, conversation_id, user_id, member_role, membership_source,
                    notification_level, lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, 'MEMBER', 'DIRECT', 'DEFAULT', 'ACTIVE', ?, ?)
                ON CONFLICT (tenant_id, conversation_id, user_id) DO NOTHING
                """, TENANT_ID, conversationId, userId, userId, userId);
    }

    private MessagingCommandRepository.MessageInsertResult send(
            UUID conversationId, UUID key, String body, UUID replyToMessageId) {
        return sendAs(USER_ID, conversationId, key, body, replyToMessageId);
    }

    private MessagingCommandRepository.MessageInsertResult sendAs(
            long userId, UUID conversationId, UUID key, String body, UUID replyToMessageId) {
        return transactions.execute(status -> new MessagingCommandRepository(jdbc).insertMessage(
                TENANT_ID, userId, conversationId, key, "Integration User", null,
                body, replyToMessageId));
    }

    private UUID insertMessage(UUID conversationId, long senderUserId, UUID replyToMessageId) {
        UUID messageId = UUID.randomUUID();
        Long sequence = jdbc.queryForObject("""
                SELECT COALESCE(MAX(sequence), 0) + 1 FROM msg_messages
                 WHERE tenant_id = ? AND conversation_id = ?
                """, Long.class, TENANT_ID, conversationId);
        jdbc.update("""
                INSERT INTO msg_messages (
                    message_id, tenant_id, conversation_id, sequence, sender_user_id,
                    sender_name, body, content_type, message_kind, reply_to_message_id)
                VALUES (?, ?, ?, ?, ?, 'Integration User', 'message', 'TEXT', 'USER', ?)
                """, messageId, TENANT_ID, conversationId, sequence, senderUserId, replyToMessageId);
        return messageId;
    }
}
