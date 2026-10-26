package com.dwp.services.people.hris.separation.hrm013;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.CallbackIdentity;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.CallbackReceiptRecord;

/** Specialized callback receipt ledger; raw callback request bytes are never accepted. */
public final class JdbcHrm013CallbackReceiptStore
        implements Hrm013PersistencePorts.CallbackReceiptStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate mandatory;
    private final TransactionTemplate required;

    public JdbcHrm013CallbackReceiptStore(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.mandatory = new TransactionTemplate(manager);
        this.mandatory.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
        this.required = new TransactionTemplate(manager);
        this.required.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Override
    public CallbackReceiptRecord append(CallbackReceiptRecord receipt) {
        CallbackReceiptRecord result = mandatory.execute(status -> {
            CallbackIdentity identity = receipt.identity();
            Hrm013TenantScope.setLocal(jdbc, identity.tenantId());
            MapSqlParameterSource parameters = parameters(receipt)
                    .addValue("lockKey", "hrm013-callback\n"
                            + identity.tenantId() + "\n" + identity.callerWorkloadId()
                            + "\n" + identity.callbackEventId());
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey,0))",
                    parameters, row -> null);
            List<CallbackReceiptRecord> existing = query(identity, true);
            if (!existing.isEmpty()) {
                CallbackReceiptRecord stored = existing.getFirst();
                if (!stored.commandDigest().equals(receipt.commandDigest())
                        || !stored.requestBodySha256().equals(receipt.requestBodySha256())) {
                    throw new IllegalStateException("HRM-013 callback identity digest conflict");
                }
                return stored;
            }
            int inserted = jdbc.update("""
                    INSERT INTO ppl_separation_callbacks(
                        tenant_id,separation_case_id,separation_task_id,callback_type,
                        callback_value,caller_workload_id,callback_event_id,
                        external_receipt_public_id,owner_revision,tenant_grant_revision,
                        authorization_revision,payload_hash,originating_payload_sha256,
                        command_digest,request_body_sha256,audience,purpose_code,
                        correlation_id,approval_request_id,waiver_decision,
                        decision_revision,decision_actor_public_id,case_maker_public_id,
                        requested_by_actor_public_id,reason_code,step_up_evidence_id,
                        step_up_evidence_audience,step_up_evidence_purpose,
                        step_up_evidence_nonce_sha256,step_up_evidence_sha256,
                        step_up_verified_at,step_up_consumed_at,receipt_public_id,
                        exact_response_body,response_body_sha256,outcome,
                        occurred_at,received_at)
                    VALUES(:tenant,:caseId,:taskId,:callbackType,:callbackValue,:caller,
                        :callbackEvent,:externalReceipt,:ownerRevision,
                        :tenantGrantRevision,:authorizationRevision,:payloadHash,
                        :originDigest,:commandDigest,:requestDigest,:audience,:purpose,
                        :correlation,:approvalRequest,:waiverDecision,:decisionRevision,
                        :decisionActor,:caseMaker,:requester,:reason,:stepUpId,
                        :stepUpAudience,:stepUpPurpose,:stepUpNonceDigest,
                        :stepUpDigest,:stepUpVerified,:stepUpConsumed,:receiptPublicId,
                        :responseBody,:responseDigest,:outcome,:occurred,:received)
                    """, parameters);
            if (inserted != 1) {
                throw new IllegalStateException("HRM-013 callback receipt insert failed");
            }
            return receipt;
        });
        if (result == null) throw new IllegalStateException("HRM-013 callback transaction returned no result");
        return result;
    }

    @Override
    public Optional<CallbackReceiptRecord> find(CallbackIdentity identity) {
        Optional<CallbackReceiptRecord> result = required.execute(status -> {
            Hrm013TenantScope.setLocal(jdbc, identity.tenantId());
            return query(identity, false).stream().findFirst();
        });
        return result == null ? Optional.empty() : result;
    }

    private List<CallbackReceiptRecord> query(CallbackIdentity identity, boolean lock) {
        return jdbc.query("""
                SELECT tenant_id,separation_case_id,separation_task_id,callback_type,
                       callback_value,caller_workload_id,callback_event_id,
                       external_receipt_public_id,owner_revision,tenant_grant_revision,
                       authorization_revision,payload_hash,originating_payload_sha256,
                       command_digest,request_body_sha256,audience,purpose_code,
                       correlation_id,approval_request_id,waiver_decision,
                       decision_revision,decision_actor_public_id,case_maker_public_id,
                       requested_by_actor_public_id,reason_code,step_up_evidence_id,
                       step_up_evidence_audience,step_up_evidence_purpose,
                       step_up_evidence_nonce_sha256,step_up_evidence_sha256,
                       step_up_verified_at,step_up_consumed_at,receipt_public_id,
                       exact_response_body,response_body_sha256,outcome,
                       occurred_at,received_at
                  FROM ppl_separation_callbacks
                 WHERE tenant_id=:tenant AND caller_workload_id=:caller
                   AND callback_event_id=:callbackEvent
                """ + (lock ? " FOR UPDATE" : ""), new MapSqlParameterSource()
                .addValue("tenant", identity.tenantId())
                .addValue("caller", identity.callerWorkloadId())
                .addValue("callbackEvent", identity.callbackEventId()),
                (row, number) -> map(row));
    }

    private static MapSqlParameterSource parameters(CallbackReceiptRecord value) {
        CallbackIdentity identity = value.identity();
        return new MapSqlParameterSource()
                .addValue("tenant", identity.tenantId())
                .addValue("caller", identity.callerWorkloadId())
                .addValue("callbackEvent", identity.callbackEventId())
                .addValue("caseId", value.separationCaseId())
                .addValue("taskId", value.separationTaskId() == 0
                        ? null : value.separationTaskId())
                .addValue("callbackType", value.callbackType())
                .addValue("callbackValue", value.callbackValue())
                .addValue("externalReceipt", value.externalReceiptPublicId())
                .addValue("ownerRevision", value.ownerRevision())
                .addValue("tenantGrantRevision", value.tenantGrantRevision())
                .addValue("authorizationRevision", value.authorizationRevision())
                .addValue("payloadHash", value.payloadHash())
                .addValue("originDigest", value.originatingPayloadSha256())
                .addValue("commandDigest", value.commandDigest())
                .addValue("requestDigest", value.requestBodySha256())
                .addValue("audience", value.audience())
                .addValue("purpose", value.purposeCode())
                .addValue("correlation", value.correlationId())
                .addValue("approvalRequest", value.approvalRequestId())
                .addValue("waiverDecision", value.waiverDecision())
                .addValue("decisionRevision", value.decisionRevision())
                .addValue("decisionActor", value.decisionActorPublicId())
                .addValue("caseMaker", value.caseMakerPublicId())
                .addValue("requester", value.requestedByActorPublicId())
                .addValue("reason", value.reasonCode())
                .addValue("stepUpId", value.stepUpEvidenceId())
                .addValue("stepUpAudience", value.stepUpEvidenceAudience())
                .addValue("stepUpPurpose", value.stepUpEvidencePurpose())
                .addValue("stepUpNonceDigest", value.stepUpEvidenceNonceSha256())
                .addValue("stepUpDigest", value.stepUpEvidenceSha256())
                .addValue("stepUpVerified", timestamp(value.stepUpVerifiedAt()))
                .addValue("stepUpConsumed", timestamp(value.stepUpConsumedAt()))
                .addValue("receiptPublicId", value.receiptPublicId())
                .addValue("responseBody", value.exactResponseBody())
                .addValue("responseDigest", value.responseBodySha256())
                .addValue("outcome", value.outcome())
                .addValue("occurred", Timestamp.from(value.occurredAt()))
                .addValue("received", Timestamp.from(value.receivedAt()));
    }

    private static CallbackReceiptRecord map(ResultSet row) throws SQLException {
        Long taskId = row.getObject("separation_task_id", Long.class);
        Long decisionRevision = row.getObject("decision_revision", Long.class);
        return new CallbackReceiptRecord(
                new CallbackIdentity(
                        row.getLong("tenant_id"),
                        row.getString("caller_workload_id"),
                        row.getObject("callback_event_id", UUID.class)),
                row.getLong("separation_case_id"),
                taskId == null ? 0 : taskId,
                row.getString("callback_type"),
                row.getString("callback_value"),
                row.getObject("external_receipt_public_id", UUID.class),
                row.getLong("owner_revision"),
                row.getLong("tenant_grant_revision"),
                row.getLong("authorization_revision"),
                row.getString("payload_hash"),
                row.getString("originating_payload_sha256"),
                row.getString("command_digest"),
                row.getString("request_body_sha256"),
                row.getString("audience"),
                row.getString("purpose_code"),
                row.getObject("correlation_id", UUID.class),
                row.getObject("approval_request_id", UUID.class),
                row.getString("waiver_decision"),
                decisionRevision,
                row.getObject("decision_actor_public_id", UUID.class),
                row.getObject("case_maker_public_id", UUID.class),
                row.getObject("requested_by_actor_public_id", UUID.class),
                row.getString("reason_code"),
                row.getObject("step_up_evidence_id", UUID.class),
                row.getString("step_up_evidence_audience"),
                row.getString("step_up_evidence_purpose"),
                row.getString("step_up_evidence_nonce_sha256"),
                row.getString("step_up_evidence_sha256"),
                instant(row.getTimestamp("step_up_verified_at")),
                instant(row.getTimestamp("step_up_consumed_at")),
                row.getObject("receipt_public_id", UUID.class),
                row.getBytes("exact_response_body"),
                row.getString("response_body_sha256"),
                row.getString("outcome"),
                row.getTimestamp("occurred_at").toInstant(),
                row.getTimestamp("received_at").toInstant());
    }

    private static Timestamp timestamp(java.time.Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static java.time.Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
