package com.dwp.services.auth.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.dwp.services.auth.hris.separation.hrm013.Hrm013IdentityTaskDispatchPersistence.InboxTask;

/** Tenant-scoped SQL, replay reservations and immutable row snapshots. */
final class Hrm013IdentityTaskDispatchPersistence {
    private static final Hrm013TaskDispatchV1.Profile PROFILE = Hrm013TaskDispatchV1.IDENTITY;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate replayTransactions;

    Hrm013IdentityTaskDispatchPersistence(JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.replayTransactions = new TransactionTemplate(transactionManager);
        this.replayTransactions.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.replayTransactions.setTimeout(5);
    }

    boolean reserveProofPair(Hrm013WorkloadProofV1.ReplayReservation replay) {
        if (!PROFILE.bindingId().equals(replay.bindingId())
                || !PROFILE.callerWorkloadId().equals(replay.callerWorkloadId())
                || replay.proofIdSha256().equals(replay.requestNonceSha256())) return false;
        Boolean reserved = replayTransactions.execute(status -> {
            setTenant(replay.tenantId());
            Instant now = databaseNow();
            if (!replay.retainUntil().isAfter(now)
                    || replay.retainUntil().isAfter(now.plusSeconds(35))) return false;
            Integer count = jdbc.queryForObject("""
                    WITH inserted AS (
                      INSERT INTO auth_hrm013_identity_task_dispatch_proof_replays(
                        tenant_id,binding_id,caller_workload_id,token_kind,
                        token_digest,expires_at,reserved_at)
                      VALUES (?, ?, ?, 'JTI', ?, ?, ?),
                             (?, ?, ?, 'REQUEST_NONCE', ?, ?, ?)
                      ON CONFLICT (tenant_id,token_digest) DO NOTHING
                      RETURNING 1)
                    SELECT count(*) FROM inserted
                    """, Integer.class,
                    replay.tenantId(), replay.bindingId(), replay.callerWorkloadId(),
                    replay.proofIdSha256(), Timestamp.from(replay.retainUntil()),
                    Timestamp.from(now), replay.tenantId(), replay.bindingId(),
                    replay.callerWorkloadId(), replay.requestNonceSha256(),
                    Timestamp.from(replay.retainUntil()), Timestamp.from(now));
            if (count == null || count != 2) status.setRollbackOnly();
            return count != null && count == 2;
        });
        return Boolean.TRUE.equals(reserved);
    }

    void purgeExpiredReplays(long tenantId) {
        jdbc.queryForObject(
                "SELECT auth_v272_purge_expired_identity_replays(?)",
                Long.class, tenantId);
    }

    Optional<StoredAdmission> storedByEvent(long tenantId, UUID eventId) {
        List<StoredAdmission> rows = jdbc.query("""
                SELECT request_body_sha256,admission_receipt,
                       admission_receipt_proof,admission_receipt_sha256
                  FROM auth_hrm013_identity_task_dispatch_inbox
                 WHERE tenant_id=? AND dispatch_event_id=? FOR UPDATE
                """, (rs, row) -> new StoredAdmission(rs.getString(1),
                Hrm013TaskDispatchV1.decodeAdmissionReceipt(rs.getBytes(2)),
                rs.getBytes(2), rs.getString(3), rs.getString(4)), tenantId, eventId);
        if (rows.size() > 1) throw Hrm013SafeErrorV1.invalidState();
        return rows.stream().findFirst();
    }

    Optional<StoredIdentity> storedByTaskVersion(
            long tenantId, UUID taskId, long taskVersion) {
        List<StoredIdentity> rows = jdbc.query("""
                SELECT dispatch_event_id,request_body_sha256
                  FROM auth_hrm013_identity_task_dispatch_inbox
                 WHERE tenant_id=? AND task_public_id=? AND task_version=? FOR UPDATE
                """, (rs, row) -> new StoredIdentity(
                rs.getObject(1, UUID.class), rs.getString(2)),
                tenantId, taskId, taskVersion);
        return rows.stream().findFirst();
    }

