package com.dwp.platform.contracts.hris.approval.v1;

import com.dwp.platform.contracts.hris.generated.ApprovalRequestV1;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Token-before transport and idempotency contract for the HRIS People/Approval
 * bridge. Token parsing remains owned by the eventual service transport adapter;
 * only verified evidence may cross this isolated dispatcher boundary.
 */
public final class HrisApprovalBridgeV1 {

    public static final Endpoint CREATE_AND_SUBMIT = new Endpoint(
            "SVC-PEP-APR-001",
            "dwp-approval-server",
            "dwp-people-server",
            "POST",
            "/internal/hris-approval/v1/approval-requests",
            "dwp-approval-server",
            "HRIS_EMPLOYEE_CHANGE_APPROVAL_REQUEST_SUBMIT");

    public static final Endpoint DECISION_CALLBACK = new Endpoint(
            "SVC-PEP-HRM-001",
            "dwp-people-server",
            "dwp-approval-server",
            "POST",
            "/internal/hris-people/v1/employee-change-requests/{requestPublicId}/approval-decisions",
            "dwp-people-server",
            "HRIS_APPROVAL_DECISION_CALLBACK");

    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9._-]{0,79}$");
    private static final Pattern CALLBACK_PATH = Pattern.compile(
            "^/internal/hris-people/v1/employee-change-requests/"
                    + "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})"
                    + "/approval-decisions$");
    private static final Set<String> PROHIBITED_HEADERS = Set.of(
            "authorization", "cookie", "origin", "referer",
            "x-dwp-user-id", "x-dwp-person-public-id", "x-dwp-display-name-b64",
            "x-forwarded-user", "x-forwarded-email");

    private HrisApprovalBridgeV1() {
    }

    public enum WorkloadAuthentication {
        MTLS_SPIFFE,
        CLIENT_CREDENTIAL_JWT
    }

    public record Endpoint(
            String bindingId,
            String ownerWorkloadId,
            String callerWorkloadId,
            String method,
            String pathTemplate,
            String audience,
            String purpose) {
        public Endpoint {
            Objects.requireNonNull(bindingId, "bindingId");
            Objects.requireNonNull(ownerWorkloadId, "ownerWorkloadId");
            Objects.requireNonNull(callerWorkloadId, "callerWorkloadId");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(pathTemplate, "pathTemplate");
            Objects.requireNonNull(audience, "audience");
            Objects.requireNonNull(purpose, "purpose");
        }

        public String resolve(UUID requestPublicId) {
            if (!pathTemplate.contains("{requestPublicId}")) {
                if (requestPublicId != null) {
                    throw new IllegalArgumentException(
                            "requestPublicId is forbidden for an exact endpoint");
                }
                return pathTemplate;
            }
            return pathTemplate.replace(
                    "{requestPublicId}", Objects.requireNonNull(requestPublicId).toString());
        }
    }

    /** Evidence already authenticated by the future mTLS/JWT transport adapter. */
    public record AdmissionEvidence(
            String bindingId,
            String method,
            String path,
            String callerWorkloadId,
            String audience,
            long tenantId,
            String purpose,
            String bodySha256,
            Instant issuedAt,
            Instant expiresAt,
            UUID proofId,
            UUID requestNonce,
            WorkloadAuthentication authentication,
            boolean workloadAuthenticated,
            boolean audiencePinned,
            boolean signedTenantClaim,
            boolean ownerTenantGrantRevalidated,
            boolean targetTenantMatched,
            boolean callerTenantOverridePresent,
            boolean tls13) {
    }

    /** Bounded response for atomic PDX-010 create-and-submit. */
    public record CreateAndSubmitReceiptV1(
            UUID requestId,
            long requestRevision,
            String state,
            String receiptDigest) {
        public CreateAndSubmitReceiptV1 {
            Objects.requireNonNull(requestId, "requestId");
            if (requestRevision < 1) {
                throw new IllegalArgumentException("requestRevision must be positive");
            }
            if (!Set.of("PENDING", "APPROVED", "REJECTED", "CANCELED", "EXPIRED")
                    .contains(state)) {
                throw new IllegalArgumentException("state is not canonical");
            }
            requireDigest(receiptDigest, "receiptDigest");
        }
    }

    /** Exact G2 Approval-to-People callback body. */
    public record ApprovalDecisionCallbackV1(
            UUID approvalRequestId,
            UUID decisionEventId,
            String decision,
            Instant decidedAt,
            UUID decisionActorPublicId,
            long approvalPolicyRevision,
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
                throw new IllegalArgumentException(
                        "approvalPolicyRevision must be positive");
            }
            Objects.requireNonNull(correlationId, "correlationId");
            requireDigest(payloadDigest, "payloadDigest");
        }
    }

    public record OutboundRequest<T>(
            Endpoint endpoint,
            String path,
            long tenantId,
            T body,
            byte[] bodyBytes,
            String bodySha256) {
        public OutboundRequest {
            Objects.requireNonNull(endpoint, "endpoint");
            Objects.requireNonNull(path, "path");
            if (tenantId < 1) {
                throw new IllegalArgumentException("tenantId must be positive");
            }
            Objects.requireNonNull(body, "body");
            bodyBytes = Objects.requireNonNull(bodyBytes, "bodyBytes").clone();
            requireDigest(bodySha256, "bodySha256");
        }

        @Override
        public byte[] bodyBytes() {
            return bodyBytes.clone();
        }
    }

    @FunctionalInterface
    public interface CreateAndSubmitHandler {
        CreateAndSubmitReceiptV1 createAndSubmit(
                long tenantId, ApprovalRequestV1 request);
    }

    @FunctionalInterface
    public interface DecisionCallbackHandler<R> {
        R record(long tenantId, UUID requestPublicId, ApprovalDecisionCallbackV1 request);
    }

    public interface ReplayStore {
        boolean reserve(UUID proofId, UUID requestNonce, Instant expiresAt);
    }

    public interface AtomicIdempotencyStore<R> {
        R execute(String key, String requestDigest, Supplier<R> operation);
    }

    /** Test/bootstrap store only; a durable owner-side ledger replaces it at token issuance. */
    public static final class InMemoryReplayStore implements ReplayStore {
        private final Map<UUID, Instant> reservations = new HashMap<>();

        @Override
        public synchronized boolean reserve(
                UUID proofId, UUID requestNonce, Instant expiresAt) {
            if (reservations.containsKey(proofId)
                    || reservations.containsKey(requestNonce)) {
                return false;
            }
            reservations.put(proofId, expiresAt);
            reservations.put(requestNonce, expiresAt);
            return true;
        }
    }

    /** Test/bootstrap store only; preserves atomic same-digest replay semantics. */
    public static final class InMemoryAtomicIdempotencyStore<R>
            implements AtomicIdempotencyStore<R> {
        private final Map<String, Entry<R>> entries = new HashMap<>();

        @Override
        public synchronized R execute(
                String key, String requestDigest, Supplier<R> operation) {
            Entry<R> prior = entries.get(key);
            if (prior != null) {
                if (!MessageDigest.isEqual(
                        prior.requestDigest().getBytes(StandardCharsets.US_ASCII),
                        requestDigest.getBytes(StandardCharsets.US_ASCII))) {
                    throw conflict("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
                }
                return prior.receipt();
            }
            R receipt = Objects.requireNonNull(operation.get(), "receipt");
            entries.put(key, new Entry<>(requestDigest, receipt));
            return receipt;
        }

        private record Entry<R>(String requestDigest, R receipt) {
        }
    }

    public static final class CreateAndSubmitDispatcher {
        private final ObjectMapper mapper;
        private final Clock clock;
        private final ReplayStore replay;
        private final AtomicIdempotencyStore<CreateAndSubmitReceiptV1> idempotency;
        private final CreateAndSubmitHandler handler;

        public CreateAndSubmitDispatcher(
                ObjectMapper mapper,
                Clock clock,
                ReplayStore replay,
                AtomicIdempotencyStore<CreateAndSubmitReceiptV1> idempotency,
                CreateAndSubmitHandler handler) {
            this.mapper = Objects.requireNonNull(mapper);
            this.clock = Objects.requireNonNull(clock);
            this.replay = Objects.requireNonNull(replay);
            this.idempotency = Objects.requireNonNull(idempotency);
            this.handler = Objects.requireNonNull(handler);
        }

        public CreateAndSubmitReceiptV1 dispatch(
                AdmissionEvidence evidence,
                Set<String> requestHeaderNames,
                ApprovalRequestV1 body) {
            Objects.requireNonNull(body, "body");
            if (!"PENDING".equals(body.state())) {
                throw denied("ATOMIC_CREATE_AND_SUBMIT_REQUIRES_PENDING_STATE");
            }
            byte[] bytes = encode(mapper, body);
            admit(CREATE_AND_SUBMIT, null, evidence, requestHeaderNames, bytes, clock, replay);
            String key = evidence.tenantId() + "\n" + body.subjectRef()
                    + "\n" + body.subjectRevision();
            CreateAndSubmitReceiptV1 receipt = idempotency.execute(
                    key, body.payloadDigest(),
                    () -> handler.createAndSubmit(evidence.tenantId(), body));
            if (!receipt.requestId().equals(body.requestId())
                    || !"PENDING".equals(receipt.state())) {
                throw denied("CREATE_AND_SUBMIT_RECEIPT_MISMATCH");
            }
            return receipt;
        }
    }

    public static final class DecisionCallbackDispatcher<R> {
        private final ObjectMapper mapper;
        private final Clock clock;
        private final ReplayStore replay;
        private final AtomicIdempotencyStore<R> idempotency;
        private final DecisionCallbackHandler<R> handler;

        public DecisionCallbackDispatcher(
                ObjectMapper mapper,
                Clock clock,
                ReplayStore replay,
                AtomicIdempotencyStore<R> idempotency,
                DecisionCallbackHandler<R> handler) {
            this.mapper = Objects.requireNonNull(mapper);
            this.clock = Objects.requireNonNull(clock);
            this.replay = Objects.requireNonNull(replay);
            this.idempotency = Objects.requireNonNull(idempotency);
            this.handler = Objects.requireNonNull(handler);
        }

        public R dispatch(
                UUID requestPublicId,
                AdmissionEvidence evidence,
                Set<String> requestHeaderNames,
                ApprovalDecisionCallbackV1 body) {
            Objects.requireNonNull(requestPublicId, "requestPublicId");
            Objects.requireNonNull(body, "body");
            byte[] bytes = encode(mapper, body);
            admit(DECISION_CALLBACK, requestPublicId, evidence,
                    requestHeaderNames, bytes, clock, replay);
            String key = evidence.tenantId() + "\n" + body.decisionEventId();
            String requestDigest = sha256(bytes);
            return idempotency.execute(key, requestDigest,
                    () -> handler.record(evidence.tenantId(), requestPublicId, body));
        }
    }

    public static OutboundRequest<ApprovalRequestV1> createAndSubmitRequest(
            ObjectMapper mapper, long tenantId, ApprovalRequestV1 body) {
        if (!"PENDING".equals(body.state())) {
            throw new IllegalArgumentException(
                    "PDX-010 create-and-submit body must already be PENDING");
        }
        return outbound(mapper, CREATE_AND_SUBMIT, null, tenantId, body);
    }

    public static OutboundRequest<ApprovalDecisionCallbackV1> decisionCallbackRequest(
            ObjectMapper mapper,
            long tenantId,
            UUID requestPublicId,
            ApprovalDecisionCallbackV1 body) {
        return outbound(mapper, DECISION_CALLBACK, requestPublicId, tenantId, body);
    }

    private static <T> OutboundRequest<T> outbound(
            ObjectMapper mapper,
            Endpoint endpoint,
            UUID requestPublicId,
            long tenantId,
            T body) {
        byte[] bytes = encode(mapper, body);
        return new OutboundRequest<>(endpoint, endpoint.resolve(requestPublicId),
                tenantId, body, bytes, sha256(bytes));
    }

    private static void admit(
            Endpoint endpoint,
            UUID requestPublicId,
            AdmissionEvidence evidence,
            Set<String> requestHeaderNames,
            byte[] body,
            Clock clock,
            ReplayStore replay) {
        if (evidence == null) {
            throw denied("WORKLOAD_EVIDENCE_REQUIRED");
        }
        rejectBrowserHeaders(requestHeaderNames);
        String exactPath = endpoint.resolve(requestPublicId);
        if (!endpoint.bindingId().equals(evidence.bindingId())
                || !endpoint.method().equals(evidence.method())
                || !exactPath.equals(evidence.path())
                || evidence.path().contains("?")
                || !endpoint.callerWorkloadId().equals(evidence.callerWorkloadId())
                || !endpoint.audience().equals(evidence.audience())
                || evidence.tenantId() < 1
                || !endpoint.purpose().equals(evidence.purpose())
                || !sha256(body).equals(evidence.bodySha256())
                || evidence.authentication() == null
                || !evidence.workloadAuthenticated()
                || !evidence.signedTenantClaim()
                || !evidence.ownerTenantGrantRevalidated()
                || !evidence.targetTenantMatched()
                || evidence.callerTenantOverridePresent()
                || !evidence.tls13()) {
            throw denied("EXACT_INTERNAL_BINDING_EVIDENCE_REQUIRED");
        }
        if (evidence.authentication() == WorkloadAuthentication.CLIENT_CREDENTIAL_JWT
                && !evidence.audiencePinned()) {
            throw denied("CLIENT_JWT_AUDIENCE_PIN_REQUIRED");
        }
        if (evidence.authentication() == WorkloadAuthentication.MTLS_SPIFFE
                && evidence.audiencePinned()) {
            throw denied("MTLS_SPIFFE_MUST_NOT_BORROW_JWT_AUDIENCE_PROOF");
        }
        Instant now = clock.instant();
        if (evidence.issuedAt() == null || evidence.expiresAt() == null
                || evidence.proofId() == null || evidence.requestNonce() == null
                || evidence.proofId().equals(evidence.requestNonce())
                || evidence.issuedAt().isAfter(now)
                || !evidence.expiresAt().isAfter(now)
                || !evidence.expiresAt().isAfter(evidence.issuedAt())
                || Duration.between(evidence.issuedAt(), evidence.expiresAt())
                .compareTo(Duration.ofSeconds(30)) > 0) {
            throw denied("FRESH_NON_REPLAYABLE_PROOF_REQUIRED");
        }
        if (!replay.reserve(
                evidence.proofId(), evidence.requestNonce(), evidence.expiresAt())) {
            throw denied("TRANSPORT_PROOF_REPLAYED");
        }
        if (endpoint == DECISION_CALLBACK
                && !CALLBACK_PATH.matcher(evidence.path()).matches()) {
            throw denied("CALLBACK_PATH_IDENTIFIER_INVALID");
        }
    }

    private static void rejectBrowserHeaders(Set<String> names) {
        if (names == null) {
            throw denied("REQUEST_HEADER_EVIDENCE_REQUIRED");
        }
        Set<String> normalized = new HashSet<>();
        for (String name : names) {
            if (name == null || !normalized.add(name.toLowerCase(Locale.ROOT))) {
                throw denied("DUPLICATE_OR_INVALID_HEADER_EVIDENCE");
            }
        }
        boolean prohibited = normalized.stream().anyMatch(name ->
                PROHIBITED_HEADERS.contains(name)
                        || name.startsWith("sec-fetch-")
                        || name.startsWith("sec-ch-ua"));
        if (prohibited) {
            throw denied("BROWSER_OR_END_USER_HEADER_FORBIDDEN");
        }
    }

    private static byte[] encode(ObjectMapper mapper, Object body) {
        try {
            return mapper.writeValueAsBytes(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Canonical body cannot be encoded", exception);
        }
    }

    public static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void requireDigest(String value, String field) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256");
        }
    }

    private static BridgeFailure denied(String reason) {
        return new BridgeFailure(403, reason);
    }

    private static BridgeFailure conflict(String reason) {
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
}
