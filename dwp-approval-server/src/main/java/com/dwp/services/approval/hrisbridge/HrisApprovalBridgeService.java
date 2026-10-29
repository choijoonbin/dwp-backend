package com.dwp.services.approval.hrisbridge;

import com.dwp.platform.contracts.hris.approval.v2.HrisApprovalBridgeV2;
import com.dwp.platform.contracts.hris.approval.v2.HrisWorkloadProofV2;
import com.dwp.platform.contracts.hris.generated.ApprovalRequestV1;
import com.dwp.services.approval.domain.ApprovalCommandRepository;
import com.dwp.services.approval.domain.ApprovalWorkflowQuorumFacade;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Atomic V58-backed create-and-submit service; no browser actor is consulted. */
@Service
@ConditionalOnProperty(prefix = "dwp.approval.hris-bridge", name = "enabled", havingValue = "true")
final class HrisApprovalBridgeService {
    private static final int MAX_BODY_BYTES = 262_144;
    private static final Set<String> RECEIPT_FIELDS = Set.of(
            "requestId", "requestRevision", "state", "receiptDigest");
    private final ObjectMapper mapper;
    private final Clock clock = Clock.systemUTC();
    private final HrisApprovalTrustProfile trust;
    private final HrisApprovalReplayStore replays;
    private final HrisApprovalAuthorityResolver authorities;
    private final ApprovalCommandRepository commands;
    private final ApprovalWorkflowQuorumFacade quorum;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    HrisApprovalBridgeService(ObjectMapper mapper, HrisApprovalTrustProfile trust,
            HrisApprovalReplayStore replays, HrisApprovalAuthorityResolver authorities,
            ApprovalCommandRepository commands, ApprovalWorkflowQuorumFacade quorum,
            NamedParameterJdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.trust = trust;
        this.replays = replays;
        this.authorities = authorities;
        this.commands = commands;
        this.quorum = quorum;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
    }

    HrisApprovalBridgeV2.PersistedResponse<HrisApprovalBridgeV2.CreateAndSubmitReceiptV2> dispatch(
            List<HrisWorkloadProofV2.Header> headers, byte[] exactBody) {
        requireUtf8(exactBody);
        ApprovalRequestV1 request = decodeRequest(exactBody);
        if (!"PENDING".equals(request.state())) {
            throw HrisApprovalProtocolException.conflict(
                    "ATOMIC_CREATE_AND_SUBMIT_REQUIRES_PENDING_STATE");
        }
        UUID employeeChangeId = employeeChangeId(request.subjectRef());
        var admission = HrisWorkloadProofV2.verify(mapper, clock,
                HrisApprovalBridgeV2.CREATE_AND_SUBMIT.verificationContract(),
                HrisApprovalBridgeV2.CREATE_AND_SUBMIT.path(null), headers, exactBody,
                trust, trust, replays);
        Aliases aliases = new Aliases(admission.tenantId(), request.subjectRef(),
                request.subjectRevision(), request.requestId(), request.idempotencyKey());
        Optional<HrisApprovalBridgeV2.PersistedResponse<
                HrisApprovalBridgeV2.CreateAndSubmitReceiptV2>> replay =
                findExisting(aliases, admission.commandDigest());
        if (replay.isPresent()) return replay.get();

        HrisApprovalAuthorityResolver.Authority authority =
                authorities.resolve(admission.tenantId(), request);
        var result = transactions.execute(status -> {
            lockAliases(aliases);
            Optional<HrisApprovalBridgeV2.PersistedResponse<
                    HrisApprovalBridgeV2.CreateAndSubmitReceiptV2>> concurrent =
                    replay(aliases, admission.commandDigest());
            if (concurrent.isPresent()) return concurrent.get();
            return create(admission.tenantId(), request, employeeChangeId, authority,
                    admission.commandDigest(), exactBody);
        });
        if (result == null) throw new IllegalStateException("HRIS create transaction returned no result.");
        return result;
    }

    Optional<HrisApprovalBridgeV2.PersistedResponse<
            HrisApprovalBridgeV2.CreateAndSubmitReceiptV2>> findExisting(
            Aliases aliases, String commandDigest) {
        Optional<HrisApprovalBridgeV2.PersistedResponse<
                HrisApprovalBridgeV2.CreateAndSubmitReceiptV2>> result =
                transactions.execute(status -> {
                    lockAliases(aliases);
                    return replay(aliases, commandDigest);
                });
        return result == null ? Optional.empty() : result;
    }

