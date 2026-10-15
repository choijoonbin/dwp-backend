package com.dwp.services.people.hris.experience;

import com.dwp.platform.contracts.hris.generated.HrisNotificationReceiptV1;
import com.dwp.platform.contracts.hris.generated.HrisNotificationIntentV1;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
class HrisExperienceNotificationRepository {

    private final NamedParameterJdbcTemplate jdbc;

    HrisExperienceNotificationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Subject> activeSubject(long tenantId, UUID personPublicId) {
        establishTenant(tenantId);
        return jdbc.query("""
                SELECT public_id,COALESCE(preferred_locale,'ko-KR') AS preferred_locale
                  FROM ppl_persons
                 WHERE tenant_id=:tenant AND public_id=:person
                   AND lifecycle_state='ACTIVE'
                """, parameters(tenantId).addValue("person", personPublicId),
                (result, ignored) -> new Subject(
                        result.getObject("public_id", UUID.class),
                        result.getString("preferred_locale")))
                .stream().findFirst();
    }

    boolean exactOriginExists(
            long tenantId,
            UUID sourceEventId,
            UUID aggregateId) {
        establishTenant(tenantId);
        Integer count = jdbc.queryForObject("""
                SELECT count(*)
                  FROM sys_people_outbox_events
                 WHERE tenant_id=:tenant AND event_id=:event
                   AND aggregate_type='EMPLOYEE_CHANGE_REQUEST'
                   AND aggregate_id=:aggregate
                   AND event_type='EmployeeChangeApplied.v1'
                """, parameters(tenantId).addValue("event", sourceEventId)
                .addValue("aggregate", aggregateId.toString()), Integer.class);
        return count != null && count == 1;
    }

    void appendReady(
            IntentDecision decision,
            HrisNotificationIntentV1 intent,
            String canonicalDigest) {
        establishTenant(decision.tenantId());
        int inserted = jdbc.update("""
                INSERT INTO ppl_hris_notification_intent_handoffs(
                    intent_id,tenant_id,source_event_id,source_urn,event_type,
                    aggregate_type,aggregate_id,recipient_user_id,excluded_user_id,
                    decision,producer_app_key,type_key,purpose_code,thread_ref,locale,
                    reason_code,subject_ref,target_ref,template_key,template_version,
                    template_model_digest,classification,action_required,idempotency_key,
                    canonical_intent_sha256,correlation_id,requested_at)
                VALUES(:intent,:tenant,:sourceEvent,
                    'urn:dwp:people:hrm:dwp-experience',
                    'EmployeeChangeAppliedNotificationRequested.v1',
                    'EMPLOYEE_CHANGE_REQUEST',:aggregate,:recipient,:excluded,
                    'READY_LOCAL_ONLY',:producerApp,:typeKey,:purpose,:threadRef,:locale,
                    :reason,:subjectRef,:targetRef,:templateKey,:templateVersion,
                    :templateModelDigest,:classification,:actionRequired,:idempotencyKey,
                    :digest,:correlation,:requestedAt)
                ON CONFLICT (tenant_id,source_event_id) DO NOTHING
                """, parameters(decision.tenantId())
                .addValue("intent", decision.intentId())
                .addValue("sourceEvent", decision.sourceEventId())
                .addValue("aggregate", decision.aggregateId())
                .addValue("recipient", decision.recipientUserId())
                .addValue("excluded", decision.excludedUserId())
                .addValue("producerApp", intent.producerAppKey())
                .addValue("typeKey", intent.typeKey())
                .addValue("purpose", intent.purposeCode())
                .addValue("threadRef", intent.threadRef())
                .addValue("locale", intent.locale())
                .addValue("reason", intent.reasonCode())
                .addValue("subjectRef", intent.subjectRef())
                .addValue("targetRef", intent.targetRef())
                .addValue("templateKey", intent.templateKey())
                .addValue("templateVersion", intent.templateVersion())
                .addValue("templateModelDigest", intent.templateModelDigest())
                .addValue("classification", intent.classification())
                .addValue("actionRequired", intent.actionRequired())
                .addValue("idempotencyKey", intent.idempotencyKey())
                .addValue("digest", canonicalDigest)
                .addValue("correlation", decision.correlationId())
                .addValue("requestedAt", Timestamp.from(decision.requestedAt())));
        if (inserted == 0 && !sameDecision(
                decision, canonicalDigest, "READY_LOCAL_ONLY", null)) {
            throw new IllegalStateException("HRIS notification source event was reused.");
        }
        if (inserted == 1) {
            int claim = jdbc.update("""
                    INSERT INTO ppl_hris_notification_handoff_claims(
                        tenant_id,intent_id,claim_state,attempt_count,maximum_attempts)
                    VALUES(:tenant,:intent,'READY',0,10)
                    """, parameters(decision.tenantId())
                    .addValue("intent", decision.intentId()));
            if (claim != 1) throw new IllegalStateException(
                    "HRIS notification handoff claim initialization failed.");
        }
    }

