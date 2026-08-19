package com.dwp.services.messaging.collaboration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.realtime.MessagingEventRecorder;
import com.dwp.services.messaging.realtime.MessagingRealtimePublisher;
import com.dwp.services.messaging.realtime.MessagingRealtimeRepository;
import com.dwp.services.messaging.security.MessagingRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "DWP_MESSAGING_INTEGRATION_DB_URL", matches = ".+")
class ConversationMembershipRepositoryIntegrationTest {

    private static final long TENANT_ID = 82_001;
    private static final long OTHER_TENANT_ID = 82_002;
    private static final long OWNER_ID = 72_001;
    private static final long SECOND_OWNER_ID = 72_002;
    private static final long MODERATOR_ID = 72_003;
    private static final long MEMBER_ID = 72_004;
    private static final long NEW_MEMBER_ID = 72_005;
    private static final long OUTSIDER_ID = 72_006;

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;

    private ConversationMembershipService service;
    private CollaborationService collaborationService;
    private MessagingRealtimeRepository realtimeRepository;

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
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM msg_audit_events WHERE tenant_id IN (?, ?)", TENANT_ID, OTHER_TENANT_ID);
        jdbc.update("DELETE FROM msg_conversations WHERE tenant_id IN (?, ?)", TENANT_ID, OTHER_TENANT_ID);
        jdbc.update("DELETE FROM msg_people_snapshot WHERE tenant_id IN (?, ?)", TENANT_ID, OTHER_TENANT_ID);

        ConversationMembershipRepository membershipRepository =
                new ConversationMembershipRepository(jdbc);
        realtimeRepository = new MessagingRealtimeRepository(jdbc, new ObjectMapper());
        MessagingEventRecorder eventRecorder = new MessagingEventRecorder(
                realtimeRepository, mock(MessagingRealtimePublisher.class));
        service = new ConversationMembershipService(membershipRepository, eventRecorder);
        collaborationService = new CollaborationService(
                new ConversationCreationRepository(jdbc),
                new SqlCollaborationSearchRepository(new NamedParameterJdbcTemplate(jdbc)));