    private HrisApprovalBridgeV2.PersistedResponse<
            HrisApprovalBridgeV2.CreateAndSubmitReceiptV2> create(
            long tenantId, ApprovalRequestV1 request, UUID employeeChangeId,
            HrisApprovalAuthorityResolver.Authority authority, String commandDigest,
            byte[] exactBody) {
        Integer requestCollision = jdbc.queryForObject(
                "SELECT count(*) FROM apr_requests WHERE request_id=:request",
                new MapSqlParameterSource("request", request.requestId()), Integer.class);
        if (requestCollision != null && requestCollision != 0) {
            throw HrisApprovalProtocolException.conflict("REQUEST_ID_ALREADY_OWNED");
        }
        var actor = new ApprovalRequestContext.Actor(authority.userId(), tenantId,
                authority.personPublicId(), authority.displayName(), Set.of(), Set.of());
        HrisApprovalBridgeV2.NativeCreatePayload nativeValue =
                HrisApprovalBridgeV2.nativePayload(request);
        Map<String, Object> nativePayload = new LinkedHashMap<>();
        nativePayload.put("requestId", nativeValue.requestId().toString());
        nativePayload.put("subjectRef", nativeValue.subjectRef());
        nativePayload.put("subjectRevision", nativeValue.subjectRevision());
        nativePayload.put("requesterRef", nativeValue.requesterRef());
        nativePayload.put("requestedAction", nativeValue.requestedAction());
        nativePayload.put("payloadRef", nativeValue.payloadRef());
        nativePayload.put("payloadDigest", nativeValue.payloadDigest());
        nativePayload.put("requestedAt", nativeValue.requestedAt().toString());
        nativePayload.put("correlationId", nativeValue.correlationId().toString());
        commands.createPinnedHrisDraft(actor, request.requestId(),
                authority.workflowVersionId(), authority.formVersionId(),
                nativeValue.title(), nativeValue.summary(), nativeValue.priority(),
                nativePayload, request.correlationId().toString());
        quorum.submit(actor, request.requestId(), 0, request.correlationId().toString());
        var frozen = jdbc.queryForList("""
                SELECT DISTINCT (context->'pins'->>'policyVersion')::bigint AS policy_version
                  FROM apr_quorum_stage_runtime
                 WHERE tenant_id=:tenant AND request_id=:request
                """, scope(tenantId, request.requestId()));
        if (frozen.size() != 1 || ((Number) frozen.getFirst().get("policy_version")).longValue() < 1) {
            throw HrisApprovalProtocolException.conflict("FROZEN_POLICY_PIN_UNAVAILABLE");
        }
        long policyVersion = ((Number) frozen.getFirst().get("policy_version")).longValue();
        Long requestRevision = jdbc.queryForObject("""
                SELECT version FROM apr_requests
                 WHERE tenant_id=:tenant AND request_id=:request AND status='IN_REVIEW'
                """, scope(tenantId, request.requestId()), Long.class);
        if (requestRevision == null || requestRevision < 1) {
            throw HrisApprovalProtocolException.conflict("ATOMIC_SUBMIT_DID_NOT_COMMIT");
        }
        var receipt = new HrisApprovalBridgeV2.CreateAndSubmitReceiptV2(
                request.requestId(), requestRevision, "PENDING", commandDigest);
        byte[] response = encode(receipt);
        int inserted = jdbc.update("""
                INSERT INTO apr_hris_employee_change_approval_bindings(
                    binding_id,tenant_id,approval_request_id,employee_change_request_public_id,
                    subject_ref,subject_revision,idempotency_key,
                    requester_person_public_id,requester_user_id,
                    workflow_key,workflow_version,workflow_version_id,form_version_id,
                    frozen_policy_version,original_correlation_id,original_payload_digest,
                    exact_request_body,request_body_sha256,command_digest,
                    exact_response_body,response_body_sha256)
                VALUES(:binding,:tenant,:request,:employeeChange,:subject,:subjectRevision,:idempotency,
                    :person,:userId,:workflowKey,:workflowVersion,:workflowVersionId,:formVersionId,
                    :policyVersion,:correlation,:payloadDigest,
                    :requestBody,:requestBodyDigest,:commandDigest,:responseBody,:responseBodyDigest)
                """, new MapSqlParameterSource()
                .addValue("binding", UUID.randomUUID())
                .addValue("tenant", tenantId)
                .addValue("request", request.requestId())
                .addValue("employeeChange", employeeChangeId)
                .addValue("subject", request.subjectRef())
                .addValue("subjectRevision", request.subjectRevision())
                .addValue("idempotency", request.idempotencyKey())
                .addValue("person", authority.personPublicId())
                .addValue("userId", authority.userId())
                .addValue("workflowKey", request.workflowKey())
                .addValue("workflowVersion", request.workflowVersion())
                .addValue("workflowVersionId", authority.workflowVersionId())
                .addValue("formVersionId", authority.formVersionId())
                .addValue("policyVersion", policyVersion)
                .addValue("correlation", request.correlationId())
                .addValue("payloadDigest", request.payloadDigest())
                .addValue("requestBody", exactBody)
                .addValue("requestBodyDigest", HrisApprovalBridgeV2.sha256(exactBody))
                .addValue("commandDigest", commandDigest)
                .addValue("responseBody", response)
                .addValue("responseBodyDigest", HrisApprovalBridgeV2.sha256(response)));
        if (inserted != 1) throw new IllegalStateException("HRIS binding ledger insert failed.");
        return new HrisApprovalBridgeV2.PersistedResponse<>(receipt, response);
    }