    void appendOmission(IntentDecision decision, String omissionCode) {
        establishTenant(decision.tenantId());
        int inserted = jdbc.update("""
                INSERT INTO ppl_hris_notification_intent_handoffs(
                    intent_id,tenant_id,source_event_id,source_urn,event_type,
                    aggregate_type,aggregate_id,recipient_user_id,excluded_user_id,
                    decision,omission_code,correlation_id,requested_at)
                VALUES(:intent,:tenant,:sourceEvent,
                    'urn:dwp:people:hrm:dwp-experience',
                    'EmployeeChangeAppliedNotificationRequested.v1',
                    'EMPLOYEE_CHANGE_REQUEST',:aggregate,NULL,:excluded,
                    :decision,:omission,:correlation,:requestedAt)
                ON CONFLICT (tenant_id,source_event_id) DO NOTHING
                """, parameters(decision.tenantId())
                .addValue("intent", decision.intentId())
                .addValue("sourceEvent", decision.sourceEventId())
                .addValue("aggregate", decision.aggregateId())
                .addValue("excluded", decision.excludedUserId())
                .addValue("decision", "ACTOR_EXCLUDED".equals(omissionCode)
                        ? "OMITTED_ACTOR_EXCLUDED"
                        : "OMITTED_NO_ELIGIBLE_RECIPIENT")
                .addValue("omission", omissionCode)
                .addValue("correlation", decision.correlationId())
                .addValue("requestedAt", Timestamp.from(decision.requestedAt())));
        String state = "ACTOR_EXCLUDED".equals(omissionCode)
                ? "OMITTED_ACTOR_EXCLUDED" : "OMITTED_NO_ELIGIBLE_RECIPIENT";
        if (inserted == 0 && !sameDecision(decision, null, state, omissionCode)) {
            throw new IllegalStateException("HRIS notification omission event was reused.");
        }
    }

    Optional<Handoff> handoff(long tenantId, UUID intentId) {
        establishTenant(tenantId);
        return jdbc.query("""
                SELECT intent_id,source_event_id,recipient_user_id,decision,
                       template_version,correlation_id
                  FROM ppl_hris_notification_intent_handoffs
                 WHERE tenant_id=:tenant AND intent_id=:intent
                """, parameters(tenantId).addValue("intent", intentId), this::handoff)
                .stream().findFirst();
    }

    Optional<String> receiptDigest(long tenantId, UUID receiptId) {
        establishTenant(tenantId);
        return jdbc.query("""
                SELECT canonical_receipt_sha256
                  FROM ppl_hris_notification_receipt_inbox
                 WHERE tenant_id=:tenant AND receipt_id=:receipt
                """, parameters(tenantId).addValue("receipt", receiptId),
                (result, ignored) -> result.getString(1)).stream().findFirst();
    }

    Optional<String> sequenceDigest(
            long tenantId,
            UUID intentId,
            long recipientUserId,
            long sequence) {
        establishTenant(tenantId);
        return jdbc.query("""
                SELECT canonical_receipt_sha256
                  FROM ppl_hris_notification_receipt_inbox
                 WHERE tenant_id=:tenant AND intent_id=:intent
                   AND recipient_user_id=:recipient AND receipt_sequence=:sequence
                """, parameters(tenantId).addValue("intent", intentId)
                .addValue("recipient", recipientUserId).addValue("sequence", sequence),
                (result, ignored) -> result.getString(1)).stream().findFirst();
    }

    StreamState lockStream(long tenantId, UUID intentId, long recipientUserId) {
        establishTenant(tenantId);
        jdbc.update("""
                INSERT INTO ppl_hris_notification_receipt_streams(
                    tenant_id,intent_id,recipient_user_id,last_sequence)
                VALUES(:tenant,:intent,:recipient,0)
                ON CONFLICT DO NOTHING
                """, parameters(tenantId).addValue("intent", intentId)
                .addValue("recipient", recipientUserId));
        return jdbc.query("""
                SELECT last_sequence,terminal_state
                  FROM ppl_hris_notification_receipt_streams
                 WHERE tenant_id=:tenant AND intent_id=:intent
                   AND recipient_user_id=:recipient
                 FOR UPDATE
                """, parameters(tenantId).addValue("intent", intentId)
                .addValue("recipient", recipientUserId),
                (result, ignored) -> new StreamState(
                        result.getLong("last_sequence"),
                        result.getString("terminal_state")))
                .stream().findFirst().orElseThrow();
    }

