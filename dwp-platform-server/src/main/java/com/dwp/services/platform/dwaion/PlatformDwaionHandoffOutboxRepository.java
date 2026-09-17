package com.dwp.services.platform.dwaion;

import com.dwp.services.platform.dwaion.PlatformDwaionHandoff.Binding;
import com.dwp.services.platform.dwaion.PlatformDwaionHandoff.Effect;
import com.dwp.services.platform.dwaion.PlatformDwaionHandoff.Identity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class PlatformDwaionHandoffOutboxRepository {
    private final JdbcTemplate jdbc;

    public PlatformDwaionHandoffOutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void committed(
            long tenantId,
            long ownerUserId,
            Binding binding,
            Identity identity,
            Effect effect,
            String correlationId) {
        if (binding == null) {
            if (identity != null || effect != null) throw PlatformDwaionHandoff.invalid();
            return;
        }
        if (tenantId <= 0 || ownerUserId <= 0 || identity == null || effect == null
                || !validEffect(binding, effect)) {
            throw PlatformDwaionHandoff.invalid();
        }
        String roles = tokens(identity.roles(), 4_000);
        String permissions = tokens(identity.permissions(), 8_000);
        String correlation = PlatformDwaionHandoff.canonical(correlationId, 160)
                ? correlationId : binding.handoffId().toString();
        int inserted = jdbc.update("""
                INSERT INTO platform_dwaion_proposal_handoffs (
                    binding_id, tenant_id, owner_user_id, person_public_id,
                    handoff_id, proposal_id, action_key, handoff_version,
                    auth_session_id, roles, permissions, correlation_id,
                    domain_name, domain_operation, resource_id,
                    domain_status, domain_version, domain_committed_at,
                    delivery_state, next_observation)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        clock_timestamp(), 'PENDING', 'HANDED_OFF')
                ON CONFLICT (handoff_id) DO NOTHING
                """, UUID.randomUUID(), tenantId, ownerUserId, identity.personPublicId(),
                binding.handoffId(), binding.proposalId(), binding.actionKey(),
                binding.handoffVersion(), identity.authSessionId(), roles, permissions,
                correlation, effect.domain(), effect.operation(), effect.resourceId(),
                effect.status(), effect.resourceVersion());
        if (inserted == 1) {
            event(binding.handoffId(), tenantId, "DOMAIN_COMMITTED", "PENDING", null, 0, null);
            return;
        }
        Existing existing = existing(binding.handoffId()).orElseThrow(PlatformDwaionHandoff::unavailable);
        if (existing.tenantId() != tenantId || existing.ownerUserId() != ownerUserId
                || !existing.proposalId().equals(binding.proposalId())
                || !existing.actionKey().equals(binding.actionKey())
                || !existing.resourceId().equals(effect.resourceId())
                || !existing.domain().equals(effect.domain())
                || !existing.operation().equals(effect.operation())
                || existing.domainVersion() != effect.resourceVersion()
                || !existing.domainStatus().equals(effect.status())) {
            throw PlatformDwaionHandoff.invalid();
        }
    }

    @Transactional
    public List<Delivery> claim(int batchSize, String workerId) {
        if (batchSize < 1 || batchSize > 100
                || !PlatformDwaionHandoff.canonical(workerId, 160)) {
            throw PlatformDwaionHandoff.invalid();
        }
        List<Delivery> claimed = jdbc.query("""
                WITH candidates AS (
                    SELECT binding_id
                      FROM platform_dwaion_proposal_handoffs
                     WHERE available_at <= clock_timestamp()
                       AND (delivery_state IN ('PENDING','RETRY')
                            OR (delivery_state='SENDING' AND locked_until<=clock_timestamp()))
                     ORDER BY available_at, created_at, binding_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                ), claimed AS (
                    UPDATE platform_dwaion_proposal_handoffs target
                       SET delivery_state='SENDING', attempt_count=attempt_count+1,
                           locked_by=?, locked_until=clock_timestamp()+INTERVAL '30 seconds',
                           updated_at=clock_timestamp()
                      FROM candidates
                     WHERE target.binding_id=candidates.binding_id
                    RETURNING target.*
                )
                SELECT * FROM claimed ORDER BY created_at,binding_id
                """, (row, ignored) -> delivery(row), batchSize, workerId);
        claimed.forEach(item -> event(item.handoffId(), item.tenantId(),
                "DELIVERY_CLAIMED", "SENDING", item.nextObservation(),
                item.attemptCount(), null));
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
                || observation.version() != delivery.handoffVersion() + 1) {
            throw PlatformDwaionHandoff.unavailable();
        }
        String state = observation.state().toUpperCase(Locale.ROOT);
        if ("COMPLETED".equals(state)) {
            if (observation.receiptId() == null) throw PlatformDwaionHandoff.unavailable();
            int updated = jdbc.update("""
                    UPDATE platform_dwaion_proposal_handoffs
                       SET delivery_state='COMPLETED', handoff_version=?, receipt_id=?,
                           completed_at=clock_timestamp(), locked_by=NULL, locked_until=NULL,
                           last_error=NULL, updated_at=clock_timestamp()
                     WHERE binding_id=? AND delivery_state='SENDING' AND locked_by=?
                       AND attempt_count=? AND locked_until=? AND locked_until>clock_timestamp()
                    """, observation.version(), observation.receiptId(), delivery.bindingId(),
                    workerId, delivery.attemptCount(), Timestamp.from(delivery.leaseUntil()));
            if (updated != 1) throw PlatformDwaionHandoff.unavailable();
            event(delivery.handoffId(), delivery.tenantId(), "COMPLETION_OBSERVED",
                    "COMPLETED", state, delivery.attemptCount(), null);
            return Optional.empty();
        }
        if (Set.of("FAILED", "CANCELLED", "COMPENSATED").contains(state)) {
            int updated = jdbc.update("""
                    UPDATE platform_dwaion_proposal_handoffs
                       SET delivery_state='TERMINAL', handoff_version=?,
                           locked_by=NULL, locked_until=NULL, last_error=?,
                           updated_at=clock_timestamp()
                     WHERE binding_id=? AND delivery_state='SENDING' AND locked_by=?
                       AND attempt_count=? AND locked_until=? AND locked_until>clock_timestamp()
                    """, observation.version(), "Agent handoff entered terminal state " + state,
                    delivery.bindingId(), workerId, delivery.attemptCount(),
                    Timestamp.from(delivery.leaseUntil()));
            if (updated != 1) throw PlatformDwaionHandoff.unavailable();
            event(delivery.handoffId(), delivery.tenantId(), "TERMINAL_OBSERVED",
                    "TERMINAL", state, delivery.attemptCount(), "AGENT_TERMINAL_STATE");
            return Optional.empty();
        }
        String next = switch (state) {
            case "HANDED_OFF" -> "RUNNING";
            case "RUNNING" -> "COMPLETED";
            default -> throw PlatformDwaionHandoff.unavailable();
        };
        if (!state.equals(delivery.nextObservation())) throw PlatformDwaionHandoff.unavailable();
        int updated = jdbc.update("""
                UPDATE platform_dwaion_proposal_handoffs
                   SET handoff_version=?, next_observation=?, last_error=NULL,
                       updated_at=clock_timestamp()
                 WHERE binding_id=? AND delivery_state='SENDING' AND locked_by=?
                   AND attempt_count=? AND locked_until=? AND locked_until>clock_timestamp()
                """, observation.version(), next, delivery.bindingId(), workerId,
                delivery.attemptCount(), Timestamp.from(delivery.leaseUntil()));
        if (updated != 1) throw PlatformDwaionHandoff.unavailable();
        event(delivery.handoffId(), delivery.tenantId(), "OBSERVATION_ACCEPTED",
                "SENDING", state, delivery.attemptCount(), null);
        return current(delivery.bindingId(), workerId);
    }

    @Transactional
    public boolean retry(Delivery delivery, String workerId, int maximumAttempts, String error) {
        requireCurrent(delivery, workerId);
        if (maximumAttempts < 1 || maximumAttempts > 100) throw PlatformDwaionHandoff.invalid();
        long delay = Math.min(900L, 1L << Math.min(9, Math.max(1, delivery.attemptCount())));
        boolean exhausted = delivery.attemptCount() >= maximumAttempts;
        String failureCode = error != null && error.matches(".*HTTP (4[0-9]{2}).*")
                ? "AGENT_OBSERVATION_REJECTED" : "AGENT_OBSERVATION_UNAVAILABLE";
        int updated = jdbc.update("""
                UPDATE platform_dwaion_proposal_handoffs
                   SET delivery_state=?, available_at=clock_timestamp()+(?*INTERVAL '1 second'),
                       locked_by=NULL, locked_until=NULL, last_error=?, failure_code=?,
                       dead_lettered_at=CASE WHEN ? THEN clock_timestamp() ELSE NULL END,
                       updated_at=clock_timestamp()
                 WHERE binding_id=? AND delivery_state='SENDING' AND locked_by=?
                   AND attempt_count=? AND locked_until=? AND locked_until>clock_timestamp()
                """, exhausted ? "DEAD" : "RETRY", delay, truncate(error), failureCode,
                exhausted, delivery.bindingId(), workerId, delivery.attemptCount(),
                Timestamp.from(delivery.leaseUntil()));
        if (updated != 1) throw PlatformDwaionHandoff.unavailable();
        event(delivery.handoffId(), delivery.tenantId(),
                exhausted ? "DELIVERY_DEAD_LETTERED" : "DELIVERY_RETRY_SCHEDULED",
                exhausted ? "DEAD" : "RETRY", delivery.nextObservation(),
                delivery.attemptCount(), failureCode);
        return exhausted;
    }

    private Optional<Existing> existing(UUID handoffId) {
        List<Existing> values = jdbc.query("""
                SELECT tenant_id,owner_user_id,proposal_id,action_key,resource_id,
                       domain_name,domain_operation,domain_version,domain_status
                  FROM platform_dwaion_proposal_handoffs WHERE handoff_id=?
                """, (row, ignored) -> new Existing(
                row.getLong("tenant_id"), row.getLong("owner_user_id"),
                row.getObject("proposal_id", UUID.class), row.getString("action_key"),
                row.getObject("resource_id", UUID.class), row.getString("domain_name"),
                row.getString("domain_operation"), row.getLong("domain_version"),
                row.getString("domain_status")), handoffId);
        return values.size() == 1 ? Optional.of(values.getFirst()) : Optional.empty();
    }

    private Optional<Delivery> current(UUID bindingId, String workerId) {
        List<Delivery> rows = jdbc.query("""
                SELECT * FROM platform_dwaion_proposal_handoffs
                 WHERE binding_id=? AND delivery_state='SENDING' AND locked_by=? FOR UPDATE
                """, (row, ignored) -> delivery(row), bindingId, workerId);
        return rows.size() == 1 ? Optional.of(rows.getFirst()) : Optional.empty();
    }

    private void requireCurrent(Delivery delivery, String workerId) {
        if (delivery == null || !PlatformDwaionHandoff.canonical(workerId, 160)
                || !workerId.equals(delivery.lockedBy()) || delivery.attemptCount() < 1
                || delivery.leaseUntil() == null || !delivery.leaseUntil().isAfter(Instant.now())) {
            throw PlatformDwaionHandoff.unavailable();
        }
    }

    private static Delivery delivery(java.sql.ResultSet row) throws java.sql.SQLException {
        return new Delivery(
                row.getObject("binding_id", UUID.class), row.getLong("tenant_id"),
                row.getLong("owner_user_id"), row.getObject("person_public_id", UUID.class),
                row.getObject("handoff_id", UUID.class), row.getObject("proposal_id", UUID.class),
                row.getString("action_key"), row.getLong("handoff_version"),
                row.getString("auth_session_id"), row.getString("roles"),
                row.getString("permissions"), row.getString("correlation_id"),
                row.getString("next_observation"), row.getString("domain_name"),
                row.getString("domain_operation"), row.getObject("resource_id", UUID.class),
                row.getString("domain_status"), row.getLong("domain_version"),
                row.getTimestamp("domain_committed_at").toInstant(),
                row.getInt("attempt_count"), row.getString("locked_by"),
                row.getTimestamp("locked_until").toInstant());
    }

    private void event(UUID handoffId, long tenantId, String eventType,
            String deliveryState, String observationState, int attemptCount, String safeErrorCode) {
        int inserted = jdbc.update("""
                INSERT INTO platform_dwaion_proposal_handoff_events (
                    event_id,binding_id,tenant_id,event_type,delivery_state,
                    observation_state,attempt_count,safe_error_code)
                SELECT ?,binding_id,tenant_id,?,?,?,?,?
                  FROM platform_dwaion_proposal_handoffs
                 WHERE handoff_id=? AND tenant_id=?
                """, UUID.randomUUID(), eventType, deliveryState, observationState,
                attemptCount, safeErrorCode, handoffId, tenantId);
        if (inserted != 1) throw PlatformDwaionHandoff.unavailable();
    }

    private static boolean validEffect(Binding binding, Effect effect) {
        return switch (binding.actionKey()) {
            case "CALENDAR.EVENT.CREATE" -> "CALENDAR".equals(effect.domain())
                    && "EVENT_CREATE".equals(effect.operation())
                    && Set.of("CONFIRMED", "TENTATIVE").contains(effect.status());
            case "MAIL.DRAFT.CREATE" -> "MAIL".equals(effect.domain())
                    && "DRAFT_CREATE".equals(effect.operation())
                    && "DRAFT".equals(effect.status());
            case "SERVICE.REQUEST.CREATE" -> "SERVICE".equals(effect.domain())
                    && "REQUEST_CREATE".equals(effect.operation())
                    && Set.of("DRAFT", "SUBMITTED").contains(effect.status());
            default -> false;
        };
    }

    private static String tokens(String value, int maximum) {
        String result = value == null ? "" : Arrays.stream(value.split(","))
                .map(String::strip).filter(item -> !item.isBlank())
                .map(item -> item.toUpperCase(Locale.ROOT)).distinct().sorted()
                .reduce((left, right) -> left + "," + right).orElse("");
        if (result.length() > maximum
                || result.codePoints().anyMatch(character -> character < 32 || character == 127)) {
            throw PlatformDwaionHandoff.invalid();
        }
        return result;
    }

    private static String truncate(String error) {
        String value = error == null || error.isBlank()
                ? "DWAI-ON observation delivery failed" : error.strip();
        return value.length() <= 1_000 ? value : value.substring(0, 1_000);
    }

    private record Existing(
            long tenantId, long ownerUserId, UUID proposalId, String actionKey,
            UUID resourceId, String domain, String operation, long domainVersion,
            String domainStatus) {
    }

    public record Delivery(
            UUID bindingId, long tenantId, long ownerUserId, UUID personPublicId,
            UUID handoffId, UUID proposalId, String actionKey, long handoffVersion,
            String authSessionId, String roles, String permissions, String correlationId,
            String nextObservation, String domain, String operation, UUID resourceId,
            String domainStatus, long domainVersion, Instant domainCommittedAt,
            int attemptCount, String lockedBy, Instant leaseUntil) {
    }

    public record ObservationSnapshot(
            UUID handoffId, UUID proposalId, String actionKey, String state,
            long version, UUID receiptId) {
    }
}
