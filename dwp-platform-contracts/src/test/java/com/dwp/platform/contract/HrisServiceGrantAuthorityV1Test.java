package com.dwp.platform.contract;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.BootstrapClaims;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.DrainAdmission;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.DrainContinuationKind;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.DrainFields;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.GrantAuthority;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.ReadinessObservation;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.ResolveRequest;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.ResolveResponse;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.ResolutionMode;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.RsaPublicJwk;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.ServiceRole;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.Status;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HrisServiceGrantAuthorityV1Test {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID CORRELATION = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");
    private static final UUID ROOT = UUID.fromString(
            "20000000-0000-0000-0000-000000000002");
    private static final String DIGEST_A = "a".repeat(64);
    private static final String DIGEST_B = "b".repeat(64);

    @Test
    void canonicalNewWorkAndDrainRequestsRoundTripWithExactConditionalFields() throws Exception {
        ResolveRequest current = request(ResolutionMode.NEW_WORK, null);
        byte[] currentBytes = HrisServiceGrantAuthorityV1.encodeRequest(MAPPER, current);
        JsonNode currentTree = MAPPER.readTree(currentBytes);
        assertThat(currentTree.size()).isEqualTo(10);
        assertThat(currentTree.has("drainRootWorkItemId")).isFalse();
        assertThat(HrisServiceGrantAuthorityV1.decodeRequest(MAPPER, currentBytes))
                .isEqualTo(current);

        ResolveRequest drain = request(ResolutionMode.DRAIN_EXISTING, drainFields());
        byte[] drainBytes = HrisServiceGrantAuthorityV1.encodeRequest(MAPPER, drain);
        JsonNode drainTree = MAPPER.readTree(drainBytes);
        assertThat(drainTree.size()).isEqualTo(17);
        assertThat(drainTree.get("drainContinuationKind").textValue())
                .isEqualTo("DEPENDENT_READ");
        assertThat(HrisServiceGrantAuthorityV1.decodeRequest(MAPPER, drainBytes))
                .isEqualTo(drain);

        assertThatIllegalArgumentException().isThrownBy(() ->
                request(ResolutionMode.DRAIN_EXISTING, null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                request(ResolutionMode.NEW_WORK, drainFields()));
        assertThatIllegalArgumentException().isThrownBy(() -> new ResolveRequest(
                42, "dwp-approval-server", "dwp-approval-server", "dwp-auth-server",
                "SVC-AUT-HRM-013", "HRIS_SEPARATION_REQUESTER_IDENTITY_RESOLVE",
                ResolutionMode.PROBE_MISSING, 9, 10, CORRELATION, null));
    }

    @Test
    void strictCodecRejectsDuplicateUnknownMissingNullCoercionNoncanonicalAndOversize()
            throws Exception {
        String json = new String(HrisServiceGrantAuthorityV1.encodeRequest(
                MAPPER, request(ResolutionMode.NEW_WORK, null)), StandardCharsets.UTF_8);
        JsonNode tree = MAPPER.readTree(json);
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree).remove("targetPurpose");

        String[] malformed = {
                json.substring(0, json.length() - 1) + ",\"tenantId\":42}",
                json.substring(0, json.length() - 1) + ",\"secret\":\"do-not-leak\"}",
                MAPPER.writeValueAsString(tree),
                json.replace("\"targetPurpose\":\"HRIS_SEPARATION_REQUESTER_IDENTITY_RESOLVE\"",
                        "\"targetPurpose\":null"),
                json.replace("\"tenantId\":42", "\"tenantId\":\"42\""),
                " " + json
        };
        for (String value : malformed) {
            assertMalformed(value.getBytes(StandardCharsets.UTF_8));
        }
        assertMalformed(new byte[HrisServiceGrantAuthorityV1.MAX_REQUEST_BYTES + 1]);
        assertMalformed(new byte[] {(byte) 0xc3, (byte) 0x28});
    }

    @Test
    void bootstrapProfilesBindCallerTupleBodyAndSignedRevisionsFailClosed() {
        ResolveRequest request = request(ResolutionMode.DRAIN_EXISTING, drainFields());
        byte[] bytes = HrisServiceGrantAuthorityV1.encodeRequest(MAPPER, request);
        BootstrapClaims claims = claims(request, bytes);

        HrisServiceGrantAuthorityV1.requireBootstrapAdmission(
                HrisServiceGrantAuthorityV1.bootstrapProfile("SVC-AUT-HRM-012"),
                request, claims, bytes, tuple -> tuple.equals(request.targetTuple()));

        BootstrapClaims drift = new BootstrapClaims(
                claims.bindingId(), claims.issuer(), claims.audience(), claims.subject(),
                claims.purpose(), claims.method(), claims.path(), claims.tenantId(),
                claims.requestingWorkloadId(), claims.targetCallerWorkloadId(),
                claims.targetOwnerWorkloadId(), claims.targetBindingId(), claims.targetPurpose(),
                claims.resolutionMode(), claims.signedTenantGrantRevision(), 999,
                claims.correlationId(), claims.drainFields(), claims.requestBodySha256(),
                claims.authentication());
        assertThatThrownBy(() -> HrisServiceGrantAuthorityV1.requireBootstrapAdmission(
                HrisServiceGrantAuthorityV1.bootstrapProfile("SVC-AUT-HRM-012"),
                request, drift, bytes, tuple -> true))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class)
                .hasMessage("AUTHORITY_DENIED");
        assertThatThrownBy(() -> HrisServiceGrantAuthorityV1.bootstrapProfile(
                "SVC-AUT-HRM-999"))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class)
                .hasMessage("AUTHORITY_DENIED");
    }

    @Test
    void typedResponsesAreCanonicalAndNonAdmitStatusesRevealOnlyCorrelation() throws Exception {
        GrantAuthority grant = grant();
        Instant activity = Instant.parse("2026-10-27T00:01:00Z");
        DrainAdmission drain = new DrainAdmission(
                UUID.fromString("40000000-0000-0000-0000-000000000004"), 3,
                Instant.parse("2026-10-27T00:00:00Z"), ServiceRole.CALLER, 8, activity,
                activity.plusSeconds(30), activity.plusSeconds(65));
        ResolveResponse admitted = ResolveResponse.admit(grant, drain, CORRELATION);
        byte[] bytes = HrisServiceGrantAuthorityV1.encodeResponse(MAPPER, admitted);
        assertThat(HrisServiceGrantAuthorityV1.decodeResponse(MAPPER, bytes))
                .isEqualTo(admitted);
        assertThat(MAPPER.readTree(bytes).size()).isEqualTo(29);

        ResolveResponse opaque = ResolveResponse.opaque(Status.QUIESCING, CORRELATION);
        byte[] opaqueBytes = HrisServiceGrantAuthorityV1.encodeResponse(MAPPER, opaque);
        assertThat(MAPPER.readTree(opaqueBytes).fieldNames())
                .toIterable().containsExactlyInAnyOrder("status", "correlationId");
        assertThat(new String(opaqueBytes, StandardCharsets.UTF_8))
                .doesNotContain("grant", "key", "tenant", "identity");

        Instant quiet = Instant.parse("2026-10-27T00:02:00Z");
        ResolveResponse readiness = ResolveResponse.readiness(new ReadinessObservation(
                42, grant.grantSetId(), 7, 9, grant.callerWorkloadId(), grant.ownerWorkloadId(),
                grant.bindingId(), grant.purpose(), drain.quiesceFenceId(), 3,
                drain.quiesceEffectiveAt(), ServiceRole.OWNER, 0, quiet,
                quiet.plusSeconds(65), DIGEST_B), CORRELATION);
        byte[] readinessBytes = HrisServiceGrantAuthorityV1.encodeResponse(MAPPER, readiness);
        assertThat(HrisServiceGrantAuthorityV1.decodeResponse(MAPPER, readinessBytes))
                .isEqualTo(readiness);
        assertThat(MAPPER.readTree(readinessBytes).has("callerSigningPublicJwk")).isFalse();
    }

    @Test
    void safeErrorsNeverReflectSecretGrantKeyOrIdentityValues() {
        String secret = "grant-secret-private-key-person-42";
        Hrm013SafeErrorV1.SafeResponse response = Hrm013SafeErrorV1.map(
                Hrm013SafeErrorV1.authorityDenied(), CORRELATION);
        assertThat(response.httpStatus()).isEqualTo(403);
        assertThat(response.bodyBytes()).isEmpty();
        assertThat(response.bodyUtf8()).doesNotContain(secret, "grant", "key", "person");
    }

    private static ResolveRequest request(ResolutionMode mode, DrainFields drain) {
        return new ResolveRequest(42, "dwp-approval-server", "dwp-approval-server",
                "dwp-auth-server", "SVC-AUT-HRM-013",
                "HRIS_SEPARATION_REQUESTER_IDENTITY_RESOLVE", mode,
                mode == ResolutionMode.PROBE_MISSING ? 0 : 7,
                mode == ResolutionMode.PROBE_MISSING ? 0 : 9, CORRELATION, drain);
    }

    private static DrainFields drainFields() {
        return new DrainFields(ROOT, Instant.parse("2026-10-27T00:00:00Z"), DIGEST_A,
                DrainContinuationKind.DEPENDENT_READ, ROOT, 0, DIGEST_A);
    }

    private static BootstrapClaims claims(ResolveRequest request, byte[] bytes) {
        return new BootstrapClaims("SVC-AUT-HRM-012", "dwp-approval-server",
                "dwp-auth-server", "dwp-approval-server",
                HrisServiceGrantAuthorityV1.PURPOSE, HrisServiceGrantAuthorityV1.METHOD,
                HrisServiceGrantAuthorityV1.PATH, request.tenantId(),
                request.requestingWorkloadId(), request.targetCallerWorkloadId(),
                request.targetOwnerWorkloadId(), request.targetBindingId(),
                request.targetPurpose(), request.resolutionMode(),
                request.signedTenantGrantRevision(), request.signedAuthorizationRevision(),
                request.correlationId(), request.drainFields(),
                HrisServiceGrantAuthorityV1.sha256(bytes),
                HrisServiceGrantAuthorityV1.AUTHENTICATION);
    }

    private static GrantAuthority grant() {
        RsaPublicJwk caller = new RsaPublicJwk("RS256", "AQAB", "approval-key-1",
                "RSA", modulus((byte) 0x55), "sig");
        RsaPublicJwk owner = new RsaPublicJwk("RS256", "AQAB", "auth-key-1",
                "RSA", modulus((byte) 0x66), "sig");
        return new GrantAuthority(UUID.fromString("30000000-0000-0000-0000-000000000003"),
                11, 42, 7, 9, "ADMIT", "dwp-approval-server",
                "spiffe://dwp/service/dwp-approval-server", caller.kid(), caller,
                "dwp-auth-server", "spiffe://dwp/service/dwp-auth-server", owner.kid(), owner,
                "SVC-AUT-HRM-013", "HRIS_SEPARATION_REQUESTER_IDENTITY_RESOLVE",
                null, DIGEST_A, Instant.parse("2026-10-27T00:00:00Z"));
    }

    private static String modulus(byte fill) {
        byte[] bytes = new byte[256];
        Arrays.fill(bytes, fill);
        bytes[0] = (byte) 0x80;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void assertMalformed(byte[] bytes) {
        assertThatThrownBy(() -> HrisServiceGrantAuthorityV1.decodeRequest(MAPPER, bytes))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class)
                .hasMessage("MALFORMED_REQUEST")
                .hasMessageNotContaining("secret")
                .hasMessageNotContaining("grant")
                .hasMessageNotContaining("key")
                .hasMessageNotContaining("tenant");
    }
}
