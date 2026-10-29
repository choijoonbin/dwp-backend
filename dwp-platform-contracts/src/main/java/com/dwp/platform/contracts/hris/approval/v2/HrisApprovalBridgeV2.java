package com.dwp.platform.contracts.hris.approval.v2;

import com.dwp.platform.contracts.hris.command.v1.CommandReceiptV1;
import com.dwp.platform.contracts.hris.generated.ApprovalRequestV1;
import com.dwp.platform.contracts.hris.generated.CanonicalHrisJson;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Production transport and idempotency contract for the HRIS People/Approval
 * bridge. It supersedes the bootstrap v1 contract without changing either
 * canonical internal HTTP resource path.
 */
public final class HrisApprovalBridgeV2 {

    public static final Endpoint CREATE_AND_SUBMIT = new Endpoint(
            "SVC-PEP-APR-001",
            "dwp-approval-server",
            "dwp-people-server",
            "POST",
            "/internal/hris-approval/v1/approval-requests",
            "HRIS_EMPLOYEE_CHANGE_APPROVAL_REQUEST_SUBMIT");

    public static final Endpoint DECISION_CALLBACK = new Endpoint(
            "SVC-PEP-HRM-001",
            "dwp-people-server",
            "dwp-approval-server",
            "POST",
            "/internal/hris-people/v1/employee-change-requests/{requestPublicId}"
                    + "/approval-decisions",
            "HRIS_APPROVAL_DECISION_CALLBACK");

