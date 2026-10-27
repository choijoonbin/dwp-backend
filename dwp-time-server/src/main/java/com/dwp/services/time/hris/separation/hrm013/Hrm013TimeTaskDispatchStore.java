package com.dwp.services.time.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable, receipt-first Time task inbox and HRM-008 callback outbox. */
@Repository
@ConditionalOnProperty(prefix = "dwp.time.hrm013-task-dispatch", name = "enabled",
        havingValue = "true")
public final class Hrm013TimeTaskDispatchStore {
    public enum AdmissionKind { ADMITTED, EXACT_REPLAY, DIGEST_CONFLICT }

    public record AdmissionCandidate(
            long tenantId, Hrm013TaskDispatchV1.TimeAndPayrollTaskDispatch dispatch,
            byte[] exactRequestBody, String requestBodySha256, String proofSha256,
            long tenantGrantRevision, long authorizationRevision,
            String grantUseMode, String drainClaimsJson,
            byte[] exactReceiptBody, String receiptProof,
            Instant admittedAt, Instant authorityExpiresAt) {
        public AdmissionCandidate {
            if (tenantId < 1 || tenantGrantRevision < 1 || authorizationRevision < 1) {
                throw new IllegalArgumentException("positive authority revisions required");
            }
            Objects.requireNonNull(dispatch); Objects.requireNonNull(exactRequestBody);
            Objects.requireNonNull(exactReceiptBody); Objects.requireNonNull(admittedAt);
            Objects.requireNonNull(authorityExpiresAt);
            requireDigest(requestBodySha256); requireDigest(proofSha256);
            if (!List.of("NEW_WORK", "DRAIN_EXISTING").contains(grantUseMode)
                    || ("DRAIN_EXISTING".equals(grantUseMode) != (drainClaimsJson != null))
                    || receiptProof == null || receiptProof.isBlank()) {
                throw new IllegalArgumentException("sealed admission candidate required");
            }
            exactRequestBody = exactRequestBody.clone();
            exactReceiptBody = exactReceiptBody.clone();
        }
        @Override public byte[] exactRequestBody() { return exactRequestBody.clone(); }
        @Override public byte[] exactReceiptBody() { return exactReceiptBody.clone(); }
    }

    public record Admission(
            AdmissionKind kind, byte[] exactReceiptBody, String receiptProof,
            Instant admittedAt) {
        public Admission {
            Objects.requireNonNull(kind); Objects.requireNonNull(exactReceiptBody);
            exactReceiptBody = exactReceiptBody.clone();
            if (kind != AdmissionKind.DIGEST_CONFLICT
                    && (receiptProof == null || receiptProof.isBlank() || admittedAt == null)) {
                throw new IllegalArgumentException("receipt evidence required");
            }
        }
        @Override public byte[] exactReceiptBody() { return exactReceiptBody.clone(); }
    }

    public record TerminalCommand(
            long tenantId, UUID dispatchEventId, String status,
            UUID externalReceiptPublicId, Instant occurredAt, UUID correlationId) {
        public TerminalCommand {
            if (tenantId < 1 || !List.of("COMPLETED", "FAILED").contains(status)) {
                throw new IllegalArgumentException("terminal command invalid");
            }
            Objects.requireNonNull(dispatchEventId); Objects.requireNonNull(externalReceiptPublicId);
            Objects.requireNonNull(occurredAt); Objects.requireNonNull(correlationId);
        }
    }

