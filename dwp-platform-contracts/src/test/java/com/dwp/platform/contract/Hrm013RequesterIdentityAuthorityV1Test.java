package com.dwp.platform.contract;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013RequesterIdentityAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013RequesterIdentityAuthorityV1.AccountPlane;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013RequesterIdentityAuthorityV1.AccountState;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013RequesterIdentityAuthorityV1.ProofBinding;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013RequesterIdentityAuthorityV1.RequesterRecord;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013RequesterIdentityAuthorityV1.ResolveRequest;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013RequesterIdentityAuthorityV1.ResolveResponse;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013RequesterIdentityAuthorityV1.Status;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Hrm013RequesterIdentityAuthorityV1Test {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID APPROVAL = UUID.fromString(
            "50000000-0000-0000-0000-000000000005");
    private static final UUID ACTOR = UUID.fromString(
            "6abcdef0-abcd-abcd-abcd-000000000006");
    private static final UUID CORRELATION = UUID.fromString(
            "70000000-0000-0000-0000-000000000007");
    private static final Instant REQUESTED_AT = Instant.parse("2026-10-27T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-27T00:00:10Z");
    private static final String PAYLOAD = "c".repeat(64);

    @Test
    void closedRequestAndResponseCodecsRoundTripCanonicalBytes() throws Exception {
        ResolveRequest request = request();
        byte[] requestBytes = Hrm013RequesterIdentityAuthorityV1.encodeRequest(MAPPER, request);
        assertThat(MAPPER.readTree(requestBytes).size()).isEqualTo(4);
        assertThat(Hrm013RequesterIdentityAuthorityV1.decodeRequest(MAPPER, requestBytes))
                .isEqualTo(request);

        ResolveResponse admitted = Hrm013RequesterIdentityAuthorityV1.admit(
                request, newWorkProof(), requester(), NOW);
        byte[] responseBytes = Hrm013RequesterIdentityAuthorityV1.encodeResponse(
                MAPPER, admitted);
        assertThat(MAPPER.readTree(responseBytes).size()).isEqualTo(8);
        assertThat(Hrm013RequesterIdentityAuthorityV1.decodeResponse(MAPPER, responseBytes))
                .isEqualTo(admitted);

        ResolveResponse denied = ResolveResponse.opaque(Status.DENY, CORRELATION);
        JsonNode deniedTree = MAPPER.readTree(
                Hrm013RequesterIdentityAuthorityV1.encodeResponse(MAPPER, denied));
        assertThat(deniedTree.size()).isEqualTo(2);
        assertThat(deniedTree.has("requesterPersonPublicId")).isFalse();
    }

    @Test
    void strictCodecRejectsDuplicateUnknownMissingNullCoercionNoncanonicalAndOversize()
            throws Exception {
        String json = new String(Hrm013RequesterIdentityAuthorityV1.encodeRequest(
                MAPPER, request()), StandardCharsets.UTF_8);
        JsonNode missing = MAPPER.readTree(json);
        ((com.fasterxml.jackson.databind.node.ObjectNode) missing).remove("approvalRequestId");
        String[] malformed = {
                json.substring(0, json.length() - 1) + ",\"tenantId\":42}",
                json.substring(0, json.length() - 1) + ",\"privateKey\":\"secret\"}",
                MAPPER.writeValueAsString(missing),
                json.replace("\"requestedByActorPublicId\":\"" + ACTOR + "\"",
                        "\"requestedByActorPublicId\":null"),
                json.replace("\"tenantId\":42", "\"tenantId\":\"42\""),
                "\n" + json,
                json.replace(ACTOR.toString(), ACTOR.toString().toUpperCase())
        };
        for (String value : malformed) assertMalformed(value.getBytes(StandardCharsets.UTF_8));
        assertMalformed(new byte[Hrm013RequesterIdentityAuthorityV1.MAX_REQUEST_BYTES + 1]);
    }

    @Test
    void requesterAdmissionBindsTenantApprovalRootActorRevisionsAndNewWork() {
        ResolveResponse response = Hrm013RequesterIdentityAuthorityV1.admit(
                request(), newWorkProof(), requester(), NOW);
        assertThat(response.status()).isEqualTo(Status.ADMIT);
        assertThat(response.requesterUserId()).isEqualTo(91);
        assertThat(response.requesterPersonPublicId()).isEqualTo(ACTOR);
        assertThat(response.tenantGrantRevision()).isEqualTo(7);
        assertThat(response.authorizationRevision()).isEqualTo(9);

        deny(new ResolveRequest(43, APPROVAL, ACTOR, CORRELATION), newWorkProof(), requester());
        deny(request(), proof(APPROVAL, UUID.randomUUID(), 7, 9,
                Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK, null, null, true), requester());
        deny(request(), proof(UUID.randomUUID(), ACTOR, 7, 9,
                Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK, null, null, true), requester());
        deny(request(), proof(APPROVAL, ACTOR, 0, 9,
                Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK, null, null, true), requester());
        deny(request(), proof(APPROVAL, ACTOR, 7, 9,
                Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK, null, null, false), requester());
        deny(request(), newWorkProof(), new RequesterRecord(
                43, 91, ACTOR, 0, 0, AccountPlane.TENANT, AccountState.ACTIVE, 1));
        deny(request(), newWorkProof(), new RequesterRecord(
                42, 91, ACTOR, 0, 0, AccountPlane.PLATFORM, AccountState.ACTIVE, 1));
        deny(request(), newWorkProof(), new RequesterRecord(
                42, 91, ACTOR, 0, 0, AccountPlane.TENANT, AccountState.INACTIVE, 1));
        deny(request(), newWorkProof(), new RequesterRecord(
                42, 91, ACTOR, 0, 0, AccountPlane.TENANT, AccountState.ACTIVE, 2));
    }

    @Test
    void drainRequesterAdmissionAllowsOnlyUnexpiredApr002DependentReadProjection() {
        Hrm013WorkloadProofV1.DrainClaims exact = drain(
                APPROVAL, APPROVAL, Hrm013WorkloadProofV1.DrainContinuationKind.DEPENDENT_READ,
                0, PAYLOAD, PAYLOAD);
        ProofBinding drainProof = proof(APPROVAL, ACTOR, 7, 9,
                Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING,
                exact, NOW.plusSeconds(20), true);
        assertThat(Hrm013RequesterIdentityAuthorityV1.admit(
                request(), drainProof, requester(), NOW).status()).isEqualTo(Status.ADMIT);

        deny(request(), proof(APPROVAL, ACTOR, 7, 9,
                Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING,
                drain(APPROVAL, APPROVAL,
                        Hrm013WorkloadProofV1.DrainContinuationKind.ROOT_DELIVERY,
                        0, PAYLOAD, PAYLOAD), NOW.plusSeconds(20), true), requester());
        deny(request(), proof(APPROVAL, ACTOR, 7, 9,
                Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING,
                drain(UUID.randomUUID(), APPROVAL,
                        Hrm013WorkloadProofV1.DrainContinuationKind.DEPENDENT_READ,
                        0, PAYLOAD, PAYLOAD), NOW.plusSeconds(20), true), requester());
        deny(request(), proof(APPROVAL, ACTOR, 7, 9,
                Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING,
                drain(APPROVAL, APPROVAL,
                        Hrm013WorkloadProofV1.DrainContinuationKind.DEPENDENT_READ,
                        1, PAYLOAD, PAYLOAD), NOW.plusSeconds(20), true), requester());
        deny(request(), proof(APPROVAL, ACTOR, 7, 9,
                Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING,
                exact, NOW, true), requester());
        deny(request(), proof(APPROVAL, ACTOR, 7, 9,
                Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING,
                drain(APPROVAL, APPROVAL,
                        Hrm013WorkloadProofV1.DrainContinuationKind.DEPENDENT_READ,
                        0, "d".repeat(64), PAYLOAD), NOW.plusSeconds(20), true), requester());
    }

    @Test
    void safeDenialDoesNotDiscloseRequesterOrGrantEvidence() {
        Hrm013SafeErrorV1.Failure failure = Hrm013SafeErrorV1.authorityDenied();
        Hrm013SafeErrorV1.SafeResponse response = Hrm013SafeErrorV1.map(failure, CORRELATION);
        assertThat(response.httpStatus()).isEqualTo(403);
        assertThat(response.bodyBytes()).isEmpty();
        assertThat(failure.getMessage()).isEqualTo("AUTHORITY_DENIED")
                .doesNotContain(ACTOR.toString(), APPROVAL.toString(), PAYLOAD, "grant", "key");
    }

    private static ResolveRequest request() {
        return new ResolveRequest(42, APPROVAL, ACTOR, CORRELATION);
    }

    private static RequesterRecord requester() {
        return new RequesterRecord(
                42, 91, ACTOR, 4, 8, AccountPlane.TENANT, AccountState.ACTIVE, 1);
    }

    private static ProofBinding newWorkProof() {
        return proof(APPROVAL, ACTOR, 7, 9,
                Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK, null, null, true);
    }

    private static ProofBinding proof(
            UUID approval, UUID actor, long tenantRevision, long authorizationRevision,
            Hrm013WorkloadProofV1.GrantUseMode mode,
            Hrm013WorkloadProofV1.DrainClaims drain, Instant expiresAt, boolean usable) {
        return new ProofBinding(Hrm013RequesterIdentityAuthorityV1.BINDING_ID,
                Hrm013RequesterIdentityAuthorityV1.CALLER_WORKLOAD_ID,
                Hrm013RequesterIdentityAuthorityV1.OWNER_WORKLOAD_ID,
                Hrm013RequesterIdentityAuthorityV1.PURPOSE, 42, approval, actor, CORRELATION,
                tenantRevision, authorizationRevision, mode, drain, REQUESTED_AT,
                PAYLOAD, expiresAt, usable);
    }

    private static Hrm013WorkloadProofV1.DrainClaims drain(
            UUID root, UUID event, Hrm013WorkloadProofV1.DrainContinuationKind kind,
            long revision, String rootDigest, String continuationDigest) {
        return new Hrm013WorkloadProofV1.DrainClaims(
                root, REQUESTED_AT, rootDigest, kind, event, revision, continuationDigest);
    }

    private static void deny(ResolveRequest request, ProofBinding proof, RequesterRecord record) {
        assertThatThrownBy(() -> Hrm013RequesterIdentityAuthorityV1.admit(
                request, proof, record, NOW))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class)
                .hasMessage("AUTHORITY_DENIED")
                .hasMessageNotContaining(ACTOR.toString())
                .hasMessageNotContaining(APPROVAL.toString())
                .hasMessageNotContaining(PAYLOAD);
    }

    private static void assertMalformed(byte[] bytes) {
        assertThatThrownBy(() -> Hrm013RequesterIdentityAuthorityV1.decodeRequest(MAPPER, bytes))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class)
                .hasMessage("MALFORMED_REQUEST")
                .hasMessageNotContaining("secret")
                .hasMessageNotContaining("privateKey")
                .hasMessageNotContaining(ACTOR.toString());
    }
}
