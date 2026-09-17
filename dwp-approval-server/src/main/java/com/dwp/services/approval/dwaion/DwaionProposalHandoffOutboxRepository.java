package com.dwp.services.approval.dwaion;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalDtos;
import com.dwp.services.approval.security.ApprovalRequestContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class DwaionProposalHandoffOutboxRepository {
    private final JdbcTemplate jdbc;

    public DwaionProposalHandoffOutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void bindDraft(
            ApprovalRequestContext.Actor actor,
            UUID requestId,
            DwaionProposalHandoffBinding binding,
            DwaionProposalHandoffIdentity identity,
            String correlationId) {
        if (actor == null || requestId == null || binding == null || identity == null
                || binding.version() == null || binding.version() != 1
                || binding.handoffId() == null || binding.proposalId() == null
                || !"APPROVAL.REQUEST.CREATE".equals(binding.actionKey())
                || binding.handoffVersion() == null || binding.handoffVersion() < 1) {
            throw invalid();
        }
        String roles = tokens(actor.roles(), 4_000);
        String permissions = tokens(actor.permissions(), 8_000);
        String correlation = canonical(correlationId, 160)
                ? correlationId
                : requestId.toString();
        int inserted = jdbc.update("""
                INSERT INTO apr_dwaion_proposal_handoffs (
                    binding_id, tenant_id, request_id, owner_user_id, person_public_id,
                    handoff_id, proposal_id, action_key, handoff_version,
                    auth_session_id, roles, permissions, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), actor.tenantId(), requestId, actor.userId(),
                actor.personPublicId(), binding.handoffId(), binding.proposalId(),
                binding.actionKey(), binding.handoffVersion(), identity.authSessionId(),
                roles, permissions, correlation);
        if (inserted != 1) throw unavailable();
        event(binding.handoffId(), actor.tenantId(), "DRAFT_BOUND", "DRAFT", null, 0, null);
    }

    /** Opens delivery only after the owning Approval command has committed its final domain state. */
    public void markDomainCommitted(
            ApprovalRequestContext.Actor actor,
            UUID requestId,
            ApprovalDtos.RequestSummary result,
            String correlationId) {
        List<String> states = jdbc.query(
                """
                SELECT delivery_state FROM apr_dwaion_proposal_handoffs
                 WHERE tenant_id=? AND request_id=? AND owner_user_id=? FOR UPDATE
                """,
                (row, ignored) -> row.getString(1), actor.tenantId(), requestId, actor.userId());
        if (states.isEmpty()) return;
        if (states.size() != 1 || !"DRAFT".equals(states.getFirst())
                || result == null || !requestId.equals(result.requestId())
                || result.version() < 1 || "DRAFT".equals(result.status())) {
            throw unavailable();
        }
        String correlation = canonical(correlationId, 160)
                ? correlationId
                : requestId.toString();
        int updated = jdbc.update("""
                UPDATE apr_dwaion_proposal_handoffs
                   SET delivery_state='PENDING', domain_status=?, domain_version=?,
                       domain_committed_at=clock_timestamp(), correlation_id=?,
                       available_at=clock_timestamp(), updated_at=clock_timestamp()
                 WHERE tenant_id=? AND request_id=? AND owner_user_id=?
                   AND delivery_state='DRAFT'
                """, result.status(), result.version(), correlation,
                actor.tenantId(), requestId, actor.userId());
        if (updated != 1) throw unavailable();
        eventByRequest(actor.tenantId(), requestId, "DOMAIN_COMMITTED", "PENDING", null, 0, null);
    }

    @Transactional
    public List<Delivery> claim(int batchSize, String workerId) {
        if (batchSize < 1 || batchSize > 100 || !canonical(workerId, 160)) throw invalid();
        List<Delivery> claimed = jdbc.query("""
                WITH candidates AS (
                    SELECT binding_id
                      FROM apr_dwaion_proposal_handoffs
                     WHERE domain_committed_at IS NOT NULL
                       AND available_at <= clock_timestamp()
                       AND (delivery_state IN ('PENDING','RETRY')
                            OR (delivery_state='SENDING' AND locked_until<=clock_timestamp()))
                     ORDER BY available_at, created_at, binding_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                ), claimed AS (
                    UPDATE apr_dwaion_proposal_handoffs target
                       SET delivery_state='SENDING', attempt_count=attempt_count+1,
                           locked_by=?, locked_until=clock_timestamp()+INTERVAL '30 seconds',
                           updated_at=clock_timestamp()
                      FROM candidates
                     WHERE target.binding_id=candidates.binding_id
                    RETURNING target.*
                )
                SELECT * FROM claimed ORDER BY created_at,binding_id
                """, (row, ignored) -> delivery(row), batchSize, workerId);
        claimed.forEach(delivery -> event(delivery.handoffId(), delivery.tenantId(),
                "DELIVERY_CLAIMED", "SENDING", delivery.nextObservation(),
                delivery.attemptCount(), null));
        return claimed;
    }

    @Transactional
    public Optional<Delivery> advance(
            Delivery delivery,
            String workerId,
            ObservationSnapshot observation) {
        requireCurrent(delivery, workerId);
        if (observation == null
                || !delivery.handoffId().equals(observation.handoffId())
                || !delivery.proposalId().equals(observation.proposalId())
                || !delivery.actionKey().equals(observation.actionKey())
                || observation.version() < delivery.handoffVersion()) {
            throw unavailable();
        }
        String state = observation.state().toUpperCase(Locale.ROOT);
        if ("COMPLETED".equals(state)) {
            if (observation.receiptId() == null) throw unavailable();
            int updated = jdbc.update("""
                    UPDATE apr_dwaion_proposal_handoffs
                       SET delivery_state='COMPLETED', handoff_version=?, receipt_id=?,
                           completed_at=clock_timestamp(), locked_by=NULL, locked_until=NULL,
                           last_error=NULL, updated_at=clock_timestamp()
                     WHERE binding_id=? AND delivery_state='SENDING' AND locked_by=?
                       AND attempt_count=? AND locked_until=? AND locked_until>clock_timestamp()
                    """, observation.version(), observation.receiptId(), delivery.bindingId(),
                    workerId, delivery.attemptCount(), Timestamp.from(delivery.leaseUntil()));
            if (updated != 1) throw unavailable();
            event(delivery.handoffId(), delivery.tenantId(), "COMPLETION_OBSERVED",
                    "COMPLETED", state, delivery.attemptCount(), null);
            return Optional.empty();
        }
        if (Set.of("FAILED", "CANCELLED", "COMPENSATED").contains(state)) {
            int updated = jdbc.update("""
                    UPDATE apr_dwaion_proposal_handoffs
                       SET delivery_state='TERMINAL', handoff_version=?,
                           locked_by=NULL, locked_until=NULL, last_error=?,
                           updated_at=clock_timestamp()
                     WHERE binding_id=? AND delivery_state='SENDING' AND locked_by=?
                       AND attempt_count=? AND locked_until=? AND locked_until>clock_timestamp()
                    """, observation.version(), "Agent handoff entered terminal state " + state,
                    delivery.bindingId(), workerId, delivery.attemptCount(),
                    Timestamp.from(delivery.leaseUntil()));
            if (updated != 1) throw unavailable();
            event(delivery.handoffId(), delivery.tenantId(), "TERMINAL_OBSERVED",
                    "TERMINAL", state, delivery.attemptCount(), "AGENT_TERMINAL_STATE");
            return Optional.empty();
        }
        String next = switch (state) {
            case "HANDED_OFF" -> "RUNNING";
            case "RUNNING" -> "COMPLETED";
            default -> throw unavailable();
        };
        if (rank(state) < rank(delivery.nextObservation())) throw unavailable();
        int updated = jdbc.update("""
                UPDATE apr_dwaion_proposal_handoffs
                   SET handoff_version=?, next_observation=?, last_error=NULL,
                       updated_at=clock_timestamp()
                 WHERE binding_id=? AND delivery_state='SENDING' AND locked_by=?
                   AND attempt_count=? AND locked_until=? AND locked_until>clock_timestamp()
                """, observation.version(), next, delivery.bindingId(), workerId,
                delivery.attemptCount(), Timestamp.from(delivery.leaseUntil()));
        if (updated != 1) throw unavailable();
        event(delivery.handoffId(), delivery.tenantId(), "OBSERVATION_ACCEPTED",
                "SENDING", state, delivery.attemptCount(), null);
        return current(delivery.bindingId(), workerId);
    }

    @Transactional
    public boolean retry(Delivery delivery, String workerId, int maximumAttempts, String error) {
        requireCurrent(delivery, workerId);
        if (maximumAttempts < 1 || maximumAttempts > 100) throw invalid();
        long delay = Math.min(900L, 1L << Math.min(9, Math.max(1, delivery.attemptCount())));
        boolean exhausted = delivery.attemptCount() >= maximumAttempts;
        String failureCode = error != null && error.matches(".*HTTP (4[0-9]{2}).*")
                ? "AGENT_OBSERVATION_REJECTED" : "AGENT_OBSERVATION_UNAVAILABLE";
        int updated = jdbc.update("""
                UPDATE apr_dwaion_proposal_handoffs
                   SET delivery_state=?, available_at=clock_timestamp()+(?*INTERVAL '1 second'),
                       locked_by=NULL, locked_until=NULL, last_error=?, failure_code=?,
                       dead_lettered_at=CASE WHEN ? THEN clock_timestamp() ELSE NULL END,
                       updated_at=clock_timestamp()
                 WHERE binding_id=? AND delivery_state='SENDING' AND locked_by=?
                   AND attempt_count=? AND locked_until=? AND locked_until>clock_timestamp()
                """, exhausted ? "DEAD" : "RETRY", delay, truncate(error), failureCode,
                exhausted, delivery.bindingId(), workerId,
                delivery.attemptCount(), Timestamp.from(delivery.leaseUntil()));
        if (updated != 1) throw unavailable();
        event(delivery.handoffId(), delivery.tenantId(),
                exhausted ? "DELIVERY_DEAD_LETTERED" : "DELIVERY_RETRY_SCHEDULED",
                exhausted ? "DEAD" : "RETRY", delivery.nextObservation(),
                delivery.attemptCount(), failureCode);
        return exhausted;
    }

    private Optional<Delivery> current(UUID bindingId, String workerId) {
        List<Delivery> rows = jdbc.query("""
                SELECT * FROM apr_dwaion_proposal_handoffs
                 WHERE binding_id=? AND delivery_state='SENDING' AND locked_by=?
                 FOR UPDATE
                """, (row, ignored) -> delivery(row), bindingId, workerId);
        return rows.size() == 1 ? Optional.of(rows.getFirst()) : Optional.empty();
    }

    private void requireCurrent(Delivery delivery, String workerId) {
        if (delivery == null || !canonical(workerId, 160)
                || !workerId.equals(delivery.lockedBy()) || delivery.attemptCount() < 1
                || delivery.leaseUntil() == null || !delivery.leaseUntil().isAfter(Instant.now())) {
            throw unavailable();
        }
    }

    private static Delivery delivery(java.sql.ResultSet row) throws java.sql.SQLException {
        return new Delivery(
                row.getObject("binding_id", UUID.class), row.getLong("tenant_id"),
                row.getObject("request_id", UUID.class), row.getLong("owner_user_id"),
                row.getObject("person_public_id", UUID.class), row.getObject("handoff_id", UUID.class),
                row.getObject("proposal_id", UUID.class), row.getString("action_key"),
                row.getLong("handoff_version"), row.getString("auth_session_id"),
                row.getString("roles"), row.getString("permissions"), row.getString("correlation_id"),
                row.getString("next_observation"), row.getString("domain_status"),
                row.getLong("domain_version"), row.getTimestamp("domain_committed_at").toInstant(),
                row.getInt("attempt_count"), row.getString("locked_by"),
                row.getTimestamp("locked_until").toInstant());
    }

    private static int rank(String state) {
        return switch (state) {
            case "HANDED_OFF" -> 1;
            case "RUNNING" -> 2;
            case "COMPLETED" -> 3;
            default -> 0;
        };
    }

    private void eventByRequest(long tenantId, UUID requestId, String eventType,
            String deliveryState, String observationState, int attemptCount, String safeErrorCode) {
        UUID handoffId = jdbc.queryForObject("""
                SELECT handoff_id FROM apr_dwaion_proposal_handoffs
                 WHERE tenant_id=? AND request_id=?
                """, UUID.class, tenantId, requestId);
        event(handoffId, tenantId, eventType, deliveryState, observationState, attemptCount, safeErrorCode);
    }

    private void event(UUID handoffId, long tenantId, String eventType,
            String deliveryState, String observationState, int attemptCount, String safeErrorCode) {
        int inserted = jdbc.update("""
                INSERT INTO apr_dwaion_proposal_handoff_events (
                    event_id,binding_id,tenant_id,event_type,delivery_state,
                    observation_state,attempt_count,safe_error_code)
                SELECT ?,binding_id,tenant_id,?,?,?,?,?
                  FROM apr_dwaion_proposal_handoffs
                 WHERE handoff_id=? AND tenant_id=?
                """, UUID.randomUUID(), eventType, deliveryState, observationState,
                attemptCount, safeErrorCode, handoffId, tenantId);
        if (inserted != 1) throw unavailable();
    }

    private static String tokens(Set<String> values, int maximum) {
        if (values == null) throw invalid();
        String result = values.stream().map(String::strip).map(value -> value.toUpperCase(Locale.ROOT))
                .filter(value -> !value.isBlank()).distinct().sorted().reduce((left, right) -> left + "," + right).orElse("");
        if (result.length() > maximum || result.codePoints().anyMatch(value -> value < 32 || value == 127)) throw invalid();
        return result;
    }

    private static boolean canonical(String value, int maximum) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.length() <= maximum && value.indexOf(',') < 0
                && value.codePoints().noneMatch(character -> character < 32 || character == 127);
    }

    private static String truncate(String error) {
        String value = error == null || error.isBlank() ? "DWAI-ON observation delivery failed" : error.strip();
        return value.length() <= 1_000 ? value : value.substring(0, 1_000);
    }

    private static BaseException invalid() {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, "The DWAI-ON Approval handoff binding is invalid.");
    }

    private static BaseException unavailable() {
        return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                "The durable DWAI-ON Approval completion bridge is unavailable.");
    }

    public record Delivery(
            UUID bindingId, long tenantId, UUID requestId, long ownerUserId, UUID personPublicId,
            UUID handoffId, UUID proposalId, String actionKey, long handoffVersion,
            String authSessionId, String roles, String permissions, String correlationId,
            String nextObservation, String domainStatus, long domainVersion,
            Instant domainCommittedAt, int attemptCount, String lockedBy, Instant leaseUntil) {
    }

    public record ObservationSnapshot(
            UUID handoffId, UUID proposalId, String actionKey, String state,
            long version, UUID receiptId) {
    }
}