    InboxTask requireInbox(long tenantId, UUID eventId) {
        List<InboxTask> rows = jdbc.query("""
                SELECT case_public_id,task_public_id,correlation_id,payload_sha256,
                       exact_request_body,lifecycle_state,tenant_grant_revision,
                       authorization_revision,dispatch_grant_use_mode,
                       drain_root_work_item_id,drain_root_created_at,
                       drain_root_payload_sha256,workload_proof_sha256,
                       dispatch_authority_request,dispatch_authority_response,
                       dispatch_preparation_tx_id,dispatch_preparation_sha256,
                       terminal_command_sha256,
                       terminal_external_receipt_public_id,completed_at
                  FROM auth_hrm013_identity_task_dispatch_inbox
                 WHERE tenant_id=? AND dispatch_event_id=? FOR UPDATE
                """, (rs, row) -> new InboxTask(rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getString(4), rs.getBytes(5), rs.getString(6), rs.getLong(7),
                rs.getLong(8), rs.getString(9), rs.getObject(10, UUID.class),
                rs.getTimestamp(11) == null ? null : rs.getTimestamp(11).toInstant(),
                rs.getString(12), rs.getString(13), rs.getBytes(14),
                rs.getBytes(15), rs.getLong(16), rs.getString(17),
                rs.getString(18), rs.getObject(19, UUID.class),
                rs.getTimestamp(20) == null ? null
                        : rs.getTimestamp(20).toInstant()), tenantId, eventId);
        if (rows.size() != 1) throw Hrm013SafeErrorV1.notFound();
        return rows.getFirst();
    }

    StoredCompletion requireCompletion(long tenantId, UUID eventId) {
        List<StoredCompletion> rows = jdbc.query("""
                SELECT exact_callback_body,callback_grant_set_id,
                       callback_grant_revision,tenant_grant_revision,
                       authorization_revision,caller_signing_key_id
                  FROM auth_hrm013_identity_task_receipt_outbox
                 WHERE tenant_id=? AND dispatch_event_id=? FOR UPDATE
                """, (rs, row) -> {
            var callback = Hrm013SeparationBridgeV1.decodeTaskReceipt(rs.getBytes(1));
            var authority = storedAuthority(tenantId, rs.getObject(2, UUID.class),
                    rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getString(6),
                    Hrm013SeparationBridgeV1.HRM_008_IDENTITY);
            return new StoredCompletion(callback, authority, true);
        }, tenantId, eventId);
        if (rows.size() != 1) throw Hrm013SafeErrorV1.invalidState();
        return rows.getFirst();
    }

    StoredReceiptClaim queryReceiptClaim(long tenantId, UUID callbackId,
            String claimOwner, UUID claimToken) {
        List<StoredReceiptClaim> rows = jdbc.query("""
                SELECT outbox.dispatch_event_id,outbox.case_public_id,
                       outbox.task_public_id,outbox.correlation_id,
                       outbox.exact_callback_body,outbox.callback_payload_sha256,
                       outbox.callback_grant_set_id,outbox.callback_grant_revision,
                       outbox.tenant_grant_revision,outbox.authorization_revision,
                       outbox.caller_signing_key_id,outbox.attempt_count,
                       outbox.claim_until,outbox.grant_use_mode,
                       outbox.drain_root_work_item_id,outbox.drain_root_created_at,
                       outbox.drain_root_payload_sha256,
                       outbox.drain_continuation_kind,
                       outbox.drain_continuation_event_id,
                       outbox.drain_continuation_revision,
                       outbox.drain_continuation_payload_sha256,
                       inbox.exact_request_body,inbox.payload_sha256
                  FROM auth_hrm013_identity_task_receipt_outbox outbox
                  JOIN auth_hrm013_identity_task_dispatch_inbox inbox
                    ON inbox.tenant_id=outbox.tenant_id
                   AND inbox.dispatch_event_id=outbox.dispatch_event_id
                 WHERE outbox.tenant_id=? AND outbox.callback_event_id=?
                   AND outbox.delivery_state='CLAIMED'
                   AND outbox.claim_owner=? AND outbox.claim_token=? FOR UPDATE
                """, (rs, row) -> {
            var parent = (Hrm013TaskDispatchV1.IdentityTaskDispatch)
                    Hrm013TaskDispatchV1.decode(PROFILE, tenantId, rs.getBytes(22));
            String persistedMode = rs.getString(14);
            return new StoredReceiptClaim(tenantId, callbackId,
                    rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                    rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                    rs.getBytes(5), rs.getString(6), rs.getObject(7, UUID.class),
                    rs.getLong(8), rs.getLong(9), rs.getLong(10), rs.getString(11),
                    rs.getInt(12), claimOwner, claimToken,
                    rs.getTimestamp(13).toInstant(), parent.dispatchedAt(),
                    rs.getString(23),
                    new Hrm013WorkloadProofV1.BusinessAuthority(
                            Hrm013WorkloadProofV1.GrantUseMode.valueOf(persistedMode),
                            rs.getLong(9), rs.getLong(10)),
                    "NEW_WORK".equals(persistedMode) ? null
                            : new Hrm013WorkloadProofV1.DrainClaims(
                                    rs.getObject(15, UUID.class),
                                    rs.getTimestamp(16).toInstant(), rs.getString(17),
                                    Hrm013WorkloadProofV1.DrainContinuationKind.valueOf(
                                            rs.getString(18)),
                                    rs.getObject(19, UUID.class), rs.getLong(20),
                                    rs.getString(21)));
        }, tenantId, callbackId, claimOwner, claimToken);
        if (rows.size() != 1) throw Hrm013SafeErrorV1.invalidState();
        return rows.getFirst();
    }

