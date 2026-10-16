package com.dwp.services.payroll.home;

import com.dwp.core.event.DomainEventEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.payroll.home.PayrollHomePorts.PayrollHomeReplayLedger;

@Repository
@ConditionalOnProperty(
        name = "dwp.hris.payroll.home-producer.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class JdbcPayrollHomeReplayLedger implements PayrollHomeReplayLedger {

    private static final String EVENT_TYPE =
            "dwp.hris.home.materialization-proof-reserved.v1";
    private static final String SOURCE = "urn:dwp:payroll:home:admission";
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    JdbcPayrollHomeReplayLedger(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reserve(UUID proofId, UUID requestNonce, long tenantId, Instant expiresAt) {
        try {
            Instant now = Instant.now();
            var data = mapper.createObjectNode()
                    .put("proofId", proofId.toString())
                    .put("requestNonce", requestNonce.toString())
                    .put("expiresAt", expiresAt.toString());
            var envelope = new DomainEventEnvelope(
                    "1.0", proofId, SOURCE, EVENT_TYPE, 1, now,
                    "HOME_MATERIALIZATION_PROOF/" + requestNonce,
                    tenantId, "HOME_MATERIALIZATION_PROOF", requestNonce.toString(),
                    1, proofId.toString(), null, null, data,
                    Map.of("delivery", "INTERNAL_LEDGER_ONLY"));
            String payload = mapper.writeValueAsString(envelope);
            String digest = sha256(payload);
            int changed = jdbc.update("""
                    INSERT INTO sys_domain_event_outbox (
                        outbox_id, event_id, spec_version, event_source, event_type,
                        schema_version, subject, tenant_id, aggregate_type, aggregate_id,
                        aggregate_sequence, correlation_id, causation_id, trace_parent,
                        payload, payload_sha256, status, attempt_count, available_at,
                        published_at, created_at, updated_at)
                    VALUES (
                        :outboxId, :eventId, '1.0', :eventSource, :eventType,
                        1, :subject, :tenantId, :aggregateType, :aggregateId,
                        1, :correlationId, NULL, NULL,
                        CAST(:payload AS jsonb), :payloadHash, 'PUBLISHED', 0,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    ON CONFLICT DO NOTHING
                    """, new MapSqlParameterSource()
                    .addValue("outboxId", UUID.randomUUID())
                    .addValue("eventId", proofId)
                    .addValue("eventSource", SOURCE)
                    .addValue("eventType", EVENT_TYPE)
                    .addValue("subject", envelope.subject())
                    .addValue("tenantId", tenantId)
                    .addValue("aggregateType", envelope.aggregateType())
                    .addValue("aggregateId", requestNonce.toString())
                    .addValue("correlationId", proofId.toString())
                    .addValue("payload", payload)
                    .addValue("payloadHash", digest));
            return changed == 1;
        } catch (DataIntegrityViolationException exception) {
            return false;
        } catch (Exception exception) {
            throw new IllegalStateException("Payroll Home replay reservation failed.", exception);
        }
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
