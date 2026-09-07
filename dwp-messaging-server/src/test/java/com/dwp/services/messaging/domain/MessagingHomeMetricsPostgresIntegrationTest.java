package com.dwp.services.messaging.domain;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "DWP_MESSAGING_INTEGRATION_DB_URL", matches = ".+")
class MessagingHomeMetricsPostgresIntegrationTest {
    private static final long TENANT = 96_401;
    private static JdbcTemplate jdbc;
    private MessagingQueryRepository queries;
    private MessagingMessageQueryRepository messages;
    private UUID conversation;
    private UUID message;

    @BeforeAll
    static void migrate() {
        var source = new DriverManagerDataSource(System.getenv("DWP_MESSAGING_INTEGRATION_DB_URL"),
                System.getenv().getOrDefault("DWP_MESSAGING_INTEGRATION_DB_USERNAME", "postgres"),
                System.getenv().getOrDefault("DWP_MESSAGING_INTEGRATION_DB_PASSWORD", "postgres"));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM msg_conversations WHERE tenant_id = ?", TENANT);
        conversation = UUID.randomUUID();
        message = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO msg_conversations (conversation_id, tenant_id, conversation_key, conversation_type,
                    name, visibility, data_classification) VALUES (?, ?, ?, 'GROUP', 'Metrics', 'PRIVATE', 'INTERNAL')
                """, conversation, TENANT, "metrics:" + conversation);
        jdbc.update("INSERT INTO msg_conversation_members (tenant_id, conversation_id, user_id) VALUES (?, ?, 100)", TENANT, conversation);
        jdbc.update("""
                INSERT INTO msg_messages (message_id, tenant_id, conversation_id, sequence, sender_user_id, sender_name, body)
                VALUES (?, ?, ?, 1, 200, 'Sender', 'A message')
                """, message, TENANT, conversation);
        jdbc.update("""
                INSERT INTO msg_message_mentions (tenant_id, conversation_id, message_id, mentioned_user_id, display_name_snapshot)
                VALUES (?, ?, ?, 100, 'Viewer')
                """, TENANT, conversation, message);
        jdbc.update("INSERT INTO msg_saved_items (tenant_id, user_id, message_id) VALUES (?, 100, ?)", TENANT, message);
        messages = new MessagingMessageQueryRepository(jdbc);
        queries = new MessagingQueryRepository(jdbc, messages);
    }

    @Test
    void deletedOnlyUnreadConversationDoesNotInflateHomeUnreadAndMentions() {
        assertThat(queries.metrics(TENANT, 100).unreadConversations()).isEqualTo(1);
        assertThat(queries.metrics(TENANT, 100).mentions()).isEqualTo(1);
        jdbc.update("UPDATE msg_messages SET deleted_at = CURRENT_TIMESTAMP WHERE message_id = ?", message);
        assertThat(queries.metrics(TENANT, 100).unreadConversations()).isZero();
        assertThat(queries.metrics(TENANT, 100).mentions()).isZero();
        assertThat(queries.conversation(TENANT, 100, conversation).orElseThrow().unreadCount()).isZero();
    }

    @Test
    void archivedConversationCannotInflateMentionsSavedCountsOrSavedPagination() {
        assertThat(queries.metrics(TENANT, 100).savedItems()).isEqualTo(1);
        jdbc.update("UPDATE msg_conversations SET lifecycle_state = 'ARCHIVED' WHERE conversation_id = ?", conversation);
        var metrics = queries.metrics(TENANT, 100);
        assertThat(metrics.unreadConversations()).isZero();
        assertThat(metrics.mentions()).isZero();
        assertThat(metrics.savedItems()).isZero();
        assertThat(messages.savedItems(TENANT, 100, 0, 20).total()).isZero();
    }
}
