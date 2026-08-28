package com.dwp.services.messaging.collaboration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.security.MessagingRequestContext;
import com.dwp.services.messaging.domain.MessagingTenantPolicyGuard;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "DWP_MESSAGING_INTEGRATION_DB_URL", matches = ".+")
class CollaborationRepositoryIntegrationTest {

    private static final long TENANT_ID = 81_001;
    private static final long OTHER_TENANT_ID = 81_002;
    private static final long OWNER_ID = 71_001;
    private static final long MEMBER_ID = 71_002;
    private static final long ACTIVE_PERSON_ID = 71_003;
    private static final long INACTIVE_PERSON_ID = 71_004;
    private static final long CROSS_TENANT_PERSON_ID = 71_099;

    private static JdbcTemplate jdbc;

    private CollaborationService service;

    @BeforeAll
    static void migrateDatabase() {
        String url = System.getenv("DWP_MESSAGING_INTEGRATION_DB_URL");
        String username = System.getenv().getOrDefault(
                "DWP_MESSAGING_INTEGRATION_DB_USERNAME", "postgres");
        String password = System.getenv().getOrDefault(
                "DWP_MESSAGING_INTEGRATION_DB_PASSWORD", "postgres");
        Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM msg_audit_events WHERE tenant_id IN (?, ?)",
                TENANT_ID, OTHER_TENANT_ID);
        jdbc.update("DELETE FROM msg_conversations WHERE tenant_id IN (?, ?)",
                TENANT_ID, OTHER_TENANT_ID);
        jdbc.update("DELETE FROM msg_people_snapshot WHERE tenant_id IN (?, ?)",
                TENANT_ID, OTHER_TENANT_ID);
        service = new CollaborationService(
                new ConversationCreationRepository(jdbc),
                new SqlCollaborationSearchRepository(new NamedParameterJdbcTemplate(jdbc)),
                mock(MessagingTenantPolicyGuard.class));
        person(TENANT_ID, OWNER_ID, "Owner", "owner@tenant.test", "ACTIVE");
        subject(TENANT_ID, OWNER_ID);
    }

    @AfterEach
    void clearContext() {
        MessagingRequestContext.clear();
    }

    @Test
    void createsGroupIdempotentlyWithCurrentUserAsOwnerAndRejectsChangedRequest() {
        person(TENANT_ID, MEMBER_ID, "Member", "member@tenant.test", "ACTIVE");
        var firstRequest = new CollaborationDtos.CreateConversationRequest(
                "Incident response",
                "Coordinate the response",
                CollaborationDtos.ConversationType.GROUP,
                List.of(MEMBER_ID),
                "group-create-0001");

        var first = service.createConversation(firstRequest);
        var replay = service.createConversation(new CollaborationDtos.CreateConversationRequest(
                "Incident response",
                "Coordinate the response",
                CollaborationDtos.ConversationType.GROUP,
                List.of(OWNER_ID, MEMBER_ID, MEMBER_ID),
                "group-create-0001"));

        assertThat(first.idempotentReplay()).isFalse();
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.conversation().conversationId())
                .isEqualTo(first.conversation().conversationId());
        assertThat(first.conversation().visibility()).isEqualTo("PRIVATE");
        assertThat(first.conversation().members())
                .extracting(CollaborationDtos.MemberSummary::userId,
                        CollaborationDtos.MemberSummary::role)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(OWNER_ID, "OWNER"),
                        org.assertj.core.groups.Tuple.tuple(MEMBER_ID, "MEMBER"));

        assertThatThrownBy(() -> service.createConversation(
                new CollaborationDtos.CreateConversationRequest(
                        "Changed incident response",
                        "Coordinate the response",
                        CollaborationDtos.ConversationType.GROUP,
                        List.of(MEMBER_ID),
                        "group-create-0001")))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void rejectsInactiveAndCrossTenantMembersWithoutDisclosingWhichLookupFailed() {
        person(TENANT_ID, INACTIVE_PERSON_ID,
                "Inactive", "inactive@tenant.test", "INACTIVE");
        person(OTHER_TENANT_ID, CROSS_TENANT_PERSON_ID,
                "Other tenant", "other@tenant.test", "ACTIVE");

        assertInvalidMember(INACTIVE_PERSON_ID, "group-create-0002");
        assertInvalidMember(CROSS_TENANT_PERSON_ID, "group-create-0003");
    }

    @Test
    void searchEnforcesTenantMembershipAndExcludesDeletedMessagesAndInactivePeople() {
        person(TENANT_ID, ACTIVE_PERSON_ID,
                "Needle Active", "needle.active@tenant.test", "ACTIVE");
        person(TENANT_ID, INACTIVE_PERSON_ID,
                "Needle Inactive", "needle.inactive@tenant.test", "INACTIVE");
        person(OTHER_TENANT_ID, CROSS_TENANT_PERSON_ID,
                "Needle Other", "needle.other@tenant.test", "ACTIVE");

        UUID accessible = conversation(TENANT_ID, "Needle accessible", OWNER_ID, true);
        UUID inaccessible = conversation(TENANT_ID, "Needle private", MEMBER_ID, false);
        UUID revoked = conversation(TENANT_ID, "Needle revoked", OWNER_ID, false);
        membership(TENANT_ID, revoked, OWNER_ID, "REVOKED");
        UUID crossTenant = conversation(
                OTHER_TENANT_ID, "Needle cross tenant", OWNER_ID, true);

        UUID visibleMessage = message(
                TENANT_ID, accessible, OWNER_ID, "<b>needle</b> & visible", false);
        UUID hiddenMessage = message(
                TENANT_ID, inaccessible, MEMBER_ID, "needle hidden", false);
        UUID deletedMessage = message(
                TENANT_ID, accessible, OWNER_ID, "needle deleted", true);
        UUID crossTenantMessage = message(
                OTHER_TENANT_ID, crossTenant, OWNER_ID, "needle cross tenant", false);

        var response = service.search("needle", "conversation,message,person", 50);

        assertThat(response.backend()).isEqualTo("SQL_FALLBACK");
        assertThat(response.results().conversations())
                .extracting(CollaborationDtos.ConversationSearchResult::conversationId)
                .containsExactly(accessible)
                .doesNotContain(inaccessible, revoked, crossTenant);
        assertThat(response.results().messages())
                .extracting(CollaborationDtos.MessageSearchResult::messageId)
                .containsExactly(visibleMessage)
                .doesNotContain(hiddenMessage, deletedMessage, crossTenantMessage);
        assertThat(response.results().messages().getFirst().snippet())
                .doesNotContain("<", ">")
                .contains("needle", "&amp;");
        assertThat(response.results().people())
                .extracting(CollaborationDtos.PersonSearchResult::userId)
                .containsExactly(ACTIVE_PERSON_ID)
                .doesNotContain(INACTIVE_PERSON_ID, CROSS_TENANT_PERSON_ID);
        assertThat(response.total()).isEqualTo(3);
    }

    @Test
    void searchValidatesQueryTypesAndGlobalLimit() {
        assertThatThrownBy(() -> service.search("x", "MESSAGE", 20))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> service.search("needle", "FILE", 20))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> service.search("needle", "MESSAGE", 51))
                .isInstanceOf(BaseException.class);
    }

    private void assertInvalidMember(long memberId, String idempotencyKey) {
        assertThatThrownBy(() -> service.createConversation(
                new CollaborationDtos.CreateConversationRequest(
                        "Restricted group",
                        null,
                        CollaborationDtos.ConversationType.GROUP,
                        List.of(memberId),
                        idempotencyKey)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    private void subject(long tenantId, long userId) {
        MessagingRequestContext.set(new MessagingRequestContext.Subject(
                userId,
                tenantId,
                null,
                "Integration User",
                Set.of("WORKSPACE_MEMBER"),
                Set.of("APP.MESSAGING:VIEW", "APP.MESSAGING:CREATE"),
                Set.of()));
    }

    private void person(long tenantId, long userId, String name, String email, String state) {
        jdbc.update("""
                INSERT INTO msg_people_snapshot (
                    tenant_id, user_id, person_public_id, email_address,
                    display_name, job_title, organization_name,
                    presence_state, lifecycle_state)
                VALUES (?, ?, ?, ?, ?, 'Engineer', 'Platform', 'AVAILABLE', ?)
                """, tenantId, userId, UUID.randomUUID(), email, name, state);
    }

    private UUID conversation(
            long tenantId,
            String name,
            long memberUserId,
            boolean activeMembership) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO msg_conversations (
                    conversation_id, tenant_id, conversation_key, conversation_type,
                    name, topic, visibility, data_classification, lifecycle_state,
                    created_by, updated_by)
                VALUES (?, ?, ?, 'CHANNEL', ?, 'needle topic', 'PRIVATE',
                        'INTERNAL', 'ACTIVE', ?, ?)
                """, id, tenantId, "test:" + id, name, memberUserId, memberUserId);
        if (activeMembership) membership(tenantId, id, memberUserId, "ACTIVE");
        return id;
    }

    private void membership(long tenantId, UUID conversationId, long userId, String state) {
        jdbc.update("""
                INSERT INTO msg_conversation_members (
                    tenant_id, conversation_id, user_id, member_role,
                    membership_source, lifecycle_state, created_by, updated_by)
                VALUES (?, ?, ?, 'MEMBER', 'DIRECT', ?, ?, ?)
                """, tenantId, conversationId, userId, state, userId, userId);
    }

    private UUID message(
            long tenantId,
            UUID conversationId,
            long senderUserId,
            String body,
            boolean deleted) {
        UUID id = UUID.randomUUID();
        Long sequence = jdbc.queryForObject("""
                SELECT COALESCE(MAX(sequence), 0) + 1
                  FROM msg_messages
                 WHERE tenant_id = ? AND conversation_id = ?
                """, Long.class, tenantId, conversationId);
        jdbc.update("""
                INSERT INTO msg_messages (
                    message_id, tenant_id, conversation_id, sender_user_id,
                    sender_name, body, content_type, message_kind, sequence, deleted_at)
                VALUES (?, ?, ?, ?, 'Sender', ?, 'TEXT', 'USER', ?,
                        CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END)
                """, id, tenantId, conversationId, senderUserId, body, sequence, deleted);
        return id;
    }
}
