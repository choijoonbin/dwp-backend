package com.dwp.services.approval.hrisbridge;

import com.dwp.platform.contracts.hris.approval.v2.HrisApprovalBridgeV2;
import com.dwp.services.approval.domain.ApprovalTerminalDecisionSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Freezes a terminal accepted vote into stable callback bytes in the vote transaction. */
@Component
@ConditionalOnProperty(prefix = "dwp.approval.hris-bridge", name = "enabled", havingValue = "true")
public final class HrisApprovalDecisionOutbox implements ApprovalTerminalDecisionSink {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public HrisApprovalDecisionOutbox(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void accepted(long tenantId, UUID requestId, UUID voteId,
            UUID actorPersonPublicId, Instant acceptedAt, String requestStatus) {
        var p = new MapSqlParameterSource().addValue("tenant", tenantId)
                .addValue("request", requestId).addValue("vote", voteId);
        Integer bound = jdbc.queryForObject("""
                SELECT count(*) FROM apr_hris_employee_change_approval_bindings
                 WHERE tenant_id=:tenant AND approval_request_id=:request
                """, p, Integer.class);
        if (bound == null || bound == 0) return;
        if (bound != 1 || !("APPROVED".equals(requestStatus) || "REJECTED".equals(requestStatus))) {
            throw new IllegalStateException("HRIS terminal decision source is inconsistent.");
        }
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

    private record FrozenSource(UUID employeeChangeRequestId, long frozenPolicyVersion,
            UUID correlationId, String payloadDigest, UUID actorPersonPublicId,
            Instant acceptedAt, long runtimePolicyVersion) { }
}