    HrisServiceGrantAuthorityV1.GrantAuthority storedAuthority(
            long tenantId, UUID grantSetId, long grantRevision,
            long tenantRevision, long authorizationRevision, String keyId,
            Hrm013SeparationBridgeV1.BindingProfile profile) {
        List<HrisServiceGrantAuthorityV1.GrantAuthority> rows = jdbc.query("""
                SELECT grant_set.grant_set_id,grant_set.grant_revision,
                       grant_set.tenant_id,grant_set.tenant_grant_revision,
                       grant_set.authorization_revision,grant_set.decision,
                       binding.caller_workload_id,binding.caller_spiffe_id,
                       binding.caller_signing_key_id,binding.caller_signing_public_jwk::text,
                       binding.owner_workload_id,binding.owner_spiffe_id,
                       binding.owner_signing_key_id,binding.owner_signing_public_jwk::text,
                       binding.binding_id,binding.purpose,
                       grant_set.supersedes_grant_set_id,grant_set.grant_digest,
                       grant_set.recorded_at
                  FROM auth_hris_service_grant_sets grant_set
                  JOIN auth_hris_service_grant_bindings binding
                    ON binding.tenant_id=grant_set.tenant_id
                   AND binding.grant_set_id=grant_set.grant_set_id
                 WHERE grant_set.tenant_id=? AND grant_set.grant_set_id=?
                   AND grant_set.grant_revision=?
                   AND grant_set.tenant_grant_revision=?
                   AND grant_set.authorization_revision=?
                   AND binding.binding_id=? AND binding.caller_workload_id=?
                   AND binding.owner_workload_id=? AND binding.purpose=?
                   AND binding.caller_signing_key_id=?
                """, (rs, row) -> new HrisServiceGrantAuthorityV1.GrantAuthority(
                rs.getObject(1, UUID.class), rs.getLong(2), rs.getLong(3),
                rs.getLong(4), rs.getLong(5), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getString(9), readJwk(rs.getString(10)),
                rs.getString(11), rs.getString(12), rs.getString(13),
                readJwk(rs.getString(14)), rs.getString(15), rs.getString(16),
                rs.getObject(17, UUID.class), rs.getString(18),
                rs.getTimestamp(19).toInstant()), tenantId, grantSetId, grantRevision,
                tenantRevision, authorizationRevision, profile.bindingId(),
                profile.callerWorkloadId(), profile.ownerWorkloadId(),
                profile.purpose(), keyId);
        if (rows.size() != 1) throw Hrm013SafeErrorV1.authorityDenied();
        return rows.getFirst();
    }

