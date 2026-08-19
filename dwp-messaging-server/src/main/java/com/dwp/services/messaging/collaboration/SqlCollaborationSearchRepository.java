package com.dwp.services.messaging.collaboration;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Repository
public class SqlCollaborationSearchRepository implements CollaborationSearchPort {

    private final NamedParameterJdbcTemplate jdbc;

    public SqlCollaborationSearchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<SearchDocument> search(SearchCriteria criteria) {
        String term = criteria.query().toLowerCase(Locale.ROOT);
        String escapedTerm = escapeLike(term);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantId", criteria.tenantId())
                .addValue("userId", criteria.userId())
                .addValue("term", term)
                .addValue("prefix", escapedTerm + "%")
                .addValue("pattern", "%" + escapedTerm + "%")
                .addValue("limit", criteria.limit());

        List<SearchDocument> documents = new ArrayList<>();
        if (criteria.types().contains(CollaborationDtos.SearchType.CONVERSATION)) {
            documents.addAll(searchConversations(parameters));
        }
        if (criteria.types().contains(CollaborationDtos.SearchType.MESSAGE)) {
            documents.addAll(searchMessages(parameters));
        }
        if (criteria.types().contains(CollaborationDtos.SearchType.PERSON)) {
            documents.addAll(searchPeople(parameters));
        }

        Comparator<SearchDocument> byRelevance = Comparator
                .comparingInt(SearchDocument::score).reversed()
                .thenComparing(SearchDocument::occurredAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(document -> document.type().name())
                .thenComparing(SearchDocument::title,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
        return documents.stream()
                .sorted(byRelevance)
                .limit(criteria.limit())
                .toList();
    }

    private List<SearchDocument> searchConversations(MapSqlParameterSource parameters) {
        return jdbc.query("""
                SELECT conversation.conversation_id,
                       conversation.conversation_type,
                       conversation.name,
                       conversation.topic,
                       COALESCE(conversation.last_message_at, conversation.created_at) AS occurred_at,
                       CASE
                           WHEN lower(COALESCE(conversation.name, '')) = :term THEN 100
                           WHEN lower(COALESCE(conversation.name, '')) LIKE :prefix ESCAPE E'\\\\' THEN 85
                           WHEN lower(COALESCE(conversation.name, '')) LIKE :pattern ESCAPE E'\\\\' THEN 70
                           ELSE 40
                       END AS relevance
                  FROM msg_conversations conversation
                  JOIN msg_conversation_members membership
                    ON membership.tenant_id = conversation.tenant_id
                   AND membership.conversation_id = conversation.conversation_id
                   AND membership.user_id = :userId
                   AND membership.lifecycle_state = 'ACTIVE'
                 WHERE conversation.tenant_id = :tenantId
                   AND conversation.lifecycle_state = 'ACTIVE'
                   AND (
                       lower(COALESCE(conversation.name, '')) LIKE :pattern ESCAPE E'\\\\'
                       OR lower(COALESCE(conversation.topic, '')) LIKE :pattern ESCAPE E'\\\\'
                   )
                 ORDER BY relevance DESC, occurred_at DESC, conversation.conversation_id
                 LIMIT :limit
                """, parameters, (resultSet, ignored) -> new SearchDocument(
                CollaborationDtos.SearchType.CONVERSATION,
                resultSet.getInt("relevance"),
                resultSet.getObject("conversation_id", UUID.class),
                null,
                null,
                null,
                resultSet.getString("conversation_type"),
                resultSet.getString("name"),
                resultSet.getString("topic"),
                resultSet.getString("topic"),
                null,
                null,
                null,
                null,
                offsetDateTime(resultSet, "occurred_at")));
    }

    private List<SearchDocument> searchMessages(MapSqlParameterSource parameters) {
        return jdbc.query("""
                SELECT message.message_id,
                       message.conversation_id,
                       message.sender_name,
                       message.body,
                       message.created_at AS occurred_at,
                       conversation.name AS conversation_name,
                       conversation.conversation_type,
                       CASE
                           WHEN lower(message.body) = :term THEN 95
                           WHEN lower(message.body) LIKE :prefix ESCAPE E'\\\\' THEN 75
                           ELSE 60
                       END AS relevance
                  FROM msg_messages message
                  JOIN msg_conversations conversation
                    ON conversation.tenant_id = message.tenant_id
                   AND conversation.conversation_id = message.conversation_id
                   AND conversation.lifecycle_state = 'ACTIVE'
                  JOIN msg_conversation_members membership
                    ON membership.tenant_id = message.tenant_id
                   AND membership.conversation_id = message.conversation_id
                   AND membership.user_id = :userId
                   AND membership.lifecycle_state = 'ACTIVE'
                   AND message.sequence >= membership.history_start_sequence
                 WHERE message.tenant_id = :tenantId
                   AND message.deleted_at IS NULL
                   AND lower(message.body) LIKE :pattern ESCAPE E'\\\\'
                 ORDER BY relevance DESC, occurred_at DESC, message.message_id
                 LIMIT :limit
                """, parameters, (resultSet, ignored) -> new SearchDocument(
                CollaborationDtos.SearchType.MESSAGE,
                resultSet.getInt("relevance"),
                resultSet.getObject("conversation_id", UUID.class),
                resultSet.getObject("message_id", UUID.class),
                null,
                null,
                resultSet.getString("conversation_type"),
                resultSet.getString("sender_name"),
                resultSet.getString("conversation_name"),
                resultSet.getString("body"),
                null,
                null,
                null,
                null,
                offsetDateTime(resultSet, "occurred_at")));
    }

    private List<SearchDocument> searchPeople(MapSqlParameterSource parameters) {
        return jdbc.query("""
                SELECT person.user_id,
                       person.person_public_id,
                       person.display_name,
                       person.email_address,
                       person.job_title,
                       person.organization_name,
                       person.presence_state,
                       person.updated_at AS occurred_at,
                       CASE
                           WHEN lower(person.email_address) = :term THEN 100
                           WHEN lower(person.display_name) = :term THEN 95
                           WHEN lower(person.display_name) LIKE :prefix ESCAPE E'\\\\' THEN 80
                           WHEN lower(person.email_address) LIKE :prefix ESCAPE E'\\\\' THEN 75
                           ELSE 50
                       END AS relevance
                  FROM msg_people_snapshot person
                 WHERE person.tenant_id = :tenantId
                   AND person.lifecycle_state = 'ACTIVE'
                   AND (
                       lower(person.display_name) LIKE :pattern ESCAPE E'\\\\'
                       OR lower(person.email_address) LIKE :pattern ESCAPE E'\\\\'
                       OR lower(COALESCE(person.job_title, '')) LIKE :pattern ESCAPE E'\\\\'
                       OR lower(COALESCE(person.organization_name, '')) LIKE :pattern ESCAPE E'\\\\'
                   )
                 ORDER BY relevance DESC, lower(person.display_name), person.user_id
                 LIMIT :limit
                """, parameters, (resultSet, ignored) -> new SearchDocument(
                CollaborationDtos.SearchType.PERSON,
                resultSet.getInt("relevance"),
                null,
                null,
                resultSet.getLong("user_id"),
                resultSet.getObject("person_public_id", UUID.class),
                null,
                resultSet.getString("display_name"),
                resultSet.getString("email_address"),
                null,
                resultSet.getString("email_address"),
                resultSet.getString("job_title"),
                resultSet.getString("organization_name"),
                resultSet.getString("presence_state"),
                offsetDateTime(resultSet, "occurred_at")));
    }

    private String escapeLike(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private OffsetDateTime offsetDateTime(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class);
    }
}
