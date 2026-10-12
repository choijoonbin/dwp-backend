package com.dwp.services.people.hris.employeeservice.employeechange.approvalbridge;

import com.dwp.platform.contracts.hris.approval.v2.HrisApprovalBridgeV2;
import com.dwp.platform.contracts.hris.approval.v2.HrisWorkloadProofV2;
import com.dwp.platform.contracts.hris.command.v1.CommandReceiptV1;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/** One-pass v2 callback verifier and atomic People lifecycle handler. */
@Service
@ConditionalOnProperty(prefix = "dwp.hris.employee-change", name = "enabled",
        havingValue = "true")
final class EmployeeChangeApprovalBridgeService {
    private static final int MAX_BODY_BYTES = 262_144;

    private final ObjectMapper mapper;
    private final Clock clock;
    private final NamedParameterJdbcTemplate jdbc;
    private final HrisApprovalBridgeV2.DecisionCallbackDispatcher dispatcher;

    EmployeeChangeApprovalBridgeService(
            ObjectMapper mapper,
            EmployeeChangeApprovalTrustProfile trust,
            EmployeeChangeApprovalReplayStore replays,
            EmployeeChangeApprovalCallbackLedger ledger,
            NamedParameterJdbcTemplate jdbc) {
        this(mapper, trust, replays, ledger, jdbc, Clock.systemUTC());
    }

    EmployeeChangeApprovalBridgeService(
            ObjectMapper mapper,
            EmployeeChangeApprovalTrustProfile trust,
            EmployeeChangeApprovalReplayStore replays,
            EmployeeChangeApprovalCallbackLedger ledger,
            NamedParameterJdbcTemplate jdbc,
            Clock clock) {
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.clock = clock;
        this.jdbc = jdbc;
        this.dispatcher = new HrisApprovalBridgeV2.DecisionCallbackDispatcher(
                this.mapper, clock, trust, trust, replays, ledger, this::record);
    }

    HrisApprovalBridgeV2.PersistedResponse<CommandReceiptV1> dispatch(
            UUID requestPublicId,
            List<HrisWorkloadProofV2.Header> headers,
            byte[] exactBody) {
        requireUtf8(exactBody);
        return dispatcher.dispatch(requestPublicId, headers, exactBody);
    }

