package com.dwp.services.approval.hrisbridge;

import com.dwp.platform.contracts.hris.approval.v2.HrisApprovalBridgeV2;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.dwp.services.approval.domain.ApprovalTerminalDecisionSink;
import com.dwp.services.approval.domain.ApprovalTerminalDecisionContext;
import com.dwp.services.approval.hrisbridge.separation.AuthHrm013ServiceAuthorityClient;
import com.dwp.services.approval.hrisbridge.separation.HrisSeparationDecisionOutboxStore;
import com.dwp.services.approval.hrisbridge.separation.HrisSeparationPersistenceModels;
import com.dwp.services.approval.hrisbridge.separation.HrisSeparationRlsScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Freezes a terminal accepted vote into stable callback bytes in the vote transaction. */
@Component
@ConditionalOnExpression("${dwp.approval.hris-bridge.enabled:false} "
        + "or ${dwp.approval.hrm013-bridge.enabled:false}")
public final class HrisApprovalDecisionOutbox implements ApprovalTerminalDecisionSink {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final HrisSeparationDecisionOutboxStore separation;
    private final AuthHrm013ServiceAuthorityClient terminalAuthority;
    private final boolean legacyEnabled;
    private final boolean separationEnabled;

    public HrisApprovalDecisionOutbox(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this(jdbc, mapper, null,
                (AuthHrm013ServiceAuthorityClient) null, true, false);
    }

    public HrisApprovalDecisionOutbox(NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper, boolean legacyEnabled,
            boolean separationEnabled) {
        this(jdbc, mapper, null,
                (AuthHrm013ServiceAuthorityClient) null,
                legacyEnabled, separationEnabled);
    }

    public HrisApprovalDecisionOutbox(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper,
            PlatformTransactionManager transactions) {
        this(jdbc, mapper, transactions,
                (AuthHrm013ServiceAuthorityClient) null, true, true);
    }

    @Autowired
    public HrisApprovalDecisionOutbox(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper,
            PlatformTransactionManager transactions,
            ObjectProvider<AuthHrm013ServiceAuthorityClient> terminalAuthorities,
            @Value("${dwp.approval.hris-bridge.enabled:false}")
                    boolean legacyEnabled,
            @Value("${dwp.approval.hrm013-bridge.enabled:false}")
                    boolean separationEnabled) {
        this(jdbc, mapper, transactions,
                terminalAuthorities == null ? null : terminalAuthorities.getIfAvailable(),
                legacyEnabled, separationEnabled);
    }

