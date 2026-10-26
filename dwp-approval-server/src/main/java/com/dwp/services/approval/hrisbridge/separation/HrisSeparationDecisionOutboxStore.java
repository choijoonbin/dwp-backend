package com.dwp.services.approval.hrisbridge.separation;

import static com.dwp.services.approval.hrisbridge.separation.HrisSeparationPersistenceModels.*;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Sole HRM-010 terminal sink and bounded delivery-state persistence store. */
public final class HrisSeparationDecisionOutboxStore {
    private final NamedParameterJdbcTemplate jdbc;
    private final HrisSeparationRlsScope scope;
    private final HrisSeparationGrantStore grants;
    private final TransactionTemplate transactions;

    public HrisSeparationDecisionOutboxStore(NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager manager, HrisSeparationGrantStore grants) {
        this.jdbc = jdbc;
        this.scope = new HrisSeparationRlsScope(jdbc);
        this.grants = grants;
        this.transactions = new TransactionTemplate(manager);
    }

    public void append(DecisionWrite value) {
        validate(value);
        transactions.executeWithoutResult(status -> {
            scope.bind(value.tenantId());
            grants.lockTenantInScope(value.tenantId());
            grants.requireCurrentAdmitInScope(value.tenantId(), value.grantId(),
                    value.tenantGrantRevision(), value.authorizationRevision());
            requireFrozenBinding(value);
            StepUpEvidence stepUp = value.stepUp();
            int inserted = jdbc.update("""
                    INSERT INTO apr_hrm013_waiver_decision_outbox(
                        outbox_id,tenant_id,grant_id,tenant_grant_revision,
                        authorization_revision,approval_request_id,case_public_id,
                        task_public_id,callback_event_id,decision,decision_revision,
                        owner_revision,decision_actor_public_id,case_maker_public_id,
                        requested_by_actor_public_id,reason_code,step_up_evidence_id,
                        step_up_evidence_audience,step_up_evidence_purpose,
                        step_up_evidence_nonce_sha256,step_up_evidence_sha256,
                        step_up_verified_at,step_up_consumed_at,decision_occurred_at,
                        callback_path,original_correlation_id,
                        approval_request_payload_sha256,callback_payload_sha256,
                        exact_callback_body,callback_body_sha256,command_digest,
                        delivery_state,attempt_count,available_at,created_at,updated_at)
                    VALUES(:outbox,:tenant,:grant,:tenantRevision,:authorizationRevision,
                        :request,:case,:task,:event,:decision,1,1,:actor,:maker,:requester,
                        :reason,:evidenceId,:evidenceAudience,:evidencePurpose,
                        :nonceDigest,:evidenceDigest,:verifiedAt,:consumedAt,:occurredAt,
                        :path,:correlation,:originDigest,:callbackPayloadDigest,
                        :body,:bodyDigest,:commandDigest,'PENDING',0,:available,:created,:created)
                    """, parameters(value, stepUp));
            if (inserted != 1) throw new IllegalStateException("HRM-013 decision insert failed.");
        });
    }