    private HrisApprovalBridgeV2.PersistedResponse<CommandReceiptV1> record(
            long tenantId,
            UUID requestPublicId,
            HrisApprovalBridgeV2.ApprovalDecisionCallbackV1 callback,
            String commandDigest,
            byte[] exactRequestBytes) {
        if (!"APPROVED".equals(callback.decision())
                && !"REJECTED".equals(callback.decision())) {
            throw EmployeeChangeApprovalProtocolException.conflict(
                    "TERMINAL_APPROVAL_DECISION_REQUIRED");
        }
        Context context = context(tenantId, requestPublicId, callback.approvalRequestId());
        if (!"IN_REVIEW".equals(context.lifecycleState())) {
            throw EmployeeChangeApprovalProtocolException.conflict(
                    "EMPLOYEE_CHANGE_NOT_IN_REVIEW");
        }
        if (context.approvalSubjectRevision() == null
                || context.bindingSubjectRevision() != context.approvalSubjectRevision()) {
            throw EmployeeChangeApprovalProtocolException.conflict(
                    "APPROVAL_SUBJECT_REVISION_MISMATCH");
        }
        if (!context.correlationId().equals(callback.correlationId())
                || !constantTime(context.patchHash(), callback.payloadDigest())) {
            throw EmployeeChangeApprovalProtocolException.conflict(
                    "FROZEN_APPROVAL_PAYLOAD_MISMATCH");
        }
        if (context.requesterPublicId().equals(callback.decisionActorPublicId())) {
            throw EmployeeChangeApprovalProtocolException.conflict(
                    "MAKER_CHECKER_SEPARATION_REQUIRED");
        }
        Long currentTargetVersion = lockTargetVersion(context, tenantId);
        if (currentTargetVersion == null
                || currentTargetVersion != context.expectedTargetVersion()) {
            throw EmployeeChangeApprovalProtocolException.conflict(
                    "TARGET_VERSION_STALE");
        }

        Instant now = clock.instant();
        String nextState = callback.decision();
        int updated = jdbc.update("""
                UPDATE ppl_employee_change_requests
                   SET lifecycle_state=:state,
                       approval_policy_revision=:policyRevision,
                       approval_decision_event_id=:decisionEvent,
                       decided_at=:decidedAt,
                       version=version+1,
                       updated_at=:updatedAt
                 WHERE tenant_id=:tenant AND employee_change_request_id=:requestId
                   AND lifecycle_state='IN_REVIEW' AND version=:version
                """, new MapSqlParameterSource()
                .addValue("state", nextState)
                .addValue("policyRevision", callback.approvalPolicyRevision())
                .addValue("decisionEvent", callback.decisionEventId())
                .addValue("decidedAt", java.sql.Timestamp.from(callback.decidedAt()))
                .addValue("updatedAt", java.sql.Timestamp.from(now))
                .addValue("tenant", tenantId)
                .addValue("requestId", context.requestId())
                .addValue("version", context.requestVersion()));
        if (updated != 1) {
            throw EmployeeChangeApprovalProtocolException.conflict(
                    "EMPLOYEE_CHANGE_VERSION_CHANGED");
        }

        UUID receiptId = UUID.randomUUID();
        String resultReference = json(Map.of(
                "employeeChangeRequestPublicId", requestPublicId.toString(),
                "approvalRequestId", callback.approvalRequestId().toString(),
                "decisionEventId", callback.decisionEventId().toString(),
                "decision", callback.decision(),
                "resultingRequestVersion", context.requestVersion() + 1));
        int receiptInserted = jdbc.update("""
                INSERT INTO ppl_command_receipts(
                    command_receipt_id,tenant_id,command_type,idempotency_key,
                    request_hash,actor_public_id,originating_action,
                    subject_principal_public_id,population_scope_digest,
                    field_policy_revision,purpose_code,authorization_revision,
                    originating_context_digest,target_type,target_public_id,
                    expected_version,lifecycle_state,result_code,result_reference,
                    correlation_id,created_at,updated_at,completed_at)
                VALUES(:receipt,:tenant,:commandType,:idempotency,
                    :requestHash,:actor,:action,:subject,:population,
                    :fieldRevision,:purpose,:authorizationRevision,
                    :contextDigest,'EMPLOYEE_CHANGE_REQUEST',:target,
                    :expectedVersion,'SUCCEEDED',:resultCode,CAST(:resultReference AS jsonb),
                    :correlation,:createdAt,:createdAt,:createdAt)
                """, new MapSqlParameterSource()
                .addValue("receipt", receiptId)
                .addValue("tenant", tenantId)
                .addValue("commandType", HrisApprovalBridgeV2.CALLBACK_COMMAND_TYPE)
                .addValue("idempotency", callback.decisionEventId().toString())
                .addValue("requestHash", HrisApprovalBridgeV2.sha256(exactRequestBytes))
                .addValue("actor", callback.decisionActorPublicId())
                .addValue("action", HrisApprovalBridgeV2.CALLBACK_ORIGINATING_ACTION)
                .addValue("subject", context.targetPersonPublicId())
                .addValue("population", context.populationScopeDigest())
                .addValue("fieldRevision", context.fieldPolicyRevision())
                .addValue("purpose", HrisApprovalBridgeV2.CALLBACK_PURPOSE)
                .addValue("authorizationRevision", context.authorizationRevision())
                .addValue("contextDigest", context.originatingContextDigest())
                .addValue("target", requestPublicId)
                .addValue("expectedVersion", context.requestVersion())
                .addValue("resultCode", callback.decision())
                .addValue("resultReference", resultReference)
                .addValue("correlation", callback.correlationId())
                .addValue("createdAt", java.sql.Timestamp.from(now)));
        if (receiptInserted != 1) {
            throw new IllegalStateException("HRM callback command receipt insert failed.");
        }

        appendEvidence(tenantId, requestPublicId, context, callback, receiptId, now);
        var receipt = new CommandReceiptV1(
                receiptId,
                HrisApprovalBridgeV2.CALLBACK_COMMAND_TYPE,
                HrisApprovalBridgeV2.CALLBACK_ORIGINATING_ACTION,
                context.targetPersonPublicId(),
                context.populationScopeDigest(),
                context.fieldPolicyRevision(),
                HrisApprovalBridgeV2.CALLBACK_PURPOSE,
                context.authorizationRevision(),
                "SUCCEEDED",
                callback.correlationId(),
                commandDigest,
                now);
        return new HrisApprovalBridgeV2.PersistedResponse<>(receipt, encode(receipt));
    }

