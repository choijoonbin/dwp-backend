package com.dwp.services.people.hr.performance.compensationplanning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Claims Performance events with a visibility lease while retaining the sealed V17 schema.
 * The incremented delivery attempt is the fencing generation for stale workers.
 */
@Repository
class CompensationPlanningEventHandoffRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    CompensationPlanningEventHandoffRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.mapper = objectMapper.copy().findAndRegisterModules();
    }

    @Transactional
    List<ClaimedEvent> claim(
            String workerId,
            int batchSize,
            Duration visibilityLease,
            Set<String> supportedAggregateTypes) {
        int visibilitySeconds = Math.toIntExact(Math.min(
                Integer.MAX_VALUE,
                Math.max(1L, visibilityLease.toSeconds())));
        return jdbc.query("""
                WITH candidates AS (
                    SELECT candidate.internal_id
                      FROM hris_performance.prf_outbox_events candidate
                     WHERE candidate.delivery_state = 'PENDING'
                       AND candidate.available_at <= CURRENT_TIMESTAMP
                       AND candidate.aggregate_type IN (:aggregateTypes)
                       AND NOT EXISTS (
                           SELECT 1
                             FROM hris_performance.prf_outbox_events predecessor
                            WHERE predecessor.tenant_id = candidate.tenant_id
                              AND predecessor.aggregate_type = candidate.aggregate_type
                              AND predecessor.aggregate_id = candidate.aggregate_id
                              AND predecessor.aggregate_version < candidate.aggregate_version
                              AND predecessor.delivery_state <> 'DELIVERED'
                       )
                     ORDER BY candidate.available_at, candidate.internal_id
                     LIMIT :batchSize
                     FOR UPDATE SKIP LOCKED
                )
                UPDATE hris_performance.prf_outbox_events outbox
                   SET delivery_attempts = outbox.delivery_attempts + 1,
                       available_at = CURRENT_TIMESTAMP
                           + make_interval(secs => :visibilitySeconds)
                  FROM candidates
                 WHERE outbox.internal_id = candidates.internal_id
                RETURNING outbox.event_id, outbox.tenant_id,
                          outbox.command_receipt_id, outbox.aggregate_type,
                          outbox.aggregate_id, outbox.aggregate_version,
                          outbox.event_type, outbox.payload,
                          outbox.delivery_attempts, outbox.created_at
                """, new MapSqlParameterSource()
                        .addValue("visibilitySeconds", visibilitySeconds)
                        .addValue("aggregateTypes", supportedAggregateTypes)
                        .addValue("batchSize", batchSize),
                (result, ignored) -> new ClaimedEvent(
                        result.getObject("event_id", UUID.class),
                        result.getLong("tenant_id"),
                        result.getObject("command_receipt_id", UUID.class),
                        result.getString("aggregate_type"),
                        result.getObject("aggregate_id", UUID.class),
                        result.getLong("aggregate_version"),
                        result.getString("event_type"),
                        readPayload(result.getString("payload")),
                        result.getInt("delivery_attempts"),
                        result.getTimestamp("created_at").toInstant(),
                        workerId));
    }

    boolean markDelivered(ClaimedEvent event) {
        return jdbc.update("""
                UPDATE hris_performance.prf_outbox_events
                   SET delivery_state = 'DELIVERED',
                       available_at = CURRENT_TIMESTAMP
                 WHERE event_id = :eventId
                   AND delivery_state = 'PENDING'
                   AND delivery_attempts = :deliveryAttempt
                """, claimParameters(event)) == 1;
    }

    FailureDisposition markFailed(
            ClaimedEvent event,
            int maximumAttempts,
            boolean retryable) {
        int delaySeconds = Math.min(
                300,
                Math.max(2, 1 << Math.min(8, event.deliveryAttempt())));
        List<String> states = jdbc.query("""
                UPDATE hris_performance.prf_outbox_events
                   SET delivery_state = CASE
                           WHEN NOT :retryable OR delivery_attempts >= :maximumAttempts
                           THEN 'QUARANTINED'
                           ELSE 'PENDING'
                       END,
                       available_at = CASE
                           WHEN NOT :retryable OR delivery_attempts >= :maximumAttempts
                           THEN available_at
                           ELSE CURRENT_TIMESTAMP
                               + make_interval(secs => :delaySeconds)
                       END
                 WHERE event_id = :eventId
                   AND delivery_state = 'PENDING'
                   AND delivery_attempts = :deliveryAttempt
                RETURNING delivery_state
                """, claimParameters(event)
                        .addValue("retryable", retryable)
                        .addValue("maximumAttempts", maximumAttempts)
                        .addValue("delaySeconds", delaySeconds),
                (result, ignored) -> result.getString("delivery_state"));
        if (states.isEmpty()) return FailureDisposition.STALE;
        return "QUARANTINED".equals(states.getFirst())
                ? FailureDisposition.QUARANTINED
                : FailureDisposition.RETRY_PENDING;
    }

    Optional<ReleasedEvent> releaseQuarantined(
            long tenantId,
            UUID eventId,
            Set<String> supportedCycleEventNames,
            Set<String> supportedProposalEventNames) {
        List<ReleasedEvent> released = jdbc.query("""
                WITH quarantined AS (
                    SELECT internal_id, delivery_attempts
                      FROM hris_performance.prf_outbox_events
                     WHERE tenant_id = :tenantId
                       AND event_id = :eventId
                       AND delivery_state = 'QUARANTINED'
                       AND (
                           (aggregate_type = 'COMPENSATION_CYCLE'
                               AND event_type IN (:cycleEventNames))
                           OR
                           (aggregate_type = 'COMPENSATION_PROPOSAL'
                               AND event_type IN (:proposalEventNames))
                       )
                     FOR UPDATE
                )
                UPDATE hris_performance.prf_outbox_events outbox
                   SET delivery_state = 'PENDING',
                       delivery_attempts = 0,
                       available_at = CURRENT_TIMESTAMP
                  FROM quarantined
                 WHERE outbox.internal_id = quarantined.internal_id
                RETURNING outbox.event_id, outbox.tenant_id,
                          outbox.aggregate_type, outbox.aggregate_id,
                          outbox.aggregate_version, outbox.event_type,
                          quarantined.delivery_attempts AS previous_delivery_attempts
                """, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("eventId", eventId)
                        .addValue("cycleEventNames", supportedCycleEventNames)
                        .addValue("proposalEventNames", supportedProposalEventNames),
                (result, ignored) -> new ReleasedEvent(
                        result.getObject("event_id", UUID.class),
                        result.getLong("tenant_id"),
                        result.getString("aggregate_type"),
                        result.getObject("aggregate_id", UUID.class),
                        result.getLong("aggregate_version"),
                        result.getString("event_type"),
                        result.getInt("previous_delivery_attempts")));
        return released.stream().findFirst();
    }

    void recordReplayRequest(
            long tenantId,
            UUID eventId,
            long actorId,
            String reason) {
        jdbc.update("""
                INSERT INTO sys_domain_event_replay_audit (
                    replay_request_id, direction, target_id, consumer_name,
                    requested_by, reason, requested_at)
                VALUES (
                    :requestId, 'OUTBOX', :targetId, :consumerName,
                    :actor, :reason, CURRENT_TIMESTAMP)
                """, new MapSqlParameterSource()
                        .addValue("requestId", UUID.randomUUID())
                        .addValue("targetId", eventId.toString())
                        .addValue("consumerName", "hris-performance-compensation-handoff")
                        .addValue("actor", "tenant:" + tenantId + ":user:" + actorId)
                        .addValue("reason", reason));
    }

    private MapSqlParameterSource claimParameters(ClaimedEvent event) {
        return new MapSqlParameterSource()
                .addValue("eventId", event.eventId())
                .addValue("deliveryAttempt", event.deliveryAttempt());
    }

    private JsonNode readPayload(String payload) {
        try {
            return mapper.readTree(payload);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid Performance outbox payload.", exception);
        }
    }

    record ClaimedEvent(
            UUID eventId,
            long tenantId,
            UUID commandReceiptId,
            String aggregateType,
            UUID aggregateId,
            long aggregateVersion,
            String eventName,
            JsonNode payload,
            int deliveryAttempt,
            Instant createdAt,
            String workerId) {
    }

    record ReleasedEvent(
            UUID eventId,
            long tenantId,
            String aggregateType,
            UUID aggregateId,
            long aggregateVersion,
            String eventName,
            int previousDeliveryAttempts) {
    }

    enum FailureDisposition {
        RETRY_PENDING,
        QUARANTINED,
        STALE
    }
}
