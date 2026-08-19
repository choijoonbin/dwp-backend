package com.dwp.services.messaging.collaboration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ConversationCreationRepository {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public ConversationCreationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    public void lockCreationRequest(long lockKey) {
        jdbc.execute(connection -> {
            var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)");
            statement.setLong(1, lockKey);
            return statement;
        }, (PreparedStatementCallback<Void>) statement -> {
            statement.execute();
            return null;
        });
    }

    public Optional<CreationRecord> findCreation(
            long tenantId,
            long requesterUserId,
            String idempotencyKey) {
        return jdbc.query("""
                SELECT request_fingerprint, conversation_id
                  FROM msg_conversation_creation_requests
                 WHERE tenant_id = ? AND requester_user_id = ? AND idempotency_key = ?
                """, (resultSet, ignored) -> new CreationRecord(
                        resultSet.getString("request_fingerprint"),
                        resultSet.getObject("conversation_id", UUID.class)),
                tenantId, requesterUserId, idempotencyKey).stream().findFirst();
    }

    public Map<Long, PersonRecord> activePeople(long tenantId, Collection<Long> userIds) {
        if (userIds.isEmpty()) return Map.of();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("userIds", userIds);
        Map<Long, PersonRecord> people = new LinkedHashMap<>();
        namedJdbc.query("""
                SELECT user_id, person_public_id, display_name, email_address
                  FROM msg_people_snapshot
                 WHERE tenant_id = :tenantId
                   AND user_id IN (:userIds)
                   AND lifecycle_state = 'ACTIVE'
                """, parameters, resultSet -> {
                    PersonRecord person = new PersonRecord(
                            resultSet.getLong("user_id"),
                            resultSet.getObject("person_public_id", UUID.class),
                            resultSet.getString("display_name"),
                            resultSet.getString("email_address"));
                    people.put(person.userId(), person);
                });
        return Map.copyOf(people);
    }

    public UUID insertConversation(
            long tenantId,
            long actorUserId,
            CollaborationDtos.ConversationType type,
            String name,
            String topic) {
        UUID conversationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO msg_conversations (
                    conversation_id, tenant_id, conversation_key, conversation_type,
                    name, topic, visibility, data_classification, lifecycle_state,
                    created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, 'PRIVATE', 'INTERNAL', 'ACTIVE', ?, ?)
                """, conversationId, tenantId,
                type.name().toLowerCase() + ':' + conversationId,
                type.name(), name, topic, actorUserId, actorUserId);
        return conversationId;
    }

    public void insertMembers(
            long tenantId,
            UUID conversationId,
            long ownerUserId,
            Collection<PersonRecord> people) {
        List<Object[]> rows = new ArrayList<>(people.size());
        for (PersonRecord person : people) {
            String role = person.userId() == ownerUserId ? "OWNER" : "MEMBER";
            rows.add(new Object[]{
                    tenantId,
                    conversationId,
                    person.userId(),
                    person.personPublicId(),
                    role,
                    ownerUserId,
                    ownerUserId
            });
        }
        jdbc.batchUpdate("""
                INSERT INTO msg_conversation_members (
                    tenant_id, conversation_id, user_id, person_public_id,
                    member_role, membership_source, lifecycle_state,
                    created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'DIRECT', 'ACTIVE', ?, ?)
                """, rows);
    }

    public void insertCreationRequest(
            long tenantId,
            long requesterUserId,
            String idempotencyKey,
            String fingerprint,
            UUID conversationId) {
        jdbc.update("""
                INSERT INTO msg_conversation_creation_requests (
                    tenant_id, requester_user_id, idempotency_key,
                    request_fingerprint, conversation_id)
                VALUES (?, ?, ?, ?, ?)
                """, tenantId, requesterUserId, idempotencyKey, fingerprint, conversationId);
    }

    public void recordCreatedAudit(
            long tenantId,
            long actorUserId,
            UUID conversationId,
            CollaborationDtos.ConversationType type) {
        jdbc.update("""
                INSERT INTO msg_audit_events (
                    tenant_id, actor_user_id, event_type, object_type, object_id, after_state)
                VALUES (?, ?, 'MESSAGING_CONVERSATION_CREATED', 'CONVERSATION', ?,
                        jsonb_build_object('conversationType', ?, 'visibility', 'PRIVATE'))
                """, tenantId, actorUserId, conversationId.toString(), type.name());
    }

    public StoredConversation conversation(long tenantId, UUID conversationId) {
        StoredConversation conversation = jdbc.queryForObject("""
                SELECT conversation_id, conversation_type, name, topic, visibility,
                       lifecycle_state, created_at
                  FROM msg_conversations
                 WHERE tenant_id = ? AND conversation_id = ?
                """, (resultSet, ignored) -> new StoredConversation(
                        resultSet.getObject("conversation_id", UUID.class),
                        CollaborationDtos.ConversationType.valueOf(
                                resultSet.getString("conversation_type")),
                        resultSet.getString("name"),
                        resultSet.getString("topic"),
                        resultSet.getString("visibility"),
                        resultSet.getString("lifecycle_state"),
                        resultSet.getObject("created_at", OffsetDateTime.class)),
                tenantId, conversationId);

        List<StoredMember> members = jdbc.query("""
                SELECT member.user_id, member.person_public_id, person.display_name,
                       person.email_address, member.member_role
                  FROM msg_conversation_members member
                  JOIN msg_people_snapshot person
                    ON person.tenant_id = member.tenant_id
                   AND person.user_id = member.user_id
                 WHERE member.tenant_id = ? AND member.conversation_id = ?
                   AND member.lifecycle_state = 'ACTIVE'
                 ORDER BY CASE member.member_role WHEN 'OWNER' THEN 0 ELSE 1 END,
                          lower(person.display_name), member.user_id
                """, (resultSet, ignored) -> new StoredMember(
                        resultSet.getLong("user_id"),
                        resultSet.getObject("person_public_id", UUID.class),
                        resultSet.getString("display_name"),
                        resultSet.getString("email_address"),
                        resultSet.getString("member_role")),
                tenantId, conversationId);
        return conversation.withMembers(members);
    }

    public record PersonRecord(
            long userId,
            UUID personPublicId,
            String displayName,
            String emailAddress) {
    }

    public record CreationRecord(String fingerprint, UUID conversationId) {
    }

    public record StoredMember(
            long userId,
            UUID personPublicId,
            String displayName,
            String emailAddress,
            String role) {
    }

    public record StoredConversation(
            UUID conversationId,
            CollaborationDtos.ConversationType type,
            String name,
            String topic,
            String visibility,
            String lifecycleState,
            OffsetDateTime createdAt,
            List<StoredMember> members) {

        StoredConversation(
                UUID conversationId,
                CollaborationDtos.ConversationType type,
                String name,
                String topic,
                String visibility,
                String lifecycleState,
                OffsetDateTime createdAt) {
            this(conversationId, type, name, topic, visibility, lifecycleState, createdAt, List.of());
        }

        StoredConversation withMembers(List<StoredMember> members) {
            return new StoredConversation(
                    conversationId, type, name, topic, visibility,
                    lifecycleState, createdAt, List.copyOf(members));
        }
    }
}
