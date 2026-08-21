package com.dwp.core.audit;

import com.dwp.audit.AuditEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Transactional
public class AuditOutboxRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AuditOutboxRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<ClaimedEvent> claim(String workerId, int batchSize, int leaseSeconds) {
        String sql = """
                WITH candidates AS (
                    SELECT outbox_id
                    FROM sys_audit_outbox
                    WHERE status IN ('PENDING', 'FAILED')
                      AND available_at <= CURRENT_TIMESTAMP
                      AND (locked_until IS NULL OR locked_until < CURRENT_TIMESTAMP)
                    ORDER BY created_at, outbox_id
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE sys_audit_outbox outbox
                SET status = 'SENDING',
                    attempt_count = outbox.attempt_count + 1,
                    locked_by = :workerId,
                    locked_until = CURRENT_TIMESTAMP + make_interval(secs => :leaseSeconds),
                    updated_at = CURRENT_TIMESTAMP
                FROM candidates
                WHERE outbox.outbox_id = candidates.outbox_id
                RETURNING outbox.outbox_id, outbox.payload::text, outbox.attempt_count
                """;
        return jdbc.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("workerId", workerId)
                        .addValue("batchSize", batchSize)
                        .addValue("leaseSeconds", leaseSeconds),
                (result, rowNumber) -> new ClaimedEvent(
                        result.getObject("outbox_id", UUID.class),
                        parse(result.getString("payload")),
                        result.getInt("attempt_count")));
    }

    public void markPublished(List<UUID> outboxIds) {
        if (outboxIds.isEmpty()) return;
        jdbc.update("""
                UPDATE sys_audit_outbox
                SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
                    locked_by = NULL, locked_until = NULL, last_error = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE outbox_id IN (:ids)
                """, new MapSqlParameterSource("ids", outboxIds));
    }

    public void markFailed(UUID outboxId, int attempts, int maximumAttempts, String error) {
        int delaySeconds = Math.min(300, Math.max(2, 1 << Math.min(8, attempts)));
        jdbc.update("""
                UPDATE sys_audit_outbox
                SET status = CASE WHEN :attempts >= :maximumAttempts THEN 'DEAD' ELSE 'FAILED' END,
                    available_at = CURRENT_TIMESTAMP + make_interval(secs => :delaySeconds),
                    locked_by = NULL, locked_until = NULL,
                    last_error = :error,
                    updated_at = CURRENT_TIMESTAMP
                WHERE outbox_id = :outboxId
                """,
                new MapSqlParameterSource()
                        .addValue("attempts", attempts)
                        .addValue("maximumAttempts", maximumAttempts)
                        .addValue("delaySeconds", delaySeconds)
                        .addValue("error", truncate(error, 500))
                        .addValue("outboxId", outboxId));
    }

    public int deletePublishedBefore(Instant cutoff) {
        return jdbc.update("""
                DELETE FROM sys_audit_outbox
                WHERE status = 'PUBLISHED' AND published_at < :cutoff
                """, new MapSqlParameterSource("cutoff", Timestamp.from(cutoff)));
    }

    private AuditEvent parse(String payload) {
        try {
            return objectMapper.readValue(payload, AuditEvent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid audit outbox payload.", exception);
        }
    }

    private static String truncate(String value, int maximumLength) {
        if (value == null) return "Unknown audit relay failure";
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }

    public record ClaimedEvent(UUID outboxId, AuditEvent event, int attempts) {
    }
}
