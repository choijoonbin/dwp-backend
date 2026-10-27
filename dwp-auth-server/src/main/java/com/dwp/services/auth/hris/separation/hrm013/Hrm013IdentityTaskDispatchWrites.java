package com.dwp.services.auth.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.LocalVerification;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;

/** Inbox admission and immutable callback writes within the caller transaction. */
final class Hrm013IdentityTaskDispatchWrites {
    private final JdbcTemplate jdbc;

    Hrm013IdentityTaskDispatchWrites(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void insertAdmission(long tenantId, Hrm013TaskDispatchV1.IdentityTaskDispatch body,
            String bodyDigest, Hrm013WorkloadProofV1.VerifiedProof proof,
            byte[] exactRequestBody, HrisServiceGrantAuthorityV1.GrantAuthority authority,
            byte[] authorityRequestBytes, byte[] authorityResponseBytes,
            LocalVerification preparation, byte[] receiptBytes, String receiptProof,
            String receiptSha256, Instant admittedAt) {
        var businessAuthority = (Hrm013WorkloadProofV1.BusinessAuthority)
                proof.claims().authorityMode();
        var inboundDrain = proof.claims().drainClaims();
        int inserted = jdbc.update("""
                INSERT INTO auth_hrm013_identity_task_dispatch_inbox(
                  tenant_id,dispatch_event_id,case_public_id,task_public_id,
                  task_version,task_owner_revision,person_public_id,separation_type,
                  effective_date,correlation_id,payload_sha256,request_body_sha256,
                  workload_proof_sha256,exact_request_body,dispatch_grant_set_id,
                  dispatch_grant_revision,tenant_grant_revision,authorization_revision,
                  dispatch_grant_use_mode,drain_root_work_item_id,
                  drain_root_created_at,drain_root_payload_sha256,
                  caller_signing_key_id,dispatch_authority_request,
                  dispatch_authority_response,dispatch_preparation_tx_id,
                  dispatch_preparation_sha256,admission_receipt,
                  admission_receipt_proof,admission_receipt_sha256,
                  lifecycle_state,admitted_at,next_execution_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,
                        ?,?,?,?,?,?,?,?,?,?,
                        ?,?,?,?,?,?,?,?,?,?,'RECEIVED',?,?)
                """, tenantId, body.dispatchEventId(), body.casePublicId(),
                body.taskPublicId(), body.taskVersion(), body.taskOwnerRevision(),
                body.personPublicId(), body.separationType(), body.effectiveDate(),
                body.correlationId(), body.payloadSha256(), bodyDigest,
                proof.proofSha256(), exactRequestBody, authority.grantSetId(),
                authority.grantRevision(), authority.tenantGrantRevision(),
                authority.authorizationRevision(),
                businessAuthority.grantUseMode().name(),
                inboundDrain == null ? null : inboundDrain.drainRootWorkItemId(),
                inboundDrain == null ? null
                        : Timestamp.from(inboundDrain.drainRootCreatedAt()),
                inboundDrain == null ? null : inboundDrain.drainRootPayloadSha256(),
                authority.callerSigningKeyId(),
                authorityRequestBytes, authorityResponseBytes,
                preparation.transactionId(),
                preparation.preparationSha256(), receiptBytes,
                receiptProof, receiptSha256,
                Timestamp.from(admittedAt), Timestamp.from(admittedAt));
        if (inserted != 1) throw Hrm013SafeErrorV1.authorityUnavailable(1);
    }

    void complete(Hrm013IdentityTerminalCommand command, String commandDigest,
            Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 callback, byte[] callbackBytes,
            HrisServiceGrantAuthorityV1.GrantAuthority authority,
            Hrm013WorkloadProofV1.BusinessAuthority mode,
            Hrm013WorkloadProofV1.DrainClaims drain, Instant occurredAt) {
        int updated = jdbc.update("""
                UPDATE auth_hrm013_identity_task_dispatch_inbox
                   SET lifecycle_state=?, completed_at=?, terminal_command_sha256=?,
                       terminal_external_receipt_public_id=?
                 WHERE tenant_id=? AND dispatch_event_id=?
                   AND lifecycle_state='RECEIVED'
                """, command.status(), Timestamp.from(occurredAt), commandDigest,
                command.externalReceiptPublicId(), command.tenantId(),
                command.dispatchEventId());
        if (updated != 1) throw Hrm013SafeErrorV1.invalidState();
        int inserted = jdbc.update("""
                INSERT INTO auth_hrm013_identity_task_receipt_outbox(
                  tenant_id,callback_event_id,dispatch_event_id,case_public_id,
                  task_public_id,correlation_id,exact_callback_body,
                  callback_payload_sha256,callback_grant_set_id,
                  callback_grant_revision,tenant_grant_revision,
                  authorization_revision,caller_signing_key_id,grant_use_mode,
                  drain_root_work_item_id,drain_root_created_at,
                  drain_root_payload_sha256,drain_continuation_kind,
                  drain_continuation_event_id,drain_continuation_revision,
                  drain_continuation_payload_sha256,next_attempt_at,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, command.tenantId(), callback.callbackEventId(),
                command.dispatchEventId(), callback.casePublicId(),
                callback.taskPublicId(), callback.correlationId(), callbackBytes,
                callback.payloadSha256(), authority.grantSetId(),
                authority.grantRevision(), authority.tenantGrantRevision(),
                authority.authorizationRevision(), authority.callerSigningKeyId(),
                mode.grantUseMode().name(),
                drain == null ? null : drain.drainRootWorkItemId(),
                drain == null ? null
                        : Timestamp.from(drain.drainRootCreatedAt()),
                drain == null ? null : drain.drainRootPayloadSha256(),
                drain == null ? null
                        : drain.drainContinuationKind().name(),
                drain == null ? null
                        : drain.drainContinuationEventId(),
                drain == null ? null
                        : drain.drainContinuationRevision(),
                drain == null ? null
                        : drain.drainContinuationPayloadSha256(),
                Timestamp.from(occurredAt),
                Timestamp.from(occurredAt));
        if (inserted != 1) throw Hrm013SafeErrorV1.invalidState();
    }

    void recordExecutionFailure(long tenantId, UUID dispatchEventId,
            Hrm013IdentityTaskDispatchPersistence persistence,
            java.util.function.IntToLongFunction backoff) {
        Integer attempt = jdbc.queryForObject("""
                SELECT execution_attempt_count + 1
                  FROM auth_hrm013_identity_task_dispatch_inbox
                 WHERE tenant_id=? AND dispatch_event_id=?
                   AND lifecycle_state='RECEIVED' FOR UPDATE
                """, Integer.class, tenantId, dispatchEventId);
        if (attempt == null || attempt > 10) return;
        long delay = backoff.applyAsLong(attempt);
        int changed = jdbc.update("""
                UPDATE auth_hrm013_identity_task_dispatch_inbox
                   SET execution_attempt_count=?,
                       next_execution_at=statement_timestamp()
                           + (? * INTERVAL '1 second'),
                       last_execution_error='EXECUTION_FAILED'
                 WHERE tenant_id=? AND dispatch_event_id=?
                   AND lifecycle_state='RECEIVED'
                """, attempt, delay, tenantId, dispatchEventId);
        if (changed == 1) {
            if (attempt == 10) {
                persistence.quarantineExecutionPoison(tenantId, dispatchEventId);
            }
            persistence.audit(tenantId, dispatchEventId, "TASK_FAILED", "RETRY",
                    Hrm013WorkloadProofV1.sha256((dispatchEventId + "\n" + attempt)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)), UUID.randomUUID());
            if (attempt == 10) {
                persistence.audit(tenantId, dispatchEventId, "DISPATCH_QUARANTINED", "FAILED",
                    Hrm013WorkloadProofV1.sha256((dispatchEventId + "\n" + attempt)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)), UUID.randomUUID());
            }
        }
    }

    ReceiptSelection claimNextReceipt(long tenantId) {
        List<UUID> ids = jdbc.queryForList("""
                SELECT callback_event_id
                  FROM auth_hrm013_identity_task_receipt_outbox
                 WHERE tenant_id=? AND attempt_count < 10
                   AND next_attempt_at <= statement_timestamp()
                   AND (delivery_state IN ('PENDING','RETRY')
                        OR (delivery_state='CLAIMED'
                            AND claim_until <= statement_timestamp()))
                 ORDER BY created_at,callback_event_id
                 FOR UPDATE SKIP LOCKED LIMIT 1
                """, UUID.class, tenantId);
        if (ids.isEmpty()) return null;
        UUID id = ids.getFirst();
        UUID claimToken = UUID.randomUUID();
        String claimOwner = "dwp-auth-server:hrm013-receipt-relay";
        int changed = jdbc.update("""
                UPDATE auth_hrm013_identity_task_receipt_outbox
                   SET delivery_state='CLAIMED',attempt_count=attempt_count+1,
                       claim_until=statement_timestamp() + INTERVAL '30 seconds',
                       claim_owner=?,claim_token=?,last_error_code=NULL
                 WHERE tenant_id=? AND callback_event_id=?
                   AND attempt_count < 10
                   AND next_attempt_at <= statement_timestamp()
                   AND (delivery_state IN ('PENDING','RETRY')
                        OR (delivery_state='CLAIMED'
                            AND claim_until <= statement_timestamp()))
                """, claimOwner, claimToken, tenantId, id);
        if (changed != 1) throw Hrm013SafeErrorV1.invalidState();
        return new ReceiptSelection(id, claimOwner, claimToken);
    }

    record ReceiptSelection(UUID callbackEventId, String owner, UUID token) { }
}
