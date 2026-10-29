package com.dwp.services.platform.mail;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
class MailEventStreamRepository {

    private final JdbcTemplate jdbc;

    MailEventStreamRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    List<EventEnvelope> catchUp(
            long tenantId,
            long userId,
            long after,
            int limit) {
        return jdbc.query("""
                SELECT event.event_sequence, event.event_type,
                       thread.thread_id, event.occurred_at
                  FROM mail_domain_events event
                  LEFT JOIN mail_action_proposals proposal
                    ON event.aggregate_type = 'MAIL_ACTION_PROPOSAL'
                   AND proposal.tenant_id = event.tenant_id
                   AND proposal.proposal_id = event.aggregate_id
                  LEFT JOIN mail_messages message
                    ON event.aggregate_type = 'MAIL_MESSAGE'
                   AND message.tenant_id = event.tenant_id
                   AND message.message_id = event.aggregate_id
                  JOIN mail_threads thread
                    ON thread.tenant_id = event.tenant_id
                   AND thread.thread_id = CASE event.aggregate_type
                       WHEN 'MAIL_THREAD' THEN event.aggregate_id
                       WHEN 'MAIL_ACTION_PROPOSAL' THEN proposal.thread_id
                       WHEN 'MAIL_MESSAGE' THEN message.thread_id
                   END
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                 WHERE event.tenant_id = ?
                   AND event.event_sequence > ?
                   AND event.aggregate_type IN (
                       'MAIL_THREAD', 'MAIL_ACTION_PROPOSAL', 'MAIL_MESSAGE')
                """ + MailAccessSql.THREAD_ACCESS + """
                 ORDER BY event.event_sequence
                 LIMIT ?
                """, (result, ignored) -> new EventEnvelope(
                result.getLong("event_sequence"),
                result.getString("event_type"),
                result.getObject("thread_id", UUID.class),
                result.getObject("occurred_at", OffsetDateTime.class)),
                tenantId, after, userId, userId, limit);
    }

    record EventEnvelope(
            long sequence,
            String type,
            UUID threadId,
            OffsetDateTime occurredAt) { }
}