    private Optional<HrisApprovalBridgeV2.PersistedResponse<
            HrisApprovalBridgeV2.CreateAndSubmitReceiptV2>> replay(
            Aliases aliases, String commandDigest) {
        var rows = jdbc.queryForList("""
                SELECT binding_id,command_digest,exact_response_body
                  FROM apr_hris_employee_change_approval_bindings
                 WHERE tenant_id=:tenant AND (
                       (subject_ref=:subject AND subject_revision=:subjectRevision)
                    OR approval_request_id=:request OR idempotency_key=:idempotency)
                 FOR UPDATE
                """, aliases.params());
        Set<UUID> owners = rows.stream().map(row -> (UUID) row.get("binding_id"))
                .collect(java.util.stream.Collectors.toSet());
        if (owners.size() > 1) {
            throw HrisApprovalProtocolException.conflict("IDEMPOTENCY_ALIAS_SPLIT_BRAIN");
        }
        if (rows.isEmpty()) return Optional.empty();
        String priorDigest = (String) rows.getFirst().get("command_digest");
        if (!constantTime(priorDigest, commandDigest)) {
            throw HrisApprovalProtocolException.conflict(
                    "IDEMPOTENCY_IDENTITY_COMMAND_MISMATCH");
        }
        byte[] bytes = (byte[]) rows.getFirst().get("exact_response_body");
        return Optional.of(new HrisApprovalBridgeV2.PersistedResponse<>(
                decodeReceipt(bytes), bytes));
    }

    private void lockAliases(Aliases aliases) {
        List<String> keys = new ArrayList<>(List.of(
                "hris-semantic\n" + aliases.tenantId + "\n" + aliases.subjectRef
                        + "\n" + aliases.subjectRevision,
                "hris-request\n" + aliases.tenantId + "\n" + aliases.requestId,
                "hris-idempotency\n" + aliases.tenantId + "\n" + aliases.idempotencyKey));
        keys.sort(String::compareTo);
        for (String key : keys) {
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))",
                    new MapSqlParameterSource("key", key), resultSet -> null);
        }
    }

    private ApprovalRequestV1 decodeRequest(byte[] bytes) {
        try {
            return mapper.readValue(bytes, ApprovalRequestV1.class);
        } catch (Exception exception) {
            throw HrisApprovalProtocolException.badRequest("PDX_010_BODY_INVALID");
        }
    }

    private HrisApprovalBridgeV2.CreateAndSubmitReceiptV2 decodeReceipt(byte[] bytes) {
        try {
            JsonNode node = mapper.readTree(bytes);
            Set<String> names = new java.util.HashSet<>();
            node.fieldNames().forEachRemaining(names::add);
            if (!node.isObject() || !RECEIPT_FIELDS.equals(names)) throw new IllegalArgumentException();
            return mapper.treeToValue(node,
                    HrisApprovalBridgeV2.CreateAndSubmitReceiptV2.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored HRIS response is invalid.", exception);
        }
    }

    private byte[] encode(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (Exception exception) {
            throw new IllegalStateException("HRIS response serialization failed.", exception);
        }
    }

    private static void requireUtf8(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BODY_BYTES) {
            throw HrisApprovalProtocolException.badRequest("EXACT_BOUNDED_UTF8_BODY_REQUIRED");
        }
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
        } catch (Exception exception) {
            throw HrisApprovalProtocolException.badRequest("EXACT_BOUNDED_UTF8_BODY_REQUIRED");
        }
    }

    private static UUID employeeChangeId(String subjectRef) {
        String prefix = "employee-change:";
        if (subjectRef == null || !subjectRef.startsWith(prefix)) {
            throw HrisApprovalProtocolException.conflict(
                    "EMPLOYEE_CHANGE_SUBJECT_REFERENCE_REQUIRED");
        }
        try {
            UUID id = UUID.fromString(subjectRef.substring(prefix.length()));
            if (!subjectRef.equals(prefix + id)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException exception) {
            throw HrisApprovalProtocolException.conflict(
                    "EMPLOYEE_CHANGE_SUBJECT_REFERENCE_REQUIRED");
        }
    }

    private static boolean constantTime(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private static MapSqlParameterSource scope(long tenantId, UUID requestId) {
        return new MapSqlParameterSource().addValue("tenant", tenantId)
                .addValue("request", requestId);
    }

    record Aliases(long tenantId, String subjectRef, long subjectRevision,
            UUID requestId, String idempotencyKey) {
        MapSqlParameterSource params() {
            return new MapSqlParameterSource().addValue("tenant", tenantId)
                    .addValue("subject", subjectRef)
                    .addValue("subjectRevision", subjectRevision)
                    .addValue("request", requestId)
                    .addValue("idempotency", idempotencyKey);
        }
    }
}
