package com.dwp.services.payroll.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable tenant-scoped inbox, proof replay ledger, quarantine and callback outbox. */
@Repository
@ConditionalOnProperty(prefix = "dwp.payroll.hrm013-task-dispatch", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public final class Hrm013PayrollTaskDispatchStore
        implements Hrm013WorkloadProofV1.ReplayStore {
    static final int MAXIMUM_CALLBACK_ATTEMPTS = 10;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TransactionTemplate independent;

    Hrm013PayrollTaskDispatchStore(NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(manager));
        this.independent = new TransactionTemplate(manager);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public boolean reserve(Hrm013WorkloadProofV1.ReplayReservation reservation) {
        Objects.requireNonNull(reservation);
        return Boolean.TRUE.equals(transactions.execute(status -> {
            bindTenant(reservation.tenantId());
            jdbc.queryForObject("""
                    SELECT pay_v56_purge_expired_replays(:tenantId)
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", reservation.tenantId()), Long.class);
            return jdbc.update("""
                    INSERT INTO pay_hrm013_proof_replay (
                        tenant_id, binding_id, caller_workload_id,
                        proof_id_sha256, request_nonce_sha256,
                        retain_until, reserved_at)
                    VALUES (:tenantId, :bindingId, :caller, :proofId, :nonce,
                            :retainUntil, :reservedAt)
                    ON CONFLICT DO NOTHING
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", reservation.tenantId())
                    .addValue("bindingId", reservation.bindingId())
                    .addValue("caller", reservation.callerWorkloadId())
                    .addValue("proofId", reservation.proofIdSha256())
                    .addValue("nonce", reservation.requestNonceSha256())
                    .addValue("retainUntil", Timestamp.from(reservation.retainUntil()))
                    .addValue("reservedAt", Timestamp.from(Instant.now()))) == 1;
        }));
    }

    byte[] admit(Admission admission) {
        Objects.requireNonNull(admission);
        Decision decision = transactions.execute(status -> {
            bindTenant(admission.tenantId());
            jdbc.queryForObject("""
                    SELECT 1 FROM (
                        SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))
                    ) locked
                    """, new MapSqlParameterSource("lockKey",
                    admission.tenantId() + ":HRM013:PAYROLL:"
                            + admission.dispatch().dispatchEventId()), Integer.class);
            Optional<Existing> existing = existing(admission.tenantId(),
                    admission.dispatch().dispatchEventId());
            if (existing.isPresent()) {
                Existing value = existing.orElseThrow();
                if (value.requestBodySha256().equals(admission.requestBodySha256())
                        && value.commandDigest().equals(admission.commandDigest())) {
                    return Decision.replay(value.exactReceipt());
                }
                return Decision.conflict(value.requestBodySha256());
            }
            var body = admission.dispatch();
            var grant = admission.authority().grant();
            var claims = admission.proof().claims();
            var drain = claims.drainClaims();
            jdbc.update("""
                    INSERT INTO pay_hrm013_dispatch_inbox (
                        tenant_id, dispatch_event_id, case_public_id, task_public_id,
                        task_type, task_owner_revision, task_version, dispatched_at,
                        correlation_id,
                        worker_public_id, relationship_public_id, separation_type,
                        effective_date, last_working_date, payload_sha256,
                        request_body_sha256, command_digest, exact_request_bytes,
                        proof_sha256, grant_set_id, grant_revision,
                        tenant_grant_revision, authorization_revision, grant_use_mode,
                        drain_root_work_item_id, drain_root_created_at,
                        drain_root_payload_sha256, admitted_at,
                        exact_admission_receipt_bytes, admission_receipt_sha256)
                    VALUES (
                        :tenantId, :dispatchEventId, :caseId, :taskId,
                        :taskType, :ownerRevision, :taskVersion, :dispatchedAt,
                        :correlationId,
                        :workerId, :relationshipId, :separationType,
                        :effectiveDate, :lastWorkingDate, :payloadDigest,
                        :requestDigest, :commandDigest, :requestBytes,
                        :proofDigest, :grantSetId, :grantRevision,
                        :tenantGrantRevision, :authorizationRevision, :grantUseMode,
                        :drainRootId, :drainRootCreatedAt, :drainRootDigest,
                        :admittedAt, :receiptBytes, :receiptDigest)
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", admission.tenantId())
                    .addValue("dispatchEventId", body.dispatchEventId())
                    .addValue("caseId", body.casePublicId())
                    .addValue("taskId", body.taskPublicId())
                    .addValue("taskType", body.taskType())
                    .addValue("ownerRevision", body.taskOwnerRevision())
                    .addValue("taskVersion", body.taskVersion())
                    .addValue("dispatchedAt", Timestamp.from(body.dispatchedAt()))
                    .addValue("correlationId", body.correlationId())
                    .addValue("workerId", body.workerPublicId())
                    .addValue("relationshipId", body.relationshipPublicId())
                    .addValue("separationType", body.separationType())
                    .addValue("effectiveDate", Date.valueOf(body.effectiveDate()))
                    .addValue("lastWorkingDate", Date.valueOf(body.lastWorkingDate()))
                    .addValue("payloadDigest", body.payloadSha256())
                    .addValue("requestDigest", admission.requestBodySha256())
                    .addValue("commandDigest", admission.commandDigest())
                    .addValue("requestBytes", admission.exactRequestBytes())
                    .addValue("proofDigest", admission.proof().proofSha256())
                    .addValue("grantSetId", grant.grantSetId())
                    .addValue("grantRevision", grant.grantRevision())
                    .addValue("tenantGrantRevision", claims.tenantGrantRevision())
                    .addValue("authorizationRevision", claims.authorizationRevision())
                    .addValue("grantUseMode", claims.grantUseMode().name())
                    .addValue("drainRootId", drain == null ? null
                            : drain.drainRootWorkItemId())
                    .addValue("drainRootCreatedAt", drain == null ? null
                            : Timestamp.from(drain.drainRootCreatedAt()))
                    .addValue("drainRootDigest", drain == null ? null
                            : drain.drainRootPayloadSha256())
                    .addValue("admittedAt", Timestamp.from(admission.admittedAt()))
                    .addValue("receiptBytes", admission.exactReceiptBytes())
                    .addValue("receiptDigest", admission.receiptSha256()));
            return Decision.replay(admission.exactReceiptBytes());
        });
        if (decision == null) throw Hrm013SafeErrorV1.failure(
                Hrm013SafeErrorV1.ErrorCode.INTERNAL_ERROR);
        if (decision.conflictingStoredDigest() != null) {
            quarantine(admission, decision.conflictingStoredDigest());
            throw Hrm013SafeErrorV1.digestConflict();
        }
        return decision.exactReceipt().clone();
    }

    void recordOutcome(Outcome outcome) {
        Objects.requireNonNull(outcome);
        OutcomeDecision decision = transactions.execute(status -> {
            bindTenant(outcome.tenantId());
            jdbc.queryForObject("""
                    SELECT 1 FROM (
                        SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))
                    ) locked
                    """, new MapSqlParameterSource("lockKey",
                    outcome.tenantId() + ":HRM013:PAYROLL:OUTCOME:"
                            + outcome.callbackEventId()), Integer.class);
            Optional<ExistingOutcome> prior = existingOutcome(
                    outcome.tenantId(), outcome.callbackEventId());
            if (prior.isPresent()) {
                ExistingOutcome existing = prior.orElseThrow();
                return existing.matches(outcome)
                        ? OutcomeDecision.replay()
                        : OutcomeDecision.conflict(existing);
            }
            int updated = jdbc.update("""
                    UPDATE pay_hrm013_dispatch_inbox
                       SET state = :state, completed_at = :occurredAt
                     WHERE tenant_id = :tenantId
                       AND dispatch_event_id = :dispatchEventId
                       AND state = 'ADMITTED'
                    """, new MapSqlParameterSource()
                    .addValue("state", outcome.taskStatus())
                    .addValue("occurredAt", Timestamp.from(outcome.occurredAt()))
                    .addValue("tenantId", outcome.tenantId())
                    .addValue("dispatchEventId", outcome.dispatchEventId()));
            if (updated == 0) {
                throw Hrm013SafeErrorV1.invalidState();
            }
            int inserted = jdbc.update("""
                    INSERT INTO pay_hrm013_receipt_outbox (
                        tenant_id, callback_event_id, dispatch_event_id,
                        case_public_id, task_public_id, correlation_id,
                        exact_callback_bytes, callback_payload_sha256,
                        originating_payload_sha256, grant_use_mode,
                        drain_root_work_item_id, drain_root_created_at,
                        drain_root_payload_sha256, available_at, created_at)
                    VALUES (:tenantId, :callbackEventId, :dispatchEventId,
                        :caseId, :taskId, :correlationId, :callbackBytes,
                        :callbackDigest, :originatingDigest, :grantUseMode,
                        :drainRootId, :drainRootCreatedAt, :drainRootDigest,
                        :occurredAt, :occurredAt)
                    ON CONFLICT (tenant_id, callback_event_id) DO NOTHING
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", outcome.tenantId())
                    .addValue("callbackEventId", outcome.callbackEventId())
                    .addValue("dispatchEventId", outcome.dispatchEventId())
                    .addValue("caseId", outcome.casePublicId())
                    .addValue("taskId", outcome.taskPublicId())
                    .addValue("correlationId", outcome.correlationId())
                    .addValue("callbackBytes", outcome.exactCallbackBytes())
                    .addValue("callbackDigest", outcome.callbackPayloadSha256())
                    .addValue("originatingDigest", outcome.originatingPayloadSha256())
                    .addValue("grantUseMode", outcome.grantUseMode().name())
                    .addValue("drainRootId", outcome.drainRootWorkItemId())
                    .addValue("drainRootCreatedAt", outcome.drainRootCreatedAt() == null
                            ? null : Timestamp.from(outcome.drainRootCreatedAt()))
                    .addValue("drainRootDigest", outcome.drainRootPayloadSha256())
                    .addValue("occurredAt", Timestamp.from(outcome.occurredAt())));
            if (inserted == 1) return OutcomeDecision.accepted();
            ExistingOutcome existing = existingOutcome(
                    outcome.tenantId(), outcome.callbackEventId())
                    .orElseThrow(() -> Hrm013SafeErrorV1.failure(
                            Hrm013SafeErrorV1.ErrorCode.INTERNAL_ERROR));
            if (existing.matches(outcome)) return OutcomeDecision.replay();
            status.setRollbackOnly();
            return OutcomeDecision.conflict(existing);
        });
        if (decision == null) throw Hrm013SafeErrorV1.failure(
                Hrm013SafeErrorV1.ErrorCode.INTERNAL_ERROR);
        if (decision.conflict() != null) {
            quarantineOutcome(outcome, decision.conflict());
            throw Hrm013SafeErrorV1.digestConflict();
        }
    }

    Optional<DispatchRecord> dispatch(long tenantId, UUID dispatchEventId) {
        return transactions.execute(status -> {
            bindTenant(tenantId);
            return jdbc.query("""
                    SELECT tenant_id, dispatch_event_id, case_public_id,
                           task_public_id, task_owner_revision, task_version,
                           dispatched_at, correlation_id, payload_sha256,
                           exact_request_bytes,
                           tenant_grant_revision, authorization_revision,
                           grant_use_mode, drain_root_work_item_id,
                           drain_root_created_at, drain_root_payload_sha256
                      FROM pay_hrm013_dispatch_inbox
                     WHERE tenant_id = :tenantId
                       AND dispatch_event_id = :dispatchEventId
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("dispatchEventId", dispatchEventId), (rs, row) ->
                    new DispatchRecord(rs.getLong("tenant_id"),
                            rs.getObject("dispatch_event_id", UUID.class),
                            rs.getObject("case_public_id", UUID.class),
                            rs.getObject("task_public_id", UUID.class),
                            rs.getLong("task_owner_revision"),
                            rs.getLong("task_version"),
                            rs.getObject("dispatched_at", OffsetDateTime.class).toInstant(),
                            rs.getObject("correlation_id", UUID.class),
                            rs.getString("payload_sha256"),
                            rs.getBytes("exact_request_bytes"),
                            rs.getLong("tenant_grant_revision"),
                            rs.getLong("authorization_revision"),
                            Hrm013WorkloadProofV1.GrantUseMode.valueOf(
                                    rs.getString("grant_use_mode")),
                            rs.getObject("drain_root_work_item_id", UUID.class),
                            optionalInstant(rs.getObject("drain_root_created_at",
                                    OffsetDateTime.class)),
                            rs.getString("drain_root_payload_sha256")))
                    .stream().findFirst();
        });
    }

    Optional<CallbackClaim> claimNext(long tenantId, String worker, Instant now,
            Duration lease) {
        Objects.requireNonNull(now);
        Objects.requireNonNull(lease);
        if (worker == null || worker.isBlank() || worker.length() > 128
                || lease.compareTo(Duration.ofSeconds(1)) < 0
                || lease.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Callback claim bounds are invalid.");
        }
        return transactions.execute(status -> {
            bindTenant(tenantId);
            return jdbc.query("""
                    WITH exhausted AS (
                        UPDATE pay_hrm013_receipt_outbox
                           SET status = 'TERMINAL', lease_owner = NULL,
                               lease_until = NULL,
                               last_failure_code = 'RESULT_UNKNOWN'
                         WHERE tenant_id = :tenantId
                           AND status = 'DELIVERING'
                           AND attempt_count = 10
                           AND lease_until <= CURRENT_TIMESTAMP
                        RETURNING callback_event_id),
                    candidate AS (
                        SELECT outbox.callback_event_id,
                               inbox.task_type, inbox.state AS task_status,
                               inbox.task_owner_revision,
                               inbox.completed_at AS occurred_at,
                               inbox.dispatched_at AS terminal_parent_created_at
                          FROM pay_hrm013_receipt_outbox outbox
                          JOIN pay_hrm013_dispatch_inbox inbox
                            ON inbox.tenant_id = outbox.tenant_id
                           AND inbox.dispatch_event_id = outbox.dispatch_event_id
                         WHERE outbox.tenant_id = :tenantId
                           AND outbox.attempt_count < 10
                           AND outbox.available_at <= CURRENT_TIMESTAMP
                           AND (outbox.status IN ('PENDING', 'RETRY')
                                OR (outbox.status = 'DELIVERING'
                                    AND outbox.lease_until <= CURRENT_TIMESTAMP))
                         ORDER BY outbox.created_at, outbox.callback_event_id
                         FOR UPDATE SKIP LOCKED LIMIT 1)
                    UPDATE pay_hrm013_receipt_outbox outbox
                       SET status = 'DELIVERING', attempt_count = attempt_count + 1,
                           lease_owner = :worker,
                           lease_until = CURRENT_TIMESTAMP
                               + (:leaseSeconds * INTERVAL '1 second')
                      FROM candidate
                     WHERE outbox.tenant_id = :tenantId
                       AND outbox.callback_event_id = candidate.callback_event_id
                    RETURNING outbox.tenant_id, outbox.callback_event_id,
                              outbox.dispatch_event_id, outbox.case_public_id,
                              outbox.task_public_id, outbox.correlation_id,
                              outbox.exact_callback_bytes,
                              outbox.callback_payload_sha256,
                              outbox.originating_payload_sha256,
                              outbox.grant_use_mode,
                              outbox.drain_root_work_item_id,
                              outbox.drain_root_created_at,
                              outbox.drain_root_payload_sha256,
                              outbox.lease_owner,
                              outbox.attempt_count,
                              candidate.task_type, candidate.task_status,
                              candidate.task_owner_revision,
                              candidate.occurred_at,
                              candidate.terminal_parent_created_at
                    """, new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("worker", worker)
                    .addValue("leaseSeconds", lease.toSeconds()),
                    (rs, row) -> new CallbackClaim(
                            rs.getLong("tenant_id"),
                            rs.getObject("callback_event_id", UUID.class),
                            rs.getObject("dispatch_event_id", UUID.class),
                            rs.getObject("case_public_id", UUID.class),
                            rs.getObject("task_public_id", UUID.class),
                            rs.getObject("correlation_id", UUID.class),
                            rs.getBytes("exact_callback_bytes"),
                            rs.getString("callback_payload_sha256"),
                            rs.getString("originating_payload_sha256"),
                            Hrm013WorkloadProofV1.GrantUseMode.valueOf(
                                    rs.getString("grant_use_mode")),
                            rs.getObject("drain_root_work_item_id", UUID.class),
                            optionalInstant(rs.getObject("drain_root_created_at",
                                    OffsetDateTime.class)),
                            rs.getString("drain_root_payload_sha256"),
                            rs.getString("lease_owner"),
                            rs.getInt("attempt_count"),
                            rs.getString("task_type"),
                            rs.getString("task_status"),
                            rs.getLong("task_owner_revision"),
                            rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                            rs.getObject("terminal_parent_created_at",
                                    OffsetDateTime.class).toInstant()))
                    .stream().findFirst();
        });
    }

    void delivered(CallbackClaim claim) {
        transition(claim, "DELIVERED", 0, null);
    }

    void retry(CallbackClaim claim, long retryDelaySeconds, String safeFailureCode,
            boolean terminal) {
        Objects.requireNonNull(claim);
        if (retryDelaySeconds < 0 || retryDelaySeconds > 300
                || safeFailureCode == null || safeFailureCode.isBlank()
                || safeFailureCode.length() > 64
                || terminal != (claim.attemptCount() == MAXIMUM_CALLBACK_ATTEMPTS)
                || terminal != "RESULT_UNKNOWN".equals(safeFailureCode)) {
            throw new IllegalArgumentException("Callback retry bounds are invalid.");
        }
        transition(claim, terminal ? "TERMINAL" : "RETRY",
                retryDelaySeconds, safeFailureCode);
    }

    private void transition(CallbackClaim claim, String state,
            long retryDelaySeconds, String failure) {
        transactions.executeWithoutResult(status -> {
            bindTenant(claim.tenantId());
            int count = jdbc.update("""
                    UPDATE pay_hrm013_receipt_outbox
                       SET status = :state,
                           delivered_at = CASE WHEN :state = 'DELIVERED'
                               THEN CURRENT_TIMESTAMP ELSE NULL END,
                           available_at = CASE WHEN :state = 'RETRY'
                               THEN CURRENT_TIMESTAMP
                                   + (:retryDelaySeconds * INTERVAL '1 second')
                               ELSE available_at END,
                           lease_owner = NULL, lease_until = NULL,
                           last_failure_code = :failure
                     WHERE tenant_id = :tenantId
                       AND callback_event_id = :callbackEventId
                       AND status = 'DELIVERING'
                       AND lease_owner = :leaseOwner
                       AND attempt_count = :attemptCount
                       AND lease_until > CURRENT_TIMESTAMP
                    """, new MapSqlParameterSource()
                    .addValue("state", state)
                    .addValue("retryDelaySeconds", retryDelaySeconds)
                    .addValue("failure", failure).addValue("tenantId", claim.tenantId())
                    .addValue("callbackEventId", claim.callbackEventId())
                    .addValue("leaseOwner", claim.leaseOwner())
                    .addValue("attemptCount", claim.attemptCount()));
            if (count != 1) throw Hrm013SafeErrorV1.invalidState();
        });
    }

    private Optional<Existing> existing(long tenantId, UUID dispatchEventId) {
        List<Existing> rows = jdbc.query("""
                SELECT request_body_sha256, command_digest,
                       exact_admission_receipt_bytes, admission_receipt_sha256
                  FROM pay_hrm013_dispatch_inbox
                 WHERE tenant_id = :tenantId AND dispatch_event_id = :dispatchEventId
                 FOR UPDATE
                """, new MapSqlParameterSource().addValue("tenantId", tenantId)
                .addValue("dispatchEventId", dispatchEventId), (rs, row) ->
                new Existing(rs.getString("request_body_sha256"),
                        rs.getString("command_digest"),
                        rs.getBytes("exact_admission_receipt_bytes"),
                        rs.getString("admission_receipt_sha256")));
        return rows.stream().findFirst();
    }

    private void quarantine(Admission admission, String storedDigest) {
        independent.executeWithoutResult(status -> {
            bindTenant(admission.tenantId());
            jdbc.update("""
                    INSERT INTO pay_hrm013_dispatch_quarantine (
                        quarantine_id, tenant_id, dispatch_event_id,
                        stored_request_body_sha256, rejected_request_body_sha256,
                        rejected_exact_request_bytes, correlation_id, reason,
                        quarantined_at)
                    VALUES (:id, :tenantId, :dispatchEventId, :storedDigest,
                        :rejectedDigest, :requestBytes, :correlationId,
                        'DISPATCH_DIGEST_CONFLICT', :now)
                    """, new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("tenantId", admission.tenantId())
                    .addValue("dispatchEventId", admission.dispatch().dispatchEventId())
                    .addValue("storedDigest", storedDigest)
                    .addValue("rejectedDigest", admission.requestBodySha256())
                    .addValue("requestBytes", admission.exactRequestBytes())
                    .addValue("correlationId", admission.dispatch().correlationId())
                    .addValue("now", Timestamp.from(Instant.now())));
        });
    }

    private Optional<ExistingOutcome> existingOutcome(
            long tenantId, UUID callbackEventId) {
        return jdbc.query("""
                SELECT outbox.dispatch_event_id, outbox.case_public_id,
                       outbox.task_public_id, outbox.correlation_id,
                       outbox.exact_callback_bytes,
                       outbox.callback_payload_sha256,
                       outbox.originating_payload_sha256,
                       outbox.grant_use_mode, outbox.drain_root_work_item_id,
                       outbox.drain_root_created_at,
                       outbox.drain_root_payload_sha256, outbox.created_at,
                       inbox.state AS dispatch_state,
                       inbox.completed_at AS dispatch_completed_at
                  FROM pay_hrm013_receipt_outbox outbox
                  JOIN pay_hrm013_dispatch_inbox inbox
                    ON inbox.tenant_id = outbox.tenant_id
                   AND inbox.dispatch_event_id = outbox.dispatch_event_id
                 WHERE outbox.tenant_id = :tenantId
                   AND outbox.callback_event_id = :callbackEventId
                 FOR UPDATE OF outbox
                """, new MapSqlParameterSource().addValue("tenantId", tenantId)
                .addValue("callbackEventId", callbackEventId), (rs, row) ->
                new ExistingOutcome(
                        rs.getObject("dispatch_event_id", UUID.class),
                        rs.getObject("case_public_id", UUID.class),
                        rs.getObject("task_public_id", UUID.class),
                        rs.getObject("correlation_id", UUID.class),
                        rs.getBytes("exact_callback_bytes"),
                        rs.getString("callback_payload_sha256"),
                        rs.getString("originating_payload_sha256"),
                        Hrm013WorkloadProofV1.GrantUseMode.valueOf(
                                rs.getString("grant_use_mode")),
                        rs.getObject("drain_root_work_item_id", UUID.class),
                        optionalInstant(rs.getObject("drain_root_created_at",
                                OffsetDateTime.class)),
                        rs.getString("drain_root_payload_sha256"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getString("dispatch_state"),
                        rs.getObject("dispatch_completed_at",
                                OffsetDateTime.class).toInstant()))
                .stream().findFirst();
    }

    private void quarantineOutcome(Outcome outcome, ExistingOutcome existing) {
        independent.executeWithoutResult(status -> {
            bindTenant(outcome.tenantId());
            jdbc.update("""
                    INSERT INTO pay_hrm013_callback_quarantine (
                        quarantine_id, tenant_id, callback_event_id,
                        stored_dispatch_event_id, rejected_dispatch_event_id,
                        stored_callback_payload_sha256,
                        rejected_callback_payload_sha256,
                        stored_exact_callback_bytes,
                        rejected_exact_callback_bytes, correlation_id,
                        reason, quarantined_at)
                    VALUES (:id, :tenantId, :callbackEventId,
                        :storedDispatchEventId, :rejectedDispatchEventId,
                        :storedDigest, :rejectedDigest,
                        :storedBytes, :rejectedBytes, :correlationId,
                        'CALLBACK_IDENTITY_CONFLICT', :now)
                    """, new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("tenantId", outcome.tenantId())
                    .addValue("callbackEventId", outcome.callbackEventId())
                    .addValue("storedDispatchEventId", existing.dispatchEventId())
                    .addValue("rejectedDispatchEventId", outcome.dispatchEventId())
                    .addValue("storedDigest", existing.callbackPayloadSha256())
                    .addValue("rejectedDigest", outcome.callbackPayloadSha256())
                    .addValue("storedBytes", existing.exactCallbackBytes())
                    .addValue("rejectedBytes", outcome.exactCallbackBytes())
                    .addValue("correlationId", outcome.correlationId())
                    .addValue("now", Timestamp.from(Instant.now())));
        });
    }

    private void bindTenant(long tenantId) {
        if (tenantId < 1) throw Hrm013SafeErrorV1.authorityDenied();
        jdbc.queryForObject("SELECT set_config('dwp.payroll_tenant_id', :tenantId, true)",
                new MapSqlParameterSource("tenantId", Long.toString(tenantId)), String.class);
    }

    private static Instant optionalInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    record Admission(long tenantId,
            Hrm013TaskDispatchV1.TimeAndPayrollTaskDispatch dispatch,
            byte[] exactRequestBytes, String requestBodySha256, String commandDigest,
            Hrm013WorkloadProofV1.VerifiedProof proof,
            AuthHrm013ServiceAuthorityClient.Authority authority,
            Instant admittedAt, byte[] exactReceiptBytes, String receiptSha256) {
        Admission {
            exactRequestBytes = exactRequestBytes.clone();
            exactReceiptBytes = exactReceiptBytes.clone();
        }
        @Override public byte[] exactRequestBytes() { return exactRequestBytes.clone(); }
        @Override public byte[] exactReceiptBytes() { return exactReceiptBytes.clone(); }
    }

    record DispatchRecord(long tenantId, UUID dispatchEventId, UUID casePublicId,
            UUID taskPublicId, long ownerRevision, long taskVersion,
            Instant dispatchedAt, UUID correlationId, String payloadSha256,
            byte[] exactRequestBytes,
            long tenantGrantRevision, long authorizationRevision,
            Hrm013WorkloadProofV1.GrantUseMode grantUseMode,
            UUID drainRootWorkItemId, Instant drainRootCreatedAt,
            String drainRootPayloadSha256) {
        DispatchRecord { exactRequestBytes = exactRequestBytes.clone(); }
        @Override public byte[] exactRequestBytes() { return exactRequestBytes.clone(); }
    }

    record Outcome(long tenantId, UUID dispatchEventId, UUID callbackEventId,
            UUID casePublicId, UUID taskPublicId, UUID correlationId,
            String taskStatus, Instant occurredAt, byte[] exactCallbackBytes,
            String callbackPayloadSha256, String originatingPayloadSha256,
            Hrm013WorkloadProofV1.GrantUseMode grantUseMode,
            UUID drainRootWorkItemId, Instant drainRootCreatedAt,
            String drainRootPayloadSha256) {
        Outcome { exactCallbackBytes = exactCallbackBytes.clone(); }
        @Override public byte[] exactCallbackBytes() { return exactCallbackBytes.clone(); }
    }

    record CallbackClaim(long tenantId, UUID callbackEventId, UUID dispatchEventId,
            UUID casePublicId, UUID taskPublicId, UUID correlationId,
            byte[] exactCallbackBytes, String callbackPayloadSha256,
            String originatingPayloadSha256,
            Hrm013WorkloadProofV1.GrantUseMode grantUseMode,
            UUID drainRootWorkItemId, Instant drainRootCreatedAt,
            String drainRootPayloadSha256, String leaseOwner, int attemptCount,
            String taskType, String taskStatus, long ownerRevision,
            Instant occurredAt, Instant terminalParentCreatedAt) {
        CallbackClaim {
            exactCallbackBytes = exactCallbackBytes.clone();
            if (leaseOwner == null || leaseOwner.isBlank()
                    || attemptCount < 1
                    || attemptCount > MAXIMUM_CALLBACK_ATTEMPTS
                    || taskType == null || taskType.isBlank()
                    || (!"COMPLETED".equals(taskStatus) && !"FAILED".equals(taskStatus))
                    || ownerRevision < 1 || occurredAt == null
                    || terminalParentCreatedAt == null) {
                throw new IllegalArgumentException("Callback lease is invalid.");
            }
        }
        @Override public byte[] exactCallbackBytes() { return exactCallbackBytes.clone(); }
    }

    private record Existing(String requestBodySha256, String commandDigest,
            byte[] exactReceipt, String receiptSha256) { }
    private record Decision(byte[] exactReceipt, String conflictingStoredDigest) {
        static Decision replay(byte[] bytes) { return new Decision(bytes.clone(), null); }
        static Decision conflict(String digest) { return new Decision(null, digest); }
    }
    private record ExistingOutcome(UUID dispatchEventId, UUID casePublicId,
            UUID taskPublicId, UUID correlationId, byte[] exactCallbackBytes,
            String callbackPayloadSha256, String originatingPayloadSha256,
            Hrm013WorkloadProofV1.GrantUseMode grantUseMode,
            UUID drainRootWorkItemId, Instant drainRootCreatedAt,
            String drainRootPayloadSha256, Instant createdAt,
            String dispatchState, Instant dispatchCompletedAt) {
        ExistingOutcome { exactCallbackBytes = exactCallbackBytes.clone(); }
        @Override public byte[] exactCallbackBytes() {
            return exactCallbackBytes.clone();
        }
        boolean matches(Outcome outcome) {
            return dispatchEventId.equals(outcome.dispatchEventId())
                    && casePublicId.equals(outcome.casePublicId())
                    && taskPublicId.equals(outcome.taskPublicId())
                    && correlationId.equals(outcome.correlationId())
                    && Arrays.equals(exactCallbackBytes, outcome.exactCallbackBytes())
                    && callbackPayloadSha256.equals(outcome.callbackPayloadSha256())
                    && originatingPayloadSha256.equals(
                            outcome.originatingPayloadSha256())
                    && grantUseMode == outcome.grantUseMode()
                    && Objects.equals(drainRootWorkItemId,
                            outcome.drainRootWorkItemId())
                    && Objects.equals(drainRootCreatedAt,
                            outcome.drainRootCreatedAt())
                    && Objects.equals(drainRootPayloadSha256,
                            outcome.drainRootPayloadSha256())
                    && createdAt.equals(outcome.occurredAt())
                    && dispatchState.equals(outcome.taskStatus())
                    && dispatchCompletedAt.equals(outcome.occurredAt());
        }
    }
    private record OutcomeDecision(ExistingOutcome conflict) {
        static OutcomeDecision accepted() { return new OutcomeDecision(null); }
        static OutcomeDecision replay() { return new OutcomeDecision(null); }
        static OutcomeDecision conflict(ExistingOutcome existing) {
            return new OutcomeDecision(existing);
        }
    }
}