    private HrisApprovalDecisionOutbox(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper,
            PlatformTransactionManager transactions,
            AuthHrm013ServiceAuthorityClient terminalAuthority,
            boolean legacyEnabled, boolean separationEnabled) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.terminalAuthority = terminalAuthority;
        this.legacyEnabled = legacyEnabled;
        this.separationEnabled = separationEnabled;
        this.separation = transactions == null ? null
                : new HrisSeparationDecisionOutboxStore(jdbc, transactions,
                        new com.dwp.services.approval.hrisbridge.separation
                                .HrisSeparationGrantStore(jdbc, transactions));
    }

    @Override
    public void accepted(long tenantId, UUID requestId, UUID voteId,
            UUID actorPersonPublicId, Instant acceptedAt, String requestStatus,
            ApprovalTerminalDecisionContext context) {
        var p = new MapSqlParameterSource().addValue("tenant", tenantId)
                .addValue("request", requestId).addValue("vote", voteId);
        Integer legacyBound = jdbc.queryForObject("""
                SELECT count(*) FROM apr_hris_employee_change_approval_bindings
                 WHERE tenant_id=:tenant AND approval_request_id=:request
                """, p, Integer.class);
        new HrisSeparationRlsScope(jdbc).bind(tenantId);
        Integer separationBound = jdbc.queryForObject("""
                SELECT count(*) FROM apr_hrm013_waiver_request_bindings
                 WHERE tenant_id=:tenant AND approval_request_id=:request
                """, p, Integer.class);
        int legacyMatches = legacyBound == null ? 0 : legacyBound;
        int separationMatches = separationBound == null ? 0 : separationBound;
        Handler handler = selectHandler(legacyMatches, separationMatches,
                legacyEnabled, separationEnabled, requestStatus, context);
        if (handler == Handler.NONE) return;
        if (handler == Handler.LEGACY) {
            appendLegacy(tenantId, requestId, voteId, actorPersonPublicId,
                    acceptedAt, requestStatus, p);
            return;
        }
        if (separation == null) {
            throw new IllegalStateException("HRM-013 terminal route does not match its outcome.");
        }
        appendSeparation(tenantId, requestId, voteId, actorPersonPublicId,
                acceptedAt, requestStatus, context, p);
    }

    enum Handler { NONE, LEGACY, SEPARATION }

    static Handler selectHandler(int legacyMatches, int separationMatches,
            boolean legacyEnabled, boolean separationEnabled,
            String requestStatus, ApprovalTerminalDecisionContext context) {
        if (legacyMatches < 0 || separationMatches < 0 || context == null) {
            throw new IllegalStateException("HRIS terminal decision source is inconsistent.");
        }
        if (legacyMatches + separationMatches == 0) {
            if (context.hrisSeparation()) {
                throw com.dwp.services.approval.hrisbridge.separation
                        .HrisSeparationProtocolException.unavailable();
            }
            return Handler.NONE;
        }
        if ((legacyMatches > 0 && !legacyEnabled)
                || (separationMatches > 0 && !separationEnabled)) {
            throw new IllegalStateException(
                    "The bound HRIS terminal handler is disabled.");
        }
        if (legacyMatches + separationMatches != 1
                || !("APPROVED".equals(requestStatus)
                || "REJECTED".equals(requestStatus))) {
            throw new IllegalStateException("HRIS terminal decision source is inconsistent.");
        }
        if (legacyMatches == 1) {
            if (context.hrisSeparation()) {
                throw new IllegalStateException(
                        "HRIS terminal route does not match its binding.");
            }
            return Handler.LEGACY;
        }
        if (!context.hrisSeparation()
                || (context.kind()
                        == ApprovalTerminalDecisionContext.Kind.HRM013_APPROVE)
                        != "APPROVED".equals(requestStatus)) {
            throw new IllegalStateException(
                    "HRM-013 terminal route does not match its outcome.");
        }
        return Handler.SEPARATION;
    }

    private void appendLegacy(long tenantId, UUID requestId, UUID voteId,
            UUID actorPersonPublicId, Instant acceptedAt, String requestStatus,
            MapSqlParameterSource p) {
        var rows = jdbc.query("""
                SELECT binding.employee_change_request_public_id,
                       binding.frozen_policy_version,binding.original_correlation_id,
                       binding.original_payload_digest,vote.actor_person_id,vote.accepted_at,
                       (stage.context->'pins'->>'policyVersion')::bigint AS runtime_policy_version
                  FROM apr_hris_employee_change_approval_bindings binding
                  JOIN apr_quorum_votes vote
                    ON vote.tenant_id=binding.tenant_id
                   AND vote.request_id=binding.approval_request_id
                   AND vote.vote_id=:vote
                  JOIN apr_quorum_stage_runtime stage
                    ON stage.tenant_id=vote.tenant_id AND stage.request_id=vote.request_id
                   AND stage.step_id=vote.step_id AND stage.generation=vote.generation
                 WHERE binding.tenant_id=:tenant AND binding.approval_request_id=:request
                """, p, (rs, row) -> new FrozenSource(
                rs.getObject("employee_change_request_public_id", UUID.class),
                rs.getLong("frozen_policy_version"),
                rs.getObject("original_correlation_id", UUID.class),
                rs.getString("original_payload_digest"),
                rs.getObject("actor_person_id", UUID.class),
                rs.getTimestamp("accepted_at").toInstant(),
                rs.getLong("runtime_policy_version")));
        if (rows.size() != 1) {
            throw new IllegalStateException("The accepted HRIS terminal vote is not unique.");
        }
        FrozenSource source = rows.getFirst();
        if (!actorPersonPublicId.equals(source.actorPersonPublicId)
                || !acceptedAt.equals(source.acceptedAt)
                || source.frozenPolicyVersion != source.runtimePolicyVersion) {
            throw new IllegalStateException("The accepted HRIS terminal vote changed while freezing.");
        }
        var callback = HrisApprovalBridgeV2.callbackFromFrozenDecision(
                new HrisApprovalBridgeV2.FrozenTerminalDecision(
                        requestId, voteId, requestStatus, acceptedAt,
                        actorPersonPublicId, source.frozenPolicyVersion,
                        source.correlationId, source.payloadDigest));
        var outbound = HrisApprovalBridgeV2.outbound(mapper,
                HrisApprovalBridgeV2.DECISION_CALLBACK,
                source.employeeChangeRequestId, tenantId, callback);
        int inserted = jdbc.update("""
                INSERT INTO apr_hris_employee_change_decision_outbox(
                    outbox_id,tenant_id,approval_request_id,employee_change_request_public_id,
                    decision_event_id,callback_path,frozen_policy_version,
                    terminal_vote_actor_public_id,original_correlation_id,original_payload_digest,
                    exact_callback_body,callback_body_sha256,command_digest)
                VALUES(:outbox,:tenant,:request,:employeeChange,:event,:path,:policy,
                    :actor,:correlation,:payloadDigest,:body,:bodyDigest,:commandDigest)
                """, p.addValue("outbox", UUID.randomUUID())
                .addValue("employeeChange", source.employeeChangeRequestId)
                .addValue("event", voteId).addValue("path", outbound.path())
                .addValue("policy", source.frozenPolicyVersion)
                .addValue("actor", actorPersonPublicId)
                .addValue("correlation", source.correlationId)
                .addValue("payloadDigest", source.payloadDigest)
                .addValue("body", outbound.exactBodyBytes())
                .addValue("bodyDigest", outbound.bodySha256())
                .addValue("commandDigest", outbound.commandDigest()));
        if (inserted != 1) throw new IllegalStateException("HRIS decision outbox insert failed.");
    }

    private void appendSeparation(long tenantId, UUID requestId, UUID voteId,
            UUID actorPersonPublicId, Instant acceptedAt, String requestStatus,
            ApprovalTerminalDecisionContext context, MapSqlParameterSource p) {
        List<SeparationSource> rows = jdbc.query("""
                SELECT binding.grant_id,binding.tenant_grant_revision,
                       binding.authorization_revision,binding.case_public_id,
                       binding.task_public_id,binding.case_maker_public_id,
                       binding.requested_by_actor_public_id,binding.reason_code,
                       binding.original_correlation_id,
                       binding.approval_request_payload_sha256,
                       binding.requested_at,grant.people_signing_key_id,
                       grant.approval_signing_key_id,
                       grant.supersedes_grant_id,
                       binding.frozen_policy_revision,vote.actor_person_id,
                       vote.accepted_at,
                       (stage.context->'pins'->>'policyVersion')::bigint
                           AS runtime_policy_revision
                  FROM apr_hrm013_waiver_request_bindings binding
                  JOIN apr_quorum_votes vote
                    ON vote.tenant_id=binding.tenant_id
                   AND vote.request_id=binding.approval_request_id
                   AND vote.vote_id=:vote
                  JOIN apr_hrm013_tenant_grants grant
                    ON grant.tenant_id=binding.tenant_id
                   AND grant.grant_id=binding.grant_id
                   AND grant.tenant_grant_revision=binding.tenant_grant_revision
                   AND grant.authorization_revision=binding.authorization_revision
                  JOIN apr_quorum_stage_runtime stage
                    ON stage.tenant_id=vote.tenant_id
                   AND stage.request_id=vote.request_id
                   AND stage.step_id=vote.step_id
                   AND stage.generation=vote.generation
                 WHERE binding.tenant_id=:tenant
                   AND binding.approval_request_id=:request
                 FOR SHARE OF binding,grant
                """, p, (row, number) -> new SeparationSource(
                row.getObject("grant_id", UUID.class),
                row.getLong("tenant_grant_revision"),
                row.getLong("authorization_revision"),
                row.getObject("case_public_id", UUID.class),
                row.getObject("task_public_id", UUID.class),
                row.getObject("case_maker_public_id", UUID.class),
                row.getObject("requested_by_actor_public_id", UUID.class),
                row.getString("reason_code"),
                row.getObject("original_correlation_id", UUID.class),
                row.getString("approval_request_payload_sha256"),
                row.getTimestamp("requested_at").toInstant(),
                row.getString("people_signing_key_id"),
                row.getString("approval_signing_key_id"),
                row.getObject("supersedes_grant_id", UUID.class),
                row.getLong("frozen_policy_revision"),
                row.getObject("actor_person_id", UUID.class),
                row.getTimestamp("accepted_at").toInstant(),
                row.getLong("runtime_policy_revision")));
        if (rows.size() != 1) {
            throw new IllegalStateException("The accepted HRM-013 terminal vote is not unique.");
        }
        SeparationSource source = rows.getFirst();
        if (!actorPersonPublicId.equals(source.actorPersonPublicId())
                || !acceptedAt.equals(source.acceptedAt())
                || source.frozenPolicyRevision() != source.runtimePolicyRevision()
                || actorPersonPublicId.equals(source.caseMakerPublicId())
                || actorPersonPublicId.equals(source.requestedByActorPublicId())
                || !context.taskId().equals(taskIdForVote(tenantId, voteId))) {
            throw new IllegalStateException("The accepted HRM-013 vote changed while freezing.");
        }
        UUID eventId = Hrm013WorkloadProofV1.terminalChildEventId(
                tenantId, requestId, 1,
                HrisSeparationPersistenceModels.CALLBACK_BINDING);
        ApprovalTerminalDecisionContext.StepUpEvidence supplied =
                context.stepUpEvidence();
        HrisSeparationPersistenceModels.StepUpEvidence storedEvidence = null;
        UUID evidenceId = null;
        UUID evidenceNonce = null;
        String evidenceAudience = null;
        String evidencePurpose = null;
        Instant verifiedAt = null;
        Instant consumedAt = null;
        if (supplied != null) {
            evidenceId = supplied.evidenceId();
            evidenceNonce = supplied.nonce();
            evidenceAudience = Hrm013SeparationBridgeV1.PEOPLE;
            evidencePurpose = HrisSeparationPersistenceModels.CALLBACK_PURPOSE;
            verifiedAt = supplied.verifiedAt();
            consumedAt = supplied.consumedAt();
        }
        var unsignedEvidence = new Hrm013SeparationBridgeV1.SeparationWaiverDecisionV2(
                eventId, requestId, source.casePublicId(), source.taskPublicId(),
                requestStatus, actorPersonPublicId, 1, 1, source.reasonCode(),
                source.approvalRequestPayloadSha256(), evidenceId,
                evidenceAudience, evidencePurpose, evidenceNonce,
                supplied == null ? null : "0".repeat(64), verifiedAt, consumedAt,
                acceptedAt, "0".repeat(64), source.originalCorrelationId());
        String evidenceDigest = supplied == null ? null
                : Hrm013SeparationBridgeV1.stepUpEvidenceSha256(
                        tenantId, unsignedEvidence);
        if (supplied != null) {
            storedEvidence = new HrisSeparationPersistenceModels.StepUpEvidence(
                    evidenceId, evidenceAudience, evidencePurpose, evidenceNonce,
                    evidenceDigest, verifiedAt, consumedAt);
        }
        var callback = Hrm013SeparationBridgeV1.seal(
                new Hrm013SeparationBridgeV1.SeparationWaiverDecisionV2(
                        eventId, requestId, source.casePublicId(), source.taskPublicId(),
                        requestStatus, actorPersonPublicId, 1, 1, source.reasonCode(),
                        source.approvalRequestPayloadSha256(), evidenceId,
                        evidenceAudience, evidencePurpose, evidenceNonce,
                        evidenceDigest, verifiedAt, consumedAt, acceptedAt,
                        "0".repeat(64), source.originalCorrelationId()));
        byte[] exact = Hrm013SeparationBridgeV1.encode(callback);
        String exactPath = Hrm013SeparationBridgeV1.HRM_010.path(
                source.casePublicId(), source.taskPublicId());
        String bodyDigest = Hrm013SeparationBridgeV1.requestBodySha256(exact);
        if (terminalAuthority == null) {
            throw new IllegalStateException("HRM-013 terminal authority is unavailable.");
        }
        var admission = terminalAuthority.authorizeTerminalChild(
                tenantId, source.grantId(), source.tenantGrantRevision(),
                source.authorizationRevision(), source.peopleSigningKeyId(),
                source.approvalSigningKeyId(), source.supersedesGrantId(),
                requestId, source.requestedAt(),
                source.approvalRequestPayloadSha256(), eventId,
                callback.payloadSha256(), source.originalCorrelationId());
        requireCommitBefore(admission.authorityExpiresAt(), () ->
                terminalAuthority.recheckTerminalChild(admission, tenantId,
                        source.grantId(), source.tenantGrantRevision(),
                        source.authorizationRevision(),
                        source.peopleSigningKeyId(),
                        source.approvalSigningKeyId(),
                        source.supersedesGrantId(), requestId,
                        source.requestedAt(),
                        source.approvalRequestPayloadSha256(), eventId,
                        callback.payloadSha256(),
                        source.originalCorrelationId()));
        separation.append(new HrisSeparationPersistenceModels.DecisionWrite(
                UUID.randomUUID(), tenantId, source.grantId(),
                source.tenantGrantRevision(), source.authorizationRevision(),
                requestId, source.casePublicId(), source.taskPublicId(), eventId,
                HrisSeparationPersistenceModels.WaiverDecision.valueOf(requestStatus),
                actorPersonPublicId, source.caseMakerPublicId(),
                source.requestedByActorPublicId(), source.reasonCode(), storedEvidence,
                acceptedAt, source.originalCorrelationId(),
                source.approvalRequestPayloadSha256(), callback.payloadSha256(), exact,
                bodyDigest, Hrm013SeparationBridgeV1.commandDigest(
                        Hrm013SeparationBridgeV1.HRM_010, exactPath, tenantId,
                        bodyDigest), acceptedAt, acceptedAt));
    }

    private void requireCommitBefore(Instant expiresAt,
            Runnable authorityRecheck) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                    "HRM-013 terminal authority requires one atomic transaction.");
        }
        requireDatabaseTimeBefore(expiresAt);
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override public int getOrder() {
                        return Ordered.LOWEST_PRECEDENCE;
                    }

                    @Override public void beforeCommit(boolean readOnly) {
                        authorityRecheck.run();
                        requireDatabaseTimeBefore(expiresAt);
                    }
                });
    }

    private void requireDatabaseTimeBefore(Instant expiresAt) {
        Timestamp now = jdbc.queryForObject(
                "SELECT clock_timestamp()", Map.of(), Timestamp.class);
        if (now == null || !now.toInstant().isBefore(expiresAt)) {
            throw new IllegalStateException(
                    "HRM-013 terminal authority expired before commit.");
        }
    }

    private UUID taskIdForVote(long tenantId, UUID voteId) {
        return jdbc.queryForObject("""
                SELECT task_id FROM apr_quorum_votes
                 WHERE tenant_id=:tenant AND vote_id=:vote
                """, new MapSqlParameterSource("tenant", tenantId)
                .addValue("vote", voteId), UUID.class);
    }

    private record FrozenSource(UUID employeeChangeRequestId, long frozenPolicyVersion,
            UUID correlationId, String payloadDigest, UUID actorPersonPublicId,
            Instant acceptedAt, long runtimePolicyVersion) { }

    private record SeparationSource(UUID grantId, long tenantGrantRevision,
            long authorizationRevision, UUID casePublicId, UUID taskPublicId,
            UUID caseMakerPublicId, UUID requestedByActorPublicId,
            String reasonCode, UUID originalCorrelationId,
            String approvalRequestPayloadSha256, Instant requestedAt,
            String peopleSigningKeyId, String approvalSigningKeyId,
            UUID supersedesGrantId,
            long frozenPolicyRevision,
            UUID actorPersonPublicId, Instant acceptedAt,
            long runtimePolicyRevision) { }
}
