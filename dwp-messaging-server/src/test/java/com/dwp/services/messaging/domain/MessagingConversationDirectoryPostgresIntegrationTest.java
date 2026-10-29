package com.dwp.services.messaging.domain;

import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "DWP_MESSAGING_INTEGRATION_DB_URL", matches = ".+")
class MessagingConversationDirectoryPostgresIntegrationTest {

    private static final long TENANT = 96_460;
    private static final long OTHER_TENANT = 96_461;
    private static final long VIEWER = 100;
    private static JdbcTemplate jdbc;
    private MessagingConversationDirectory directory;

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
        jdbc.update("DELETE FROM msg_conversations WHERE tenant_id IN (?, ?)", TENANT, OTHER_TENANT);
        insert(TENANT, UUID.fromString("73000000-0000-0000-0000-000000000001"),
                "Pinned", true, false, "2026-10-25T00:00:00Z");
        insert(TENANT, UUID.fromString("73000000-0000-0000-0000-000000000002"),
                "Favorite", false, true, "2026-10-28T00:00:00Z");
        insert(TENANT, UUID.fromString("73000000-0000-0000-0000-000000000003"),
                "Recent A", false, false, "2026-10-27T00:00:00Z");
        insert(TENANT, UUID.fromString("73000000-0000-0000-0000-000000000004"),
                "Recent B", false, false, "2026-10-27T00:00:00Z");
        insert(TENANT, UUID.fromString("73000000-0000-0000-0000-000000000005"),
                "Empty", false, false, null);
        insertWithoutMembership(
                TENANT,
                UUID.fromString("73000000-0000-0000-0000-000000000006"),
                "Not a member");
        insert(OTHER_TENANT, UUID.randomUUID(), "Other tenant", true, true,
                "2026-10-29T00:00:00Z");
        directory = new MessagingConversationDirectory(new MessagingQueryRepository(
                jdbc, new MessagingMessageQueryRepository(jdbc)));
        setSubject(TENANT, VIEWER);
    }

    @AfterEach
    void clear() {
        MessagingRequestContext.clear();
    }

    @Test
    void cursorTraversalIsStableWhenANewerConversationArrivesBetweenPages() {
        MessagingDtos.ConversationPage first = directory.page("ALL", "", 0, 2, null);
        assertThat(first.items()).extracting(MessagingDtos.ConversationSummary::name)
                .containsExactly("Pinned", "Favorite");
        assertThat(first.total()).isEqualTo(5);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isNotBlank();
        assertThat(first.nextPage()).isEqualTo(1);

        insert(TENANT, UUID.randomUUID(), "Arrived later", true, true,
                "2026-10-30T00:00:00Z");
        MessagingDtos.ConversationPage second = directory.page(
                "ALL", "", 99, 2, first.nextCursor());
        assertThat(second.items()).extracting(MessagingDtos.ConversationSummary::name)
                .containsExactly("Recent A", "Recent B");
        assertThat(second.items()).extracting(MessagingDtos.ConversationSummary::conversationId)
                .doesNotContainAnyElementsOf(first.items().stream()
                        .map(MessagingDtos.ConversationSummary::conversationId).toList());
        assertThat(second.nextPage()).isNull();

        MessagingDtos.ConversationPage third = directory.page(
                "ALL", "", 0, 2, second.nextCursor());
        assertThat(third.items()).extracting(MessagingDtos.ConversationSummary::name)
                .containsExactly("Empty");
        assertThat(third.hasMore()).isFalse();
        assertThat(third.nextCursor()).isNull();
    }

    @Test
    void cursorCannotBeReusedAcrossScopeOrSearchAndOffsetFallbackStillWorks() {
        MessagingDtos.ConversationPage first = directory.page("ALL", "", 0, 1, null);
        assertThatThrownBy(() -> directory.page(
                "FAVORITES", "", 0, 1, first.nextCursor()))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> directory.page(
                "ALL", "recent", 0, 1, first.nextCursor()))
                .isInstanceOf(BaseException.class);

        MessagingDtos.ConversationPage offset = directory.page("ALL", "", 1, 2, null);
        assertThat(offset.items()).extracting(MessagingDtos.ConversationSummary::name)
                .containsExactly("Recent A", "Recent B");
    }

    @Test
    void cursorCannotCrossTenantOrUserIdentity() {
        MessagingDtos.ConversationPage first = directory.page("ALL", "", 0, 1, null);

        setSubject(OTHER_TENANT, VIEWER);
        assertThatThrownBy(() -> directory.page(
                "ALL", "", 0, 1, first.nextCursor()))
                .isInstanceOf(BaseException.class);

        setSubject(TENANT, VIEWER + 1);
        assertThatThrownBy(() -> directory.page(
                "ALL", "", 0, 1, first.nextCursor()))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void tenantAndActiveMembershipRemainMandatoryForEveryPage() {
        MessagingDtos.ConversationPage page = directory.page("ALL", "", 0, 20, null);

        assertThat(page.total()).isEqualTo(5);
        assertThat(page.items()).extracting(MessagingDtos.ConversationSummary::name)
                .containsExactly("Pinned", "Favorite", "Recent A", "Recent B", "Empty")
                .doesNotContain("Not a member", "Other tenant");
    }

    private void setSubject(long tenantId, long userId) {
        MessagingRequestContext.set(new MessagingRequestContext.Subject(
                userId, tenantId, null, "Viewer", Set.of(),
                Set.of("APP.MESSAGING:VIEW"), Set.of()));
    }

    private void insert(
            long tenantId,
            UUID conversationId,
            String name,
            boolean pinned,
            boolean favorite,
            String lastMessageAt) {
        jdbc.update("""
                INSERT INTO msg_conversations (
                    conversation_id, tenant_id, conversation_key, conversation_type,
                    name, visibility, data_classification, lifecycle_state)
                VALUES (?, ?, ?, 'CHANNEL', ?, 'PRIVATE', 'INTERNAL', 'ACTIVE')
                """, conversationId, tenantId, "cursor:" + conversationId, name);
        jdbc.update("""
                INSERT INTO msg_conversation_members (
                    tenant_id, conversation_id, user_id, favorite, pinned,
                    lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """, tenantId, conversationId, VIEWER, favorite, pinned, VIEWER, VIEWER);
        if (lastMessageAt == null) return;
        UUID messageId = UUID.randomUUID();
        OffsetDateTime timestamp = OffsetDateTime.parse(lastMessageAt);
        jdbc.update("""
                INSERT INTO msg_messages (
                    message_id, tenant_id, conversation_id, sequence,
                    sender_user_id, sender_name, body, created_at)
                VALUES (?, ?, ?, 1, ?, 'Sender', 'cursor message', ?)
                """, messageId, tenantId, conversationId, VIEWER, timestamp);
        jdbc.update("""
                UPDATE msg_conversations
                   SET last_message_id = ?, last_message_at = ?
                 WHERE tenant_id = ? AND conversation_id = ?
                """, messageId, timestamp, tenantId, conversationId);
    }

    private void insertWithoutMembership(long tenantId, UUID conversationId, String name) {
        jdbc.update("""
                INSERT INTO msg_conversations (
                    conversation_id, tenant_id, conversation_key, conversation_type,
                    name, visibility, data_classification, lifecycle_state)
                VALUES (?, ?, ?, 'CHANNEL', ?, 'PRIVATE', 'INTERNAL', 'ACTIVE')
                """, conversationId, tenantId, "cursor:" + conversationId, name);
    }
}