    private Context context(long tenantId, UUID requestPublicId, UUID approvalRequestId) {
        var parameters = new MapSqlParameterSource()
                .addValue("tenant", tenantId)
                .addValue("request", requestPublicId)
                .addValue("approval", approvalRequestId);
        List<RequestContext> requests = jdbc.query("""
                SELECT request.employee_change_request_id,request.lifecycle_state,
                       request.version AS request_version,
                       request.expected_target_version,request.target_aggregate_type,
                       request.target_aggregate_public_id,
                       request.approval_case_public_id,
                       request.approval_subject_revision,
                       requester.public_id AS requester_public_id,
                       target.public_id AS target_person_public_id,
                       request.correlation_id,request.patch_hash,
                       request.population_scope_digest,request.field_policy_revision,
                       request.authorization_revision,request.originating_context_digest
                  FROM ppl_employee_change_requests request
                  JOIN ppl_persons requester
                    ON requester.tenant_id=request.tenant_id
                   AND requester.person_id=request.requester_person_id
                  JOIN ppl_persons target
                    ON target.tenant_id=request.tenant_id
                   AND target.person_id=request.target_person_id
                 WHERE request.tenant_id=:tenant AND request.public_id=:request
                 FOR UPDATE OF request
                """, parameters, (resultSet, row) -> new RequestContext(
                        resultSet.getLong("employee_change_request_id"),
                        resultSet.getString("lifecycle_state"),
                        resultSet.getLong("request_version"),
                        resultSet.getLong("expected_target_version"),
                        resultSet.getString("target_aggregate_type"),
                        resultSet.getObject("target_aggregate_public_id", UUID.class),
                        resultSet.getObject("approval_case_public_id", UUID.class),
                        resultSet.getObject("approval_subject_revision", Long.class),
                        resultSet.getObject("requester_public_id", UUID.class),
                        resultSet.getObject("target_person_public_id", UUID.class),
                        resultSet.getObject("correlation_id", UUID.class),
                        resultSet.getString("patch_hash"),
                        resultSet.getString("population_scope_digest"),
                        resultSet.getLong("field_policy_revision"),
                        resultSet.getLong("authorization_revision"),
                        resultSet.getString("originating_context_digest")));
        if (requests.size() != 1) {
            throw EmployeeChangeApprovalProtocolException.conflict(
                    "EMPLOYEE_CHANGE_APPROVAL_BINDING_UNAVAILABLE");
        }
        RequestContext request = requests.getFirst();
        List<Long> bindings = jdbc.query("""
                SELECT subject_revision
                  FROM ppl_employee_change_approval_bindings
                 WHERE tenant_id=:tenant
                   AND employee_change_request_id=:requestId
                   AND approval_request_id=:approval
                 FOR SHARE
                """, parameters.addValue("requestId", request.requestId()),
                (resultSet, row) -> resultSet.getLong("subject_revision"));
        if (bindings.size() == 1) {
            return request.bound(bindings.getFirst());
        }
        if (bindings.isEmpty() && bindingIsPending(request, approvalRequestId, parameters)) {
            throw EmployeeChangeApprovalProtocolException.tooEarly(
                    "EMPLOYEE_CHANGE_APPROVAL_BINDING_PENDING");
        }
        throw EmployeeChangeApprovalProtocolException.conflict(
                "EMPLOYEE_CHANGE_APPROVAL_BINDING_UNAVAILABLE");
    }

