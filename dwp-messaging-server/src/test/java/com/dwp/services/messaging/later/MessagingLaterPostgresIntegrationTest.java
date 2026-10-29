package com.dwp.services.messaging.later;

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
class MessagingLaterPostgresIntegrationTest {

    private static final long TENANT = 96_440;
    private static final long VIEWER = 100;
    private static JdbcTemplate jdbc;
    private MessagingLaterService service;
    private UUID conversation;
    private UUID message;

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
                VALUES (?, ?, 'viewer@later.test', 'Viewer')
                """, TENANT, VIEWER);
        conversation = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO msg_conversations (
                    conversation_id, tenant_id, conversation_key, conversation_type,
                    name, visibility, data_classification)
                VALUES (?, ?, ?, 'GROUP', 'Follow-up team', 'PRIVATE', 'INTERNAL')
                """, conversation, TENANT, "later:" + conversation);
        jdbc.update("""
                INSERT INTO msg_conversation_members (tenant_id, conversation_id, user_id)
                VALUES (?, ?, ?)
                """, TENANT, conversation, VIEWER);
        message = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO msg_messages (
                    message_id, tenant_id, conversation_id, sequence,
                    sender_user_id, sender_name, body)
                VALUES (?, ?, ?, 1, 200, 'Sender', 'Review this decision')
                """, message, TENANT, conversation);
        jdbc.update("""
                INSERT INTO msg_saved_items (tenant_id, user_id, message_id)
                VALUES (?, ?, ?)
                """, TENANT, VIEWER, message);
        service = new MessagingLaterService(new MessagingLaterRepository(jdbc));
        asViewer();
    }

    @AfterEach
    void clearContext() {
        MessagingRequestContext.clear();
    }

    @Test
    void snoozeBecomesDueAndOptimisticVersionPreventsLostUpdates() {
        OffsetDateTime future = OffsetDateTime.now().plusHours(1);
        var snoozed = service.update(
                message,
                new MessagingLaterDtos.UpdateLaterRequest("SNOOZED", future, future, 0L),
                "later-test");
        assertThat(snoozed.state()).isEqualTo(MessagingLaterDtos.LaterState.SNOOZED);
        assertThat(snoozed.version()).isOne();

        assertThatThrownBy(() -> service.update(
                message,
                new MessagingLaterDtos.UpdateLaterRequest("COMPLETED", null, null, 0L),
                "stale-test"));

        jdbc.update("""
                UPDATE msg_saved_items
                   SET reminder_at = CURRENT_TIMESTAMP - INTERVAL '1 minute',
                       snoozed_until = CURRENT_TIMESTAMP - INTERVAL '1 minute'
                 WHERE tenant_id = ? AND user_id = ? AND message_id = ?
                """, TENANT, VIEWER, message);
        assertThat(service.items("DUE", "", 0, 30).items()).singleElement()
                .satisfies(item -> assertThat(item.state())
                        .isEqualTo(MessagingLaterDtos.LaterState.DUE));
    }

    @Test
    void redactsProtectedContentAndRemovesItemsImmediatelyAfterAccessRevocation() {
        jdbc.update("""
                UPDATE msg_conversations SET data_classification = 'CONFIDENTIAL'
                 WHERE conversation_id = ?
                """, conversation);
        assertThat(service.items("ALL", "", 0, 30).items()).singleElement().satisfies(item -> {
            assertThat(item.protectedContent()).isTrue();
            assertThat(item.preview()).isNull();
        });

        jdbc.update("""
                UPDATE msg_conversation_members SET lifecycle_state = 'REVOKED'
                 WHERE tenant_id = ? AND conversation_id = ? AND user_id = ?
                """, TENANT, conversation, VIEWER);
        assertThat(service.items("ALL", "", 0, 30).items()).isEmpty();

        int staleMutation = new MessagingLaterRepository(jdbc).update(
                TENANT,
                VIEWER,
                message,
                MessagingLaterDtos.LaterState.COMPLETED,
                null,
                null,
                OffsetDateTime.now(),
                0L);
        assertThat(staleMutation).isZero();
    }

    private void asViewer() {
        MessagingRequestContext.set(new MessagingRequestContext.Subject(
                VIEWER, TENANT, null, "Viewer", Set.of(), Set.of("APP.MESSAGING:VIEW"), Set.of()));
    }
}
