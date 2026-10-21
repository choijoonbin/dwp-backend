package com.dwp.services.time.absence.employment;

import com.dwp.core.event.DomainEventEnvelope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/** Read-only shared-ledger scanner scoped to the exact TIM-003 tuple. */
public final class JdbcEmploymentAbsenceInboxSource
        implements EmploymentAbsenceInboxSource {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcEmploymentAbsenceInboxSource(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public List<InboxCandidate> unresolved(int maximumRows) {
        if (maximumRows < 1 || maximumRows > 500) {
            throw new IllegalArgumentException("maximumRows must be 1..500");
        }
        return jdbc.query("""
                SELECT event_id, payload_sha256, tenant_id, aggregate_id,
                       payload::text AS payload, status, lock_token,
                       locked_until, last_error
                  FROM sys_domain_event_inbox
                 WHERE consumer_name = :consumerName
                   AND tenant_id > 0
                   AND event_source = :eventSource
                   AND aggregate_type = :aggregateType
                   AND status IN ('RECEIVED', 'PROCESSING', 'FAILED', 'DEFERRED',
                                  'REPLAY_PENDING', 'DEAD')
                 ORDER BY CASE WHEN status = 'DEAD' THEN 1 ELSE 0 END,
                          available_at, created_at, event_id
                 LIMIT :maximumRows
                """, new MapSqlParameterSource()
                .addValue("consumerName", EmploymentAbsenceContract.CONSUMER)
                .addValue("eventSource", EmploymentAbsenceContract.EVENT_SOURCE)
                .addValue("aggregateType", EmploymentAbsenceContract.AGGREGATE_TYPE)
                .addValue("maximumRows", maximumRows),
                (result, ignored) -> candidate(result));
    }

    private InboxCandidate candidate(ResultSet result) throws SQLException {
        DomainEventEnvelope event = null;
        try {
            event = mapper.readValue(result.getString("payload"),
                    DomainEventEnvelope.class);
        } catch (JsonProcessingException | RuntimeException malformed) {
            // Preserve the ledger identity so one corrupt row cannot abort the batch.
        }
        return new InboxCandidate(
                event,
                result.getObject("event_id", java.util.UUID.class),
                result.getString("payload_sha256"),
                result.getLong("tenant_id"),
                result.getString("aggregate_id"),
                result.getString("status"),
                result.getString("lock_token"),
                instant(result, "locked_until"),
                result.getString("last_error"));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        OffsetDateTime value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
