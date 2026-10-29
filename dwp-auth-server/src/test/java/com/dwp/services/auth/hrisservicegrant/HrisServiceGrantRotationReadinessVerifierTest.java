package com.dwp.services.auth.hrisservicegrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.ServiceRole;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class HrisServiceGrantRotationReadinessVerifierTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HrisServiceGrantAuthorityV1.TargetTuple TARGET =
            HrisServiceGrantQuiesceService.REQUESTER;
    private static final UUID GRANT = UUID.fromString(
            "10000000-0000-0000-0000-000000000001");
    private static final UUID PREDECESSOR = UUID.fromString(
            "10000000-0000-0000-0000-000000000002");
    private static final UUID FENCE = UUID.fromString(
            "20000000-0000-0000-0000-000000000001");
    private static final Instant EFFECTIVE = Instant.parse("2026-10-27T00:00:00Z");
    private static final Instant ELIGIBLE = EFFECTIVE.plusSeconds(65);
    private static final Instant NOW = EFFECTIVE.plusSeconds(70);
    private static KeyPair callerKey;
    private static KeyPair ownerKey;
    private static KeyPair wrongKey;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        callerKey = generator.generateKeyPair();
        ownerKey = generator.generateKeyPair();
        wrongKey = generator.generateKeyPair();
    }

    @Test
    void verifiesExactDetachedReceiptsAndExposesAtomicReplayReservations() {
        Fixture fixture = fixture();
        ReadinessItem caller = item(fixture, ServiceRole.CALLER, callerKey, "caller-v1",
                ignored -> { }, ignored -> { });
        ReadinessItem owner = item(fixture, ServiceRole.OWNER, ownerKey, "owner-v1",
                ignored -> { }, ignored -> { });

        assertThat(detachedSignatureVerifiesAgainstCarriedBytes(caller, callerKey)).isTrue();
        assertThat(detachedSignatureVerifiesAgainstCarriedBytes(owner, ownerKey)).isTrue();

        var verified = new HrisServiceGrantRotationReadinessVerifier(JSON).verifySigned(
                fixture.locked(), fixture.authority(), fixture.observations(),
                List.of(owner, caller));

        assertThat(verified.receipts()).hasSize(2);
        assertThat(verified.receipts()).extracting(value -> value.role().name())
                .containsExactlyInAnyOrder("CALLER", "OWNER");
        assertThat(verified.receipts()).allSatisfy(value -> {
            assertThat(value.replay().proofIdSha256()).matches("[0-9a-f]{64}");
            assertThat(value.replay().requestNonceSha256()).matches("[0-9a-f]{64}");
            assertThat(value.replay().proofIdSha256())
                    .isNotEqualTo(value.replay().requestNonceSha256());
            assertThat(value.replay().retainUntil()).isEqualTo(EFFECTIVE.plusSeconds(200));
        });
        assertThat(verified.rotationReadinessDigest()).matches("[0-9a-f]{64}");
        assertThat(verified.rotationReadinessDigest()).isEqualTo(
                HrisServiceGrantRotationReadinessVerifier.aggregateVerified(
                        verified.receipts().reversed()));
    }

    @Test
    void replayReservationExpiresAtExactProofExpiryAtMaximumLifetime() {
        Fixture fixture = fixture();
        Instant maximumExpiry = EFFECTIVE.plusSeconds(367);
        ReadinessItem caller = item(fixture, ServiceRole.CALLER, callerKey, "caller-v1",
                ignored -> { }, claims -> claims.put("exp", maximumExpiry.getEpochSecond()));

        var verified = new HrisServiceGrantRotationReadinessVerifier(JSON).verifySigned(
                fixture.locked(), fixture.authority(), fixture.observations(),
                List.of(caller, validOwner(fixture)));

        assertThat(verified.receipts()).filteredOn(value -> value.role() == ServiceRole.CALLER)
                .singleElement().satisfies(value ->
                        assertThat(value.replay().retainUntil()).isEqualTo(maximumExpiry));
    }

    @Test
    void futureSkewMaximumLifetimeClampsReplayExpiryToDatabaseLimit() {
        Fixture fixture = fixture();
        Instant issuedAt = NOW.plusSeconds(5);
        Instant proofExpiry = issuedAt.plusSeconds(300);
        ReadinessItem caller = item(fixture, ServiceRole.CALLER, callerKey, "caller-v1",
                receipt -> receipt.put("observedAt", NOW.toString()), claims -> {
                    claims.put("iat", issuedAt.getEpochSecond());
                    claims.put("nbf", issuedAt.getEpochSecond());
                    claims.put("exp", proofExpiry.getEpochSecond());
                });

        var verified = new HrisServiceGrantRotationReadinessVerifier(JSON).verifySigned(
                fixture.locked(), fixture.authority(), fixture.observations(),
                List.of(caller, validOwner(fixture)));

        assertThat(verified.receipts()).filteredOn(value -> value.role() == ServiceRole.CALLER)
                .singleElement().satisfies(value -> {
                    assertThat(proofExpiry).isEqualTo(NOW.plusSeconds(305));
                    assertThat(value.replay().retainUntil()).isEqualTo(NOW.plusSeconds(300));
                });
    }

    @Test
    void rejectsWrongRoleKeyTamperAndNoncanonicalOrDuplicateJson() {
        Fixture fixture = fixture();
        ReadinessItem caller = item(fixture, ServiceRole.CALLER, callerKey, "caller-v1",
                ignored -> { }, ignored -> { });
        ReadinessItem ownerWrongKey = item(fixture, ServiceRole.OWNER, wrongKey, "owner-v1",
                ignored -> { }, ignored -> { });
        assertRejected(fixture, caller, ownerWrongKey);

        byte[] spaced = Arrays.copyOf(caller.receiptBytes(), caller.receiptBytes().length + 1);
        spaced[spaced.length - 1] = ' ';
        assertRejected(fixture,
                new ReadinessItem(spaced, caller.proofClaimBytes(), caller.readinessProof()),
                validOwner(fixture));

        String canonical = new String(caller.receiptBytes(), StandardCharsets.UTF_8);
        byte[] duplicate = ("{\"tenantId\":7," + canonical.substring(1))
                .getBytes(StandardCharsets.UTF_8);
        assertRejected(fixture,
                new ReadinessItem(duplicate, caller.proofClaimBytes(), caller.readinessProof()),
                validOwner(fixture));

        byte[] changedClaims = caller.proofClaimBytes();
        changedClaims[changedClaims.length - 2] ^= 1;
        assertRejected(fixture,
                new ReadinessItem(caller.receiptBytes(), changedClaims,
                        caller.readinessProof()), validOwner(fixture));
    }

    @Test
    void rejectsExpiredAndPreEligibilityReceiptsAndDuplicateCoverage() {
        Fixture fixture = fixture();
        ReadinessItem expired = item(fixture, ServiceRole.CALLER, callerKey, "caller-v1",
                receipt -> receipt.put("expiresAt", NOW.minusSeconds(1).toString()),
                ignored -> { });
        assertRejected(fixture, expired, validOwner(fixture));

        ReadinessItem early = item(fixture, ServiceRole.CALLER, callerKey, "caller-v1",
                receipt -> receipt.put("observedAt", ELIGIBLE.minusSeconds(1).toString()),
                ignored -> { });
        assertRejected(fixture, early, validOwner(fixture));

        ReadinessItem caller = item(fixture, ServiceRole.CALLER, callerKey, "caller-v1",
                ignored -> { }, ignored -> { });
        assertRejected(fixture, caller, caller);
    }

    @Test
    void rejectsClaimAndReceiptDriftEvenWhenResigned() {
        Fixture fixture = fixture();
        ReadinessItem pending = item(fixture, ServiceRole.CALLER, callerKey, "caller-v1",
                receipt -> receipt.put("pendingCount", 1), ignored -> { });
        assertRejected(fixture, pending, validOwner(fixture));

        ReadinessItem crossTuple = item(fixture, ServiceRole.CALLER, callerKey, "caller-v1",
                ignored -> { }, claims -> claims.put("bindingPurpose", "OTHER_PURPOSE"));
        assertRejected(fixture, crossTuple, validOwner(fixture));

        ReadinessItem future = item(fixture, ServiceRole.CALLER, callerKey, "caller-v1",
                receipt -> receipt.put("observedAt", NOW.plusSeconds(6).toString()),
                ignored -> { });
        assertRejected(fixture, future, validOwner(fixture));
    }

    @Test
    void observationAndGroupDigestsAreRfc8785StableGoldenVectors() {
        Fixture fixture = fixture();
        assertThat(fixture.observations().getFirst().readinessObservationSha256())
                .isEqualTo("c84c94badf548497ef397a772b5102937332fcc6ab73448e36fdf2ef404ad7bd");
        assertThat(HrisServiceGrantRotationReadinessVerifier.aggregateDigest(List.of(
                "b".repeat(64), "a".repeat(64))))
                .isEqualTo("2ba3056e95d23c0c154eca683e330401c46e441d5074523e16349e29d7597a64");
    }

    private static void assertRejected(Fixture fixture,
            ReadinessItem first, ReadinessItem second) {
        assertThatThrownBy(() -> new HrisServiceGrantRotationReadinessVerifier(JSON)
                .verifySigned(fixture.locked(), fixture.authority(), fixture.observations(),
                        List.of(first, second)))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class);
    }

    private static ReadinessItem validOwner(Fixture fixture) {
        return item(fixture, ServiceRole.OWNER, ownerKey, "owner-v1",
                ignored -> { }, ignored -> { });
    }

    private static Fixture fixture() {
        var callerJwk = jwk(callerKey, "caller-v1");
        var ownerJwk = jwk(ownerKey, "owner-v1");
        var authority = new HrisServiceGrantAuthorityV1.GrantAuthority(
                GRANT, 5, 7, 11, 13, "ADMIT", TARGET.callerWorkloadId(),
                "spiffe://dwp/service/" + TARGET.callerWorkloadId(), "caller-v1", callerJwk,
                TARGET.ownerWorkloadId(),
                "spiffe://dwp/service/" + TARGET.ownerWorkloadId(), "owner-v1", ownerJwk,
                TARGET.bindingId(), TARGET.purpose(), PREDECESSOR, "a".repeat(64),
                EFFECTIVE.minusSeconds(5));
        var locked = new HrisServiceGrantRotationReadinessVerifier.LockedRotation(
                7, TARGET, GRANT, 11, 13, FENCE, 3, EFFECTIVE, NOW);
        return new Fixture(locked, authority, List.of(
                observation(locked, ServiceRole.CALLER),
                observation(locked, ServiceRole.OWNER)));
    }

    private static HrisServiceGrantAuthorityV1.ReadinessObservation observation(
            HrisServiceGrantRotationReadinessVerifier.LockedRotation locked,
            ServiceRole role) {
        var fence = new HrisServiceGrantQuiesceService.Fence(FENCE, GRANT, 3,
                "QUIESCING", EFFECTIVE);
        String digest = HrisServiceGrantRotationReadinessVerifier.observationDigest(
                locked.tenantId(), TARGET, GRANT, fence, role, 0, EFFECTIVE, ELIGIBLE);
        return new HrisServiceGrantAuthorityV1.ReadinessObservation(locked.tenantId(),
                GRANT, 11, 13, TARGET.callerWorkloadId(), TARGET.ownerWorkloadId(),
                TARGET.bindingId(), TARGET.purpose(), FENCE, 3, EFFECTIVE, role, 0,
                EFFECTIVE, ELIGIBLE, digest);
    }

    private static ReadinessItem item(Fixture fixture, ServiceRole role, KeyPair signer,
            String headerKeyId, Consumer<ObjectNode> receiptMutation,
            Consumer<ObjectNode> claimsMutation) {
        try {
            var observation = fixture.observations().stream()
                    .filter(value -> value.attestingServiceRole() == role).findFirst().orElseThrow();
            String workload = role == ServiceRole.CALLER
                    ? TARGET.callerWorkloadId() : TARGET.ownerWorkloadId();
            UUID correlation = role == ServiceRole.CALLER
                    ? UUID.fromString("30000000-0000-0000-0000-000000000001")
                    : UUID.fromString("30000000-0000-0000-0000-000000000002");
            ObjectNode receipt = JSON.createObjectNode();
            receipt.put("schemaName", HrisServiceGrantRotationReadinessVerifier.SCHEMA);
            receipt.put("schemaVersion", 1);
            receipt.put("tenantId", 7);
            receipt.put("bindingId", TARGET.bindingId());
            receipt.put("callerWorkloadId", TARGET.callerWorkloadId());
            receipt.put("ownerWorkloadId", TARGET.ownerWorkloadId());
            receipt.put("bindingPurpose", TARGET.purpose());
            receipt.put("serviceWorkloadId", workload);
            receipt.put("attestingServiceRole", role.name());
            receipt.put("oldGrantSetId", GRANT.toString());
            receipt.put("quiesceFenceId", FENCE.toString());
            receipt.put("quiesceRevision", 3);
            receipt.put("quiesceEffectiveAt", EFFECTIVE.toString());
            receipt.put("observedDrainActivityRevision", 0);
            receipt.put("drainQuietSince", EFFECTIVE.toString());
            receipt.put("readinessEligibleAt", ELIGIBLE.toString());
            receipt.put("readinessObservationSha256",
                    observation.readinessObservationSha256());
            receipt.put("observedGrantRevision", 5);
            receipt.put("tenantGrantRevision", 11);
            receipt.put("authorizationRevision", 13);
            receipt.put("pendingCount", 0);
            receipt.putNull("oldestPendingAt");
            receipt.put("observedAt", EFFECTIVE.plusSeconds(66).toString());
            receipt.put("expiresAt", EFFECTIVE.plusSeconds(150).toString());
            receipt.put("correlationId", correlation.toString());
            receiptMutation.accept(receipt);
            receipt.put("evidenceSha256", Hrm013WorkloadProofV1.sha256(
                    HrisServiceGrantRotationReadinessVerifier.canonicalBytes(receipt)));
            byte[] receiptBytes = HrisServiceGrantRotationReadinessVerifier
                    .canonicalBytes(receipt);

            ObjectNode claims = JSON.createObjectNode();
            claims.put("iss", workload); claims.put("aud", "dwp-auth-server");
            claims.put("sub", workload);
            Instant observedAt = Instant.parse(receipt.get("observedAt").textValue());
            Instant receiptExpiry = Instant.parse(receipt.get("expiresAt").textValue());
            claims.put("iat", observedAt.plusSeconds(1).getEpochSecond());
            claims.put("nbf", observedAt.plusSeconds(1).getEpochSecond());
            claims.put("exp", receiptExpiry.plusSeconds(50).getEpochSecond());
            claims.put("jti", role == ServiceRole.CALLER
                    ? "40000000-0000-0000-0000-000000000001"
                    : "40000000-0000-0000-0000-000000000002");
            claims.put("requestNonce", role == ServiceRole.CALLER
                    ? "50000000-0000-0000-0000-000000000001"
                    : "50000000-0000-0000-0000-000000000002");
            claims.put("tenantId", 7); claims.put("bindingId", TARGET.bindingId());
            claims.put("callerWorkloadId", TARGET.callerWorkloadId());
            claims.put("ownerWorkloadId", TARGET.ownerWorkloadId());
            claims.put("bindingPurpose", TARGET.purpose());
            claims.put("serviceWorkloadId", workload);
            claims.put("attestingServiceRole", role.name());
            claims.put("oldGrantSetId", GRANT.toString());
            claims.put("quiesceFenceId", FENCE.toString());
            claims.put("quiesceRevision", 3);
            claims.put("quiesceEffectiveAt", EFFECTIVE.toString());
            claims.put("observedDrainActivityRevision", 0);
            claims.put("drainQuietSince", EFFECTIVE.toString());
            claims.put("readinessEligibleAt", ELIGIBLE.toString());
            claims.put("readinessObservationSha256",
                    observation.readinessObservationSha256());
            claims.put("observedGrantRevision", 5);
            claims.put("tenantGrantRevision", 11);
            claims.put("authorizationRevision", 13);
            claims.put("evidenceSha256", receipt.get("evidenceSha256").textValue());
            claims.put("correlationId", correlation.toString());
            claims.put("proofPurpose", HrisServiceGrantRotationReadinessVerifier.PROOF_PURPOSE);
            claimsMutation.accept(claims);
            byte[] claimBytes = HrisServiceGrantRotationReadinessVerifier.canonicalBytes(claims);

            ObjectNode header = JSON.createObjectNode();
            header.put("alg", "RS256");
            header.put("typ", HrisServiceGrantRotationReadinessVerifier.PROOF_TYPE);
            header.put("kid", headerKeyId);
            String encodedHeader = encode(
                    HrisServiceGrantRotationReadinessVerifier.canonicalBytes(header));
            String signingInput = encodedHeader + "." + encode(claimBytes);
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(signer.getPrivate());
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            String compact = encodedHeader + ".." + encode(signature.sign());
            return new ReadinessItem(receiptBytes, claimBytes, compact);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static HrisServiceGrantAuthorityV1.RsaPublicJwk jwk(
            KeyPair keyPair, String keyId) {
        RSAPublicKey key = (RSAPublicKey) keyPair.getPublic();
        return new HrisServiceGrantAuthorityV1.RsaPublicJwk("RS256",
                encode(unsigned(key.getPublicExponent().toByteArray())), keyId, "RSA",
                encode(unsigned(key.getModulus().toByteArray())), "sig");
    }

    private static byte[] unsigned(byte[] value) {
        return value.length > 1 && value[0] == 0 ? Arrays.copyOfRange(value, 1, value.length)
                : value;
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static boolean detachedSignatureVerifiesAgainstCarriedBytes(
            ReadinessItem item, KeyPair signer) {
        try {
            String[] proof = item.readinessProof().split("\\.", -1);
            if (proof.length != 3 || !proof[1].isEmpty()) return false;
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(signer.getPublic());
            verifier.update((proof[0] + "." + encode(item.proofClaimBytes()))
                    .getBytes(StandardCharsets.US_ASCII));
            return verifier.verify(Base64.getUrlDecoder().decode(proof[2]));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record Fixture(
            HrisServiceGrantRotationReadinessVerifier.LockedRotation locked,
            HrisServiceGrantAuthorityV1.GrantAuthority authority,
            List<HrisServiceGrantAuthorityV1.ReadinessObservation> observations) { }
}