    void appendReceipt(
            HrisNotificationReceiptV1 receipt,
            String canonicalDigest,
            String terminalState) {
        establishTenant(receipt.tenantId());
        int inserted = jdbc.update("""
                INSERT INTO ppl_hris_notification_receipt_inbox(
                    receipt_id,tenant_id,intent_id,source_event_id,recipient_user_id,
                    receipt_sequence,state,channel,decision_code,reason_code,
                    notification_id,policy_revision,template_version,
                    materialized_digest,provider_receipt_digest,attempt_number,
                    occurred_at,error_code,retryable,next_attempt_at,correlation_id,
                    canonical_receipt_sha256)
                VALUES(:receipt,:tenant,:intent,:sourceEvent,:recipient,:sequence,
                    :state,:channel,:decision,:reason,:notification,:policy,:template,
                    :materialized,:provider,:attempt,:occurredAt,:error,:retryable,
                    :nextAttempt,:correlation,:digest)
                """, parameters(receipt.tenantId())
                .addValue("receipt", receipt.receiptId())
                .addValue("intent", receipt.intentId())
                .addValue("sourceEvent", receipt.sourceEventId())
                .addValue("recipient", receipt.recipientUserId())
                .addValue("sequence", receipt.receiptSequence())
                .addValue("state", receipt.state())
                .addValue("channel", receipt.channel())
                .addValue("decision", receipt.decisionCode())
                .addValue("reason", receipt.reasonCode())
                .addValue("notification", receipt.notificationId())
                .addValue("policy", receipt.policyRevision())
                .addValue("template", receipt.templateVersion())
                .addValue("materialized", receipt.materializedDigest())
                .addValue("provider", receipt.providerReceiptDigest())
                .addValue("attempt", receipt.attemptNumber())
                .addValue("occurredAt", Timestamp.from(receipt.occurredAt()))
                .addValue("error", receipt.errorCode())
                .addValue("retryable", receipt.retryable())
                .addValue("nextAttempt", receipt.nextAttemptAt() == null
                        ? null : Timestamp.from(receipt.nextAttemptAt()))
                .addValue("correlation", receipt.correlationId())
                .addValue("digest", canonicalDigest));
        if (inserted != 1) throw new IllegalStateException(
                "HRIS notification receipt append failed.");
        int advanced = jdbc.update("""
                UPDATE ppl_hris_notification_receipt_streams
                   SET last_sequence=:sequence,terminal_state=:terminal
                 WHERE tenant_id=:tenant AND intent_id=:intent
                   AND recipient_user_id=:recipient
                """, parameters(receipt.tenantId()).addValue("intent", receipt.intentId())
                .addValue("recipient", receipt.recipientUserId())
                .addValue("sequence", receipt.receiptSequence())
                .addValue("terminal", terminalState));
        if (advanced != 1) throw new IllegalStateException(
                "HRIS notification receipt stream advance failed.");
    }

    private boolean sameDecision(
            IntentDecision decision,
            String digest,
            String state,
            String omissionCode) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*)
                  FROM ppl_hris_notification_intent_handoffs
                 WHERE tenant_id=:tenant AND source_event_id=:sourceEvent
                   AND intent_id=:intent AND aggregate_id=:aggregate
                   AND recipient_user_id IS NOT DISTINCT FROM :recipient
                   AND excluded_user_id=:excluded AND decision=:decision
                   AND correlation_id=:correlation
                   AND canonical_intent_sha256 IS NOT DISTINCT FROM :digest
                   AND omission_code IS NOT DISTINCT FROM :omission
                """, parameters(decision.tenantId())
                .addValue("sourceEvent", decision.sourceEventId())
                .addValue("intent", decision.intentId())
                .addValue("aggregate", decision.aggregateId())
                .addValue("recipient", decision.recipientUserId())
                .addValue("excluded", decision.excludedUserId())
                .addValue("decision", state)
                .addValue("correlation", decision.correlationId())
                .addValue("digest", digest)
                .addValue("omission", omissionCode), Integer.class);
        return count != null && count == 1;
    }

    private Handoff handoff(ResultSet result, int ignored) throws SQLException {
        return new Handoff(
                result.getObject("intent_id", UUID.class),
                result.getObject("source_event_id", UUID.class),
                result.getObject("recipient_user_id", Long.class),
                result.getString("decision"),
                result.getObject("template_version", Long.class),
                result.getObject("correlation_id", UUID.class));
    }

    private void establishTenant(long tenantId) {
        jdbc.queryForObject("SELECT set_config('dwp.tenant_id', CAST(:tenant AS text), true)",
                parameters(tenantId), String.class);
    }

    private MapSqlParameterSource parameters(long tenantId) {
        return new MapSqlParameterSource("tenant", tenantId);
    }

    record Subject(UUID personPublicId, String locale) {
    }

    record IntentDecision(
            UUID intentId,
            long tenantId,
            UUID sourceEventId,
            UUID aggregateId,
            Long recipientUserId,
            long excludedUserId,
            UUID correlationId,
            Instant requestedAt) {
    }

    record Handoff(
            UUID intentId,
            UUID sourceEventId,
            Long recipientUserId,
            String decision,
            Long templateVersion,
            UUID correlationId) {
    }

    record StreamState(long lastSequence, String terminalState) {
    }
}
