package com.dwp.services.messaging.domain;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "DWP_MESSAGING_INTEGRATION_DB_URL", matches = ".+")
class MessagingHistoryVisibilityIntegrationTest {

    private static final long TENANT_ID = 83_001;
    private static final long USER_ID = 73_001;
    private static final long SENDER_ID = 73_002;

    private static JdbcTemplate jdbc;

    private MessagingMessageQueryRepository messageQueries;
    private MessagingCommandRepository commands;
    private MessagingQueryRepository queries;
    private UUID conversationId;
    private UUID hiddenMessageId;
    private UUID visibleMessageId;

    @BeforeAll
    static void migrateDatabase() {
        String url = System.getenv("DWP_MESSAGING_INTEGRATION_DB_URL");
        String username = System.getenv().getOrDefault(
                "DWP_MESSAGING_INTEGRATION_DB_USERNAME", "postgres");
        String password = System.getenv().getOrDefault(
                "DWP_MESSAGING_INTEGRATION_DB_PASSWORD", "postgres");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, username, password);
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM msg_conversations WHERE tenant_id = ?", TENANT_ID);
        jdbc.update("DELETE FROM msg_people_snapshot WHERE tenant_id = ?", TENANT_ID);
        person(USER_ID, "Member", "member@history.test");
        person(SENDER_ID, "Sender", "sender@history.test");
        conversationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO msg_conversations (
                    conversation_id, tenant_id, conversation_key, conversation_type,
                    name, visibility, data_classification, lifecycle_state,
                    created_by, updated_by)
                VALUES (?, ?, ?, 'CHANNEL', 'History boundary', 'PRIVATE',
                        'INTERNAL', 'ACTIVE', ?, ?)
                """, conversationId, TENANT_ID, "history:" + conversationId, SENDER_ID, SENDER_ID);
        jdbc.update("""
                INSERT INTO msg_conversation_members (
                    tenant_id, conversation_id, user_id, member_role,
                    membership_source, lifecycle_state, history_start_sequence,
                    last_read_sequence, created_by, updated_by)
                VALUES (?, ?, ?, 'MEMBER', 'DIRECT', 'ACTIVE', 2, 1, ?, ?)
                """, TENANT_ID, conversationId, USER_ID, USER_ID, USER_ID);
        hiddenMessageId = message(1, "before join");
        visibleMessageId = message(2, "after join");
        jdbc.update("""
                UPDATE msg_conversations
                   SET last_message_id = ?, last_message_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND conversation_id = ?
                """, visibleMessageId, TENANT_ID, conversationId);
        jdbc.update("""
                INSERT INTO msg_saved_items (tenant_id, user_id, message_id)
                VALUES (?, ?, ?), (?, ?, ?)
                """, TENANT_ID, USER_ID, hiddenMessageId,
                TENANT_ID, USER_ID, visibleMessageId);

        messageQueries = new MessagingMessageQueryRepository(jdbc);
        commands = new MessagingCommandRepository(jdbc);
        queries = new MessagingQueryRepository(jdbc, messageQueries);
    }

    @Test
    void appliesTheSameHistoryBoundaryToTimelineDirectLookupSavedItemsAndReadCursor() {
        assertThat(messageQueries.timeline(TENANT_ID, conversationId, USER_ID, 20))
                .extracting(MessagingDtos.MessageSummary::messageId)
                .containsExactly(visibleMessageId);
        assertThat(messageQueries.message(
                TENANT_ID, conversationId, USER_ID, hiddenMessageId)).isEmpty();
        assertThat(messageQueries.access(
                TENANT_ID, conversationId, USER_ID, hiddenMessageId)).isEmpty();
        assertThat(messageQueries.savedItems(TENANT_ID, USER_ID, 0, 20).items())
                .extracting(item -> item.message().messageId())
                .containsExactly(visibleMessageId);
        assertThat(commands.markRead(
                TENANT_ID, USER_ID, conversationId, hiddenMessageId)).isEmpty();
        assertThat(commands.markRead(
                TENANT_ID, USER_ID, conversationId, visibleMessageId)).isPresent();
    }

    @Test
    void excludesPreJoinMessagesFromUnreadAndConversationPreviews() {
        var conversation = queries.conversation(TENANT_ID, USER_ID, conversationId).orElseThrow();
        assertThat(conversation.unreadCount()).isEqualTo(1);
        assertThat(conversation.lastMessage()).isNotNull();
        assertThat(conversation.lastMessage().messageId()).isEqualTo(visibleMessageId);
        assertThat(queries.metrics(TENANT_ID, USER_ID).savedItems()).isEqualTo(1);
    }

    @Test
    void pagesOnlyRootMessagesByDurableSequenceInDisplayOrder() {
        UUID rootThree = message(3, "root three");
        message(4, "thread reply", rootThree);
        UUID rootFive = message(5, "root five");
        UUID rootSix = message(6, "root six");

        MessagingDtos.MessagePage latest = messageQueries.messagePage(
                TENANT_ID, conversationId, USER_ID, null, 2);
        MessagingDtos.MessagePage previous = messageQueries.messagePage(
                TENANT_ID, conversationId, USER_ID, latest.nextBeforeSequence(), 2);

        assertThat(latest.items())
                .extracting(MessagingDtos.MessageSummary::messageId)
                .containsExactly(rootFive, rootSix);
        assertThat(latest.items())
                .extracting(MessagingDtos.MessageSummary::sequence)
                .containsExactly(5L, 6L);
        assertThat(latest.hasMore()).isTrue();
        assertThat(latest.nextBeforeSequence()).isEqualTo(5L);
        assertThat(latest.items())
                .allSatisfy(message -> assertThat(message.rootPreview()).isNull());

        assertThat(previous.items())
                .extracting(MessagingDtos.MessageSummary::messageId)
                .containsExactly(visibleMessageId, rootThree);
        assertThat(previous.items())
                .extracting(MessagingDtos.MessageSummary::sequence)
                .containsExactly(2L, 3L);
        assertThat(previous.hasMore()).isFalse();
        assertThat(previous.nextBeforeSequence()).isNull();
        assertThat(previous.items().getLast().replyCount()).isEqualTo(1);
    }

    @Test
    void pageQueryEnforcesTenantActiveMembershipAndHistoryBoundary() {
        assertThat(messageQueries.messagePage(
                TENANT_ID + 1, conversationId, USER_ID, null, 20).items()).isEmpty();
        assertThat(messageQueries.messagePage(
                TENANT_ID, conversationId, USER_ID, 2L, 20).items()).isEmpty();

        jdbc.update("""
                UPDATE msg_conversation_members
                   SET lifecycle_state = 'REVOKED'
                 WHERE tenant_id = ? AND conversation_id = ? AND user_id = ?
                """, TENANT_ID, conversationId, USER_ID);

        assertThat(messageQueries.messagePage(
                TENANT_ID, conversationId, USER_ID, null, 20).items()).isEmpty();
    }

    private void person(long userId, String name, String email) {
        jdbc.update("""
                INSERT INTO msg_people_snapshot (
                    tenant_id, user_id, person_public_id, email_address,
                    display_name, presence_state, lifecycle_state)
                VALUES (?, ?, ?, ?, ?, 'AVAILABLE', 'ACTIVE')
                """, TENANT_ID, userId, UUID.randomUUID(), email, name);
    }

    private UUID message(long sequence, String body) {
        return message(sequence, body, null);
    }

    private UUID message(long sequence, String body, UUID replyToMessageId) {
        UUID messageId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO msg_messages (
                    message_id, tenant_id, conversation_id, sequence,
                    sender_user_id, sender_name, body, content_type, message_kind,
                    reply_to_message_id)
                VALUES (?, ?, ?, ?, ?, 'Sender', ?, 'TEXT', 'USER', ?)
                """, messageId, TENANT_ID, conversationId, sequence, SENDER_ID, body,
                replyToMessageId);
        return messageId;
    }
}
