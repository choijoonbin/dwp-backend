package com.dwp.platform.contract;

import com.dwp.platform.contracts.hris.approval.v2.HrisApprovalBridgeV2;
import com.dwp.platform.contracts.hris.approval.v2.HrisApprovalBridgeStoresV2;
import com.dwp.platform.contracts.hris.approval.v2.HrisWorkloadProofV2;
import com.dwp.platform.contracts.hris.command.v1.CommandReceiptV1;
import com.dwp.platform.contracts.hris.generated.ApprovalRequestV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.dwp.platform.contracts.hris.approval.v2.HrisApprovalBridgeV2.CREATE_AND_SUBMIT;
import static com.dwp.platform.contracts.hris.approval.v2.HrisApprovalBridgeV2.DECISION_CALLBACK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HrisApprovalBridgeV2Test {

    private static final Instant NOW = Instant.parse("2026-10-12T03:00:00Z");
    private static KeyPair keyPair;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicInteger proofSequence = new AtomicInteger(100);

    @BeforeAll
    static void createSigningKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    @Test
    void admitsExactProofAndReplaysOriginalReceiptAcrossAllCreateAliases() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        var dispatcher = createDispatcher(
                new HrisApprovalBridgeStoresV2.Create<>(),
                authority(), executions);
        ApprovalRequestV1 request = request(uuid(1), "subject:employee-change-1",
                "employee-change-command-1", "a".repeat(64));
        byte[] bytes = mapper.writeValueAsBytes(request);

        var first = dispatcher.dispatch(proof(CREATE_AND_SUBMIT,
                CREATE_AND_SUBMIT.path(null), bytes), bytes);
        var replay = dispatcher.dispatch(proof(CREATE_AND_SUBMIT,
                CREATE_AND_SUBMIT.path(null), bytes), bytes);

        assertThat(replay).isSameAs(first);
        assertThat(executions).hasValue(1);
        assertThat(first.body().requestId()).isEqualTo(request.requestId());

        ApprovalRequestV1 changed = request(uuid(1), "subject:employee-change-1",
                "employee-change-command-1", "c".repeat(64));
        byte[] changedBytes = mapper.writeValueAsBytes(changed);
        assertThatThrownBy(() -> dispatcher.dispatch(proof(
                CREATE_AND_SUBMIT, CREATE_AND_SUBMIT.path(null), changedBytes),
                changedBytes)).isInstanceOfSatisfying(
                        HrisApprovalBridgeV2.BridgeFailure.class,
                        failure -> {
                            assertThat(failure.httpStatus()).isEqualTo(409);
                            assertThat(failure.reason())
                                    .isEqualTo("IDEMPOTENCY_IDENTITY_COMMAND_MISMATCH");
                        });
    }

    @Test
    void rejectsAliasCollisionBrowserTenantHeadersAndDerivedAuthMismatch() throws Exception {
        var ledger = new HrisApprovalBridgeStoresV2.Create<
                HrisApprovalBridgeV2.PersistedResponse<
                        HrisApprovalBridgeV2.CreateAndSubmitReceiptV2>>();
        var dispatcher = createDispatcher(ledger, authority(), new AtomicInteger());
        ApprovalRequestV1 first = request(uuid(10), "subject:first",
                "shared-idempotency", "a".repeat(64));
        byte[] firstBytes = mapper.writeValueAsBytes(first);
        dispatcher.dispatch(proof(CREATE_AND_SUBMIT,
                CREATE_AND_SUBMIT.path(null), firstBytes), firstBytes);

        ApprovalRequestV1 aliasCollision = request(uuid(11), "subject:second",
                "shared-idempotency", "b".repeat(64));
        byte[] collisionBytes = mapper.writeValueAsBytes(aliasCollision);
        assertThatThrownBy(() -> dispatcher.dispatch(proof(
                CREATE_AND_SUBMIT, CREATE_AND_SUBMIT.path(null), collisionBytes),
                collisionBytes)).isInstanceOfSatisfying(
                        HrisApprovalBridgeV2.BridgeFailure.class,
                        failure -> assertThat(failure.httpStatus()).isEqualTo(409));

        List<HrisWorkloadProofV2.Header> browserHeaders = new ArrayList<>(proof(
                CREATE_AND_SUBMIT, CREATE_AND_SUBMIT.path(null), collisionBytes));
        browserHeaders.add(new HrisWorkloadProofV2.Header("X-DWP-Tenant-ID", "7"));
        assertThatThrownBy(() -> dispatcher.dispatch(browserHeaders, collisionBytes))
                .isInstanceOfSatisfying(HrisApprovalBridgeV2.BridgeFailure.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo("BROWSER_END_USER_OR_TENANT_HEADER_FORBIDDEN"));

        var wrongDerivedAuth = createDispatcher(
                new HrisApprovalBridgeStoresV2.Create<>(),
                authority(), new AtomicInteger(),
                HrisWorkloadProofV2.WorkloadAuthentication.MTLS_SPIFFE);
        assertThatThrownBy(() -> wrongDerivedAuth.dispatch(proof(
                CREATE_AND_SUBMIT, CREATE_AND_SUBMIT.path(null), collisionBytes),
                collisionBytes)).isInstanceOfSatisfying(
                        HrisApprovalBridgeV2.BridgeFailure.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo("DERIVED_WORKLOAD_AUTHENTICATION_MISMATCH"));
    }

    @Test
    void mapsUnknownSigningKeyResolutionToStableForbiddenFailure() throws Exception {
        ApprovalRequestV1 request = request(uuid(12), "subject:unknown-key",
                "unknown-key-command", "a".repeat(64));
        byte[] bytes = mapper.writeValueAsBytes(request);
        List<HrisWorkloadProofV2.Header> headers = proof(
                CREATE_AND_SUBMIT, CREATE_AND_SUBMIT.path(null), bytes);

        assertThatThrownBy(() -> HrisWorkloadProofV2.verify(
                mapper, Clock.fixed(NOW, ZoneOffset.UTC), CREATE_AND_SUBMIT,
                CREATE_AND_SUBMIT.path(null), headers, bytes,
                keyId -> {
                    throw new IllegalArgumentException("unknown key");
                },
                (endpoint, path, caller, tenant) -> true,
                new HrisApprovalBridgeStoresV2.Replay()))
                .isInstanceOfSatisfying(HrisApprovalBridgeV2.BridgeFailure.class,
                        failure -> {
                            assertThat(failure.httpStatus()).isEqualTo(403);
                            assertThat(failure.reason())
                                    .isEqualTo("WORKLOAD_SIGNING_KEY_UNTRUSTED");
                        });

        assertThatThrownBy(() -> HrisWorkloadProofV2.verify(
                mapper, Clock.fixed(NOW, ZoneOffset.UTC), CREATE_AND_SUBMIT,
                CREATE_AND_SUBMIT.path(null), headers, bytes,
                keyId -> null,
                (endpoint, path, caller, tenant) -> true,
                new HrisApprovalBridgeStoresV2.Replay()))
                .isInstanceOfSatisfying(HrisApprovalBridgeV2.BridgeFailure.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo("WORKLOAD_SIGNING_KEY_UNTRUSTED"));
    }

    @Test
    void bindsRequesterAndPinnedWorkflowAuthorityWithoutCurrentVersionFallback() throws Exception {
        ApprovalRequestV1 invalidRequester = new ApprovalRequestV1(
                uuid(20), "hris.employee-change.v1", 1,
                "subject:change-20", 4, "user:102", "EMPLOYEE_CHANGE_APPLY",
                "hris-change:20", "a".repeat(64), "PENDING", NOW.minusSeconds(2),
                uuid(21), "employee-change-command-20");
        byte[] invalidBytes = mapper.writeValueAsBytes(invalidRequester);
        var dispatcher = createDispatcher(
                new HrisApprovalBridgeStoresV2.Create<>(),
                authority(), new AtomicInteger());

        assertThatThrownBy(() -> dispatcher.dispatch(List.of(), invalidBytes))
                .isInstanceOfSatisfying(HrisApprovalBridgeV2.BridgeFailure.class,
                        failure -> {
                            assertThat(failure.httpStatus()).isEqualTo(409);
                            assertThat(failure.reason())
                                    .isEqualTo("CANONICAL_PERSON_REQUESTER_REF_REQUIRED");
                        });

        HrisApprovalBridgeV2.CreateAuthority staleCurrentVersion =
                new HrisApprovalBridgeV2.CreateAuthority(
                        uuid(102), 99, 1, "hris.employee-change.v1", 2,
                        uuid(301), true, uuid(302), 1, 7);
        var staleDispatcher = createDispatcher(
                new HrisApprovalBridgeStoresV2.Create<>(),
                staleCurrentVersion, new AtomicInteger());
        ApprovalRequestV1 valid = request(uuid(22), "subject:change-22",
                "employee-change-command-22", "a".repeat(64));
        byte[] validBytes = mapper.writeValueAsBytes(valid);
        assertThatThrownBy(() -> staleDispatcher.dispatch(proof(
                CREATE_AND_SUBMIT, CREATE_AND_SUBMIT.path(null), validBytes),
                validBytes)).isInstanceOfSatisfying(
                        HrisApprovalBridgeV2.BridgeFailure.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo("PINNED_REQUESTER_WORKFLOW_OR_FORM_AUTHORITY_UNAVAILABLE"));
    }

    @Test
    void derivesCallbackFromFrozenVoteAndReturnsStrictDigestBoundReceipt() throws Exception {
        var frozen = new HrisApprovalBridgeV2.FrozenTerminalDecision(
                uuid(40), uuid(41), "APPROVED", NOW.minusSeconds(1), uuid(42),
                17, uuid(43), "d".repeat(64));
        var callback = HrisApprovalBridgeV2.callbackFromFrozenDecision(frozen);
        assertThat(callback.approvalPolicyRevision()).isEqualTo(17);
        assertThat(callback.decisionActorPublicId()).isEqualTo(uuid(42));
        byte[] body = mapper.writeValueAsBytes(callback);
        UUID requestPublicId = uuid(44);
        var ledger = new HrisApprovalBridgeStoresV2.Callback<
                HrisApprovalBridgeV2.PersistedResponse<CommandReceiptV1>>();
        var dispatcher = new HrisApprovalBridgeV2.DecisionCallbackDispatcher(
                mapper, Clock.fixed(NOW, ZoneOffset.UTC),
                keyResolver("dwp-approval-server",
                        HrisWorkloadProofV2.WorkloadAuthentication.CLIENT_CREDENTIAL_JWT),
                (endpoint, path, caller, tenant) -> tenant == 7,
                new HrisApprovalBridgeStoresV2.Replay(), ledger,
                (tenant, pathId, request, digest, exactBytes) -> persisted(
                        new CommandReceiptV1(
                                uuid(45), HrisApprovalBridgeV2.CALLBACK_COMMAND_TYPE,
                                HrisApprovalBridgeV2.CALLBACK_ORIGINATING_ACTION,
                                uuid(46), "e".repeat(64), 3,
                                HrisApprovalBridgeV2.CALLBACK_PURPOSE, 5, "SUCCEEDED",
                                request.correlationId(), digest, NOW)));
        String path = DECISION_CALLBACK.path(requestPublicId);

        var first = dispatcher.dispatch(requestPublicId,
                proof(DECISION_CALLBACK, path, body), body);
        var replay = dispatcher.dispatch(requestPublicId,
                proof(DECISION_CALLBACK, path, body), body);

        assertThat(replay).isSameAs(first);
        assertThat(first.body().requestDigest()).isEqualTo(
                HrisApprovalBridgeV2.commandDigest(
                        DECISION_CALLBACK, path, 7, HrisApprovalBridgeV2.sha256(body)));
        assertThat(HrisApprovalBridgeV2.commandDigest(
                DECISION_CALLBACK, DECISION_CALLBACK.path(uuid(47)), 7,
                HrisApprovalBridgeV2.sha256(body)))
                .isNotEqualTo(first.body().requestDigest());
    }

    private HrisApprovalBridgeV2.CreateAndSubmitDispatcher createDispatcher(
            HrisApprovalBridgeV2.CreateIdempotencyStore<
                    HrisApprovalBridgeV2.PersistedResponse<
                            HrisApprovalBridgeV2.CreateAndSubmitReceiptV2>> ledger,
            HrisApprovalBridgeV2.CreateAuthority authority,
            AtomicInteger executions) {
        return createDispatcher(ledger, authority, executions,
                HrisWorkloadProofV2.WorkloadAuthentication.CLIENT_CREDENTIAL_JWT);
    }

    private HrisApprovalBridgeV2.CreateAndSubmitDispatcher createDispatcher(
            HrisApprovalBridgeV2.CreateIdempotencyStore<
                    HrisApprovalBridgeV2.PersistedResponse<
                            HrisApprovalBridgeV2.CreateAndSubmitReceiptV2>> ledger,
            HrisApprovalBridgeV2.CreateAuthority authority,
            AtomicInteger executions,
            HrisWorkloadProofV2.WorkloadAuthentication derivedAuthentication) {
        return new HrisApprovalBridgeV2.CreateAndSubmitDispatcher(
                mapper, Clock.fixed(NOW, ZoneOffset.UTC),
                keyResolver("dwp-people-server", derivedAuthentication),
                (endpoint, path, caller, tenant) -> tenant == 7,
                new HrisApprovalBridgeStoresV2.Replay(), ledger,
                (tenant, request) -> authority,
                (tenant, request, nativePayload, resolved, digest, exactBytes) -> {
                    executions.incrementAndGet();
                    return persisted(new HrisApprovalBridgeV2.CreateAndSubmitReceiptV2(
                            request.requestId(), 1, "PENDING", "f".repeat(64)));
                });
    }

    private HrisWorkloadProofV2.SigningKeyResolver keyResolver(
            String workloadId,
            HrisWorkloadProofV2.WorkloadAuthentication authentication) {
        return keyId -> new HrisWorkloadProofV2.VerifiedSigningKey(
                keyPair.getPublic(), authentication, workloadId);
    }

    private List<HrisWorkloadProofV2.Header> proof(
            HrisApprovalBridgeV2.Endpoint endpoint,
            String path,
            byte[] body) throws Exception {
        int sequence = proofSequence.incrementAndGet();
        ObjectNode jose = mapper.createObjectNode();
        jose.put("alg", "RS256");
        jose.put("typ", "JWT");
        jose.put("kid", "hris-test-rsa-1");
        ObjectNode claims = mapper.createObjectNode();
        claims.put("iss", endpoint.callerWorkloadId());
        claims.put("aud", endpoint.ownerWorkloadId());
        claims.put("sub", endpoint.callerWorkloadId());
        claims.put("iat", NOW.minusSeconds(1).getEpochSecond());
        claims.put("nbf", NOW.minusSeconds(1).getEpochSecond());
        claims.put("exp", NOW.plusSeconds(20).getEpochSecond());
        claims.put("jti", uuid(1000 + sequence).toString());
        claims.put("requestNonce", uuid(2000 + sequence).toString());
        claims.put("bindingId", endpoint.bindingId());
        claims.put("purpose", endpoint.purpose());
        claims.put("method", endpoint.method());
        claims.put("path", path);
        claims.put("tenantId", 7);
        claims.put("requestBodySha256", HrisApprovalBridgeV2.sha256(body));
        claims.put("authn", "CLIENT_CREDENTIAL_JWT");
        String compact = sign(jose, claims);
        return HrisWorkloadProofV2.headers(endpoint.callerWorkloadId(), compact);
    }

    private String sign(ObjectNode header, ObjectNode claims) throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String encodedHeader = encoder.encodeToString(mapper.writeValueAsBytes(header));
        String encodedClaims = encoder.encodeToString(mapper.writeValueAsBytes(claims));
        String signingInput = encodedHeader + "." + encodedClaims;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + encoder.encodeToString(signature.sign());
    }

    private <T> HrisApprovalBridgeV2.PersistedResponse<T> persisted(T value) {
        try {
            return new HrisApprovalBridgeV2.PersistedResponse<>(
                    value, mapper.writeValueAsBytes(value));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static HrisApprovalBridgeV2.CreateAuthority authority() {
        return new HrisApprovalBridgeV2.CreateAuthority(
                uuid(102), 99, 1, "hris.employee-change.v1", 1,
                uuid(301), true, uuid(302), 1, 7);
    }

    private static ApprovalRequestV1 request(
            UUID requestId,
            String subjectRef,
            String idempotencyKey,
            String payloadDigest) {
        return new ApprovalRequestV1(
                requestId, "hris.employee-change.v1", 1, subjectRef, 4,
                "person:" + uuid(102), "EMPLOYEE_CHANGE_APPLY",
                "hris-change:" + requestId, payloadDigest, "PENDING",
                NOW.minusSeconds(2), uuid(2), idempotencyKey);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