    public Optional<DeliveryClaim> claim(long tenantId, String workerId,
            Instant leaseUntil) {
        DeliveryClaim claimed = transactions.execute(status -> {
            scope.bind(tenantId);
            jdbc.update("""
                    WITH expired AS (
                        SELECT outbox_id
                          FROM apr_hrm013_waiver_decision_outbox
                         WHERE tenant_id=:tenant AND delivery_state='CLAIMED'
                           AND lease_until<=clock_timestamp() AND attempt_count>=10
                         ORDER BY lease_until,created_at,outbox_id
                         FOR UPDATE SKIP LOCKED LIMIT 1
                    )
                    UPDATE apr_hrm013_waiver_decision_outbox outbox
                       SET delivery_state='RESULT_UNKNOWN',lease_token=NULL,
                           lease_owner=NULL,lease_until=NULL,
                           last_failure_code='LEASE_EXPIRED_AT_LIMIT'
                      FROM expired WHERE outbox.outbox_id=expired.outbox_id
                    """, new MapSqlParameterSource("tenant", tenantId));
            List<DeliveryClaim> rows = jdbc.query("""
                    WITH current_grant AS (
                        SELECT grant_id,tenant_grant_revision,authorization_revision,
                               approval_signing_key_id
                          FROM apr_hrm013_tenant_grants
                         WHERE tenant_id=:tenant
                         ORDER BY grant_revision DESC LIMIT 1
                    ), candidate AS (
                        SELECT outbox.outbox_id,current_grant.approval_signing_key_id
                          FROM apr_hrm013_waiver_decision_outbox outbox
                          JOIN current_grant
                            ON current_grant.grant_id=outbox.grant_id
                           AND current_grant.tenant_grant_revision=outbox.tenant_grant_revision
                           AND current_grant.authorization_revision=outbox.authorization_revision
                         WHERE outbox.tenant_id=:tenant
                           AND outbox.delivery_state IN ('PENDING','RETRY','CLAIMED')
                           AND outbox.available_at<=clock_timestamp()
                           AND (outbox.delivery_state<>'CLAIMED'
                                OR outbox.lease_until<=clock_timestamp())
                           AND outbox.attempt_count<10
                           AND EXISTS (
                               SELECT 1 FROM apr_hrm013_tenant_grants admitted
                                WHERE admitted.tenant_id=:tenant
                                  AND admitted.grant_id=current_grant.grant_id
                                  AND admitted.decision='ADMIT')
                         ORDER BY outbox.available_at,outbox.created_at,outbox.outbox_id
                         FOR UPDATE OF outbox SKIP LOCKED LIMIT 1
                    )
                    UPDATE apr_hrm013_waiver_decision_outbox outbox
                       SET delivery_state='CLAIMED',attempt_count=attempt_count+1,
                           lease_token=gen_random_uuid(),lease_owner=:worker,
                           lease_until=:leaseUntil,last_failure_code=NULL
                      FROM candidate
                     WHERE outbox.outbox_id=candidate.outbox_id
                    RETURNING outbox.outbox_id,outbox.tenant_id,outbox.grant_id,
                              outbox.tenant_grant_revision,outbox.authorization_revision,
                              candidate.approval_signing_key_id,outbox.callback_path,
                              outbox.exact_callback_body,outbox.callback_body_sha256,
                              outbox.command_digest,outbox.original_correlation_id,
                              outbox.lease_token,outbox.lease_owner,outbox.attempt_count
                    """, new MapSqlParameterSource("tenant", tenantId)
                    .addValue("worker", workerId)
                    .addValue("leaseUntil", Timestamp.from(leaseUntil)),
                    (row, number) -> new DeliveryClaim(
                            row.getObject("outbox_id", UUID.class), row.getLong("tenant_id"),
                            row.getObject("grant_id", UUID.class),
                            row.getLong("tenant_grant_revision"),
                            row.getLong("authorization_revision"),
                            row.getString("approval_signing_key_id"),
                            row.getString("callback_path"), row.getBytes("exact_callback_body"),
                            row.getString("callback_body_sha256"),
                            row.getString("command_digest"),
                            row.getObject("original_correlation_id", UUID.class),
                            row.getObject("lease_token", UUID.class),
                            row.getString("lease_owner"),
                            row.getInt("attempt_count")));
            if (rows.size() > 1) throw new IllegalStateException("HRM-013 claim is ambiguous.");
            return rows.isEmpty() ? null : rows.getFirst();
        });
        return Optional.ofNullable(claimed);
    }

    public TenantGrant revalidateForSend(DeliveryClaim claim) {
        TenantGrant result = transactions.execute(status -> {
            scope.bind(claim.tenantId());
            if (!sha256(claim.exactCallbackBody()).equals(claim.callbackBodySha256())) {
                throw new IllegalStateException("HRM-013 callback bytes changed after claim.");
            }
            TenantGrant grant = grants.requireCurrentAdmitInScope(
                    claim.tenantId(), claim.grantId(), claim.tenantGrantRevision(),
                    claim.authorizationRevision());
            if (!grant.approvalSigningKeyId().equals(claim.approvalSigningKeyId())) {
                throw new IllegalStateException("HRM-013 Approval signing authority is stale.");
            }
            Integer lease = jdbc.queryForObject("""
                    SELECT count(*) FROM apr_hrm013_waiver_decision_outbox
                     WHERE tenant_id=:tenant AND outbox_id=:outbox
                       AND delivery_state='CLAIMED' AND lease_token=:lease
                       AND lease_owner=:owner AND lease_until>clock_timestamp()
                       AND attempt_count=:attempt
                       AND grant_id=:grant AND tenant_grant_revision=:tenantRevision
                       AND authorization_revision=:authorizationRevision
                       AND callback_path=:path AND callback_body_sha256=:bodyDigest
                       AND command_digest=:commandDigest
                       AND original_correlation_id=:correlation
                    """, new MapSqlParameterSource("tenant", claim.tenantId())
                    .addValue("outbox", claim.outboxId()).addValue("lease", claim.leaseToken())
                    .addValue("owner", claim.leaseOwner())
                    .addValue("attempt", claim.attemptCount()).addValue("grant", claim.grantId())
                    .addValue("tenantRevision", claim.tenantGrantRevision())
                    .addValue("authorizationRevision", claim.authorizationRevision())
                    .addValue("path", claim.callbackPath())
                    .addValue("bodyDigest", claim.callbackBodySha256())
                    .addValue("commandDigest", claim.commandDigest())
                    .addValue("correlation", claim.originalCorrelationId()), Integer.class);
            if (lease == null || lease != 1) {
                throw new IllegalStateException("HRM-013 delivery lease is no longer current.");
            }
            return grant;
        });
        if (result == null) throw new IllegalStateException("HRM-013 transaction returned no value.");
        return result;
    }