    HrisServiceGrantAuthorityV1.RsaPublicJwk readJwk(String value) {
        try {
            JsonNode node = mapper.readTree(value);
            return new HrisServiceGrantAuthorityV1.RsaPublicJwk(
                    node.path("alg").asText(), node.path("e").asText(),
                    node.path("kid").asText(), node.path("kty").asText(),
                    node.path("n").asText(), node.path("use").asText());
        } catch (RuntimeException | java.io.IOException failure) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    void recoverExpiredClaims(long tenantId) {
        jdbc.update("""
                UPDATE auth_hrm013_identity_task_receipt_outbox
                   SET delivery_state='DEAD',claim_until=NULL,claim_owner=NULL,
                       claim_token=NULL,last_error_code='RESULT_UNKNOWN'
                 WHERE tenant_id=? AND delivery_state='CLAIMED'
                   AND attempt_count=10 AND claim_until <= statement_timestamp()
                """, tenantId);
    }

    String terminalCommandDigest(Hrm013IdentityTerminalCommand command) {
        var node = mapper.createObjectNode();
        node.put("tenantId", command.tenantId());
        node.put("dispatchEventId", command.dispatchEventId().toString());
        node.put("callbackEventId", command.callbackEventId().toString());
        node.put("externalReceiptPublicId",
                command.externalReceiptPublicId().toString());
        node.put("status", command.status());
        if (command.authorityMode() == null) {
            node.put("authoritySelection", "AUTO_NEW_WORK_THEN_DRAIN");
        } else if (command.authorityMode()
                instanceof Hrm013WorkloadProofV1.BusinessAuthority mode) {
            node.put("grantUseMode", mode.grantUseMode().name());
            node.put("tenantGrantRevision", mode.tenantGrantRevision());
            node.put("authorizationRevision", mode.authorizationRevision());
        } else {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        Hrm013WorkloadProofV1.DrainClaims drain = command.drainClaims();
        if (drain != null) {
            node.put("drainRootWorkItemId", drain.drainRootWorkItemId().toString());
            node.put("drainRootCreatedAt", drain.drainRootCreatedAt().toString());
            node.put("drainRootPayloadSha256", drain.drainRootPayloadSha256());
            node.put("drainContinuationKind",
                    drain.drainContinuationKind().name());
            node.put("drainContinuationEventId",
                    drain.drainContinuationEventId().toString());
            node.put("drainContinuationRevision",
                    drain.drainContinuationRevision());
            node.put("drainContinuationPayloadSha256",
                    drain.drainContinuationPayloadSha256());
        }
        try {
            return Hrm013WorkloadProofV1.rfc8785Sha256(
                    mapper, mapper.writeValueAsBytes(node));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw Hrm013SafeErrorV1.invalidState();
        }
    }

    static boolean terminalReplayMatches(Hrm013IdentityTerminalCommand command,
            InboxTask task,
            Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 callback) {
        return command.callbackEventId().equals(callback.callbackEventId())
                && command.externalReceiptPublicId()
                        .equals(callback.externalReceiptPublicId())
                && command.status().equals(callback.status())
                && task.casePublicId().equals(callback.casePublicId())
                && task.taskPublicId().equals(callback.taskPublicId())
                && task.payloadSha256().equals(callback.originatingPayloadSha256())
                && task.correlationId().equals(callback.correlationId())
                && Objects.equals(task.completedAt(), callback.occurredAt())
                && callback.payloadSha256().equals(
                        Hrm013SeparationBridgeV1.payloadSha256(callback));
    }

    /** Applies the identity-owner side effect under the inbox row lock. */
    void revokeIdentityAccess(UUID dispatchEventId) {
        List<RevocationOutcome> outcomes = jdbc.query("""
                SELECT subject_state,evidence_sha256
                  FROM public.auth_v272_revoke_identity_access(?)
                """, (rs, row) -> new RevocationOutcome(
                rs.getString(1), rs.getString(2)), dispatchEventId);
        if (outcomes.size() != 1
                || !List.of("ABSENT", "REVOKED", "ALREADY_REVOKED")
                        .contains(outcomes.getFirst().subjectState())
                || outcomes.getFirst().evidenceSha256() == null
                || !outcomes.getFirst().evidenceSha256()
                        .matches("^[a-f0-9]{64}$")) {
            throw Hrm013SafeErrorV1.invalidState();
        }
    }

    void quarantine(long tenantId, UUID eventId, String storedDigest,
            String rejectedDigest, String proofDigest, String reason, UUID correlationId) {
        jdbc.update("""
                INSERT INTO auth_hrm013_identity_task_dispatch_quarantine(
                  quarantine_id,tenant_id,dispatch_event_id,stored_request_body_sha256,
                  rejected_request_body_sha256,rejected_workload_proof_sha256,
                  reason_code,correlation_id)
                VALUES (?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), tenantId, eventId, storedDigest,
                rejectedDigest, proofDigest, reason, correlationId);
        audit(tenantId, eventId, "DISPATCH_QUARANTINED", "CONFLICT",
                rejectedDigest, correlationId);
    }

    void quarantineExecutionPoison(long tenantId, UUID eventId) {
        int inserted = jdbc.update("""
                INSERT INTO auth_hrm013_identity_task_dispatch_quarantine(
                  quarantine_id,tenant_id,dispatch_event_id,
                  stored_request_body_sha256,rejected_request_body_sha256,
                  rejected_workload_proof_sha256,reason_code,correlation_id)
                SELECT ?,tenant_id,dispatch_event_id,request_body_sha256,
                       request_body_sha256,workload_proof_sha256,
                       'EXECUTION_POISON',correlation_id
                  FROM auth_hrm013_identity_task_dispatch_inbox
                 WHERE tenant_id=? AND dispatch_event_id=?
                   AND lifecycle_state='RECEIVED'
                   AND execution_attempt_count=10
                """, UUID.randomUUID(), tenantId, eventId);
        if (inserted != 1) throw Hrm013SafeErrorV1.invalidState();
    }

    void audit(long tenantId, UUID eventId, String action, String outcome,
            String evidence, UUID correlationId) {
        jdbc.update("""
                INSERT INTO auth_hrm013_identity_task_audit_events(
                  audit_event_id,tenant_id,dispatch_event_id,action_code,
                  outcome_code,evidence_sha256,correlation_id)
                VALUES (?,?,?,?,?,?,?)
                """, UUID.randomUUID(), tenantId, eventId, action, outcome,
                evidence, correlationId);
    }

    void setTenant(long tenantId) {
        if (tenantId < 1) throw Hrm013SafeErrorV1.authorityDenied();
        jdbc.queryForObject("SELECT set_config(?, ?, true)", String.class,
                "dwp.auth.hrm013.tenant", Long.toString(tenantId));
    }

    void lock(long tenantId, String key) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                ignored -> { }, "DWP_AUTH_HRM013_IDENTITY_V1:" + tenantId + ":" + key);
    }

    Instant databaseNow() {
        Timestamp value = jdbc.queryForObject(
                "SELECT clock_timestamp()", Timestamp.class);
        if (value == null) throw Hrm013SafeErrorV1.authorityUnavailable(1);
        return value.toInstant();
    }

    Instant transactionNow() {
        Timestamp value = jdbc.queryForObject(
                "SELECT transaction_timestamp()", Timestamp.class);
        if (value == null) throw Hrm013SafeErrorV1.authorityUnavailable(1);
        return value.toInstant();
    }

    static boolean constantEquals(String left, String right) {
        return java.security.MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    record StoredAdmission(
            String requestBodySha256,
            Hrm013TaskDispatchV1.Hrm013TaskDispatchAdmissionReceiptV1 receipt,
            byte[] exactReceiptBody, String receiptProof, String receiptSha256) {
        StoredAdmission { exactReceiptBody = exactReceiptBody.clone(); }
        @Override public byte[] exactReceiptBody() { return exactReceiptBody.clone(); }
    }
    record StoredIdentity(UUID dispatchEventId, String requestBodySha256) { }
    record RevocationOutcome(String subjectState, String evidenceSha256) { }
    record InboxTask(
            UUID casePublicId, UUID taskPublicId, UUID correlationId,
            String payloadSha256, byte[] exactRequestBody, String state,
            long tenantGrantRevision, long authorizationRevision,
            String dispatchGrantUseMode, UUID drainRootWorkItemId,
            Instant drainRootCreatedAt, String drainRootPayloadSha256,
            String workloadProofSha256, byte[] dispatchAuthorityRequest,
            byte[] dispatchAuthorityResponse,
            long dispatchPreparationTransactionId,
            String dispatchPreparationSha256,
            String terminalCommandSha256,
            UUID terminalExternalReceiptPublicId, Instant completedAt) {
        InboxTask {
            exactRequestBody = exactRequestBody.clone();
            dispatchAuthorityRequest = dispatchAuthorityRequest.clone();
            dispatchAuthorityResponse = dispatchAuthorityResponse.clone();
        }
        @Override public byte[] exactRequestBody() { return exactRequestBody.clone(); }
        @Override public byte[] dispatchAuthorityRequest() {
            return dispatchAuthorityRequest.clone();
        }
        @Override public byte[] dispatchAuthorityResponse() {
            return dispatchAuthorityResponse.clone();
        }
    }
    record StoredReceiptClaim(
            long tenantId, UUID callbackEventId, UUID dispatchEventId,
            UUID casePublicId, UUID taskPublicId, UUID correlationId,
            byte[] exactCallbackBody, String callbackPayloadSha256,
            UUID grantSetId, long grantRevision, long tenantGrantRevision,
            long authorizationRevision, String callerSigningKeyId,
            int attemptCount, String claimOwner, UUID claimToken,
            Instant claimUntil, Instant terminalParentCreatedAt,
            String originatingPayloadSha256,
            Hrm013WorkloadProofV1.BusinessAuthority authorityMode,
            Hrm013WorkloadProofV1.DrainClaims drainClaims) { }
    record StoredCompletion(
            Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 callback,
            HrisServiceGrantAuthorityV1.GrantAuthority authority, boolean replayed) { }
}
