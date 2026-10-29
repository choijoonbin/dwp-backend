package com.dwp.services.messaging.attention;

import com.dwp.services.messaging.security.MessagingRequestContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "DWP_MESSAGING_INTEGRATION_DB_URL", matches = ".+")
class MessagingAttentionPostgresIntegrationTest {

    private static final long TENANT = 96_430;
    private static final long VIEWER = 100;
    private static final long SENDER = 200;
    private static JdbcTemplate jdbc;
    private MessagingAttentionService service;
    private UUID conversation;

    @BeforeAll
    static void migrate() {
        var source = new DriverManagerDataSource(
                System.getenv("DWP_MESSAGING_INTEGRATION_DB_URL"),
                System.getenv().getOrDefault("DWP_MESSAGING_INTEGRATION_DB_USERNAME", "postgres"),
                System.getenv().getOrDefault("DWP_MESSAGING_INTEGRATION_DB_PASSWORD", "postgres"));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM msg_conversations WHERE tenant_id = ?", TENANT);
        jdbc.update("DELETE FROM msg_people_snapshot WHERE tenant_id = ?", TENANT);
        jdbc.update("""
                INSERT INTO msg_people_snapshot (tenant_id, user_id, email_address, display_name)
                VALUES (?, ?, 'viewer@attention.test', 'Viewer'),
                       (?, ?, 'sender@attention.test', 'Sender')
                """, TENANT, VIEWER, TENANT, SENDER);
        conversation = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO msg_conversations (
                    conversation_id, tenant_id, conversation_key, conversation_type,
                    name, visibility, data_classification)
                VALUES (?, ?, ?, 'GROUP', 'Response team', 'PRIVATE', 'INTERNAL')
                """, conversation, TENANT, "attention:" + conversation);
        jdbc.update("""
                INSERT INTO msg_conversation_members (tenant_id, conversation_id, user_id)
                VALUES (?, ?, ?), (?, ?, ?)
                """, TENANT, conversation, VIEWER, TENANT, conversation, SENDER);
        service = new MessagingAttentionService(new MessagingAttentionRepository(jdbc));
        asViewer();
    }

    @AfterEach
    void clearContext() {
        MessagingRequestContext.clear();
    }

    @Test
    void returnsOnlyAuthorizedMentionsParticipatingRepliesReactionsAndSystemChanges() {
        UUID ownedRoot = message(1, VIEWER, "Decision needed", null, "USER");
        UUID reply = message(2, SENDER, "I can take it", ownedRoot, "USER");
        UUID mention = message(3, SENDER, "Viewer please review", null, "USER");
        jdbc.update("""
                INSERT INTO msg_message_mentions (
                    tenant_id, conversation_id, message_id, mentioned_user_id, display_name_snapshot)
                VALUES (?, ?, ?, ?, 'Viewer')
                """, TENANT, conversation, mention, VIEWER);
        jdbc.update("""
                INSERT INTO msg_message_reactions (tenant_id, message_id, user_id, emoji)
                VALUES (?, ?, ?, '👍')
                """, TENANT, ownedRoot, SENDER);
        UUID system = message(4, SENDER, "Sender joined the conversation", null, "SYSTEM");

        var activity = service.activity(100).items();
        assertThat(activity).extracting(MessagingAttentionDtos.ActivityItem::kind)
                .contains(
                        MessagingAttentionDtos.ActivityKind.MENTION,
                        MessagingAttentionDtos.ActivityKind.THREAD_REPLY,
                        MessagingAttentionDtos.ActivityKind.REACTION,
                        MessagingAttentionDtos.ActivityKind.SYSTEM);
        assertThat(activity).extracting(MessagingAttentionDtos.ActivityItem::messageId)
                .contains(mention, reply, ownedRoot, system);
        assertThat(service.threads(100).items()).singleElement().satisfies(thread -> {
            assertThat(thread.rootMessageId()).isEqualTo(ownedRoot);
            assertThat(thread.latestReplyMessageId()).isEqualTo(reply);
            assertThat(thread.replyCount()).isOne();
        });
    }

    @Test
    void redactsProtectedPreviewsAndFailsClosedAfterMembershipRevocation() {
        jdbc.update("""
                UPDATE msg_conversations SET data_classification = 'RESTRICTED'
                 WHERE conversation_id = ?
                """, conversation);
        UUID root = message(1, VIEWER, "Restricted decision", null, "USER");
        message(2, SENDER, "Restricted reply", root, "USER");

        assertThat(service.activity(100).items()).allSatisfy(item -> {
            assertThat(item.protectedContent()).isTrue();
            assertThat(item.preview()).isNull();
        });
        assertThat(service.threads(100).items()).singleElement().satisfies(thread -> {
            assertThat(thread.protectedContent()).isTrue();
            assertThat(thread.rootPreview()).isNull();
        });

        jdbc.update("""
                UPDATE msg_conversation_members SET lifecycle_state = 'REVOKED'
                 WHERE tenant_id = ? AND conversation_id = ? AND user_id = ?
                """, TENANT, conversation, VIEWER);
        assertThat(service.activity(100).items()).isEmpty();
        assertThat(service.threads(100).items()).isEmpty();
    }

    @Test
    void exposesTerminalScheduledFailureWithoutReexposingRevokedConversationMetadata() {
        UUID scheduledId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO msg_scheduled_messages (
                    scheduled_message_id, tenant_id, sender_user_id, conversation_id,
                    body, request_idempotency_key, send_idempotency_key, request_hash,
                    scheduled_for, display_time_zone, status, next_attempt_at,
                    failure_code, failure_detail)
                VALUES (?, ?, ?, ?, 'payload', ?, ?, ?, CURRENT_TIMESTAMP,
                        'UTC', 'FAILED', CURRENT_TIMESTAMP,
                        'MEMBERSHIP_REVOKED', 'You no longer have access to this conversation.')
                """, scheduledId, TENANT, VIEWER, conversation,
                UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64));
        jdbc.update("""
                UPDATE msg_conversation_members SET lifecycle_state = 'REVOKED'
                 WHERE tenant_id = ? AND conversation_id = ? AND user_id = ?
                """, TENANT, conversation, VIEWER);

        assertThat(service.activity(100).items()).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo(
                    MessagingAttentionDtos.ActivityKind.SCHEDULE_FAILED);
            assertThat(item.id()).isEqualTo("SCHEDULE_FAILED:" + scheduledId);
            assertThat(item.messageId()).isNull();
            assertThat(item.conversationName()).isEmpty();
            assertThat(item.preview()).contains("no longer have access");
        });
    }

    private UUID message(int sequence, long sender, String body, UUID replyTo, String kind) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO msg_messages (
                    message_id, tenant_id, conversation_id, sequence, sender_user_id,
                    sender_name, body, reply_to_message_id, message_kind)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, TENANT, conversation, sequence, sender,
                sender == VIEWER ? "Viewer" : "Sender", body, replyTo, kind);
        return id;
    }

    private void asViewer() {
        MessagingRequestContext.set(new MessagingRequestContext.Subject(
                VIEWER, TENANT, null, "Viewer", Set.of(), Set.of("APP.MESSAGING:VIEW"), Set.of()));
    }
}