    public void delivered(DeliveryClaim claim, String workerId,
            byte[] exactReceiptBody, Instant deliveredAt) {
        transition(claim, workerId, """
                delivery_state='DELIVERED',lease_token=NULL,lease_owner=NULL,lease_until=NULL,
                exact_receipt_body=:receipt,
                receipt_body_sha256=encode(sha256(:receipt),'hex'),
                delivered_at=:delivered,last_failure_code=NULL
                """, new MapSqlParameterSource().addValue("receipt", exactReceiptBody)
                .addValue("delivered", Timestamp.from(deliveredAt)));
    }

    public void failed(DeliveryClaim claim, String workerId, boolean permanent,
            boolean remoteCommitAmbiguous, String failureCode, Instant retryAt) {
        boolean exhausted = claim.attemptCount() >= MAXIMUM_ATTEMPTS;
        String next = permanent ? "DEAD"
                : exhausted ? (remoteCommitAmbiguous ? "RESULT_UNKNOWN" : "DEAD") : "RETRY";
        transition(claim, workerId, """
                delivery_state=:next,lease_token=NULL,lease_owner=NULL,lease_until=NULL,
                available_at=CASE WHEN :next='RETRY' THEN :retryAt ELSE available_at END,
                last_failure_code=:failure
                """, new MapSqlParameterSource("next", next)
                .addValue("retryAt", Timestamp.from(retryAt))
                .addValue("failure", failureCode));
    }

    private void transition(DeliveryClaim claim, String workerId,
            String assignments, MapSqlParameterSource values) {
        transactions.executeWithoutResult(status -> {
            scope.bind(claim.tenantId());
            int updated = jdbc.update("UPDATE apr_hrm013_waiver_decision_outbox SET "
                    + assignments + " WHERE tenant_id=:tenant AND outbox_id=:outbox"
                    + " AND delivery_state='CLAIMED' AND lease_token=:lease"
                    + " AND lease_owner=:worker", values.addValue("tenant", claim.tenantId())
                    .addValue("outbox", claim.outboxId()).addValue("lease", claim.leaseToken())
                    .addValue("worker", workerId));
            if (updated != 1) throw new IllegalStateException("HRM-013 delivery lease was lost.");
        });
    }

    private void requireFrozenBinding(DecisionWrite value) {
        Integer matches = jdbc.queryForObject("""
                SELECT count(*)
                  FROM apr_hrm013_waiver_request_bindings
                 WHERE tenant_id=:tenant AND approval_request_id=:request
                   AND grant_id=:grant AND tenant_grant_revision=:tenantRevision
                   AND authorization_revision=:authorizationRevision
                   AND case_public_id=:case AND task_public_id=:task
                   AND case_maker_public_id=:maker
                   AND requested_by_actor_public_id=:requester
                   AND reason_code=:reason AND original_correlation_id=:correlation
                   AND approval_request_payload_sha256=:originDigest
                """, parameters(value, value.stepUp()), Integer.class);
        if (matches == null || matches != 1) {
            throw new IllegalStateException("HRM-013 terminal source differs from its binding.");
        }
    }

