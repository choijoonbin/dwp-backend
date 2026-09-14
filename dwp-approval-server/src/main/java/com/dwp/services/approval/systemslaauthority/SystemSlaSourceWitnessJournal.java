package com.dwp.services.approval.systemslaauthority;

import static com.dwp.services.approval.systemslaauthority.SystemSlaJson.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** An RLS tenant selector is isolation, not authority. Only the verifier's private one-use origin can write here. */
public final class SystemSlaSourceWitnessJournal {
    private final NamedParameterJdbcTemplate jdbc;
    private final SystemSlaJson json;
    public SystemSlaSourceWitnessJournal(NamedParameterJdbcTemplate jdbc, SystemSlaJson json) { this.jdbc=jdbc; this.json=json; }
    public void record(UUID event, SystemSlaSourceAttestationVerifier.Verified original) {
        if (original==null || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !TransactionSynchronizationManager.hasResource(Objects.requireNonNull(jdbc.getJdbcTemplate().getDataSource()))) throw unavailable();
        var exchange=original.exchange(); var seal=exchange.seal(); var binding=seal.bindings(); var source=binding.get("source");
        long transaction=Objects.requireNonNull(jdbc.getJdbcTemplate().queryForObject("SELECT txid_current()",Long.class));
        if (!seal.capturedIn(jdbc.getJdbcTemplate().getDataSource(),transaction)) throw denied();
        var now=Objects.requireNonNull(jdbc.getJdbcTemplate().queryForObject("SELECT clock_timestamp()",Timestamp.class)).toInstant();
        original.consumeOrigin(event,now);
        var body=json.parse(exchange.body()); keys(body,Set.of("sourceProof","bindings"));
        var p=new MapSqlParameterSource().addValue("tenant",integer(binding,"tenantId",true)).addValue("event",event)
                .addValue("request",uuid(source.get("request"),"requestId"));
        jdbc.queryForObject("SELECT set_config('dwp.approval.system_sla.tenant',:tenant::text,true)",p,String.class);
        var rows=jdbc.queryForList("SELECT payload::text AS raw FROM apr_integration_outbox WHERE tenant_id=:tenant AND request_id=:request AND event_id=:event",p);
        if (rows.size()!=1) throw changed(); String raw=(String) rows.getFirst().get("raw"); var envelope=json.parse(raw.getBytes(StandardCharsets.UTF_8));
        var eventBody=envelope.get("payload");
        p.addValue("step",uuid(source.get("stage"),"stepId")).addValue("timer",uuid(source.get("timer"),"timerId"))
                .addValue("generation",integer(source.get("stage"),"generation",true))
                .addValue("classification",text(source.get("request"),"dataClassification",20))
                .addValue("requestVersion",integer(source.get("request"),"requestVersion",false))
                .addValue("stageVersion",integer(source.get("stage"),"version",false))
                .addValue("body",exchange.body()).addValue("transport",exchange.transport()).addValue("auth",original.originalAttestation())
                .addValue("ownerJti",UUID.fromString(exchange.ownerId())).addValue("transportJti",UUID.fromString(exchange.transportId()))
                .addValue("authJti",original.attestationId()).addValue("digest",hash(binding,"sourceDigest"))
                .addValue("bindingHash",exchange.bindingHash()).addValue("nativeHash",seal.nativeVectorSha256())
                .addValue("revision",original.authorityRevision()).addValue("proofHash",json.digest(Map.of("sourceBodySha256",sha(exchange.body()),
                        "transportProof",exchange.transport(),"authAttestation",original.originalAttestation())))
                .addValue("rawHash",sha(raw.getBytes(StandardCharsets.UTF_8))).addValue("canonicalHash",json.digest(envelope))
                .addValue("recipientHash",hash(eventBody,"recipientSnapshotSha256")).addValue("pinHash",json.digest(eventPins(eventBody)))
                .addValue("created",Timestamp.from(now)).addValue("expires",Timestamp.from(original.expiresAt()));
        if (jdbc.update("""
                INSERT INTO apr_system_sla_source_witnesses(tenant_id,event_id,request_id,step_id,timer_id,generation,original_classification,
                  original_request_version,original_stage_version,source_body,transport_proof,auth_attestation,owner_jti,transport_jti,auth_jti,
                  original_source_digest,bindings_sha256,native_vector_sha256,authority_revision,proof_sha256,raw_envelope_sha256,
                  canonical_envelope_sha256,recipient_snapshot_sha256,source_pins_sha256,created_at,expires_at)
                VALUES(:tenant,:event,:request,:step,:timer,:generation,:classification,:requestVersion,:stageVersion,:body,:transport,:auth,
                  :ownerJti,:transportJti,:authJti,:digest,:bindingHash,:nativeHash,:revision,:proofHash,:rawHash,:canonicalHash,:recipientHash,:pinHash,:created,:expires)
                """,p)!=1) throw changed();
    }
    public static Map<String,com.fasterxml.jackson.databind.JsonNode> eventPins(com.fasterxml.jackson.databind.JsonNode body) {
        var result=new LinkedHashMap<String,com.fasterxml.jackson.databind.JsonNode>();
        for (String key:List.of("requestTitle","managementResourceSetKey","stageKey","authorityRevision","stepId","workflowVersionId","formVersionId","timerId",
                "workflowDefinitionSha256","formSchemaSha256","payloadSha256","policySha256","requestVersion","stageVersion","generation","workflowVersion","payloadRevision","policyVersion","leaseEpoch")) {
            if (!body.hasNonNull(key)) throw denied(); result.put(key,body.get(key));
        }
        return result;
    }
}
