package com.dwp.core.audit;

import com.dwp.audit.AuditEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Transactional
public class AuditOutboxRepository {

    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final String relayRole;

    public AuditOutboxRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this(jdbc, objectMapper, "");
    }

    public AuditOutboxRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            String relayRole) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.relayRole = validateRelayRole(relayRole);
    }

    public List<ClaimedEvent> claim(String workerId, int batchSize, int leaseSeconds) {
        applyRelayRole();
        String leaseToken = workerId + ":" + UUID.randomUUID();
        String sql = """
                WITH candidates AS (
                    SELECT outbox_id
                    FROM sys_audit_outbox
                    WHERE status IN ('PENDING', 'FAILED', 'SENDING')
                      AND available_at <= CURRENT_TIMESTAMP
                      AND (locked_until IS NULL OR locked_until < CURRENT_TIMESTAMP)
                    ORDER BY created_at, outbox_id
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE sys_audit_outbox outbox
                SET status = 'SENDING',
                    attempt_count = outbox.attempt_count + 1,
                    locked_by = :leaseToken,
                    locked_until = CURRENT_TIMESTAMP + make_interval(secs => :leaseSeconds),
                    updated_at = CURRENT_TIMESTAMP
                FROM candidates
                WHERE outbox.outbox_id = candidates.outbox_id
                RETURNING outbox.outbox_id, outbox.payload::text, outbox.attempt_count
                """;
        return jdbc.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("leaseToken", leaseToken)
                        .addValue("batchSize", batchSize)
                        .addValue("leaseSeconds", leaseSeconds),
                (result, rowNumber) -> new ClaimedEvent(
                        result.getObject("outbox_id", UUID.class),
                        parse(result.getString("payload")),
                        result.getInt("attempt_count"),
                        leaseToken));
    }

    public int markPublished(List<ClaimedEvent> events) {
        if (events.isEmpty()) return 0;
        applyRelayRole();
        int[] changes = jdbc.batchUpdate("""
                UPDATE sys_audit_outbox
                SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
                    locked_by = NULL, locked_until = NULL, last_error = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE outbox_id = :outboxId
                  AND status = 'SENDING'
                  AND locked_by = :leaseToken
                """, events.stream()
                .map(event -> new MapSqlParameterSource()
                        .addValue("outboxId", event.outboxId())
                        .addValue("leaseToken", event.leaseToken()))
                .toArray(MapSqlParameterSource[]::new));
        return Arrays.stream(changes).filter(change -> change > 0).sum();
    }

    public boolean markFailed(
            ClaimedEvent event,
            int attempts,
            int maximumAttempts,
            String error) {
        applyRelayRole();
        int delaySeconds = Math.min(300, Math.max(2, 1 << Math.min(8, attempts)));
        return jdbc.update("""
                UPDATE sys_audit_outbox
                SET status = CASE WHEN :attempts >= :maximumAttempts THEN 'DEAD' ELSE 'FAILED' END,
                    available_at = CURRENT_TIMESTAMP + make_interval(secs => :delaySeconds),
                    locked_by = NULL, locked_until = NULL,
                    last_error = :error,
                    updated_at = CURRENT_TIMESTAMP
                WHERE outbox_id = :outboxId
                  AND status = 'SENDING'
                  AND locked_by = :leaseToken
                """,
                new MapSqlParameterSource()
                        .addValue("attempts", attempts)
                        .addValue("maximumAttempts", maximumAttempts)
                        .addValue("delaySeconds", delaySeconds)
                        .addValue("error", truncate(error, 500))
                        .addValue("outboxId", event.outboxId())
                        .addValue("leaseToken", event.leaseToken())) > 0;
    }

    public int deletePublishedBefore(Instant cutoff) {
        applyRelayRole();
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

    private void applyRelayRole() {
        if (!relayRole.isBlank()) {
            jdbc.getJdbcOperations().execute("SET LOCAL ROLE " + relayRole);
        }
    }

    private static String validateRelayRole(String relayRole) {
        String normalized = Objects.requireNonNullElse(relayRole, "").trim();
        if (!normalized.isEmpty() && !SQL_IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Audit relay database role is invalid.");
        }
        return normalized;
    }

    private static String truncate(String value, int maximumLength) {
        if (value == null) return "Unknown audit relay failure";
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }

    public record ClaimedEvent(
            UUID outboxId,
            AuditEvent event,
            int attempts,
            String leaseToken) {
    }
}
