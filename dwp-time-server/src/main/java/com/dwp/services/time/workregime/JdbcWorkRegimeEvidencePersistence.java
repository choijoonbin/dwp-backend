package com.dwp.services.time.workregime;

import com.dwp.services.time.workregime.WorkRegimeModels.PolicyState;
import com.dwp.services.time.workregime.WorkRegimeRepository.CommandEvidence;
import com.dwp.services.time.workregime.WorkRegimeRepository.DraftWrite;
import com.dwp.services.time.workregime.WorkRegimeRepository.SimulationWrite;
import com.dwp.services.time.workregime.WorkRegimeRepository.TransitionWrite;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Writes immutable audit and transactional-outbox evidence beside each owner mutation. */
final class JdbcWorkRegimeEvidencePersistence {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    JdbcWorkRegimeEvidencePersistence(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    void created(DraftWrite draft) {
        requireReceipt(
                draft.tenantId(), draft.publicId(), "CREATE_DRAFT", draft.commandEvidence());
        append(
                draft.tenantId(), "WORK_REGIME", draft.publicId(), draft.revision(),
                "WORK_REGIME_DRAFT_CREATED", PolicyState.DRAFT,
                null, draft.templateDigest(), draft.commandEvidence());
    }

    void transitioned(
            TransitionWrite transition,
            long aggregateRevision,
            PolicyState before,
            long beforeVersion,
            long afterVersion) {
        requireReceipt(
                transition.tenantId(), transition.publicId(), operation(transition.nextState()),
                transition.commandEvidence());
        String beforeDigest = digest(before.name() + ":" + beforeVersion);
        String afterDigest = digest(transition.nextState().name() + ":" + afterVersion);
        append(
                transition.tenantId(), "WORK_REGIME", transition.publicId(),
                aggregateRevision, "WORK_REGIME_" + transition.nextState().name(),
                transition.nextState(), beforeDigest, afterDigest,
                transition.commandEvidence());
    }

    void simulated(SimulationWrite simulation) {
        requireReceipt(
                simulation.tenantId(), simulation.workRegimePublicId(), "SIMULATE",
                simulation.commandEvidence());
        append(
                simulation.tenantId(), "SCHEDULE_SIMULATION", simulation.publicId(),
                simulation.policyRevision(),
                "SCHEDULE_SIMULATION_" + simulation.result().state().name(),
                simulation.result().state().name(), null, simulation.resultDigest(),
                simulation.commandEvidence());
    }

    private void append(
            long tenantId,
            String aggregateType,
            UUID aggregateId,
            long aggregateRevision,
            String eventType,
            Object state,
            String beforeDigest,
            String afterDigest,
            CommandEvidence evidence) {
        jdbc.update("""
                INSERT INTO tim_work_regime_audit_events (
                    public_id, tenant_id, aggregate_type, aggregate_public_id,
                    aggregate_revision, event_type, actor_id, purpose_code,
                    authorization_decision_id, before_digest, after_digest,
                    receipt_public_id, correlation_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), tenantId, aggregateType, aggregateId, aggregateRevision,
                eventType, evidence.actorId(), evidence.purpose(), evidence.decisionId(),
                beforeDigest, afterDigest, evidence.receiptId(), evidence.correlationId());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("aggregateType", aggregateType);
        payload.put("aggregatePublicId", aggregateId.toString());
        payload.put("aggregateRevision", aggregateRevision);
        payload.put("eventType", eventType);
        payload.put("state", state.toString());
        payload.put("receiptId", evidence.receiptId().toString());
        String payloadJson = json(payload);
        jdbc.update("""
                INSERT INTO tim_work_regime_outbox_events (
                    public_id, tenant_id, aggregate_type, aggregate_public_id,
                    aggregate_revision, command_receipt_public_id, event_type,
                    event_payload, payload_digest, correlation_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                """,
                UUID.randomUUID(), tenantId, aggregateType, aggregateId, aggregateRevision,
                evidence.receiptId(), eventType, payloadJson, digest(payloadJson),
                evidence.correlationId());
    }

    private void requireReceipt(
            long tenantId,
            UUID aggregateId,
            String operation,
            CommandEvidence evidence) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*)
                  FROM tim_command_receipts
                 WHERE tenant_id = ?
                   AND public_id = ?
                   AND operation = ?
                   AND aggregate_public_id = ?
                   AND actor_id = ?
                   AND purpose_code = ?
                   AND authorization_decision_id = ?
                   AND correlation_id = ?
                   AND lifecycle_state IN ('RUNNING', 'SUCCEEDED')
                """, Integer.class,
                tenantId, evidence.receiptId(), operation, aggregateId,
                evidence.actorId(), evidence.purpose(), evidence.decisionId(),
                evidence.correlationId());
        if (count == null || count != 1) {
            throw new IllegalStateException(
                    "Mutation evidence requires its exact running command receipt");
        }
    }

    private static String operation(PolicyState state) {
        return switch (state) {
            case VALIDATED -> "VALIDATE";
            case SIMULATED -> "SIMULATE";
            case IN_REVIEW -> "SUBMIT_REVIEW";
            case APPROVED -> "APPLY_APPROVAL";
            case PUBLISHED -> "PUBLISH";
            default -> throw new IllegalArgumentException(
                    "No command operation exists for transition to " + state);
        };
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize TIM outbox evidence", exception);
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
