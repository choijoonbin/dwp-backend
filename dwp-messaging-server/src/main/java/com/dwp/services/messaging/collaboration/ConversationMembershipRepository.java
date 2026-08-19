package com.dwp.services.messaging.collaboration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ConversationMembershipRepository {

    private final JdbcTemplate jdbc;

    public ConversationMembershipRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ConversationAccess> lockConversation(
            long tenantId, UUID conversationId, long actorUserId) {
        return conversationAccess(tenantId, conversationId, actorUserId, true);
    }

    public Optional<ConversationAccess> conversationAccess(
            long tenantId, UUID conversationId, long actorUserId) {
        return conversationAccess(tenantId, conversationId, actorUserId, false);
    }

    private Optional<ConversationAccess> conversationAccess(
            long tenantId, UUID conversationId, long actorUserId, boolean lock) {
        String lockClause = lock ? " FOR UPDATE OF conversation" : "";
        return jdbc.query("""
                SELECT conversation.conversation_type, conversation.visibility,
                       conversation.lifecycle_state, conversation.version AS conversation_version,
                       actor.member_role AS actor_role,
                       actor.membership_source AS actor_source,
                       actor.version AS actor_version
                  FROM msg_conversations conversation
                  JOIN msg_conversation_members actor
                    ON actor.tenant_id = conversation.tenant_id
                   AND actor.conversation_id = conversation.conversation_id
                   AND actor.user_id = ?
                   AND actor.lifecycle_state = 'ACTIVE'
                  JOIN msg_people_snapshot actor_person
                    ON actor_person.tenant_id = actor.tenant_id
                   AND actor_person.user_id = actor.user_id
                   AND actor_person.lifecycle_state = 'ACTIVE'
                 WHERE conversation.tenant_id = ?
                   AND conversation.conversation_id = ?
                   AND conversation.lifecycle_state = 'ACTIVE'
                """ + lockClause, (row, ignored) -> new ConversationAccess(
                row.getString("conversation_type"),
                row.getString("visibility"),
                row.getString("lifecycle_state"),
                row.getLong("conversation_version"),
                row.getString("actor_role"),
                row.getString("actor_source"),
                row.getLong("actor_version")), actorUserId, tenantId, conversationId)
                .stream()
                .findFirst();
    }

    public Optional<PersonRecord> activePerson(long tenantId, long userId) {
        return jdbc.query("""
                SELECT user_id, person_public_id, display_name, email_address,
                       job_title, organization_name
                  FROM msg_people_snapshot
                 WHERE tenant_id = ? AND user_id = ? AND lifecycle_state = 'ACTIVE'
                """, (row, ignored) -> new PersonRecord(
                row.getLong("user_id"),
                row.getObject("person_public_id", UUID.class),
                row.getString("display_name"),
                row.getString("email_address"),
                row.getString("job_title"),
                row.getString("organization_name")), tenantId, userId).stream().findFirst();
    }

    public Optional<MemberRecord> member(long tenantId, UUID conversationId, long userId) {
        return jdbc.query(memberSelect() + """
                 WHERE member.tenant_id = ?
                   AND member.conversation_id = ?
                   AND member.user_id = ?
                """, (row, ignored) -> member(row), tenantId, conversationId, userId)
                .stream()
                .findFirst();
    }

    public List<MemberRecord> activeMembers(long tenantId, UUID conversationId) {
        return jdbc.query(memberSelect() + """
                 WHERE member.tenant_id = ?
                   AND member.conversation_id = ?
                   AND member.lifecycle_state = 'ACTIVE'
                 ORDER BY CASE member.member_role
                              WHEN 'OWNER' THEN 0 WHEN 'MODERATOR' THEN 1
                              WHEN 'MEMBER' THEN 2 ELSE 3 END,
                          lower(person.display_name), member.user_id
                """, (row, ignored) -> member(row), tenantId, conversationId);
    }

    public long nextHistoryStartSequence(long tenantId, UUID conversationId) {
        jdbc.update("""
                INSERT INTO msg_conversation_sequences (tenant_id, conversation_id, next_sequence)
                SELECT conversation.tenant_id, conversation.conversation_id,
                       COALESCE(MAX(message.sequence), 0) + 1
                  FROM msg_conversations conversation
                  LEFT JOIN msg_messages message
                    ON message.tenant_id = conversation.tenant_id
                   AND message.conversation_id = conversation.conversation_id
                 WHERE conversation.tenant_id = ? AND conversation.conversation_id = ?
                 GROUP BY conversation.tenant_id, conversation.conversation_id
                ON CONFLICT (tenant_id, conversation_id) DO NOTHING
                """, tenantId, conversationId);
        Long next = jdbc.queryForObject("""
                SELECT next_sequence
                  FROM msg_conversation_sequences
                 WHERE tenant_id = ? AND conversation_id = ?
                 FOR UPDATE
                """, Long.class, tenantId, conversationId);
        if (next == null || next < 1) {
            throw new IllegalStateException("Conversation message sequence is unavailable.");
        }
        return next;
    }

    public int activeMemberCount(long tenantId, UUID conversationId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM msg_conversation_members
                 WHERE tenant_id = ? AND conversation_id = ?
                   AND lifecycle_state = 'ACTIVE'
                """, Integer.class, tenantId, conversationId);
        return count == null ? 0 : count;
    }

    public int activeOwnerCount(long tenantId, UUID conversationId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM msg_conversation_members
                 WHERE tenant_id = ? AND conversation_id = ?
                   AND lifecycle_state = 'ACTIVE' AND member_role = 'OWNER'
                """, Integer.class, tenantId, conversationId);
        return count == null ? 0 : count;
    }

    public int advanceConversationVersion(
            long tenantId, UUID conversationId, long actorUserId, long expectedVersion) {
        return jdbc.update("""
                UPDATE msg_conversations
                   SET version = version + 1,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?
                 WHERE tenant_id = ? AND conversation_id = ? AND version = ?
                   AND lifecycle_state = 'ACTIVE'
                """, actorUserId, tenantId, conversationId, expectedVersion);
    }

    public int addOrReactivate(
            long tenantId,
            UUID conversationId,
            PersonRecord person,
            CollaborationDtos.MemberRole role,
            long historyStartSequence,
            long actorUserId) {
        Integer version = jdbc.queryForObject("""
                INSERT INTO msg_conversation_members (
                    tenant_id, conversation_id, user_id, person_public_id,
                    member_role, membership_source, notification_level,
                    favorite, pinned, last_read_message_id, last_read_sequence,
                    last_read_at, history_start_sequence, membership_started_at,
                    lifecycle_state, version, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'DIRECT', 'DEFAULT', FALSE, FALSE, NULL, ?,
                        NULL, ?, CURRENT_TIMESTAMP, 'ACTIVE', 0, ?, ?)
                ON CONFLICT (tenant_id, conversation_id, user_id) DO UPDATE SET
                    person_public_id = EXCLUDED.person_public_id,
                    member_role = EXCLUDED.member_role,
                    membership_source = 'DIRECT',
                    notification_level = 'DEFAULT',
                    favorite = FALSE,
                    pinned = FALSE,
                    last_read_message_id = NULL,
                    last_read_sequence = EXCLUDED.last_read_sequence,
                    last_read_at = NULL,
                    history_start_sequence = EXCLUDED.history_start_sequence,
                    membership_started_at = CURRENT_TIMESTAMP,
                    lifecycle_state = 'ACTIVE',
                    version = msg_conversation_members.version + 1,
                    updated_at = CURRENT_TIMESTAMP,
                    updated_by = EXCLUDED.updated_by
                WHERE msg_conversation_members.lifecycle_state = 'REVOKED'
                RETURNING version
                """, Integer.class,
                tenantId, conversationId, person.userId(), person.personPublicId(), role.name(),
                historyStartSequence - 1, historyStartSequence, actorUserId, actorUserId);
        return version == null ? -1 : version;
    }

    public int updateRole(
            long tenantId,
            UUID conversationId,
            long userId,
            CollaborationDtos.MemberRole role,
            long expectedVersion,
            long actorUserId) {
        return jdbc.update("""
                UPDATE msg_conversation_members
                   SET member_role = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND conversation_id = ? AND user_id = ?
                   AND lifecycle_state = 'ACTIVE' AND version = ?
                """, role.name(), actorUserId,
                tenantId, conversationId, userId, expectedVersion);
    }

    public int revoke(
            long tenantId,
            UUID conversationId,
            long userId,
            long expectedVersion,
            long actorUserId) {
        return jdbc.update("""
                UPDATE msg_conversation_members
                   SET lifecycle_state = 'REVOKED', version = version + 1,
                       favorite = FALSE, pinned = FALSE,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND conversation_id = ? AND user_id = ?
                   AND lifecycle_state = 'ACTIVE' AND version = ?
                """, actorUserId, tenantId, conversationId, userId, expectedVersion);
    }

    public void recordAudit(
            long tenantId,
            long actorUserId,
            String eventType,
            UUID conversationId,
            long targetUserId,
            String correlationId) {
        jdbc.update("""
                INSERT INTO msg_audit_events (
                    tenant_id, actor_user_id, event_type, object_type, object_id,
                    before_state, after_state, correlation_id)
                VALUES (?, ?, ?, 'CONVERSATION_MEMBERSHIP', ?, '{}'::jsonb, '{}'::jsonb, ?)
                """, tenantId, actorUserId, eventType,
                conversationId + ":" + targetUserId, correlationId);
    }

    private String memberSelect() {
        return """
                SELECT member.user_id, member.person_public_id,
                       person.display_name, person.email_address,
                       person.job_title, person.organization_name,
                       member.member_role, member.membership_source,
                       member.lifecycle_state, member.history_start_sequence,
                       member.membership_started_at, member.version
                  FROM msg_conversation_members member
                  LEFT JOIN msg_people_snapshot person
                    ON person.tenant_id = member.tenant_id
                   AND person.user_id = member.user_id
                """;
    }

    private MemberRecord member(java.sql.ResultSet row) throws java.sql.SQLException {
        return new MemberRecord(
                row.getLong("user_id"),
                row.getObject("person_public_id", UUID.class),
                row.getString("display_name"),
                row.getString("email_address"),
                row.getString("job_title"),
                row.getString("organization_name"),
                row.getString("member_role"),
                row.getString("membership_source"),
                row.getString("lifecycle_state"),
                row.getLong("history_start_sequence"),
                row.getObject("membership_started_at", OffsetDateTime.class),
                row.getLong("version"));
    }

    public record ConversationAccess(
            String conversationType,
            String visibility,
            String lifecycleState,
            long conversationVersion,
            String actorRole,
            String actorSource,
            long actorVersion) {
    }

    public record PersonRecord(
            long userId,
            UUID personPublicId,
            String displayName,
            String emailAddress,
            String jobTitle,
            String organizationName) {
    }

    public record MemberRecord(
            long userId,
            UUID personPublicId,
            String displayName,
            String emailAddress,
            String jobTitle,
            String organizationName,
            String role,
            String source,
            String lifecycleState,
            long historyStartSequence,
            OffsetDateTime membershipStartedAt,
            long version) {

        boolean active() {
            return "ACTIVE".equals(lifecycleState);
        }
    }
}