    private boolean bindingIsPending(
            RequestContext request,
            UUID approvalRequestId,
            MapSqlParameterSource parameters) {
        if (!"SUBMITTED".equals(request.lifecycleState())
                || request.approvalSubjectRevision() == null
                || !approvalRequestId.equals(request.approvalRequestId())) {
            return false;
        }
        List<UUID> outbox = jdbc.query("""
                SELECT outbox_id
                  FROM ppl_employee_change_approval_request_outbox
                 WHERE tenant_id=:tenant
                   AND employee_change_request_id=:requestId
                   AND employee_change_request_public_id=:request
                   AND approval_request_id=:approval
                   AND subject_revision=:subjectRevision
                   AND delivery_state IN ('PENDING','CLAIMED','RETRY','RESULT_UNKNOWN')
                 FOR UPDATE
                """, parameters.addValue(
                        "subjectRevision", request.approvalSubjectRevision()),
                (resultSet, row) -> resultSet.getObject("outbox_id", UUID.class));
        return outbox.size() == 1;
    }

    private Long lockTargetVersion(Context context, long tenantId) {
        String sql = switch (context.targetAggregateType()) {
            case "PERSON" -> """
                    SELECT version FROM ppl_persons
                     WHERE tenant_id=:tenant AND public_id=:aggregate
                       AND public_id=:targetPerson FOR UPDATE
                    """;
            case "WORKER" -> """
                    SELECT worker.version FROM ppl_workers worker
                      JOIN ppl_persons person
                        ON person.tenant_id=worker.tenant_id
                       AND person.person_id=worker.person_id
                     WHERE worker.tenant_id=:tenant AND worker.public_id=:aggregate
                       AND person.public_id=:targetPerson FOR UPDATE OF worker
                    """;
            case "WORK_RELATIONSHIP" -> """
                    SELECT relationship.version FROM ppl_work_relationships relationship
                      JOIN ppl_workers worker
                        ON worker.tenant_id=relationship.tenant_id
                       AND worker.worker_id=relationship.worker_id
                      JOIN ppl_persons person
                        ON person.tenant_id=worker.tenant_id
                       AND person.person_id=worker.person_id
                     WHERE relationship.tenant_id=:tenant
                       AND relationship.public_id=:aggregate
                       AND person.public_id=:targetPerson FOR UPDATE OF relationship
                    """;
            case "ASSIGNMENT" -> """
                    SELECT assignment.version FROM ppl_assignments assignment
                      JOIN ppl_work_relationships relationship
                        ON relationship.tenant_id=assignment.tenant_id
                       AND relationship.work_relationship_id=assignment.work_relationship_id
                      JOIN ppl_workers worker
                        ON worker.tenant_id=relationship.tenant_id
                       AND worker.worker_id=relationship.worker_id
                      JOIN ppl_persons person
                        ON person.tenant_id=worker.tenant_id
                       AND person.person_id=worker.person_id
                     WHERE assignment.tenant_id=:tenant
                       AND assignment.public_id=:aggregate
                       AND person.public_id=:targetPerson FOR UPDATE OF assignment
                    """;
            default -> throw EmployeeChangeApprovalProtocolException.conflict(
                    "SUPPORTED_TARGET_AGGREGATE_REQUIRED");
        };
        List<Long> versions = jdbc.query(sql, new MapSqlParameterSource()
                .addValue("tenant", tenantId)
                .addValue("aggregate", context.targetAggregatePublicId())
                .addValue("targetPerson", context.targetPersonPublicId()),
                (resultSet, row) -> resultSet.getLong("version"));
        return versions.size() == 1 ? versions.getFirst() : null;
    }