    public record CallbackRecord(
            long tenantId, UUID outboxId, UUID dispatchEventId, UUID callbackEventId,
            UUID casePublicId, UUID taskPublicId, long ownerRevision,
            String status, UUID externalReceiptPublicId,
            String originatingPayloadSha256, Instant terminalParentCreatedAt,
            Instant occurredAt, UUID correlationId,
            byte[] exactCallbackBody, String callbackPayloadSha256,
            String grantUseMode, String drainClaimsJson, int attemptCount) {
        public CallbackRecord {
            exactCallbackBody = Objects.requireNonNull(exactCallbackBody).clone();
            requireDigest(callbackPayloadSha256);
        }
        @Override public byte[] exactCallbackBody() { return exactCallbackBody.clone(); }
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate required;
    private final TransactionTemplate requiresNew;
    private final Clock clock;

    public Hrm013TimeTaskDispatchStore(
            NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this(jdbc, transactionManager, Clock.systemUTC());
    }

    Hrm013TimeTaskDispatchStore(
            NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.required = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Reserves proof-id and nonce digests in one independent transaction. */
    public boolean reserveProof(Hrm013WorkloadProofV1.ReplayReservation reservation) {
        if (reservation == null || reservation.tenantId() < 1
                || reservation.proofIdSha256().equals(reservation.requestNonceSha256())) {
            return false;
        }
        Boolean result = requiresNew.execute(status -> {
            setTenant(reservation.tenantId());
            MapSqlParameterSource parameters = new MapSqlParameterSource()
                    .addValue("tenant", reservation.tenantId())
                    .addValue("binding", reservation.bindingId())
                    .addValue("caller", reservation.callerWorkloadId())
                    .addValue("proof", reservation.proofIdSha256())
                    .addValue("nonce", reservation.requestNonceSha256())
                    .addValue("retainUntil", Timestamp.from(reservation.retainUntil()));
            int inserted = jdbc.update("""
                    INSERT INTO hris_hrm013_time_proof_replay(
                        tenant_id,binding_id,caller_workload_id,digest_kind,digest_sha256,
                        retain_until)
                    VALUES
                        (:tenant,:binding,:caller,'PROOF_ID',:proof,:retainUntil),
                        (:tenant,:binding,:caller,'REQUEST_NONCE',:nonce,:retainUntil)
                    ON CONFLICT DO NOTHING
                    """, parameters);
            if (inserted != 2) status.setRollbackOnly();
            return inserted == 2;
        });
        return Boolean.TRUE.equals(result);
    }

    /** Atomically reserves the dispatch and returns the originally stored receipt on replay. */
    public Admission admit(AdmissionCandidate candidate) {
        Objects.requireNonNull(candidate);
        Admission result = required.execute(status -> admitInTransaction(candidate));
        return Objects.requireNonNull(result);
    }

    private Admission admitInTransaction(AdmissionCandidate value) {
        setTenant(value.tenantId());
        requireBefore(value.authorityExpiresAt());
        List<StoredAdmission> existing = findForUpdate(
                value.tenantId(), value.dispatch().dispatchEventId());
        if (!existing.isEmpty()) {
            Admission replay = compareOrQuarantine(value, existing.getFirst());
            requireBefore(value.authorityExpiresAt());
            return replay;
        }
        if (hasDigestConflict(value)) {
            quarantine(value, "DIGEST_RESERVATION_CONFLICT");
            requireBefore(value.authorityExpiresAt());
            return conflict();
        }
        MapSqlParameterSource parameters = parameters(value);
        int inserted = jdbc.update("""
                    INSERT INTO hris_hrm013_time_task_dispatch_inbox(
                        tenant_id,dispatch_event_id,case_public_id,task_public_id,task_type,
                        task_owner_revision,task_version,worker_public_id,
                        relationship_public_id,separation_type,effective_date,last_working_date,
                        dispatched_at,
                        payload_sha256,request_body_sha256,proof_sha256,exact_request_body,
                        exact_receipt_body,receipt_proof,tenant_grant_revision,
                        authorization_revision,grant_use_mode,correlation_id,
                        drain_claims_json,admitted_at)
                    VALUES(
                        :tenant,:event,:caseId,:taskId,'TIME_FINALIZATION',:ownerRevision,
                        :taskVersion,:workerId,:relationshipId,:separationType,:effectiveDate,
                        :lastWorkingDate,:dispatchedAt,:payload,:requestDigest,:proofDigest,:requestBody,
                        :receiptBody,:receiptProof,:tenantGrantRevision,:authorizationRevision,
                        :grantUseMode,:correlation,CAST(:drainClaims AS jsonb),
                        :admittedAt)
                    ON CONFLICT DO NOTHING
                    """, parameters);
        if (inserted == 1) {
            requireBefore(value.authorityExpiresAt());
            return new Admission(AdmissionKind.ADMITTED, value.exactReceiptBody(),
                    value.receiptProof(), value.admittedAt());
        }
        existing = findForUpdate(value.tenantId(), value.dispatch().dispatchEventId());
        if (existing.isEmpty()) {
            if (hasDigestConflict(value)) {
                quarantine(value, "DIGEST_RESERVATION_CONFLICT");
                requireBefore(value.authorityExpiresAt());
                return conflict();
            }
            throw Hrm013SafeErrorV1.authorityUnavailable(1);
        }
        return compareOrQuarantine(value, existing.getFirst());
    }

    private Admission compareOrQuarantine(
            AdmissionCandidate candidate, StoredAdmission stored) {
        boolean exact = stored.taskPublicId().equals(candidate.dispatch().taskPublicId())
                && stored.payloadSha256().equals(candidate.dispatch().payloadSha256())
                && stored.requestBodySha256().equals(candidate.requestBodySha256())
                && Arrays.equals(stored.exactRequestBody(), candidate.exactRequestBody());
        if (exact) return new Admission(AdmissionKind.EXACT_REPLAY,
                stored.exactReceiptBody(), stored.receiptProof(), stored.admittedAt());
        quarantine(candidate, "DISPATCH_EVENT_DIGEST_CONFLICT");
        return conflict();
    }

    private Admission conflict() {
        return new Admission(AdmissionKind.DIGEST_CONFLICT, new byte[0], null, null);
    }

    private void quarantine(AdmissionCandidate value, String reason) {
        jdbc.update("""
                INSERT INTO hris_hrm013_time_task_dispatch_quarantine(
                    quarantine_id,tenant_id,dispatch_event_id,task_public_id,
                    payload_sha256,request_body_sha256,proof_sha256,reason_code,
                    exact_request_body,correlation_id,quarantined_at)
                VALUES(:id,:tenant,:event,:taskId,:payload,:requestDigest,:proofDigest,
                    :reason,:requestBody,:correlation,CURRENT_TIMESTAMP)
                ON CONFLICT (tenant_id,dispatch_event_id,request_body_sha256) DO NOTHING
                """, parameters(value).addValue("id", UUID.randomUUID())
                .addValue("reason", reason));
    }

    public void enqueueTerminal(TerminalCommand command) {
        Objects.requireNonNull(command);
        TerminalDecision decision = required.execute(status -> {
            setTenant(command.tenantId());
            List<StoredDispatch> rows = findDispatchForUpdate(
                    command.tenantId(), command.dispatchEventId());
            if (rows.size() != 1) throw Hrm013SafeErrorV1.notFound();
            StoredDispatch dispatch = rows.getFirst();
            long callbackRevision = Math.addExact(dispatch.taskOwnerRevision(), 1);
            UUID callbackEvent = Hrm013WorkloadProofV1.terminalChildEventId(
                    command.tenantId(), dispatch.dispatchEventId(), callbackRevision,
                    "SVC-PEP-HRM-008");
            var callback = Hrm013SeparationBridgeV1.seal(
                    new Hrm013SeparationBridgeV1.SeparationTaskReceiptV2(
                            callbackEvent, dispatch.casePublicId(), dispatch.taskPublicId(),
                            Hrm013WorkloadProofV1.TaskOwner.TIME.taskType(), command.status(),
                            callbackRevision, command.externalReceiptPublicId(),
                            dispatch.payloadSha256(), command.occurredAt(), "0".repeat(64),
                            command.correlationId()));
            Hrm013SeparationBridgeV1.requireBodyBinding(
                    Hrm013SeparationBridgeV1.HRM_008_TIME, callback);
            TerminalCandidate candidate = new TerminalCandidate(
                    callbackEvent, callbackRevision, command.status(),
                    command.externalReceiptPublicId(), command.occurredAt(),
                    command.correlationId(), Hrm013SeparationBridgeV1.encode(callback),
                    callback.payloadSha256());
            List<StoredTerminal> prior = findTerminalForUpdate(
                    command.tenantId(), command.dispatchEventId());
            if (!prior.isEmpty()) {
                StoredTerminal existing = prior.getFirst();
                if (existing.matches(candidate)) return TerminalDecision.replay();
                status.setRollbackOnly();
                return TerminalDecision.conflict(existing, candidate);
            }
            if (!"ADMITTED".equals(dispatch.status())) {
                throw Hrm013SafeErrorV1.invalidState();
            }
            int terminal = jdbc.update("""
                    UPDATE hris_hrm013_time_task_dispatch_inbox
                       SET status=:status, terminal_at=:occurredAt, updated_at=CURRENT_TIMESTAMP
                     WHERE tenant_id=:tenant AND dispatch_event_id=:dispatch
                       AND status='ADMITTED'
                    """, new MapSqlParameterSource()
                    .addValue("status", command.status())
                    .addValue("occurredAt", Timestamp.from(command.occurredAt()))
                    .addValue("tenant", command.tenantId())
                    .addValue("dispatch", command.dispatchEventId()));
            if (terminal != 1) throw Hrm013SafeErrorV1.invalidState();
            int inserted = jdbc.update("""
                    INSERT INTO hris_hrm013_time_task_receipt_outbox(
                        tenant_id,outbox_id,dispatch_event_id,callback_event_id,
                        case_public_id,task_public_id,owner_revision,status,
                        external_receipt_public_id,originating_payload_sha256,
                        terminal_parent_created_at,occurred_at,
                        correlation_id,exact_callback_body,callback_payload_sha256,
                        grant_use_mode,drain_claims_json,
                        available_at)
                    VALUES(:tenant,:outbox,:dispatch,:callback,:caseId,:taskId,:revision,
                        :status,:externalReceipt,:payload,:parentCreatedAt,:occurredAt,:correlation,
                        :callbackBody,:callbackPayload,:grantUseMode,
                        CAST(:drainClaims AS jsonb),CURRENT_TIMESTAMP)
                    """, new MapSqlParameterSource()
                    .addValue("tenant", command.tenantId())
                    .addValue("outbox", UUID.randomUUID())
                    .addValue("dispatch", dispatch.dispatchEventId())
                    .addValue("callback", callbackEvent)
                    .addValue("caseId", dispatch.casePublicId())
                    .addValue("taskId", dispatch.taskPublicId())
                    .addValue("revision", callbackRevision)
                    .addValue("status", command.status())
                    .addValue("externalReceipt", command.externalReceiptPublicId())
                    .addValue("payload", dispatch.payloadSha256())
                    .addValue("parentCreatedAt", Timestamp.from(dispatch.dispatchedAt()))
                    .addValue("occurredAt", Timestamp.from(command.occurredAt()))
                    .addValue("correlation", command.correlationId())
                    .addValue("callbackBody", candidate.exactCallbackBody())
                    .addValue("callbackPayload", candidate.callbackPayloadSha256())
                    .addValue("grantUseMode", dispatch.grantUseMode())
                    .addValue("drainClaims", dispatch.drainClaimsJson()));
            if (inserted != 1) throw Hrm013SafeErrorV1.failure(
                    Hrm013SafeErrorV1.ErrorCode.INTERNAL_ERROR);
            return TerminalDecision.accepted();
        });
        if (decision == null) throw Hrm013SafeErrorV1.failure(
                Hrm013SafeErrorV1.ErrorCode.INTERNAL_ERROR);
        if (decision.existing() != null) {
            quarantineTerminal(command, decision.existing(), decision.rejected());
            throw Hrm013SafeErrorV1.digestConflict();
        }
    }

    public List<CallbackRecord> claimCallbacks(long tenantId, int limit, DurationLease lease) {
        if (tenantId < 1 || limit < 1 || limit > 100 || lease == null) {
            throw new IllegalArgumentException("bounded callback claim required");
        }
        return required.execute(status -> {
            setTenant(tenantId);
            jdbc.update("""
                WITH expired_final_attempts AS (
                    SELECT tenant_id,outbox_id
                      FROM hris_hrm013_time_task_receipt_outbox
                     WHERE tenant_id=:tenant
                       AND delivery_status='SENDING'
                       AND locked_until <= CURRENT_TIMESTAMP
                       AND attempt_count=10
                     ORDER BY locked_until,created_at
                     LIMIT :limit FOR UPDATE SKIP LOCKED
                )
                UPDATE hris_hrm013_time_task_receipt_outbox o
                   SET delivery_status='DEAD',locked_by=NULL,locked_until=NULL,
                       last_error_code='RESULT_UNKNOWN',updated_at=CURRENT_TIMESTAMP
                  FROM expired_final_attempts e
                 WHERE o.tenant_id=e.tenant_id AND o.outbox_id=e.outbox_id
                """, new MapSqlParameterSource().addValue("tenant", tenantId)
                .addValue("limit", limit));
            return jdbc.query("""
                WITH candidates AS (
                    SELECT tenant_id,outbox_id
                      FROM hris_hrm013_time_task_receipt_outbox
                     WHERE tenant_id=:tenant
                       AND attempt_count < 10
                       AND ((delivery_status IN ('PENDING','RETRY')
                             AND available_at <= CURRENT_TIMESTAMP)
                            OR (delivery_status='SENDING'
                                AND locked_until <= CURRENT_TIMESTAMP))
                     ORDER BY available_at,created_at
                     LIMIT :limit FOR UPDATE SKIP LOCKED
                )
                UPDATE hris_hrm013_time_task_receipt_outbox o
                   SET delivery_status='SENDING',locked_by=:worker,
                       locked_until=CURRENT_TIMESTAMP + (:seconds * INTERVAL '1 second'),
                       attempt_count=o.attempt_count+1,updated_at=CURRENT_TIMESTAMP
                  FROM candidates c
                 WHERE o.tenant_id=c.tenant_id AND o.outbox_id=c.outbox_id
                RETURNING o.*
                """, new MapSqlParameterSource().addValue("tenant", tenantId)
                .addValue("limit", limit)
                .addValue("worker", lease.workerId()).addValue("seconds", lease.seconds()),
                Hrm013TimeTaskDispatchStore::callback);
        });
    }

    public void markDelivered(long tenantId, UUID outboxId, String workerId,
            int leaseAttempt) {
        requireLeaseFence(workerId, leaseAttempt);
        Integer updated = required.execute(status -> {
            setTenant(tenantId);
            return jdbc.update("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET delivery_status='DELIVERED',delivered_at=CURRENT_TIMESTAMP,
                       locked_by=NULL,locked_until=NULL,last_error_code=NULL,
                       updated_at=CURRENT_TIMESTAMP
                 WHERE tenant_id=:tenant AND outbox_id=:outbox AND locked_by=:worker
                   AND attempt_count=:attempt AND delivery_status='SENDING'
                   AND locked_until > CURRENT_TIMESTAMP
                """, new MapSqlParameterSource().addValue("tenant", tenantId)
                .addValue("outbox", outboxId).addValue("worker", workerId)
                .addValue("attempt", leaseAttempt));
        });
        if (!Integer.valueOf(1).equals(updated)) throw Hrm013SafeErrorV1.authorityDenied();
    }

    public void markRetry(long tenantId, UUID outboxId, String workerId,
            int leaseAttempt, String safeCode, long retryDelaySeconds) {
        requireLeaseFence(workerId, leaseAttempt);
        if (safeCode == null || safeCode.isBlank() || safeCode.length() > 80
                || "RESULT_UNKNOWN".equals(safeCode)
                || retryDelaySeconds < 0 || retryDelaySeconds > 300) {
            throw new IllegalArgumentException("bounded retry evidence required");
        }
        Integer updated = required.execute(status -> {
            setTenant(tenantId);
            return jdbc.update("""
                UPDATE hris_hrm013_time_task_receipt_outbox
                   SET delivery_status=CASE WHEN attempt_count = 10 THEN 'DEAD' ELSE 'RETRY' END,
                       available_at=CASE WHEN attempt_count = 10 THEN available_at
                           ELSE CURRENT_TIMESTAMP + (:delay * INTERVAL '1 second') END,
                       locked_by=NULL,locked_until=NULL,
                       last_error_code=CASE WHEN attempt_count = 10
                           THEN 'RESULT_UNKNOWN' ELSE :error END,
                       updated_at=CURRENT_TIMESTAMP
                 WHERE tenant_id=:tenant AND outbox_id=:outbox AND locked_by=:worker
                   AND attempt_count=:attempt AND delivery_status='SENDING'
                   AND locked_until > CURRENT_TIMESTAMP
                """, new MapSqlParameterSource().addValue("tenant", tenantId)
                .addValue("outbox", outboxId).addValue("worker", workerId)
                .addValue("attempt", leaseAttempt).addValue("error", safeCode)
                .addValue("delay", retryDelaySeconds));
        });
        if (!Integer.valueOf(1).equals(updated)) throw Hrm013SafeErrorV1.authorityDenied();
    }

    private static void requireLeaseFence(String workerId, int leaseAttempt) {
        if (workerId == null || workerId.isBlank() || workerId.length() > 120
                || leaseAttempt < 1 || leaseAttempt > 10) {
            throw new IllegalArgumentException("current callback lease fence required");
        }
    }

    public record DurationLease(String workerId, long seconds) {
        public DurationLease {
            if (workerId == null || workerId.isBlank() || seconds < 1 || seconds > 300) {
                throw new IllegalArgumentException("bounded lease required");
            }
        }
    }

    private List<StoredAdmission> findForUpdate(long tenantId, UUID event) {
        return jdbc.query("""
                SELECT task_public_id,payload_sha256,request_body_sha256,exact_request_body,
                       exact_receipt_body,receipt_proof,admitted_at
                  FROM hris_hrm013_time_task_dispatch_inbox
                 WHERE tenant_id=:tenant AND dispatch_event_id=:event FOR UPDATE
                """, new MapSqlParameterSource().addValue("tenant", tenantId)
                .addValue("event", event), (rs, row) -> new StoredAdmission(
                        rs.getObject("task_public_id", UUID.class), rs.getString("payload_sha256"),
                        rs.getString("request_body_sha256"), rs.getBytes("exact_request_body"),
                        rs.getBytes("exact_receipt_body"), rs.getString("receipt_proof"),
                        rs.getTimestamp("admitted_at").toInstant()));
    }

    private boolean hasDigestConflict(AdmissionCandidate value) {
        List<Integer> rows = jdbc.query("""
                SELECT 1
                  FROM hris_hrm013_time_task_dispatch_inbox
                 WHERE tenant_id=:tenant
                   AND (request_body_sha256=:requestDigest
                        OR (task_public_id=:taskId AND task_version=:taskVersion))
                 FOR UPDATE
                """, new MapSqlParameterSource().addValue("tenant", value.tenantId())
                .addValue("requestDigest", value.requestBodySha256())
                .addValue("taskId", value.dispatch().taskPublicId())
                .addValue("taskVersion", value.dispatch().taskVersion()),
                (rs, row) -> 1);
        return !rows.isEmpty();
    }

    private List<StoredDispatch> findDispatchForUpdate(long tenantId, UUID event) {
        return jdbc.query("""
                SELECT dispatch_event_id,case_public_id,task_public_id,task_owner_revision,status,
                       payload_sha256,dispatched_at,grant_use_mode,drain_claims_json
                  FROM hris_hrm013_time_task_dispatch_inbox
                 WHERE tenant_id=:tenant AND dispatch_event_id=:event FOR UPDATE
                """, new MapSqlParameterSource().addValue("tenant", tenantId)
                .addValue("event", event), (rs, row) -> new StoredDispatch(
                        rs.getObject("dispatch_event_id", UUID.class),
                        rs.getObject("case_public_id", UUID.class),
                        rs.getObject("task_public_id", UUID.class),
                        rs.getLong("task_owner_revision"), rs.getString("status"),
                        rs.getString("payload_sha256"),
                        rs.getTimestamp("dispatched_at").toInstant(),
                        rs.getString("grant_use_mode"), rs.getString("drain_claims_json")));
    }

    private List<StoredTerminal> findTerminalForUpdate(long tenantId, UUID event) {
        return jdbc.query("""
                SELECT callback_event_id,owner_revision,status,
                       external_receipt_public_id,occurred_at,correlation_id,
                       exact_callback_body,callback_payload_sha256
                  FROM hris_hrm013_time_task_receipt_outbox
                 WHERE tenant_id=:tenant AND dispatch_event_id=:event FOR UPDATE
                """, new MapSqlParameterSource().addValue("tenant", tenantId)
                .addValue("event", event), (rs, row) -> new StoredTerminal(
                        rs.getObject("callback_event_id", UUID.class),
                        rs.getLong("owner_revision"),
                        rs.getString("status"),
                        rs.getObject("external_receipt_public_id", UUID.class),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getObject("correlation_id", UUID.class),
                        rs.getBytes("exact_callback_body"),
                        rs.getString("callback_payload_sha256")));
    }

    private static MapSqlParameterSource parameters(AdmissionCandidate value) {
        var dispatch = value.dispatch();
        return new MapSqlParameterSource().addValue("tenant", value.tenantId())
                .addValue("event", dispatch.dispatchEventId())
                .addValue("caseId", dispatch.casePublicId()).addValue("taskId", dispatch.taskPublicId())
                .addValue("ownerRevision", dispatch.taskOwnerRevision())
                .addValue("taskVersion", dispatch.taskVersion()).addValue("workerId", dispatch.workerPublicId())
                .addValue("relationshipId", dispatch.relationshipPublicId())
                .addValue("separationType", dispatch.separationType())
                .addValue("effectiveDate", dispatch.effectiveDate())
                .addValue("lastWorkingDate", dispatch.lastWorkingDate())
                .addValue("dispatchedAt", Timestamp.from(dispatch.dispatchedAt()))
                .addValue("payload", dispatch.payloadSha256())
                .addValue("requestDigest", value.requestBodySha256())
                .addValue("proofDigest", value.proofSha256())
                .addValue("requestBody", value.exactRequestBody())
                .addValue("receiptBody", value.exactReceiptBody())
                .addValue("receiptProof", value.receiptProof())
                .addValue("tenantGrantRevision", value.tenantGrantRevision())
                .addValue("authorizationRevision", value.authorizationRevision())
                .addValue("grantUseMode", value.grantUseMode())
                .addValue("drainClaims", value.drainClaimsJson())
                .addValue("correlation", dispatch.correlationId())
                .addValue("admittedAt", Timestamp.from(value.admittedAt()));
    }

    private static CallbackRecord callback(ResultSet rs, int row) throws SQLException {
        return new CallbackRecord(rs.getLong("tenant_id"),
                rs.getObject("outbox_id", UUID.class), rs.getObject("dispatch_event_id", UUID.class),
                rs.getObject("callback_event_id", UUID.class), rs.getObject("case_public_id", UUID.class),
                rs.getObject("task_public_id", UUID.class), rs.getLong("owner_revision"),
                rs.getString("status"), rs.getObject("external_receipt_public_id", UUID.class),
                rs.getString("originating_payload_sha256"),
                rs.getTimestamp("terminal_parent_created_at").toInstant(),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getObject("correlation_id", UUID.class),
                rs.getBytes("exact_callback_body"),
                rs.getString("callback_payload_sha256"), rs.getString("grant_use_mode"),
                rs.getString("drain_claims_json"), rs.getInt("attempt_count"));
    }

    private void quarantineTerminal(TerminalCommand command, StoredTerminal stored,
            TerminalCandidate rejected) {
        requiresNew.executeWithoutResult(status -> {
            setTenant(command.tenantId());
            jdbc.update("""
                    INSERT INTO hris_hrm013_time_callback_quarantine(
                        quarantine_id,tenant_id,callback_event_id,dispatch_event_id,
                        stored_callback_payload_sha256,
                        rejected_callback_payload_sha256,
                        stored_exact_callback_body,rejected_exact_callback_body,
                        correlation_id,reason_code,quarantined_at)
                    VALUES(:id,:tenant,:callback,:dispatch,:storedDigest,
                        :rejectedDigest,:storedBody,:rejectedBody,:correlation,
                        'CALLBACK_IDENTITY_CONFLICT',CURRENT_TIMESTAMP)
                    ON CONFLICT (tenant_id,callback_event_id,
                        rejected_callback_payload_sha256) DO NOTHING
                    """, new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("tenant", command.tenantId())
                    .addValue("callback", stored.callbackEventId())
                    .addValue("dispatch", command.dispatchEventId())
                    .addValue("storedDigest", stored.callbackPayloadSha256())
                    .addValue("rejectedDigest", rejected.callbackPayloadSha256())
                    .addValue("storedBody", stored.exactCallbackBody())
                    .addValue("rejectedBody", rejected.exactCallbackBody())
                    .addValue("correlation", command.correlationId()));
        });
    }

    private void setTenant(long tenantId) {
        String value = jdbc.queryForObject(
                "SELECT set_config('dwp.tenant_id', :tenant, true)",
                new MapSqlParameterSource().addValue("tenant", Long.toString(tenantId)),
                String.class);
        if (!Long.toString(tenantId).equals(value)) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private void requireBefore(Instant deadline) {
        Boolean current = jdbc.queryForObject(
                "SELECT CURRENT_TIMESTAMP < :deadline",
                new MapSqlParameterSource().addValue("deadline", Timestamp.from(deadline)),
                Boolean.class);
        if (!Boolean.TRUE.equals(current)) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    private record StoredAdmission(
            UUID taskPublicId, String payloadSha256, String requestBodySha256,
            byte[] exactRequestBody, byte[] exactReceiptBody, String receiptProof,
            Instant admittedAt) { }
    private record StoredDispatch(
            UUID dispatchEventId, UUID casePublicId, UUID taskPublicId,
            long taskOwnerRevision, String status, String payloadSha256,
            Instant dispatchedAt,
            String grantUseMode, String drainClaimsJson) { }
    private record TerminalCandidate(
            UUID callbackEventId, long ownerRevision, String status,
            UUID externalReceiptPublicId, Instant occurredAt, UUID correlationId,
            byte[] exactCallbackBody, String callbackPayloadSha256) {
        private TerminalCandidate {
            exactCallbackBody = exactCallbackBody.clone();
        }
        @Override public byte[] exactCallbackBody() { return exactCallbackBody.clone(); }
    }

    private record StoredTerminal(
            UUID callbackEventId, long ownerRevision, String status,
            UUID externalReceiptPublicId, Instant occurredAt, UUID correlationId,
            byte[] exactCallbackBody, String callbackPayloadSha256) {
        private StoredTerminal {
            exactCallbackBody = exactCallbackBody.clone();
        }
        @Override public byte[] exactCallbackBody() { return exactCallbackBody.clone(); }
        private boolean matches(TerminalCandidate other) {
            return callbackEventId.equals(other.callbackEventId())
                    && ownerRevision == other.ownerRevision()
                    && status.equals(other.status())
                    && externalReceiptPublicId.equals(other.externalReceiptPublicId())
                    && occurredAt.equals(other.occurredAt())
                    && correlationId.equals(other.correlationId())
                    && callbackPayloadSha256.equals(other.callbackPayloadSha256())
                    && Arrays.equals(exactCallbackBody, other.exactCallbackBody());
        }
    }

    private record TerminalDecision(
            StoredTerminal existing, TerminalCandidate rejected) {
        private static TerminalDecision accepted() {
            return new TerminalDecision(null, null);
        }
        private static TerminalDecision replay() {
            return new TerminalDecision(null, null);
        }
        private static TerminalDecision conflict(
                StoredTerminal existing, TerminalCandidate rejected) {
            return new TerminalDecision(existing, rejected);
        }
    }

    private static void requireDigest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("SHA-256 digest required");
        }
    }
}
