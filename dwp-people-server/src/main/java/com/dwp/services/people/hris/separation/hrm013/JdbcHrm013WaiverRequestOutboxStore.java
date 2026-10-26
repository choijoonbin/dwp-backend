package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.WaiverRequestFence;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.WaiverDeliveryAcceptance;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.WaiverDeliveryClaim;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.WaiverDeliveryClaimRequest;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.WaiverDeliveryDisposition;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.WaiverDeliveryFailure;
import static com.dwp.services.people.hris.separation.hrm013.Hrm013PersistenceModels.WaiverFinalClaimRecovery;

/** Immutable APR-002 request fence. It performs no network delivery. */
public final class JdbcHrm013WaiverRequestOutboxStore
        implements Hrm013PersistencePorts.WaiverRequestOutboxStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate mandatory;
    private final TransactionTemplate required;

    public JdbcHrm013WaiverRequestOutboxStore(
            NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.mandatory = new TransactionTemplate(manager);
        this.mandatory.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
        this.required = new TransactionTemplate(manager);
        this.required.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Override
    public WaiverRequestFence insert(WaiverRequestFence fence) {
        WaiverRequestFence result = mandatory.execute(status -> {
            Hrm013TenantScope.setLocal(jdbc, fence.tenantId());
            var parameters = parameters(fence);
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey,0))",
                    parameters.addValue("lockKey", "hrm013-waiver\n"
                            + fence.tenantId() + "\n" + fence.approvalRequestId()),
                    row -> null);
            List<WaiverRequestFence> existing = jdbc.query("""
                    SELECT public_id,tenant_id,separation_case_id,separation_task_id,
                           approval_request_id,case_public_id,task_public_id,
                           request_revision,case_maker_public_id,
                           requested_by_actor_public_id,reason_code,
                           task_owner_revision,task_version,exact_request_body,
                           request_body_sha256,approval_request_payload_sha256,
                           command_digest,correlation_id,available_at,created_at
                      FROM ppl_separation_waiver_request_outbox
                     WHERE tenant_id=:tenant AND approval_request_id=:approvalRequest
                     FOR UPDATE
                    """, parameters, (row, number) -> map(row));
            if (!existing.isEmpty()) {
                if (!same(existing.getFirst(), fence)) {
                    throw new IllegalStateException("HRM-013 waiver request identity conflict");
                }
                return existing.getFirst();
            }
            int inserted = jdbc.update("""
                    INSERT INTO ppl_separation_waiver_request_outbox(
                        public_id,tenant_id,separation_case_id,separation_task_id,
                        approval_request_id,case_public_id,task_public_id,
                        request_revision,case_maker_public_id,
                        requested_by_actor_public_id,reason_code,
                        task_owner_revision,task_version,exact_request_body,
                        request_body_sha256,approval_request_payload_sha256,
                        command_digest,correlation_id,available_at,created_at,updated_at)
                    VALUES(:publicId,:tenant,:caseId,:taskId,:approvalRequest,
                        :casePublicId,:taskPublicId,:requestRevision,:maker,:requester,
                        :reason,:ownerRevision,:taskVersion,:body,:bodyDigest,
                        :payloadDigest,:commandDigest,:correlation,:available,:created,:created)
                    """, parameters);
            if (inserted != 1) {
                throw new IllegalStateException("HRM-013 waiver request insert failed");
            }
            return fence;
        });
        if (result == null) throw new IllegalStateException("HRM-013 waiver transaction returned no result");
        return result;
    }

    @Override
    public Optional<WaiverRequestFence> find(long tenantId, UUID approvalRequestId) {
        if (tenantId < 1 || approvalRequestId == null) {
            throw new IllegalArgumentException("tenantId and approvalRequestId are required");
        }
        Optional<WaiverRequestFence> result = required.execute(status -> {
            Hrm013TenantScope.setLocal(jdbc, tenantId);
            List<WaiverRequestFence> rows = jdbc.query("""
                    SELECT public_id,tenant_id,separation_case_id,separation_task_id,
                           approval_request_id,case_public_id,task_public_id,
                           request_revision,case_maker_public_id,
                           requested_by_actor_public_id,reason_code,
                           task_owner_revision,task_version,exact_request_body,
                           request_body_sha256,approval_request_payload_sha256,
                           command_digest,correlation_id,available_at,created_at
                      FROM ppl_separation_waiver_request_outbox
                     WHERE tenant_id=:tenant AND approval_request_id=:approvalRequest
                    """, new MapSqlParameterSource()
                    .addValue("tenant", tenantId)
                    .addValue("approvalRequest", approvalRequestId),
                    (row, number) -> map(row));
            return rows.stream().findFirst();
        });
        return result == null ? Optional.empty() : result;
    }

    @Override
    public Optional<WaiverDeliveryClaim> claimNext(WaiverDeliveryClaimRequest request) {
        Optional<WaiverDeliveryClaim> result = required.execute(status -> {
            Hrm013TenantScope.setLocal(jdbc, request.tenantId());
            recoverExpiredFinalClaimInTransaction(
                    request.tenantId(), databaseNow());
            var parameters = new MapSqlParameterSource()
                    .addValue("tenant", request.tenantId())
                    .addValue("leaseToken", request.leaseToken())
                    .addValue("leaseOwner", request.leaseOwner())
                    .addValue("leaseUntil", Timestamp.from(request.leaseUntil()));
            List<WaiverDeliveryClaim> rows = jdbc.query("""
                    WITH candidate AS (
                        SELECT separation_waiver_request_id
                          FROM ppl_separation_waiver_request_outbox
                         WHERE tenant_id=:tenant
                           AND :leaseUntil > pg_catalog.clock_timestamp()
                           AND attempt_count < 10
                           AND ((dispatch_state IN ('PENDING','RETRY')
                                 AND available_at <= pg_catalog.clock_timestamp())
                                OR (dispatch_state='CLAIMED'
                                    AND lease_until <= pg_catalog.clock_timestamp()))
                         ORDER BY available_at,separation_waiver_request_id
                         FOR UPDATE SKIP LOCKED
                         LIMIT 1
                    )
                    UPDATE ppl_separation_waiver_request_outbox AS delivery
                       SET dispatch_state='CLAIMED',
                           attempt_count=delivery.attempt_count+1,
                           lease_token=:leaseToken,
                           lease_owner=:leaseOwner,
                           lease_until=:leaseUntil
                      FROM candidate
                     WHERE delivery.separation_waiver_request_id
                           = candidate.separation_waiver_request_id
                    RETURNING delivery.public_id,delivery.tenant_id,
                              delivery.separation_case_id,
                              delivery.separation_task_id,
                              delivery.approval_request_id,
                              delivery.case_public_id,delivery.task_public_id,
                              delivery.request_revision,
                              delivery.case_maker_public_id,
                              delivery.requested_by_actor_public_id,
                              delivery.reason_code,delivery.task_owner_revision,
                              delivery.task_version,delivery.exact_request_body,
                              delivery.request_body_sha256,
                              delivery.approval_request_payload_sha256,
                              delivery.command_digest,delivery.correlation_id,
                              delivery.available_at,delivery.created_at,
                              delivery.lease_token,delivery.lease_owner,
                              delivery.attempt_count,delivery.lease_until
                    """, parameters, (row, number) -> new WaiverDeliveryClaim(
                    map(row),
                    row.getObject("lease_token", UUID.class),
                    row.getString("lease_owner"),
                    row.getInt("attempt_count"),
                    row.getTimestamp("lease_until").toInstant()));
            return rows.stream().findFirst();
        });
        return result == null ? Optional.empty() : result;
    }

    @Override
    public WaiverDeliveryDisposition accept(WaiverDeliveryAcceptance acceptance) {
        WaiverDeliveryDisposition result = required.execute(status -> {
            Hrm013TenantScope.setLocal(jdbc, acceptance.tenantId());
            List<AcceptanceState> rows = jdbc.query("""
                    SELECT approval_request_id,request_revision,command_digest,
                           correlation_id,dispatch_state,lease_token,
                           exact_response_body,response_body_sha256,accepted_at
                      FROM ppl_separation_waiver_request_outbox
                     WHERE tenant_id=:tenant
                       AND approval_request_id=:approvalRequest
                     FOR UPDATE
                    """, new MapSqlParameterSource()
                    .addValue("tenant", acceptance.tenantId())
                    .addValue("approvalRequest", acceptance.approvalRequestId()),
                    (row, ignored) -> new AcceptanceState(
                            row.getObject("approval_request_id", UUID.class),
                            row.getLong("request_revision"),
                            row.getString("command_digest"),
                            row.getObject("correlation_id", UUID.class),
                            row.getString("dispatch_state"),
                            row.getObject("lease_token", UUID.class),
                            row.getBytes("exact_response_body"),
                            row.getString("response_body_sha256"),
                            row.getTimestamp("accepted_at") == null ? null
                                    : row.getTimestamp("accepted_at").toInstant()));
            if (rows.size() != 1) {
                throw new IllegalStateException("HRM-013 waiver acceptance row missing");
            }
            AcceptanceState current = rows.getFirst();
            var receipt = Hrm013SeparationBridgeV1.decodeWaiverReceipt(
                    acceptance.exactResponseBody());
            if (!receipt.approvalRequestId().equals(current.approvalRequestId())
                    || receipt.requestRevision() != current.requestRevision()
                    || !receipt.requestDigest().equals(current.commandDigest())
                    || !receipt.correlationId().equals(current.correlationId())
                    || !receipt.acceptedAt().equals(acceptance.acceptedAt())) {
                throw new IllegalStateException(
                        "HRM-013 waiver acceptance receipt binding differs");
            }
            if ("ACCEPTED".equals(current.dispatchState())) {
                if (!MessageDigest.isEqual(current.exactResponseBody(),
                        acceptance.exactResponseBody())
                        || !acceptance.responseBodySha256().equals(
                        current.responseBodySha256())
                        || !acceptance.acceptedAt().equals(current.acceptedAt())) {
                    throw new IllegalStateException(
                            "conflicting HRM-013 waiver acceptance replay");
                }
                return WaiverDeliveryDisposition.ACCEPTED;
            }
            if (!"CLAIMED".equals(current.dispatchState())
                    || !acceptance.leaseToken().equals(current.leaseToken())) {
                throw new IllegalStateException("HRM-013 waiver acceptance lease was lost");
            }
            int updated = jdbc.update("""
                    UPDATE ppl_separation_waiver_request_outbox
                       SET dispatch_state='ACCEPTED',lease_token=NULL,
                           lease_owner=NULL,lease_until=NULL,
                           exact_response_body=:response,
                           response_body_sha256=:responseDigest,
                           accepted_at=:acceptedAt,last_failure_code=NULL
                     WHERE tenant_id=:tenant
                       AND approval_request_id=:approvalRequest
                       AND dispatch_state='CLAIMED'
                       AND lease_token=:leaseToken
                    """, new MapSqlParameterSource()
                    .addValue("tenant", acceptance.tenantId())
                    .addValue("approvalRequest", acceptance.approvalRequestId())
                    .addValue("leaseToken", acceptance.leaseToken())
                    .addValue("response", acceptance.exactResponseBody())
                    .addValue("responseDigest", acceptance.responseBodySha256())
                    .addValue("acceptedAt", Timestamp.from(acceptance.acceptedAt())));
            if (updated != 1) {
                throw new IllegalStateException("HRM-013 waiver acceptance lease was lost");
            }
            return WaiverDeliveryDisposition.ACCEPTED;
        });
        if (result == null) {
            throw new IllegalStateException("HRM-013 waiver acceptance returned no result");
        }
        return result;
    }

    private record AcceptanceState(
            UUID approvalRequestId, long requestRevision, String commandDigest,
            UUID correlationId, String dispatchState, UUID leaseToken,
            byte[] exactResponseBody, String responseBodySha256,
            Instant acceptedAt) {
        private AcceptanceState {
            exactResponseBody = exactResponseBody == null
                    ? null : exactResponseBody.clone();
        }
        @Override public byte[] exactResponseBody() {
            return exactResponseBody == null ? null : exactResponseBody.clone();
        }
    }

    @Override
    public WaiverDeliveryDisposition retryOrTerminal(WaiverDeliveryFailure failure) {
        WaiverDeliveryDisposition result = required.execute(status -> {
            Hrm013TenantScope.setLocal(jdbc, failure.tenantId());
            var identity = new MapSqlParameterSource()
                    .addValue("tenant", failure.tenantId())
                    .addValue("approvalRequest", failure.approvalRequestId())
                    .addValue("leaseToken", failure.leaseToken());
            List<Integer> attempts = jdbc.queryForList("""
                    SELECT attempt_count
                      FROM ppl_separation_waiver_request_outbox
                     WHERE tenant_id=:tenant
                       AND approval_request_id=:approvalRequest
                       AND dispatch_state='CLAIMED'
                       AND lease_token=:leaseToken
                     FOR UPDATE
                    """, identity, Integer.class);
            if (attempts.size() != 1) {
                throw new IllegalStateException("HRM-013 waiver failure lease was lost");
            }
            int attempt = attempts.getFirst();
            WaiverDeliveryDisposition disposition = failure.retryable() && attempt < 10
                    ? WaiverDeliveryDisposition.RETRY
                    : failure.remoteCommitAmbiguous()
                            ? WaiverDeliveryDisposition.RESULT_UNKNOWN
                            : WaiverDeliveryDisposition.DEAD;
            int updated = jdbc.update("""
                    UPDATE ppl_separation_waiver_request_outbox
                       SET dispatch_state=:disposition,lease_token=NULL,
                           lease_owner=NULL,lease_until=NULL,
                           available_at=CASE WHEN :disposition='RETRY'
                               THEN CAST(:retryAt AS TIMESTAMPTZ)
                               ELSE available_at END,
                           last_failure_code=:failureCode
                     WHERE tenant_id=:tenant
                       AND approval_request_id=:approvalRequest
                       AND dispatch_state='CLAIMED'
                       AND lease_token=:leaseToken
                       AND attempt_count=:attempt
                    """, identity
                    .addValue("disposition", disposition.name())
                    .addValue("retryAt", failure.retryAt() == null
                            ? null : Timestamp.from(failure.retryAt()))
                    .addValue("failureCode", failure.failureCode())
                    .addValue("attempt", attempt));
            if (updated != 1) {
                throw new IllegalStateException("HRM-013 waiver failure CAS was lost");
            }
            if (disposition != WaiverDeliveryDisposition.RETRY) {
                auditTerminal(failure.tenantId(), failure.approvalRequestId(),
                        disposition, failure.failureCode(), databaseNow());
            }
            return disposition;
        });
        if (result == null) {
            throw new IllegalStateException("HRM-013 waiver failure returned no result");
        }
        return result;
    }

    @Override
    public WaiverDeliveryDisposition resolveExpiredFinalClaim(
            WaiverFinalClaimRecovery recovery) {
        WaiverDeliveryDisposition disposition = recovery.remoteCommitAmbiguous()
                ? WaiverDeliveryDisposition.RESULT_UNKNOWN
                : WaiverDeliveryDisposition.DEAD;
        WaiverDeliveryDisposition result = required.execute(status -> {
            Hrm013TenantScope.setLocal(jdbc, recovery.tenantId());
            int updated = jdbc.update("""
                    UPDATE ppl_separation_waiver_request_outbox
                       SET dispatch_state=:disposition,lease_token=NULL,
                           lease_owner=NULL,lease_until=NULL,
                           last_failure_code=:failureCode
                     WHERE tenant_id=:tenant
                       AND approval_request_id=:approvalRequest
                       AND dispatch_state='CLAIMED'
                       AND attempt_count=10
                       AND lease_token=:leaseToken
                       AND lease_until <= :observedAt
                       AND lease_until <= pg_catalog.clock_timestamp()
                    """, new MapSqlParameterSource()
                    .addValue("disposition", disposition.name())
                    .addValue("failureCode", recovery.failureCode())
                    .addValue("tenant", recovery.tenantId())
                    .addValue("approvalRequest", recovery.approvalRequestId())
                    .addValue("leaseToken", recovery.leaseToken())
                    .addValue("observedAt", Timestamp.from(recovery.observedAt())));
            if (updated != 1) {
                throw new IllegalStateException(
                        "HRM-013 expired final waiver claim recovery was lost");
            }
            auditTerminal(recovery.tenantId(), recovery.approvalRequestId(),
                    disposition, recovery.failureCode(), recovery.observedAt());
            return disposition;
        });
        if (result == null) {
            throw new IllegalStateException(
                    "HRM-013 final waiver claim recovery returned no result");
        }
        return result;
    }

    private void recoverExpiredFinalClaimInTransaction(long tenantId, Instant observedAt) {
        List<UUID> expired = jdbc.queryForList("""
                SELECT approval_request_id
                  FROM ppl_separation_waiver_request_outbox
                 WHERE tenant_id=:tenant AND dispatch_state='CLAIMED'
                   AND attempt_count=10 AND lease_until <= :observed
                   AND lease_until <= pg_catalog.clock_timestamp()
                 ORDER BY lease_until,separation_waiver_request_id
                 FOR UPDATE SKIP LOCKED LIMIT 1
                """, new MapSqlParameterSource().addValue("tenant", tenantId)
                .addValue("observed", Timestamp.from(observedAt)), UUID.class);
        if (expired.isEmpty()) return;
        UUID approvalRequest = expired.getFirst();
        int updated = jdbc.update("""
                UPDATE ppl_separation_waiver_request_outbox
                   SET dispatch_state='RESULT_UNKNOWN',lease_token=NULL,
                       lease_owner=NULL,lease_until=NULL,
                       last_failure_code='FINAL_CLAIM_EXPIRED'
                 WHERE tenant_id=:tenant AND approval_request_id=:approvalRequest
                   AND dispatch_state='CLAIMED' AND attempt_count=10
                   AND lease_until <= :observed
                """, new MapSqlParameterSource().addValue("tenant", tenantId)
                .addValue("approvalRequest", approvalRequest)
                .addValue("observed", Timestamp.from(observedAt)));
        if (updated == 1) {
            auditTerminal(tenantId, approvalRequest,
                    WaiverDeliveryDisposition.RESULT_UNKNOWN,
                    "FINAL_CLAIM_EXPIRED", observedAt);
        }
    }

    private void auditTerminal(
            long tenantId, UUID approvalRequest,
            WaiverDeliveryDisposition disposition, String failureCode,
            Instant occurredAt) {
        jdbc.update("""
                INSERT INTO sys_people_audit_events(
                    audit_event_id,tenant_id,actor_type,actor_id,action,target_type,
                    target_id,outcome,correlation_id,after_snapshot,occurred_at)
                SELECT :audit,:tenant,'SERVICE','dwp-people-server',
                       'HRM013_WAIVER_DELIVERY_TERMINATED','SEPARATION_WAIVER_REQUEST',
                       approval_request_id::text,'FAILED',correlation_id,
                       jsonb_build_object('disposition',:disposition,
                                          'failureCode',:failureCode),:occurred
                  FROM ppl_separation_waiver_request_outbox
                 WHERE tenant_id=:tenant AND approval_request_id=:approvalRequest
                """, new MapSqlParameterSource().addValue("audit", UUID.randomUUID())
                .addValue("tenant", tenantId)
                .addValue("approvalRequest", approvalRequest)
                .addValue("disposition", disposition.name())
                .addValue("failureCode", failureCode)
                .addValue("occurred", Timestamp.from(occurredAt)));
    }

    private Instant databaseNow() {
        Timestamp value = jdbc.queryForObject(
                "SELECT pg_catalog.transaction_timestamp()",
                new MapSqlParameterSource(), Timestamp.class);
        if (value == null) {
            throw new IllegalStateException("HRM-013 database clock is unavailable");
        }
        return value.toInstant();
    }

    private static MapSqlParameterSource parameters(WaiverRequestFence value) {
        return new MapSqlParameterSource()
                .addValue("publicId", value.publicId())
                .addValue("tenant", value.tenantId())
                .addValue("caseId", value.separationCaseId())
                .addValue("taskId", value.separationTaskId())
                .addValue("approvalRequest", value.approvalRequestId())
                .addValue("casePublicId", value.casePublicId())
                .addValue("taskPublicId", value.taskPublicId())
                .addValue("requestRevision", value.requestRevision())
                .addValue("maker", value.caseMakerPublicId())
                .addValue("requester", value.requestedByActorPublicId())
                .addValue("reason", value.reasonCode())
                .addValue("ownerRevision", value.taskOwnerRevision())
                .addValue("taskVersion", value.taskVersion())
                .addValue("body", value.exactRequestBody())
                .addValue("bodyDigest", value.requestBodySha256())
                .addValue("payloadDigest", value.approvalRequestPayloadSha256())
                .addValue("commandDigest", value.commandDigest())
                .addValue("correlation", value.correlationId())
                .addValue("available", Timestamp.from(value.availableAt()))
                .addValue("created", Timestamp.from(value.createdAt()));
    }

    private static WaiverRequestFence map(java.sql.ResultSet row) throws java.sql.SQLException {
        return new WaiverRequestFence(
                row.getObject("public_id", UUID.class),
                row.getLong("tenant_id"),
                row.getLong("separation_case_id"),
                row.getLong("separation_task_id"),
                row.getObject("approval_request_id", UUID.class),
                row.getObject("case_public_id", UUID.class),
                row.getObject("task_public_id", UUID.class),
                row.getLong("request_revision"),
                row.getObject("case_maker_public_id", UUID.class),
                row.getObject("requested_by_actor_public_id", UUID.class),
                row.getString("reason_code"),
                row.getLong("task_owner_revision"),
                row.getLong("task_version"),
                row.getBytes("exact_request_body"),
                row.getString("request_body_sha256"),
                row.getString("approval_request_payload_sha256"),
                row.getString("command_digest"),
                row.getObject("correlation_id", UUID.class),
                row.getTimestamp("available_at").toInstant(),
                row.getTimestamp("created_at").toInstant());
    }

    private static boolean same(WaiverRequestFence left, WaiverRequestFence right) {
        return left.publicId().equals(right.publicId())
                && left.tenantId() == right.tenantId()
                && left.separationCaseId() == right.separationCaseId()
                && left.separationTaskId() == right.separationTaskId()
                && left.approvalRequestId().equals(right.approvalRequestId())
                && left.casePublicId().equals(right.casePublicId())
                && left.taskPublicId().equals(right.taskPublicId())
                && left.requestRevision() == right.requestRevision()
                && left.caseMakerPublicId().equals(right.caseMakerPublicId())
                && left.requestedByActorPublicId().equals(right.requestedByActorPublicId())
                && left.reasonCode().equals(right.reasonCode())
                && left.taskOwnerRevision() == right.taskOwnerRevision()
                && left.taskVersion() == right.taskVersion()
                && MessageDigest.isEqual(left.exactRequestBody(), right.exactRequestBody())
                && left.requestBodySha256().equals(right.requestBodySha256())
                && left.approvalRequestPayloadSha256().equals(
                        right.approvalRequestPayloadSha256())
                && left.commandDigest().equals(right.commandDigest())
                && left.correlationId().equals(right.correlationId());
    }
}