    private void appendEvidence(
            long tenantId,
            UUID requestPublicId,
            Context context,
            HrisApprovalBridgeV2.ApprovalDecisionCallbackV1 callback,
            UUID receiptId,
            Instant now) {
        String eventPayload = json(Map.of(
                "employeeChangeRequestPublicId", requestPublicId.toString(),
                "approvalRequestId", callback.approvalRequestId().toString(),
                "decisionEventId", callback.decisionEventId().toString(),
                "decision", callback.decision(),
                "commandReceiptId", receiptId.toString(),
                "requestVersion", context.requestVersion() + 1));
        jdbc.update("""
                INSERT INTO sys_people_audit_events(
                    audit_event_id,tenant_id,actor_type,actor_id,action,target_type,
                    target_id,outcome,correlation_id,after_snapshot,occurred_at)
                VALUES(:event,:tenant,'SERVICE',:actor,
                    'people.hris-employee-change.approval-decision-recorded',
                    'EMPLOYEE_CHANGE_REQUEST',:target,'SUCCESS',:correlation,
                    CAST(:payload AS jsonb),:occurredAt)
                """, new MapSqlParameterSource()
                .addValue("event", UUID.randomUUID())
                .addValue("tenant", tenantId)
                .addValue("actor", "dwp-approval-server:"
                        + callback.decisionActorPublicId())
                .addValue("target", requestPublicId.toString())
                .addValue("correlation", callback.correlationId().toString())
                .addValue("payload", eventPayload)
                .addValue("occurredAt", java.sql.Timestamp.from(now)));
        jdbc.update("""
                INSERT INTO sys_people_outbox_events(
                    event_id,tenant_id,aggregate_type,aggregate_id,event_type,
                    payload,correlation_id,occurred_at)
                VALUES(:event,:tenant,'EMPLOYEE_CHANGE_REQUEST',:aggregate,:eventType,
                    CAST(:payload AS jsonb),:correlation,:occurredAt)
                """, new MapSqlParameterSource()
                .addValue("event", callback.decisionEventId())
                .addValue("tenant", tenantId)
                .addValue("aggregate", requestPublicId.toString())
                .addValue("eventType", "APPROVED".equals(callback.decision())
                        ? "EmployeeChangeApproved.v1" : "EmployeeChangeRejected.v1")
                .addValue("payload", eventPayload)
                .addValue("correlation", callback.correlationId().toString())
                .addValue("occurredAt", java.sql.Timestamp.from(now)));
    }

    private byte[] encode(CommandReceiptV1 receipt) {
        try {
            return mapper.writeValueAsBytes(receipt);
        } catch (Exception exception) {
            throw new IllegalStateException("HRM callback receipt could not be encoded.", exception);
        }
    }

    private String json(Map<String, ?> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("HRM callback evidence could not be encoded.", exception);
        }
    }

    private static void requireUtf8(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw EmployeeChangeApprovalProtocolException.badRequest(
                    "NON_EMPTY_JSON_BODY_REQUIRED");
        }
        if (bytes.length > MAX_BODY_BYTES) {
            throw EmployeeChangeApprovalProtocolException.payloadTooLarge(
                    "REQUEST_BODY_TOO_LARGE");
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw EmployeeChangeApprovalProtocolException.badRequest(
                    "CANONICAL_UTF8_JSON_REQUIRED");
        }
    }

    private static boolean constantTime(String left, String right) {
        return left != null && right != null && java.security.MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private record Context(
            long requestId,
            String lifecycleState,
            long requestVersion,
            long expectedTargetVersion,
            String targetAggregateType,
            UUID targetAggregatePublicId,
            Long approvalSubjectRevision,
            long bindingSubjectRevision,
            UUID requesterPublicId,
            UUID targetPersonPublicId,
            UUID correlationId,
            String patchHash,
            String populationScopeDigest,
            long fieldPolicyRevision,
            long authorizationRevision,
            String originatingContextDigest) {
    }

    private record RequestContext(
            long requestId,
            String lifecycleState,
            long requestVersion,
            long expectedTargetVersion,
            String targetAggregateType,
            UUID targetAggregatePublicId,
            UUID approvalRequestId,
            Long approvalSubjectRevision,
            UUID requesterPublicId,
            UUID targetPersonPublicId,
            UUID correlationId,
            String patchHash,
            String populationScopeDigest,
            long fieldPolicyRevision,
            long authorizationRevision,
            String originatingContextDigest) {
        private Context bound(long bindingSubjectRevision) {
            return new Context(
                    requestId, lifecycleState, requestVersion, expectedTargetVersion,
                    targetAggregateType, targetAggregatePublicId, approvalSubjectRevision,
                    bindingSubjectRevision, requesterPublicId, targetPersonPublicId,
                    correlationId, patchHash, populationScopeDigest, fieldPolicyRevision,
                    authorizationRevision, originatingContextDigest);
        }
    }
}