        person(TENANT_ID, OWNER_ID, "Owner", "owner@tenant.test", "ACTIVE");
        person(TENANT_ID, SECOND_OWNER_ID, "Second owner", "owner2@tenant.test", "ACTIVE");
        person(TENANT_ID, MODERATOR_ID, "Moderator", "moderator@tenant.test", "ACTIVE");
        person(TENANT_ID, MEMBER_ID, "Member", "member@tenant.test", "ACTIVE");
        person(TENANT_ID, NEW_MEMBER_ID, "New member", "new@tenant.test", "ACTIVE");
        person(TENANT_ID, OUTSIDER_ID, "Outsider", "outsider@tenant.test", "ACTIVE");
        subject(TENANT_ID, OWNER_ID);
    }

    @AfterEach
    void clearContext() {
        MessagingRequestContext.clear();
    }

    @Test
    void addsAndReaddsIdempotentlyAtAFromJoinBoundaryWithPayloadFreeEvents() {
        UUID conversationId = conversation(TENANT_ID, "CHANNEL");
        membership(TENANT_ID, conversationId, OWNER_ID, "OWNER", "DIRECT", "ACTIVE");
        UUID oldMessageId = message(TENANT_ID, conversationId, OWNER_ID, 1, "needle before join");
        realtimeMessageEvent(TENANT_ID, conversationId, oldMessageId, 1, OWNER_ID);
        realtimePrivateMessageEvent(
                TENANT_ID, conversationId, oldMessageId, 1, OWNER_ID, NEW_MEMBER_ID);

        var added = inTransaction(() -> service.addMember(
                conversationId,
                new CollaborationDtos.AddConversationMemberRequest(
                        NEW_MEMBER_ID, CollaborationDtos.MemberRole.MEMBER, 0),
                "membership-add-1"));

        assertThat(added.idempotentReplay()).isFalse();
        var newMember = added.membership().members().stream()
                .filter(member -> member.userId() == NEW_MEMBER_ID)
                .findFirst()
                .orElseThrow();
        assertThat(newMember.historyStartSequence()).isEqualTo(2);
        assertThat(newMember.version()).isZero();
        assertThat(added.membership().conversationVersion()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT last_read_sequence FROM msg_conversation_members
                 WHERE tenant_id = ? AND conversation_id = ? AND user_id = ?
                """, Long.class, TENANT_ID, conversationId, NEW_MEMBER_ID)).isEqualTo(1);

        var replay = inTransaction(() -> service.addMember(
                conversationId,
                new CollaborationDtos.AddConversationMemberRequest(
                        NEW_MEMBER_ID, CollaborationDtos.MemberRole.MEMBER, 0),
                "membership-add-replay"));
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.membership().conversationVersion()).isEqualTo(1);
        assertError(ErrorCode.RESOURCE_CONFLICT, () -> inTransaction(() -> service.addMember(
                conversationId,
                new CollaborationDtos.AddConversationMemberRequest(
                        OUTSIDER_ID, CollaborationDtos.MemberRole.MEMBER, 0),
                null)));

        assertThat(auditCount(conversationId, "messaging.membership.added")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM msg_audit_events
                 WHERE tenant_id = ? AND event_type = 'messaging.membership.added'
                   AND before_state = '{}'::jsonb AND after_state = '{}'::jsonb
                """, Integer.class, TENANT_ID)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM msg_realtime_events
                 WHERE tenant_id = ? AND conversation_id = ?
                   AND event_type = 'messaging.membership.added'
                   AND payload = '{}'::jsonb
                """, Integer.class, TENANT_ID, conversationId)).isEqualTo(1);

        subject(TENANT_ID, NEW_MEMBER_ID);
        assertThat(collaborationService.search("needle", "MESSAGE", 20).results().messages()).isEmpty();
        assertThat(realtimeRepository.eventsAfter(MessagingRequestContext.get(), 0, 100))
                .extracting(event -> event.messageId())
                .doesNotContain(oldMessageId);

        UUID newMessageId = message(TENANT_ID, conversationId, OWNER_ID, 2, "needle after join");
        assertThat(collaborationService.search("needle", "MESSAGE", 20).results().messages())
                .extracting(CollaborationDtos.MessageSearchResult::messageId)
                .containsExactly(newMessageId);
    }

    @Test
    void enforcesManagerOwnerContinuityProtectedSourcesAndOptimisticVersions() {
        UUID conversationId = conversation(TENANT_ID, "GROUP");
        membership(TENANT_ID, conversationId, OWNER_ID, "OWNER", "DIRECT", "ACTIVE");
        membership(TENANT_ID, conversationId, SECOND_OWNER_ID, "OWNER", "DIRECT", "ACTIVE");
        membership(TENANT_ID, conversationId, MODERATOR_ID, "MODERATOR", "DIRECT", "ACTIVE");
        membership(TENANT_ID, conversationId, MEMBER_ID, "MEMBER", "DIRECT", "ACTIVE");
        membership(TENANT_ID, conversationId, NEW_MEMBER_ID, "MEMBER", "SYSTEM", "ACTIVE");

        subject(TENANT_ID, MODERATOR_ID);
        assertError(ErrorCode.FORBIDDEN, () -> inTransaction(() -> service.updateRole(
                conversationId,
                MEMBER_ID,
                new CollaborationDtos.UpdateConversationMemberRoleRequest(
                        CollaborationDtos.MemberRole.OWNER, 0),
                null)));
        assertError(ErrorCode.INVALID_STATE, () -> inTransaction(() -> service.removeMember(
                conversationId, NEW_MEMBER_ID, 0, null)));
        assertError(ErrorCode.FORBIDDEN, () -> inTransaction(() -> service.removeMember(
                conversationId, SECOND_OWNER_ID, 0, null)));

        var promoted = inTransaction(() -> service.updateRole(
                conversationId,
                MEMBER_ID,
                new CollaborationDtos.UpdateConversationMemberRoleRequest(
                        CollaborationDtos.MemberRole.MODERATOR, 0),
                "role-change-1"));
        assertThat(promoted.membership().members()).anySatisfy(member -> {
            if (member.userId() == MEMBER_ID) {
                assertThat(member.role()).isEqualTo("MODERATOR");
                assertThat(member.version()).isEqualTo(1);
            }
        });
        assertError(ErrorCode.RESOURCE_CONFLICT, () -> inTransaction(() -> service.updateRole(
                conversationId,
                MEMBER_ID,
                new CollaborationDtos.UpdateConversationMemberRoleRequest(
                        CollaborationDtos.MemberRole.MEMBER, 0),
                null)));

        subject(TENANT_ID, OWNER_ID);
        inTransaction(() -> service.removeMember(conversationId, SECOND_OWNER_ID, 0, null));
        assertError(ErrorCode.INVALID_STATE, () -> inTransaction(() -> service.removeMember(
                conversationId, OWNER_ID, 0, null)));
    }

    @Test
    void blocksIdorInactivePeopleUnsupportedConversationTypesAndNonManagers() {
        UUID channel = conversation(TENANT_ID, "CHANNEL");
        membership(TENANT_ID, channel, OWNER_ID, "OWNER", "DIRECT", "ACTIVE");
        UUID direct = conversation(TENANT_ID, "DIRECT");
        membership(TENANT_ID, direct, OWNER_ID, "OWNER", "DIRECT", "ACTIVE");
        UUID announcement = conversation(TENANT_ID, "ANNOUNCEMENT");
        membership(TENANT_ID, announcement, OWNER_ID, "OWNER", "DIRECT", "ACTIVE");
        UUID otherTenantChannel = conversation(OTHER_TENANT_ID, "CHANNEL");
        person(OTHER_TENANT_ID, OWNER_ID, "Other owner", "owner@other.test", "ACTIVE");
        person(OTHER_TENANT_ID, 72_098, "Other member", "member@other.test", "ACTIVE");
        membership(OTHER_TENANT_ID, otherTenantChannel, OWNER_ID, "OWNER", "DIRECT", "ACTIVE");
        person(TENANT_ID, 72_099, "Inactive", "inactive@tenant.test", "INACTIVE");

        assertError(ErrorCode.INVALID_STATE, () -> service.members(direct));
        assertError(ErrorCode.INVALID_STATE, () -> service.members(announcement));
        assertError(ErrorCode.ENTITY_NOT_FOUND, () -> service.members(otherTenantChannel));
        assertError(ErrorCode.INVALID_INPUT_VALUE, () -> inTransaction(() -> service.addMember(
                channel,
                new CollaborationDtos.AddConversationMemberRequest(
                        72_099, CollaborationDtos.MemberRole.MEMBER, 0),
                null)));
        assertError(ErrorCode.INVALID_INPUT_VALUE, () -> inTransaction(() -> service.addMember(
                channel,
                new CollaborationDtos.AddConversationMemberRequest(
                        72_098, CollaborationDtos.MemberRole.MEMBER, 0),
                null)));

        membership(TENANT_ID, channel, MEMBER_ID, "MEMBER", "DIRECT", "ACTIVE");
        subject(TENANT_ID, MEMBER_ID);
        assertError(ErrorCode.FORBIDDEN, () -> inTransaction(() -> service.addMember(
                channel,
                new CollaborationDtos.AddConversationMemberRequest(
                        OUTSIDER_ID, CollaborationDtos.MemberRole.MEMBER, 0),
                null)));
        subject(TENANT_ID, OUTSIDER_ID);
        assertError(ErrorCode.ENTITY_NOT_FOUND, () -> service.members(channel));

        subject(TENANT_ID, NEW_MEMBER_ID);
        membership(TENANT_ID, channel, NEW_MEMBER_ID, "MEMBER", "SPACE_MIRRORED", "ACTIVE");
        assertError(ErrorCode.INVALID_STATE, () -> inTransaction(() -> service.leave(
                channel, new CollaborationDtos.LeaveConversationRequest(0), null)));
    }

    @Test
    void letsDirectMembersLeaveAndRefreshesHistoryWhenTheyRejoin() {
        UUID conversationId = conversation(TENANT_ID, "GROUP");
        membership(TENANT_ID, conversationId, OWNER_ID, "OWNER", "DIRECT", "ACTIVE");
        membership(TENANT_ID, conversationId, MEMBER_ID, "MEMBER", "DIRECT", "ACTIVE");
        message(TENANT_ID, conversationId, OWNER_ID, 1, "first");

        subject(TENANT_ID, MEMBER_ID);
        var left = inTransaction(() -> service.leave(
                conversationId, new CollaborationDtos.LeaveConversationRequest(0), "leave-1"));
        assertThat(left.membership().members())
                .extracting(CollaborationDtos.ManagedMemberSummary::userId)
                .doesNotContain(MEMBER_ID);
        assertThat(auditCount(conversationId, "messaging.membership.left")).isEqualTo(1);

        message(TENANT_ID, conversationId, OWNER_ID, 2, "while absent");
        subject(TENANT_ID, OWNER_ID);
        var rejoined = inTransaction(() -> service.addMember(
                conversationId,
                new CollaborationDtos.AddConversationMemberRequest(
                        MEMBER_ID, CollaborationDtos.MemberRole.MEMBER, 1),
                "rejoin-1"));
        assertThat(rejoined.membership().members())
                .filteredOn(member -> member.userId() == MEMBER_ID)
                .singleElement()
                .satisfies(member -> {
                    assertThat(member.historyStartSequence()).isEqualTo(3);
                    assertThat(member.version()).isEqualTo(2);
                });
    }

    private <T> T inTransaction(Supplier<T> operation) {
        return transactions.execute(status -> operation.get());
    }

    private void assertError(ErrorCode expected, Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    private void subject(long tenantId, long userId) {
        MessagingRequestContext.set(new MessagingRequestContext.Subject(
                userId,
                tenantId,
                null,
                "Integration User",
                Set.of("WORKSPACE_MEMBER"),
                Set.of("APP.MESSAGING:VIEW", "APP.MESSAGING:CREATE", "APP.MESSAGING:UPDATE"),
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

    private UUID conversation(long tenantId, String type) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO msg_conversations (
                    conversation_id, tenant_id, conversation_key, conversation_type,
                    name, visibility, data_classification, lifecycle_state,
                    created_by, updated_by)
                VALUES (?, ?, ?, ?, 'Integration conversation', 'PRIVATE',
                        'INTERNAL', 'ACTIVE', ?, ?)
                """, id, tenantId, "membership:" + id, type, OWNER_ID, OWNER_ID);
        jdbc.update("""
                INSERT INTO msg_conversation_sequences (tenant_id, conversation_id, next_sequence)
                VALUES (?, ?, 1)
                """, tenantId, id);
        return id;
    }

    private void membership(
            long tenantId,
            UUID conversationId,
            long userId,
            String role,
            String source,
            String state) {
        jdbc.update("""
                INSERT INTO msg_conversation_members (
                    tenant_id, conversation_id, user_id, person_public_id,
                    member_role, membership_source, lifecycle_state,
                    history_start_sequence, membership_started_at,
                    created_by, updated_by)
                SELECT ?, ?, person.user_id, person.person_public_id,
                       ?, ?, ?, 1, CURRENT_TIMESTAMP, ?, ?
                  FROM msg_people_snapshot person
                 WHERE person.tenant_id = ? AND person.user_id = ?
                """, tenantId, conversationId, role, source, state,
                userId, userId, tenantId, userId);
    }

    private UUID message(
            long tenantId, UUID conversationId, long senderUserId, long sequence, String body) {
        UUID messageId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO msg_messages (
                    message_id, tenant_id, conversation_id, sequence,
                    sender_user_id, sender_name, body, content_type, message_kind)
                VALUES (?, ?, ?, ?, ?, 'Sender', ?, 'TEXT', 'USER')
                """, messageId, tenantId, conversationId, sequence, senderUserId, body);
        jdbc.update("""
                UPDATE msg_conversation_sequences
                   SET next_sequence = GREATEST(next_sequence, ?)
                 WHERE tenant_id = ? AND conversation_id = ?
                """, sequence + 1, tenantId, conversationId);
        jdbc.update("""
                UPDATE msg_conversations
                   SET last_message_id = ?, last_message_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND conversation_id = ?
                """, messageId, tenantId, conversationId);
        return messageId;
    }

    private void realtimeMessageEvent(
            long tenantId,
            UUID conversationId,
            UUID messageId,
            long messageSequence,
            long actorUserId) {
        jdbc.update("""
                INSERT INTO msg_realtime_events (
                    tenant_id, conversation_id, message_id, message_sequence,
                    actor_user_id, event_type, payload)
                VALUES (?, ?, ?, ?, ?, 'messaging.message.created', '{}'::jsonb)
                """, tenantId, conversationId, messageId, messageSequence, actorUserId);
    }

    private void realtimePrivateMessageEvent(
            long tenantId,
            UUID conversationId,
            UUID messageId,
            long messageSequence,
            long actorUserId,
            long audienceUserId) {
        jdbc.update("""
                INSERT INTO msg_realtime_events (
                    tenant_id, audience_user_id, conversation_id, message_id,
                    message_sequence, actor_user_id, event_type, payload)
                VALUES (?, ?, ?, ?, ?, ?, 'messaging.saved-item.created', '{}'::jsonb)
                """, tenantId, audienceUserId, conversationId, messageId,
                messageSequence, actorUserId);
    }

    private int auditCount(UUID conversationId, String eventType) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM msg_audit_events
                 WHERE tenant_id = ? AND event_type = ? AND object_id LIKE ?
                """, Integer.class, TENANT_ID, eventType, conversationId + ":%");
        return count == null ? 0 : count;
    }
}