    private static void validate(DecisionWrite value) {
        var body = Hrm013SeparationBridgeV1.decodeWaiverDecision(
                value.exactCallbackBody());
        Hrm013SeparationBridgeV1.requireBodyBinding(
                Hrm013SeparationBridgeV1.HRM_010, body);
        Hrm013SeparationBridgeV1.requireStepUpEvidenceSha256(
                value.tenantId(), body);
        String exactPath = Hrm013SeparationBridgeV1.HRM_010.path(
                value.casePublicId(), value.taskPublicId());
        String bodyDigest = Hrm013SeparationBridgeV1.requestBodySha256(
                value.exactCallbackBody());
        boolean evidenceMatches = value.stepUp() == null
                ? body.stepUpEvidenceId() == null
                : value.stepUp().evidenceId().equals(body.stepUpEvidenceId())
                    && value.stepUp().audience().equals(body.stepUpEvidenceAudience())
                    && value.stepUp().purpose().equals(body.stepUpEvidencePurpose())
                    && value.stepUp().nonce().equals(body.stepUpEvidenceNonce())
                    && value.stepUp().evidenceSha256().equals(
                            body.stepUpEvidenceDigest())
                    && value.stepUp().verifiedAt().equals(body.stepUpVerifiedAt())
                    && value.stepUp().consumedAt().equals(body.stepUpConsumedAt());
        if (!value.callbackEventId().equals(body.callbackEventId())
                || !value.approvalRequestId().equals(body.approvalRequestId())
                || !value.casePublicId().equals(body.casePublicId())
                || !value.taskPublicId().equals(body.taskPublicId())
                || !value.decision().name().equals(body.decision())
                || !value.decisionActorPublicId().equals(
                        body.decisionActorPublicId())
                || body.ownerRevision() != 1 || body.decisionRevision() != 1
                || !value.reasonCode().equals(body.reasonCode())
                || !value.approvalRequestPayloadSha256().equals(
                        body.originatingPayloadSha256())
                || !value.decisionOccurredAt().equals(body.occurredAt())
                || !value.originalCorrelationId().equals(body.correlationId())
                || !value.callbackPayloadSha256().equals(body.payloadSha256())
                || !value.callbackPath().equals(exactPath)
                || !value.callbackBodySha256().equals(bodyDigest)
                || !value.commandDigest().equals(
                        Hrm013SeparationBridgeV1.commandDigest(
                                Hrm013SeparationBridgeV1.HRM_010, exactPath,
                                value.tenantId(), bodyDigest))
                || !evidenceMatches) {
            throw new IllegalStateException(
                    "HRM-013 terminal callback is not an exact frozen decision.");
        }
    }

    private static MapSqlParameterSource parameters(DecisionWrite value, StepUpEvidence stepUp) {
        return new MapSqlParameterSource().addValue("outbox", value.outboxId())
                .addValue("tenant", value.tenantId()).addValue("grant", value.grantId())
                .addValue("tenantRevision", value.tenantGrantRevision())
                .addValue("authorizationRevision", value.authorizationRevision())
                .addValue("request", value.approvalRequestId())
                .addValue("case", value.casePublicId()).addValue("task", value.taskPublicId())
                .addValue("event", value.callbackEventId())
                .addValue("decision", value.decision().name())
                .addValue("actor", value.decisionActorPublicId())
                .addValue("maker", value.caseMakerPublicId())
                .addValue("requester", value.requestedByActorPublicId())
                .addValue("reason", value.reasonCode())
                .addValue("evidenceId", stepUp == null ? null : stepUp.evidenceId())
                .addValue("evidenceAudience", stepUp == null ? null : stepUp.audience())
                .addValue("evidencePurpose", stepUp == null ? null : stepUp.purpose())
                .addValue("nonceDigest", stepUp == null ? null : canonicalUuidDigest(stepUp.nonce()))
                .addValue("evidenceDigest", stepUp == null ? null : stepUp.evidenceSha256())
                .addValue("verifiedAt", stepUp == null ? null : Timestamp.from(stepUp.verifiedAt()))
                .addValue("consumedAt", stepUp == null ? null : Timestamp.from(stepUp.consumedAt()))
                .addValue("occurredAt", Timestamp.from(value.decisionOccurredAt()))
                .addValue("path", value.callbackPath())
                .addValue("correlation", value.originalCorrelationId())
                .addValue("originDigest", value.approvalRequestPayloadSha256())
                .addValue("callbackPayloadDigest", value.callbackPayloadSha256())
                .addValue("body", value.exactCallbackBody())
                .addValue("bodyDigest", value.callbackBodySha256())
                .addValue("commandDigest", value.commandDigest())
                .addValue("available", Timestamp.from(value.availableAt()))
                .addValue("created", Timestamp.from(value.createdAt()));
    }
}
