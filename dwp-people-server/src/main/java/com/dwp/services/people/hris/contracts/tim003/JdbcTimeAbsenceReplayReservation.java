package com.dwp.services.people.hris.contracts.tim003;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Atomic REQUIRES_NEW jti-plus-nonce reservation in the existing shared inbox. */
@Component
final class JdbcTimeAbsenceReplayReservation
        implements TimeAbsenceOwnerPorts.ReplayReservation {

    static final String CONSUMER = "dwp-tim003-owner-query-proof-v1";
    static final String SOURCE =
            "/dwp-people-server/hris/employment-absence/svc-007";
    static final String TYPE =
            "dwp.hris.people.tim003-workload-proof-reserved.v1";

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate requiresNew;
    private final TimeAbsenceOwnerCanonicalJson canonicalJson;

    JdbcTimeAbsenceReplayReservation(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            TimeAbsenceOwnerCanonicalJson canonicalJson) {
        this.jdbc = jdbc;
        this.canonicalJson = canonicalJson;
        this.requiresNew = new TransactionTemplate(transactions);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNew.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.requiresNew.setTimeout(5);
    }

    @Override
    public boolean reserve(TimeAbsenceOwnerContract.VerifiedRequest request) {
        Boolean reserved = requiresNew.execute(status -> reservePair(request));
        return Boolean.TRUE.equals(reserved);
    }

    private boolean reservePair(TimeAbsenceOwnerContract.VerifiedRequest request) {
        OffsetDateTime databaseInstant = jdbc.queryForObject(
                "SELECT pg_catalog.transaction_timestamp()",
                Map.of(), OffsetDateTime.class);
        if (databaseInstant == null) throw TimeAbsenceOwnerContract.Failure.unavailable();
        String payload = canonicalJson.json(payload(request));
        String digest = canonicalJson.digest(payload(request));
        int inserted = insert(request, request.jti(), payload, digest, databaseInstant)
                + insert(request, request.nonce(), payload, digest, databaseInstant);
        if (inserted != 2) {
            throw TimeAbsenceOwnerContract.Failure.invalidProof();
        }
        return true;
    }

    private int insert(
            TimeAbsenceOwnerContract.VerifiedRequest request,
            UUID eventId,
            String payload,
            String digest,
            OffsetDateTime databaseInstant) {
        return jdbc.update("""
                INSERT INTO sys_domain_event_inbox(
                    consumer_name,event_id,event_source,event_type,schema_version,
                    tenant_id,aggregate_type,aggregate_id,aggregate_sequence,
                    correlation_id,causation_id,trace_parent,payload,payload_sha256,
                    status,attempt_count,replay_count,available_at,locked_by,
                    lock_token,locked_until,processed_at,dead_lettered_at,last_error,
                    created_at,updated_at)
                VALUES(
                    :consumer,:eventId,:source,:eventType,1,:tenant,
                    'TIM003_WORKLOAD_PROOF',:aggregateId,1,:correlation,
                    NULL,NULL,CAST(:payload AS jsonb),:payloadSha256,'SUCCEEDED',0,0,
                    :at,NULL,NULL,NULL,:at,NULL,NULL,:at,:at)
                ON CONFLICT (consumer_name,event_id) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("consumer", CONSUMER)
                .addValue("eventId", eventId)
                .addValue("source", SOURCE)
                .addValue("eventType", TYPE)
                .addValue("tenant", request.tenantId())
                .addValue("aggregateId", eventId.toString())
                .addValue("correlation", request.jti().toString())
                .addValue("payload", payload)
                .addValue("payloadSha256", digest)
                .addValue("at", databaseInstant));
    }

    private static Map<String, Object> payload(
            TimeAbsenceOwnerContract.VerifiedRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bindingId", TimeAbsenceOwnerContract.BINDING_ID);
        payload.put("businessTimeZone", request.businessTimeZone());
        payload.put("effectiveOn", request.request().effectiveOn().toString());
        payload.put("expiresAt", request.expiresAt().atOffset(ZoneOffset.UTC).toString());
        payload.put("minimumRelationshipRevision",
                request.request().minimumRelationshipRevision());
        payload.put("relationshipPublicId",
                request.request().relationshipPublicId().toString());
        payload.put("tenantId", request.tenantId());
        return payload;
    }
}
