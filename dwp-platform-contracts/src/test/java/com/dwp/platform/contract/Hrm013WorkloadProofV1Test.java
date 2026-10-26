package com.dwp.platform.contract;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.DrainContinuationKind;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.FailureCode;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.GrantUseMode;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.Header;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.Profile;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.ResolutionMode;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.TaskOwner;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.TransportIdentity;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.VerificationException;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.VerifiedSigningKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Hrm013WorkloadProofV1Test {

    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    private static final long TENANT_ID = 17;
    private static final UUID CORRELATION_ID = uuid(100);
    private static final String BUSINESS_KEY_ID = "hrm013-grant-key-1";
    private static KeyPair keyPair;
    private static KeyPair wrongKeyPair;
    private static String leafThumbprint;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        wrongKeyPair = generator.generateKeyPair();
        leafThumbprint = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256")
                        .digest("hrm013-test-leaf".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void verifiesExactNewWorkProofAndPreservesResolveReplayRecheckOrder() throws Exception {
        Profile profile = Hrm013WorkloadProofV1.taskDispatch(TaskOwner.TIME);
        byte[] body = canonicalBody(Map.of(
                "dispatchEventId", uuid(1).toString(),
                "taskType", "TIME_FINALIZATION"));
        Map<String, Object> claims = businessClaims(profile, body, GrantUseMode.NEW_WORK);
        addTaskDispatchClaims(claims, TaskOwner.TIME);
        String compact = compact(profile, claims, keyPair, BUSINESS_KEY_ID, true);
        List<String> order = new ArrayList<>();

        var verified = Hrm013WorkloadProofV1.verify(
                mapper, fixedClock(), profile, "POST", profile.exactPath(),
                Hrm013WorkloadProofV1.headers(profile, compact, CORRELATION_ID), body,
                transport(profile),
                (selected, selector, keyId) -> {
                    order.add("resolve");
                    assertThat(selector.tenantId()).isEqualTo(TENANT_ID);
                    assertThat(selector.authorityMode().drainExisting()).isFalse();
                    return new VerifiedSigningKey(
                            keyPair.getPublic(), keyId, profile.callerWorkloadId());
                },
                reservation -> {
                    order.add("reserve");
                    assertThat(reservation.proofIdSha256()).hasSize(64);
                    assertThat(reservation.requestNonceSha256()).hasSize(64);
                    assertThat(reservation.proofIdSha256())
                            .isNotEqualTo(reservation.requestNonceSha256());
                    assertThat(reservation.retainUntil())
                            .isEqualTo(NOW.plusSeconds(35));
                    return true;
                },
                (selected, key, proofClaims) -> {
                    order.add("recheck");
                    return proofClaims.tenantGrantRevision() == 31
                            && proofClaims.authorizationRevision() == 47;
                });

        assertThat(order).containsExactly("resolve", "reserve", "recheck");
        assertThat(verified.claims().grantUseMode()).isEqualTo(GrantUseMode.NEW_WORK);
        assertThat(verified.claims().drainClaims()).isNull();
        assertThat(verified.claims().profileClaims().get("taskType"))
                .isEqualTo("TIME_FINALIZATION");
        assertThat(verified.commandDigest()).isEqualTo(
                Hrm013WorkloadProofV1.commandDigest(
                        profile, TENANT_ID, Hrm013WorkloadProofV1.sha256(body)));
        assertThat(verified.proofSha256()).hasSize(64);
    }

    @Test
    void enforcesNewWorkAbsenceAndAllSevenDrainExistingClaims() throws Exception {
        Profile profile = Hrm013WorkloadProofV1.apr002();
        UUID approvalRequestId = uuid(20);
        byte[] body = canonicalBody(Map.of("approvalRequestId", approvalRequestId.toString()));
        Map<String, Object> drainClaims = businessClaims(
                profile, body, GrantUseMode.DRAIN_EXISTING);
        addApr002Claims(drainClaims, approvalRequestId);
        addRootDrainClaims(drainClaims, approvalRequestId);

        var verified = verify(profile, body, drainClaims, BUSINESS_KEY_ID, keyPair);
        assertThat(verified.claims().grantUseMode())
                .isEqualTo(GrantUseMode.DRAIN_EXISTING);
        assertThat(verified.claims().drainClaims().drainContinuationKind())
                .isEqualTo(DrainContinuationKind.ROOT_DELIVERY);
        assertThat(verified.claims().drainClaims().drainContinuationRevision()).isZero();

        Map<String, Object> missing = new LinkedHashMap<>(drainClaims);
        missing.remove("drainRootCreatedAt");
        assertFailure(
                () -> verify(profile, body, missing, BUSINESS_KEY_ID, keyPair),
                FailureCode.SELECTOR_INVALID);

        Map<String, Object> newWorkWithDrain = new LinkedHashMap<>(drainClaims);
        newWorkWithDrain.put("grantUseMode", "NEW_WORK");
        assertFailure(
                () -> verify(profile, body, newWorkWithDrain, BUSINESS_KEY_ID, keyPair),
                FailureCode.CLAIM_SET_INVALID);

        Map<String, Object> invalidRoot = new LinkedHashMap<>(drainClaims);
        invalidRoot.put("drainContinuationEventId", uuid(21).toString());
        assertFailure(
                () -> verify(profile, body, invalidRoot, BUSINESS_KEY_ID, keyPair),
                FailureCode.DRAIN_CLAIMS_INVALID);
    }

    @Test
    void restrictsRequesterDrainToApr002BoundDependentRead() throws Exception {
        Profile profile = Hrm013WorkloadProofV1.requesterIdentity();
        UUID approvalRequestId = uuid(30);
        byte[] body = canonicalBody(Map.of(
                "approvalRequestId", approvalRequestId.toString(),
                "requestedByActorPublicId", uuid(31).toString()));
        Map<String, Object> claims = businessClaims(
                profile, body, GrantUseMode.DRAIN_EXISTING);
        claims.put("approvalRequestId", approvalRequestId.toString());
        claims.put("requestedByActorPublicId", uuid(31).toString());
        addDependentReadDrainClaims(claims, approvalRequestId);

        var verified = verify(profile, body, claims, BUSINESS_KEY_ID, keyPair);
        assertThat(verified.claims().drainClaims().drainContinuationKind())
                .isEqualTo(DrainContinuationKind.DEPENDENT_READ);
        assertThat(verified.claims().drainClaims().drainRootWorkItemId())
                .isEqualTo(approvalRequestId);

        Map<String, Object> wrongKind = new LinkedHashMap<>(claims);
        wrongKind.put("drainContinuationKind", "ROOT_DELIVERY");
        assertFailure(
                () -> verify(profile, body, wrongKind, BUSINESS_KEY_ID, keyPair),
                FailureCode.DRAIN_CLAIMS_INVALID);

        Map<String, Object> wrongRoot = new LinkedHashMap<>(claims);
        UUID otherRoot = uuid(32);
        wrongRoot.put("drainRootWorkItemId", otherRoot.toString());
        wrongRoot.put("drainContinuationEventId", otherRoot.toString());
        assertFailure(
                () -> verify(profile, body, wrongRoot, BUSINESS_KEY_ID, keyPair),
                FailureCode.DRAIN_CLAIMS_INVALID);
    }

    @Test
    void resolverTerminalChildIdentityUsesTheTargetBusinessBinding() throws Exception {
        Profile profile = Hrm013WorkloadProofV1.resolverBootstrap(
                Hrm013WorkloadProofV1.ResolverCaller.PEOPLE);
        byte[] body = canonicalBody(Map.of("tenantId", TENANT_ID));
        Map<String, Object> claims = resolverClaims(
                profile, body, ResolutionMode.DRAIN_EXISTING);
        addResolverClaims(claims, profile.callerWorkloadId());
        claims.put("targetCallerWorkloadId", "dwp-time-server");
        claims.put("targetOwnerWorkloadId", "dwp-people-server");
        claims.put("targetBindingId", "SVC-PEP-HRM-008");
        claims.put("targetPurpose", "HRIS_SEPARATION_TIME_TASK_RECEIPT");
        UUID rootId = uuid(33);
        claims.put("drainRootWorkItemId", rootId.toString());
        claims.put("drainRootCreatedAt", NOW.minusSeconds(60).toString());
        claims.put("drainRootPayloadSha256", "d".repeat(64));
        claims.put("drainContinuationKind", "TERMINAL_CHILD");
        claims.put("drainContinuationRevision", 1);
        claims.put("drainContinuationPayloadSha256", "e".repeat(64));
        UUID targetChild = Hrm013WorkloadProofV1.terminalChildEventId(
                TENANT_ID, rootId, 1, "SVC-PEP-HRM-008");
        claims.put("drainContinuationEventId", targetChild.toString());

        assertThat(verify(profile, body, claims, leafThumbprint, keyPair)
                .claims().drainClaims().drainContinuationEventId())
                .isEqualTo(targetChild);

        Map<String, Object> resolverDerived = new LinkedHashMap<>(claims);
        resolverDerived.put("drainContinuationEventId",
                Hrm013WorkloadProofV1.terminalChildEventId(
                        TENANT_ID, rootId, 1, profile.bindingId()).toString());
        assertFailure(
                () -> verify(profile, body, resolverDerived, leafThumbprint, keyPair),
                FailureCode.DRAIN_CLAIMS_INVALID);
    }

    @Test
    void verifiesLeafSignedResolverProbeAndRejectsRevisionOrKidDrift() throws Exception {
        Profile profile = Hrm013WorkloadProofV1.resolverBootstrap(
                Hrm013WorkloadProofV1.ResolverCaller.PEOPLE);
        byte[] body = canonicalBody(Map.of("tenantId", TENANT_ID));
        Map<String, Object> claims = resolverClaims(profile, body, ResolutionMode.PROBE_MISSING);
        addResolverClaims(claims, profile.callerWorkloadId());

        var verified = verify(profile, body, claims, leafThumbprint, keyPair);
        assertThat(verified.claims().resolutionMode()).isEqualTo(ResolutionMode.PROBE_MISSING);
        assertThat(verified.claims().tenantGrantRevision()).isZero();

        Map<String, Object> badRevision = new LinkedHashMap<>(claims);
        badRevision.put("signedTenantGrantRevision", 1);
        assertFailure(
                () -> verify(profile, body, badRevision, leafThumbprint, keyPair),
                FailureCode.SELECTOR_INVALID);

        assertFailure(
                () -> verify(profile, body, claims, BUSINESS_KEY_ID, keyPair),
                FailureCode.CERTIFICATE_CONFIRMATION_MISMATCH);
    }

    @Test
    void rejectsAuthorizationDuplicateExtraAndNonExactMediaHeaders() throws Exception {
        Profile profile = Hrm013WorkloadProofV1.taskDispatch(TaskOwner.PAYROLL);
        byte[] body = canonicalBody(Map.of("dispatchEventId", uuid(40).toString()));
        Map<String, Object> claims = businessClaims(profile, body, GrantUseMode.NEW_WORK);
        addTaskDispatchClaims(claims, TaskOwner.PAYROLL);
        String compact = compact(profile, claims, keyPair, BUSINESS_KEY_ID, true);
        List<Header> valid = Hrm013WorkloadProofV1.headers(
                profile, compact, CORRELATION_ID);

        List<Header> authorization = new ArrayList<>(valid);
        authorization.add(new Header("Authorization", "Bearer forbidden"));
        assertFailure(
                () -> verify(profile, body, authorization, keyPair),
                FailureCode.AUTHORIZATION_HEADER_FORBIDDEN);

        List<Header> duplicate = new ArrayList<>(valid);
        duplicate.add(new Header("accept", "application/json"));
        assertFailure(
                () -> verify(profile, body, duplicate, keyPair),
                FailureCode.DUPLICATE_OR_INVALID_HEADER);

        List<Header> extra = new ArrayList<>(valid);
        extra.add(new Header("X-DWP-Tenant-ID", "17"));
        assertFailure(
                () -> verify(profile, body, extra, keyPair),
                FailureCode.APPLICATION_HEADER_FORBIDDEN);

        List<Header> parameterizedContentType = replaceHeader(
                valid, "Content-Type", "application/json; charset=utf-8");
        assertFailure(
                () -> verify(profile, body, parameterizedContentType, keyPair),
                FailureCode.EXACT_JSON_MEDIA_HEADERS_REQUIRED);

        List<Header> missing = valid.stream()
                .filter(header -> !header.name().equalsIgnoreCase("Accept"))
                .toList();
        assertFailure(
                () -> verify(profile, body, missing, keyPair),
                FailureCode.EXACT_FIVE_HEADER_ENVELOPE_REQUIRED);

        List<Header> oversizedProof = replaceHeader(
                valid, Hrm013WorkloadProofV1.WORKLOAD_PROOF_HEADER, "a".repeat(16_385));
        assertFailure(
                () -> verify(profile, body, oversizedProof, keyPair),
                FailureCode.APPLICATION_HEADER_TOO_LARGE);

        assertFailure(
                () -> verify(profile, new byte[32_769], valid, keyPair),
                FailureCode.REQUEST_BODY_TOO_LARGE);
    }

    @Test
    void rejectsWrongMethodPathBodyCnfFreshnessAndNoncanonicalBody() throws Exception {
        Profile profile = Hrm013WorkloadProofV1.taskDispatch(TaskOwner.IDENTITY);
        byte[] body = canonicalBody(Map.of("dispatchEventId", uuid(50).toString()));
        Map<String, Object> claims = businessClaims(profile, body, GrantUseMode.NEW_WORK);
        addTaskDispatchClaims(claims, TaskOwner.IDENTITY);
        String compact = compact(profile, claims, keyPair, BUSINESS_KEY_ID, true);
        List<Header> headers = Hrm013WorkloadProofV1.headers(
                profile, compact, CORRELATION_ID);

        assertFailure(
                () -> verify(profile, "GET", profile.exactPath(), headers, body, keyPair),
                FailureCode.EXACT_METHOD_OR_PATH_REQUIRED);
        assertFailure(
                () -> verify(profile, "POST", profile.exactPath() + "/", headers, body,
                        keyPair),
                FailureCode.EXACT_METHOD_OR_PATH_REQUIRED);
        assertFailure(
                () -> verify(profile, canonicalBody(Map.of("changed", true)), claims,
                        BUSINESS_KEY_ID, keyPair),
                FailureCode.EXACT_CLAIMS_REQUIRED);

        Map<String, Object> wrongCnf = deepCopyClaims(claims);
        wrongCnf.put("cnf", Map.of("x5t#S256", thumbprint("another-leaf")));
        assertFailure(
                () -> verify(profile, body, wrongCnf, BUSINESS_KEY_ID, keyPair),
                FailureCode.CERTIFICATE_CONFIRMATION_MISMATCH);

        Map<String, Object> tooLong = deepCopyClaims(claims);
        tooLong.put("exp", NOW.plusSeconds(31).getEpochSecond());
        assertFailure(
                () -> verify(profile, body, tooLong, BUSINESS_KEY_ID, keyPair),
                FailureCode.FRESHNESS_INVALID);

        byte[] noncanonicalBody = "{ \"dispatchEventId\" : \"x\" }"
                .getBytes(StandardCharsets.UTF_8);
        Map<String, Object> matchingNoncanonical = businessClaims(
                profile, noncanonicalBody, GrantUseMode.NEW_WORK);
        addTaskDispatchClaims(matchingNoncanonical, TaskOwner.IDENTITY);
        AtomicBoolean replayCalled = new AtomicBoolean();
        assertFailure(
                () -> verify(
                        profile, noncanonicalBody, matchingNoncanonical,
                        BUSINESS_KEY_ID, keyPair, replayCalled),
                FailureCode.REQUEST_BODY_INVALID);
        assertThat(replayCalled).isFalse();
    }

    @Test
    void rejectsUnknownDuplicateNullCoercedNoncanonicalAndBadSignatureClaims()
            throws Exception {
        Profile profile = Hrm013WorkloadProofV1.taskDispatch(TaskOwner.TIME);
        byte[] body = canonicalBody(Map.of("dispatchEventId", uuid(60).toString()));
        Map<String, Object> valid = businessClaims(profile, body, GrantUseMode.NEW_WORK);
        addTaskDispatchClaims(valid, TaskOwner.TIME);

        Map<String, Object> unknown = deepCopyClaims(valid);
        unknown.put("unexpected", "closed-means-closed");
        assertFailure(
                () -> verify(profile, body, unknown, BUSINESS_KEY_ID, keyPair),
                FailureCode.CLAIM_SET_INVALID);

        Map<String, Object> nullClaim = deepCopyClaims(valid);
        nullClaim.put("taskType", null);
        assertFailure(
                () -> verify(profile, body, nullClaim, BUSINESS_KEY_ID, keyPair),
                FailureCode.CLAIMS_INVALID);

        Map<String, Object> coerced = deepCopyClaims(valid);
        coerced.put("tenantId", Long.toString(TENANT_ID));
        assertFailure(
                () -> verify(profile, body, coerced, BUSINESS_KEY_ID, keyPair),
                FailureCode.SELECTOR_INVALID);

        String noncanonical = compact(profile, valid, keyPair, BUSINESS_KEY_ID, false);
        assertFailure(
                () -> verify(
                        profile, body,
                        Hrm013WorkloadProofV1.headers(profile, noncanonical, CORRELATION_ID),
                        keyPair),
                FailureCode.CLAIMS_INVALID);

        String duplicateJson = duplicateTenantClaimJson(valid);
        String duplicateProof = compactRaw(duplicateJson.getBytes(StandardCharsets.UTF_8),
                keyPair, BUSINESS_KEY_ID, true);
        assertFailure(
                () -> verify(
                        profile, body,
                        Hrm013WorkloadProofV1.headers(
                                profile, duplicateProof, CORRELATION_ID),
                        keyPair),
                FailureCode.CLAIMS_INVALID);

        String wrongSignature = compact(profile, valid, wrongKeyPair, BUSINESS_KEY_ID, true);
        assertFailure(
                () -> verify(
                        profile, body,
                        Hrm013WorkloadProofV1.headers(
                                profile, wrongSignature, CORRELATION_ID),
                        keyPair),
                FailureCode.SIGNATURE_INVALID);
    }

    @Test
    void canonicalizesRfc8785PreimagesAndMatchesFrozenTerminalChildVector()
            throws Exception {
        byte[] input = "{\"z\":1.0,\"b\":2,\"a\":\"é\"}"
                .getBytes(StandardCharsets.UTF_8);
        assertThat(new String(
                Hrm013WorkloadProofV1.rfc8785CanonicalBytes(mapper, input),
                StandardCharsets.UTF_8))
                .isEqualTo("{\"a\":\"é\",\"b\":2,\"z\":1}");

        assertThat(Hrm013WorkloadProofV1.terminalChildEventId(
                1, UUID.fromString("00000000-0000-0000-0000-000000000001"),
                1, "SVC-PEP-HRM-010"))
                .isEqualTo(UUID.fromString("87614dd7-b22b-5e02-9cdd-21720c767533"));
    }

    @Test
    void canonicalizesRfc8785EcmaBinary64EdgeNumbers() {
        byte[] input = "[4.9e-324,1e-7,1e-6,1e20,1e21]"
                .getBytes(StandardCharsets.UTF_8);

        assertThat(new String(
                Hrm013WorkloadProofV1.rfc8785CanonicalBytes(mapper, input),
                StandardCharsets.UTF_8))
                .isEqualTo(
                        "[5e-324,1e-7,0.000001,100000000000000000000,1e+21]");
    }

    private Hrm013WorkloadProofV1.VerifiedProof verify(
            Profile profile,
            byte[] body,
            Map<String, Object> claims,
            String keyId,
            KeyPair signer) throws Exception {
        String compact = compact(profile, claims, signer, keyId, true);
        return verify(profile, body,
                Hrm013WorkloadProofV1.headers(profile, compact, CORRELATION_ID), keyPair);
    }

    private Hrm013WorkloadProofV1.VerifiedProof verify(
            Profile profile,
            byte[] body,
            Map<String, Object> claims,
            String keyId,
            KeyPair signer,
            AtomicBoolean replayCalled) throws Exception {
        String compact = compact(profile, claims, signer, keyId, true);
        return Hrm013WorkloadProofV1.verify(
                mapper, fixedClock(), profile, "POST", profile.exactPath(),
                Hrm013WorkloadProofV1.headers(profile, compact, CORRELATION_ID), body,
                transport(profile),
                (selected, selector, resolvedKeyId) -> new VerifiedSigningKey(
                        keyPair.getPublic(), resolvedKeyId, profile.callerWorkloadId()),
                reservation -> {
                    replayCalled.set(true);
                    return true;
                },
                (selected, key, verifiedClaims) -> true);
    }

    private Hrm013WorkloadProofV1.VerifiedProof verify(
            Profile profile, byte[] body, List<Header> headers, KeyPair trustedKey) {
        return verify(profile, "POST", profile.exactPath(), headers, body, trustedKey);
    }

    private Hrm013WorkloadProofV1.VerifiedProof verify(
            Profile profile,
            String method,
            String path,
            List<Header> headers,
            byte[] body,
            KeyPair trustedKey) {
        return Hrm013WorkloadProofV1.verify(
                mapper, fixedClock(), profile, method, path, headers, body,
                transport(profile),
                (selected, selector, keyId) -> new VerifiedSigningKey(
                        trustedKey.getPublic(), keyId, profile.callerWorkloadId()),
                reservation -> true,
                (selected, key, claims) -> true);
    }

    private TransportIdentity transport(Profile profile) {
        return new TransportIdentity(profile.callerWorkloadId(), leafThumbprint);
    }

    private Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private byte[] canonicalBody(Map<String, ?> values) throws Exception {
        return Hrm013WorkloadProofV1.rfc8785CanonicalBytes(
                mapper, mapper.writeValueAsBytes(values));
    }

    private Map<String, Object> businessClaims(
            Profile profile, byte[] body, GrantUseMode mode) {
        Map<String, Object> claims = commonClaims(profile, body);
        claims.put("tenantGrantRevision", 31);
        claims.put("authorizationRevision", 47);
        claims.put("grantUseMode", mode.name());
        return claims;
    }

    private Map<String, Object> resolverClaims(
            Profile profile, byte[] body, ResolutionMode mode) {
        Map<String, Object> claims = commonClaims(profile, body);
        claims.put("resolutionMode", mode.name());
        claims.put("signedTenantGrantRevision",
                mode == ResolutionMode.PROBE_MISSING ? 0 : 31);
        claims.put("signedAuthorizationRevision",
                mode == ResolutionMode.PROBE_MISSING ? 0 : 47);
        return claims;
    }

    private Map<String, Object> commonClaims(Profile profile, byte[] body) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", profile.callerWorkloadId());
        claims.put("aud", profile.ownerWorkloadId());
        claims.put("sub", profile.callerWorkloadId());
        claims.put("iat", NOW.getEpochSecond());
        claims.put("nbf", NOW.getEpochSecond());
        claims.put("exp", NOW.plusSeconds(30).getEpochSecond());
        claims.put("jti", uuid(901).toString());
        claims.put("requestNonce", uuid(902).toString());
        claims.put("bindingId", profile.bindingId());
        claims.put("purpose", profile.purpose());
        claims.put("method", profile.method());
        claims.put("path", profile.exactPath());
        claims.put("tenantId", TENANT_ID);
        claims.put("correlationId", CORRELATION_ID.toString());
        claims.put("requestBodySha256", Hrm013WorkloadProofV1.sha256(body));
        claims.put("authn", profile.authentication());
        claims.put("cnf", Map.of("x5t#S256", leafThumbprint));
        return claims;
    }

    private void addTaskDispatchClaims(Map<String, Object> claims, TaskOwner owner) {
        claims.put("dispatchEventId", uuid(70).toString());
        claims.put("casePublicId", uuid(71).toString());
        claims.put("taskPublicId", uuid(72).toString());
        claims.put("taskType", owner.taskType());
        claims.put("taskOwnerRevision", 1);
        claims.put("payloadSha256", "a".repeat(64));
    }

    private void addApr002Claims(Map<String, Object> claims, UUID approvalRequestId) {
        claims.put("approvalRequestId", approvalRequestId.toString());
        claims.put("casePublicId", uuid(81).toString());
        claims.put("taskPublicId", uuid(82).toString());
    }

    private void addResolverClaims(Map<String, Object> claims, String requestingWorkloadId) {
        claims.put("requestingWorkloadId", requestingWorkloadId);
        claims.put("targetCallerWorkloadId", "dwp-people-server");
        claims.put("targetOwnerWorkloadId", "dwp-time-server");
        claims.put("targetBindingId", "SVC-PEP-TIM-004");
        claims.put("targetPurpose", "HRIS_SEPARATION_TIME_TASK_DISPATCH");
    }

    private void addRootDrainClaims(Map<String, Object> claims, UUID rootId) {
        claims.put("drainRootWorkItemId", rootId.toString());
        claims.put("drainRootCreatedAt", NOW.minusSeconds(60).toString());
        claims.put("drainRootPayloadSha256", "d".repeat(64));
        claims.put("drainContinuationKind", "ROOT_DELIVERY");
        claims.put("drainContinuationEventId", rootId.toString());
        claims.put("drainContinuationRevision", 0);
        claims.put("drainContinuationPayloadSha256", "d".repeat(64));
    }

    private void addDependentReadDrainClaims(Map<String, Object> claims, UUID rootId) {
        addRootDrainClaims(claims, rootId);
        claims.put("drainContinuationKind", "DEPENDENT_READ");
    }

    private String compact(
            Profile profile,
            Map<String, Object> claims,
            KeyPair signer,
            String keyId,
            boolean canonicalClaims) throws Exception {
        byte[] claimBytes = canonicalClaims
                ? Hrm013WorkloadProofV1.rfc8785CanonicalBytes(
                        mapper, mapper.writeValueAsBytes(claims))
                : mapper.writeValueAsBytes(claims);
        return compactRaw(claimBytes, signer, keyId, true);
    }

    private String compactRaw(
            byte[] claimBytes, KeyPair signer, String keyId, boolean canonicalJose)
            throws Exception {
        Map<String, Object> jose = new LinkedHashMap<>();
        jose.put("typ", "JWT");
        jose.put("kid", keyId);
        jose.put("alg", "RS256");
        byte[] joseBytes = canonicalJose
                ? Hrm013WorkloadProofV1.rfc8785CanonicalBytes(
                        mapper, mapper.writeValueAsBytes(jose))
                : mapper.writeValueAsBytes(jose);
        String encodedJose = encode(joseBytes);
        String encodedClaims = encode(claimBytes);
        String signingInput = encodedJose + "." + encodedClaims;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(signer.getPrivate());
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + encode(signature.sign());
    }

    private String duplicateTenantClaimJson(Map<String, Object> claims) throws Exception {
        String canonical = new String(
                Hrm013WorkloadProofV1.rfc8785CanonicalBytes(
                        mapper, mapper.writeValueAsBytes(claims)),
                StandardCharsets.UTF_8);
        return canonical.replace(
                "\"tenantId\":" + TENANT_ID,
                "\"tenantId\":" + TENANT_ID + ",\"tenantId\":" + TENANT_ID);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopyClaims(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>(source);
        copy.put("cnf", new LinkedHashMap<>((Map<String, Object>) source.get("cnf")));
        return copy;
    }

    private List<Header> replaceHeader(List<Header> headers, String name, String value) {
        return headers.stream()
                .map(header -> header.name().equalsIgnoreCase(name)
                        ? new Header(header.name(), value) : header)
                .toList();
    }

    private static String thumbprint(String seed) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256")
                        .digest(seed.getBytes(StandardCharsets.UTF_8)));
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }

    private static void assertFailure(ThrowingOperation operation, FailureCode code) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(
                        VerificationException.class,
                        failure -> assertThat(failure.code()).isEqualTo(code));
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }
}
