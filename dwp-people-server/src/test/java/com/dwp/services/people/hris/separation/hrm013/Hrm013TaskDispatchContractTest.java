package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Hrm013TaskDispatchContractTest {
    @Test
    void allThreeProfilesRoundTripOnlyCanonicalSealedBodies() {
        for (Hrm013TaskDispatchV1.Profile profile : Hrm013TaskDispatchV1.PROFILES) {
            var packet = Hrm013RuntimeTestSupport.dispatch(profile, 73L);
            byte[] exact = Hrm013TaskDispatchV1.encode(
                    profile, packet.tenantId(), packet.body());

            assertThat(Hrm013TaskDispatchV1.decode(
                    profile, packet.tenantId(), exact)).isEqualTo(packet.body());
            byte[] changed = exact.clone();
            changed[0] = (byte) (changed[0] == '{' ? '[' : '{');
            assertThatThrownBy(() -> Hrm013TaskDispatchV1.decode(
                    profile, packet.tenantId(), changed)).isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void dispatchIdentityIsDeterministicAndBindingSpecific() {
        UUID task = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID first = Hrm013TaskDispatchV1.dispatchEventId(
                1L, task, 1L, Hrm013TaskDispatchV1.TIME.bindingId());
        assertThat(first).isEqualTo(Hrm013TaskDispatchV1.dispatchEventId(
                1L, task, 1L, Hrm013TaskDispatchV1.TIME.bindingId()));
        assertThat(first).isNotEqualTo(Hrm013TaskDispatchV1.dispatchEventId(
                1L, task, 1L, Hrm013TaskDispatchV1.PAYROLL.bindingId()));
    }
}

final class Hrm013RuntimeTestSupport {
    static final String ZERO_SHA = "0".repeat(64);
    static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");

    private Hrm013RuntimeTestSupport() { }

    static DispatchPacket dispatch(Hrm013TaskDispatchV1.Profile profile, long tenantId) {
        UUID caseId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID eventId = Hrm013TaskDispatchV1.dispatchEventId(
                tenantId, taskId, 1L, profile.bindingId());
        Hrm013TaskDispatchV1.DispatchBody unsealed;
        if (profile.equals(Hrm013TaskDispatchV1.IDENTITY)) {
            unsealed = new Hrm013TaskDispatchV1.IdentityTaskDispatch(
                    Hrm013TaskDispatchV1.SCHEMA_NAME,
                    Hrm013TaskDispatchV1.SCHEMA_VERSION, eventId, caseId, taskId,
                    profile.taskType(), 1L, 1L, NOW, ZERO_SHA, UUID.randomUUID(),
                    UUID.randomUUID(), "RESIGNATION", LocalDate.parse("2026-11-01"));
        } else {
            unsealed = new Hrm013TaskDispatchV1.TimeAndPayrollTaskDispatch(
                    Hrm013TaskDispatchV1.SCHEMA_NAME,
                    Hrm013TaskDispatchV1.SCHEMA_VERSION, eventId, caseId, taskId,
                    profile.taskType(), 1L, 1L, NOW, ZERO_SHA, UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), "RESIGNATION",
                    LocalDate.parse("2026-11-01"), LocalDate.parse("2026-10-31"));
        }
        return new DispatchPacket(
                tenantId, profile, Hrm013TaskDispatchV1.seal(unsealed));
    }

    static Hrm013PersistenceModels.TaskDispatchFence fence(
            Hrm013TaskDispatchV1.Profile profile, long tenantId) {
        DispatchPacket packet = dispatch(profile, tenantId);
        byte[] body = Hrm013TaskDispatchV1.encode(profile, tenantId, packet.body());
        return new Hrm013PersistenceModels.TaskDispatchFence(
                packet.body().dispatchEventId(), tenantId, packet.body().casePublicId(),
                packet.body().taskPublicId(), profile.bindingId(),
                profile.ownerWorkloadId(), profile.purpose(), profile.taskType(),
                profile.eventType(), packet.body().taskVersion(),
                packet.body().taskOwnerRevision(), body,
                Hrm013TaskDispatchV1.requestBodySha256(body),
                packet.body().payloadSha256(), packet.body().correlationId(),
                7L, 11L, 7L, 11L, packet.body().dispatchedAt());
    }

    static List<Hrm013WorkloadProofV1.Header> headers(UUID correlationId) {
        return List.of(
                new Hrm013WorkloadProofV1.Header("Content-Type", "application/json"),
                new Hrm013WorkloadProofV1.Header("Accept", "application/json"),
                new Hrm013WorkloadProofV1.Header(
                        "X-DWP-Service-Identity", "dwp-people-server"),
                new Hrm013WorkloadProofV1.Header("X-DWP-Workload-Proof", "a.b.c"),
                new Hrm013WorkloadProofV1.Header(
                        "X-Correlation-ID", correlationId.toString()));
    }

    static KeyMaterial keys(String callerKid, String ownerKid) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair caller = generator.generateKeyPair();
            KeyPair owner = generator.generateKeyPair();
            return new KeyMaterial(caller, owner,
                    jwk(callerKid, (RSAPublicKey) caller.getPublic()),
                    jwk(ownerKid, (RSAPublicKey) owner.getPublic()));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    static Hrm013ServiceAuthorityPort.SigningAuthority authority(
            Hrm013ServiceAuthorityPort.Query query, String ownerWorkload,
            KeyMaterial keys, HrisServiceGrantAuthorityV1.DrainAdmission drain) {
        var grant = new Hrm013ServiceAuthorityPort.CurrentGrant(
                query.tenantId(), query.callerWorkloadId(), query.bindingId(),
                query.purpose(), query.signedTenantGrantRevision(),
                query.signedAuthorizationRevision());
        return new Hrm013ServiceAuthorityPort.SigningAuthority(
                query, grant, UUID.fromString("00000000-0000-0000-0000-000000000099"),
                3L, "spiffe://dwp/service/" + query.callerWorkloadId(),
                ownerWorkload, "spiffe://dwp/service/" + ownerWorkload,
                keys.callerJwk().kid(), keys.caller().getPublic(), keys.callerJwk(),
                keys.ownerJwk().kid(), keys.owner().getPublic(), keys.ownerJwk(),
                NOW.minusSeconds(1), drain);
    }

    private static HrisServiceGrantAuthorityV1.RsaPublicJwk jwk(
            String kid, RSAPublicKey key) {
        return new HrisServiceGrantAuthorityV1.RsaPublicJwk(
                "RS256", base64(key.getPublicExponent()), kid, "RSA",
                base64(key.getModulus()), "sig");
    }

    private static String base64(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    record DispatchPacket(
            long tenantId, Hrm013TaskDispatchV1.Profile profile,
            Hrm013TaskDispatchV1.DispatchBody body) { }

    record KeyMaterial(
            KeyPair caller, KeyPair owner,
            HrisServiceGrantAuthorityV1.RsaPublicJwk callerJwk,
            HrisServiceGrantAuthorityV1.RsaPublicJwk ownerJwk) { }
}