    public static final String CALLBACK_COMMAND_TYPE =
            "RECORD_EMPLOYEE_CHANGE_APPROVAL_DECISION";
    public static final String CALLBACK_ORIGINATING_ACTION =
            "HRM_EMPLOYEE_CHANGE_APPROVAL_DECISION_RECORD";
    public static final String CALLBACK_PURPOSE = "HRIS_APPROVAL_DECISION_CALLBACK";

    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9._-]{0,79}$");
    private static final Pattern REQUESTER_REF = Pattern.compile(
            "^person:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
                    + "[0-9a-f]{4}-[0-9a-f]{12})$");
    private static final Set<String> CALLBACK_FIELDS = Set.of(
            "approvalRequestId", "decisionEventId", "decision", "decidedAt",
            "decisionActorPublicId", "approvalPolicyRevision", "correlationId",
            "payloadDigest");

    private HrisApprovalBridgeV2() {
    }

    public record Endpoint(
            String bindingId,
            String ownerWorkloadId,
            String callerWorkloadId,
            String method,
            String pathTemplate,
            String purpose) {
        public Endpoint {
            Objects.requireNonNull(bindingId, "bindingId");
            Objects.requireNonNull(ownerWorkloadId, "ownerWorkloadId");
            Objects.requireNonNull(callerWorkloadId, "callerWorkloadId");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(pathTemplate, "pathTemplate");
            Objects.requireNonNull(purpose, "purpose");
        }

        public String path(UUID requestPublicId) {
            return verificationContract().path(requestPublicId);
        }

        public boolean matchesPath(String path) {
            return verificationContract().matchesPath(path);
        }

        public HrisApprovalVerificationContractV2 verificationContract() {
            return new HrisApprovalVerificationContractV2(
                    bindingId, ownerWorkloadId, callerWorkloadId, method,
                    pathTemplate, purpose, HrisApprovalBridgeV2::denied,
                    failure -> failure instanceof BridgeFailure);
        }
    }

    public record CreateAndSubmitReceiptV2(
            UUID requestId,
            long requestRevision,
            String state,
            String receiptDigest) {
        public CreateAndSubmitReceiptV2 {
            Objects.requireNonNull(requestId, "requestId");
            if (requestRevision < 1) {
                throw new IllegalArgumentException("requestRevision must be positive");
            }
            if (!"PENDING".equals(state)) {
                throw new IllegalArgumentException("create-and-submit state must be PENDING");
            }
            requireDigest(receiptDigest, "receiptDigest");
        }
    }

    /** Exact stable callback body; approvalPolicyRevision is the frozen quorum pin. */
    public record ApprovalDecisionCallbackV1(
            @JsonSerialize(using = CanonicalHrisJson.UuidSerializer.class)
            UUID approvalRequestId,
            @JsonSerialize(using = CanonicalHrisJson.UuidSerializer.class)
            UUID decisionEventId,
            String decision,
            @JsonSerialize(using = CanonicalHrisJson.InstantSerializer.class)
            Instant decidedAt,
            @JsonSerialize(using = CanonicalHrisJson.UuidSerializer.class)
            UUID decisionActorPublicId,
            long approvalPolicyRevision,
            @JsonSerialize(using = CanonicalHrisJson.UuidSerializer.class)
            UUID correlationId,
            String payloadDigest) {
        public ApprovalDecisionCallbackV1 {
            Objects.requireNonNull(approvalRequestId, "approvalRequestId");
            Objects.requireNonNull(decisionEventId, "decisionEventId");
            if (decision == null || !CODE.matcher(decision).matches()) {
                throw new IllegalArgumentException("decision is not a canonical code");
            }
            Objects.requireNonNull(decidedAt, "decidedAt");
            Objects.requireNonNull(decisionActorPublicId, "decisionActorPublicId");
            if (approvalPolicyRevision < 1) {
                throw new IllegalArgumentException("approvalPolicyRevision must be positive");
            }
            Objects.requireNonNull(correlationId, "correlationId");
            requireDigest(payloadDigest, "payloadDigest");
        }
    }

    /** Values read from the accepted terminal vote and the frozen stage runtime. */
    public record FrozenTerminalDecision(
            UUID approvalRequestId,
            UUID decisionEventId,
            String decision,
            Instant decidedAt,
            UUID terminalAcceptedVoteActorPublicId,
            long frozenWorkflowPolicyVersion,
            UUID originalPdxCorrelationId,
            String originalPdxPayloadDigest) {
    }

    public static ApprovalDecisionCallbackV1 callbackFromFrozenDecision(
            FrozenTerminalDecision source) {
        Objects.requireNonNull(source, "source");
        return new ApprovalDecisionCallbackV1(
                source.approvalRequestId(), source.decisionEventId(), source.decision(),
                source.decidedAt(), source.terminalAcceptedVoteActorPublicId(),
                source.frozenWorkflowPolicyVersion(), source.originalPdxCorrelationId(),
                source.originalPdxPayloadDigest());
    }

    /** Closed native mapping; state and idempotency remain owner-ledger concerns. */
    public record NativeCreatePayload(
            UUID requestId,
            String subjectRef,
            long subjectRevision,
            String requesterRef,
            String requestedAction,
            String payloadRef,
            String payloadDigest,
            Instant requestedAt,
            UUID correlationId,
            String title,
            String summary,
            String priority) {
    }

    public static NativeCreatePayload nativePayload(ApprovalRequestV1 request) {
        Objects.requireNonNull(request, "request");
        return new NativeCreatePayload(
                request.requestId(), request.subjectRef(), request.subjectRevision(),
                request.requesterRef(), request.requestedAction(), request.payloadRef(),
                request.payloadDigest(), request.requestedAt(), request.correlationId(),
                "Employee change request " + request.requestId(),
                "Requested action " + request.requestedAction()
                        + " for " + request.subjectRef(),
                "NORMAL");
    }

    /** Result of service-native requester, workflow, and form resolution. */
    public record CreateAuthority(
            UUID requesterPersonPublicId,
            long requesterUserId,
            int activeSameTenantRequesterMatches,
            String workflowKey,
            long workflowVersion,
            UUID workflowVersionId,
            boolean publishedEffectiveTypedQuorumWorkflow,
            UUID formVersionId,
            int activeEffectivePublishedFormBindings,
            long frozenPolicyVersion) {
        public CreateAuthority {
            Objects.requireNonNull(requesterPersonPublicId, "requesterPersonPublicId");
            if (requesterUserId < 1 || frozenPolicyVersion < 1) {
                throw new IllegalArgumentException("authority revisions and ids must be positive");
            }
            Objects.requireNonNull(workflowKey, "workflowKey");
            Objects.requireNonNull(workflowVersionId, "workflowVersionId");
            Objects.requireNonNull(formVersionId, "formVersionId");
        }
    }

    /** Must query native Approval/Auth authority; no browser or ThreadLocal actor fallback. */
    @FunctionalInterface
    public interface CreateAuthorityResolver {
        CreateAuthority resolve(long tenantId, ApprovalRequestV1 request);
    }

    public record PersistedResponse<T>(T body, byte[] exactResponseBytes) {
        public PersistedResponse {
            Objects.requireNonNull(body, "body");
            exactResponseBytes = Objects.requireNonNull(
                    exactResponseBytes, "exactResponseBytes").clone();
            if (exactResponseBytes.length == 0) {
                throw new IllegalArgumentException("exactResponseBytes must not be empty");
            }
        }

        @Override
        public byte[] exactResponseBytes() {
            return exactResponseBytes.clone();
        }
    }

    public record CreateAliases(
            long tenantId,
            String subjectRef,
            long subjectRevision,
            UUID requestId,
            String idempotencyKey) {
    }

    @FunctionalInterface
    public interface CreateIdempotencyStore<R> {
        R execute(
                CreateAliases aliases,
                String commandDigest,
                byte[] exactRequestBytes,
                Supplier<R> operation);
    }

    @FunctionalInterface
    public interface CallbackIdempotencyStore<R> {
        R execute(
                long tenantId,
                UUID decisionEventId,
                String commandDigest,
                byte[] exactRequestBytes,
                Supplier<R> operation);
    }

    @FunctionalInterface
    public interface CreateAndSubmitHandler {
        PersistedResponse<CreateAndSubmitReceiptV2> createAndSubmit(
                long tenantId,
                ApprovalRequestV1 request,
                NativeCreatePayload nativePayload,
                CreateAuthority authority,
                String commandDigest,
                byte[] exactRequestBytes);
    }

    @FunctionalInterface
    public interface DecisionCallbackHandler {
        PersistedResponse<CommandReceiptV1> record(
                long tenantId,
                UUID requestPublicId,
                ApprovalDecisionCallbackV1 request,
                String commandDigest,
                byte[] exactRequestBytes);
    }

    public static final class CreateAndSubmitDispatcher {
        private final ObjectMapper mapper;
        private final Clock clock;
        private final HrisWorkloadProofV2.SigningKeyResolver keys;
        private final HrisWorkloadProofV2.TenantAdmissionVerifier tenants;
        private final HrisWorkloadProofV2.ReplayStore replays;
        private final CreateIdempotencyStore<PersistedResponse<CreateAndSubmitReceiptV2>> ledger;
        private final CreateAuthorityResolver authorityResolver;
        private final CreateAndSubmitHandler handler;

        public CreateAndSubmitDispatcher(
                ObjectMapper mapper,
                Clock clock,
                HrisWorkloadProofV2.SigningKeyResolver keys,
                HrisWorkloadProofV2.TenantAdmissionVerifier tenants,
                HrisWorkloadProofV2.ReplayStore replays,
                CreateIdempotencyStore<PersistedResponse<CreateAndSubmitReceiptV2>> ledger,
                CreateAuthorityResolver authorityResolver,
                CreateAndSubmitHandler handler) {
            this.mapper = Objects.requireNonNull(mapper);
            this.clock = Objects.requireNonNull(clock);
            this.keys = Objects.requireNonNull(keys);
            this.tenants = Objects.requireNonNull(tenants);
            this.replays = Objects.requireNonNull(replays);
            this.ledger = Objects.requireNonNull(ledger);
            this.authorityResolver = Objects.requireNonNull(authorityResolver);
            this.handler = Objects.requireNonNull(handler);
        }

        public PersistedResponse<CreateAndSubmitReceiptV2> dispatch(
                java.util.List<HrisWorkloadProofV2.Header> headers,
                byte[] exactBodyBytes) {
            ApprovalRequestV1 body = decode(mapper, exactBodyBytes, ApprovalRequestV1.class);
            if (!"PENDING".equals(body.state())) {
                throw denied("ATOMIC_CREATE_AND_SUBMIT_REQUIRES_PENDING_STATE");
            }
            UUID requesterPersonId = requesterPersonId(body.requesterRef());
            var admission = HrisWorkloadProofV2.verify(
                    mapper, clock, CREATE_AND_SUBMIT.verificationContract(),
                    CREATE_AND_SUBMIT.path(null),
                    headers, exactBodyBytes, keys, tenants, replays);
            CreateAuthority authority = Objects.requireNonNull(
                    authorityResolver.resolve(admission.tenantId(), body),
                    "resolved create authority");
            requireCreateAuthority(body, requesterPersonId, authority);
            CreateAliases aliases = new CreateAliases(
                    admission.tenantId(), body.subjectRef(), body.subjectRevision(),
                    body.requestId(), body.idempotencyKey());
            PersistedResponse<CreateAndSubmitReceiptV2> response = ledger.execute(
                    aliases, admission.commandDigest(), exactBodyBytes,
                    () -> handler.createAndSubmit(
                            admission.tenantId(), body, nativePayload(body), authority,
                            admission.commandDigest(), exactBodyBytes.clone()));
            CreateAndSubmitReceiptV2 receipt = response.body();
            if (!receipt.requestId().equals(body.requestId())) {
                throw denied("CREATE_AND_SUBMIT_RECEIPT_MISMATCH");
            }
            requireExactResponse(mapper, response, CreateAndSubmitReceiptV2.class);
            return response;
        }
    }

    public static final class DecisionCallbackDispatcher {
        private final ObjectMapper mapper;
        private final Clock clock;
        private final HrisWorkloadProofV2.SigningKeyResolver keys;
        private final HrisWorkloadProofV2.TenantAdmissionVerifier tenants;
        private final HrisWorkloadProofV2.ReplayStore replays;
        private final CallbackIdempotencyStore<PersistedResponse<CommandReceiptV1>> ledger;
        private final DecisionCallbackHandler handler;

        public DecisionCallbackDispatcher(
                ObjectMapper mapper,
                Clock clock,
                HrisWorkloadProofV2.SigningKeyResolver keys,
                HrisWorkloadProofV2.TenantAdmissionVerifier tenants,
                HrisWorkloadProofV2.ReplayStore replays,
                CallbackIdempotencyStore<PersistedResponse<CommandReceiptV1>> ledger,
                DecisionCallbackHandler handler) {
            this.mapper = Objects.requireNonNull(mapper);
            this.clock = Objects.requireNonNull(clock);
            this.keys = Objects.requireNonNull(keys);
            this.tenants = Objects.requireNonNull(tenants);
            this.replays = Objects.requireNonNull(replays);
            this.ledger = Objects.requireNonNull(ledger);
            this.handler = Objects.requireNonNull(handler);
        }

        public PersistedResponse<CommandReceiptV1> dispatch(
                UUID requestPublicId,
                java.util.List<HrisWorkloadProofV2.Header> headers,
                byte[] exactBodyBytes) {
            Objects.requireNonNull(requestPublicId, "requestPublicId");
            ApprovalDecisionCallbackV1 body = decodeCallback(mapper, exactBodyBytes);
            String path = DECISION_CALLBACK.path(requestPublicId);
            var admission = HrisWorkloadProofV2.verify(
                    mapper, clock, DECISION_CALLBACK.verificationContract(), path,
                    headers, exactBodyBytes,
                    keys, tenants, replays);
            PersistedResponse<CommandReceiptV1> response = ledger.execute(
                    admission.tenantId(), body.decisionEventId(),
                    admission.commandDigest(), exactBodyBytes,
                    () -> handler.record(
                            admission.tenantId(), requestPublicId, body,
                            admission.commandDigest(), exactBodyBytes.clone()));
            requireCallbackReceipt(response.body(), body, admission.commandDigest());
            requireExactResponse(mapper, response, CommandReceiptV1.class);
            return response;
        }
    }

    public static <T> OutboundRequest<T> outbound(
            ObjectMapper mapper,
            Endpoint endpoint,
            UUID requestPublicId,
            long tenantId,
            T body) {
        if (tenantId < 1) {
            throw new IllegalArgumentException("tenantId must be positive");
        }
        byte[] bytes = encode(mapper, body);
        String path = endpoint.path(requestPublicId);
        String bodyDigest = sha256(bytes);
        return new OutboundRequest<>(
                endpoint, path, tenantId, body, bytes, bodyDigest,
                commandDigest(endpoint, path, tenantId, bodyDigest));
    }

    public record OutboundRequest<T>(
            Endpoint endpoint,
            String path,
            long tenantId,
            T body,
            byte[] exactBodyBytes,
            String bodySha256,
            String commandDigest) {
        public OutboundRequest {
            Objects.requireNonNull(endpoint, "endpoint");
            Objects.requireNonNull(path, "path");
            if (tenantId < 1) {
                throw new IllegalArgumentException("tenantId must be positive");
            }
            Objects.requireNonNull(body, "body");
            exactBodyBytes = Objects.requireNonNull(exactBodyBytes, "exactBodyBytes").clone();
            requireDigest(bodySha256, "bodySha256");
            requireDigest(commandDigest, "commandDigest");
        }

        @Override
        public byte[] exactBodyBytes() {
            return exactBodyBytes.clone();
        }
    }

    public static String commandDigest(
            Endpoint endpoint,
            String exactPath,
            long tenantId,
            String bodySha256) {
        return Objects.requireNonNull(endpoint, "endpoint")
                .verificationContract()
                .commandDigest(exactPath, tenantId, bodySha256);
    }

    public static String sha256(byte[] bytes) {
        return HrisApprovalVerificationContractV2.sha256(bytes);
    }

    static BridgeFailure denied(String reason) {
        return new BridgeFailure(403, reason);
    }

    static BridgeFailure conflict(String reason) {
        return new BridgeFailure(409, reason);
    }

    public static final class BridgeFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int httpStatus;
        private final String reason;

        private BridgeFailure(int httpStatus, String reason) {
            super(reason);
            this.httpStatus = httpStatus;
            this.reason = reason;
        }

        public int httpStatus() {
            return httpStatus;
        }

        public String reason() {
            return reason;
        }
    }

    private static void requireCreateAuthority(
            ApprovalRequestV1 request,
            UUID requesterPersonId,
            CreateAuthority authority) {
        if (!requesterPersonId.equals(authority.requesterPersonPublicId())
                || authority.activeSameTenantRequesterMatches() != 1
                || !request.workflowKey().equals(authority.workflowKey())
                || request.workflowVersion() != authority.workflowVersion()
                || !authority.publishedEffectiveTypedQuorumWorkflow()
                || authority.activeEffectivePublishedFormBindings() != 1) {
            throw conflict("PINNED_REQUESTER_WORKFLOW_OR_FORM_AUTHORITY_UNAVAILABLE");
        }
    }

    private static UUID requesterPersonId(String requesterRef) {
        Matcher matcher = REQUESTER_REF.matcher(
                Objects.requireNonNull(requesterRef, "requesterRef"));
        if (!matcher.matches()) {
            throw conflict("CANONICAL_PERSON_REQUESTER_REF_REQUIRED");
        }
        return UUID.fromString(matcher.group(1));
    }

    private static void requireCallbackReceipt(
            CommandReceiptV1 receipt,
            ApprovalDecisionCallbackV1 request,
            String commandDigest) {
        if (!CALLBACK_COMMAND_TYPE.equals(receipt.commandType())
                || !CALLBACK_ORIGINATING_ACTION.equals(receipt.originatingAction())
                || !CALLBACK_PURPOSE.equals(receipt.purposeCode())
                || !"SUCCEEDED".equals(receipt.status())
                || !request.correlationId().equals(receipt.correlationId())
                || !commandDigest.equals(receipt.requestDigest())) {
            throw denied("CALLBACK_COMMAND_RECEIPT_MISMATCH");
        }
    }

    private static ApprovalDecisionCallbackV1 decodeCallback(
            ObjectMapper mapper,
            byte[] exactBytes) {
        JsonNode node = strictObject(mapper, exactBytes, CALLBACK_FIELDS);
        try {
            return new ApprovalDecisionCallbackV1(
                    UUID.fromString(requiredText(node, "approvalRequestId")),
                    UUID.fromString(requiredText(node, "decisionEventId")),
                    requiredText(node, "decision"),
                    Instant.parse(requiredText(node, "decidedAt")),
                    UUID.fromString(requiredText(node, "decisionActorPublicId")),
                    requiredLong(node, "approvalPolicyRevision"),
                    UUID.fromString(requiredText(node, "correlationId")),
                    requiredText(node, "payloadDigest"));
        } catch (RuntimeException exception) {
            throw conflict("CALLBACK_BODY_INVALID");
        }
    }

    private static JsonNode strictObject(
            ObjectMapper mapper,
            byte[] exactBytes,
            Set<String> exactFields) {
        requireUtf8(exactBytes);
        try (JsonParser parser = mapper.getFactory().createParser(exactBytes)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode node = mapper.readTree(parser);
            if (node == null || !node.isObject() || parser.nextToken() != null) {
                throw conflict("CALLBACK_BODY_INVALID");
            }
            Set<String> fields = new HashSet<>();
            node.fieldNames().forEachRemaining(fields::add);
            if (!fields.equals(exactFields)) {
                throw conflict("CALLBACK_BODY_INVALID");
            }
            return node;
        } catch (IOException exception) {
            throw conflict("CALLBACK_BODY_INVALID");
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw conflict("CALLBACK_BODY_INVALID");
        }
        return value.textValue();
    }

    private static long requiredLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw conflict("CALLBACK_BODY_INVALID");
        }
        return value.longValue();
    }

    private static <T> T decode(
            ObjectMapper mapper,
            byte[] exactBytes,
            Class<T> type) {
        requireUtf8(exactBytes);
        try {
            return mapper.readValue(exactBytes, type);
        } catch (IOException | RuntimeException exception) {
            throw conflict("CANONICAL_REQUEST_BODY_INVALID");
        }
    }

    private static byte[] encode(ObjectMapper mapper, Object body) {
        try {
            return mapper.writeValueAsBytes(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("body cannot be encoded", exception);
        }
    }

    private static void requireUtf8(byte[] bytes) {
        Objects.requireNonNull(bytes, "exact request bytes");
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException exception) {
            throw conflict("EXACT_UTF8_REQUEST_BODY_REQUIRED");
        }
    }

    private static <T> void requireExactResponse(
            ObjectMapper mapper,
            PersistedResponse<T> response,
            Class<T> type) {
        T decoded = decode(mapper, response.exactResponseBytes(), type);
        if (!response.body().equals(decoded)) {
            throw denied("PERSISTED_RESPONSE_BYTES_MISMATCH");
        }
    }

    private static void requireDigest(String value, String field) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
    }

}
