package com.dwp.services.approval.policyautomation;

import com.dwp.core.common.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import static com.dwp.services.approval.policyautomation.PolicyAutomationModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyChannelAttestationVerifierTest {
    private static final Instant NOW = Instant.parse("2026-09-16T02:00:00Z");
    private static final String ISSUER = "notification-owner";
    private static final String IDENTITY = "notification-runtime";
    private static final String KEY_ID = "notification-key-1";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void verifiesExactBindingAndRejectsTamperedScope() throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        PolicyChannelAttestationVerifier verifier = verifier(keys);
        Context context = context("RS_APPROVALS");
        UUID channelId = UUID.randomUUID();
        ChannelObservation unsigned = unsigned();
        String[] proof = proof(keys, claims(context, channelId, unsigned));
        ChannelObservation signed = withProof(unsigned, proof);

        assertThat(verifier.verify(context, channelId, signed))
                .matches("verified:[a-f0-9]{64}");
        assertThatThrownBy(() -> verifier.verify(context("RS_OTHER"), channelId, signed))
                .isInstanceOfSatisfying(PolicyAutomationRejected.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void missingTrustFailsUnavailable() {
        PolicyChannelAttestationVerifier verifier = new PolicyChannelAttestationVerifier(
                mapper, Clock.fixed(NOW, ZoneOffset.UTC), null);
        assertThatThrownBy(() -> verifier.verify(
                context("RS_APPROVALS"), UUID.randomUUID(), unsigned()))
                .isInstanceOfSatisfying(PolicyAutomationRejected.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }

    private PolicyChannelAttestationVerifier verifier(KeyPair keys) {
        return new PolicyChannelAttestationVerifier(
                mapper, Clock.fixed(NOW, ZoneOffset.UTC),
                new PolicyChannelAttestationVerifier.TrustedAttestor(
                        ISSUER, IDENTITY, KEY_ID, keys.getPublic()));
    }

    private Context context(String scope) {
        return new Context(42, scope, 17, UUID.randomUUID(), "observe-channel");
    }

    private ChannelObservation unsigned() {
        return new ChannelObservation(
                UUID.randomUUID(), Readiness.READY, "notification-r12", "a".repeat(64),
                NOW.minusSeconds(10), NOW.plusSeconds(300), 3, null, null);
    }

    private PolicyChannelAttestationVerifier.Claims claims(
            Context context,
            UUID channelId,
            ChannelObservation observation) {
        return new PolicyChannelAttestationVerifier.Claims(
                ISSUER, IDENTITY, KEY_ID,
                PolicyChannelAttestationVerifier.AUDIENCE,
                PolicyChannelAttestationVerifier.PURPOSE,
                context.tenantId(), context.resourceSetKey(), channelId,
                observation.expectedVersion(), observation.readiness().name(),
                observation.sourceRevision(), observation.evidenceSha256(),
                observation.observedAt(), observation.validUntil(),
                NOW.minusSeconds(1), NOW.plusSeconds(120), "channel-proof-nonce-001");
    }

    private ChannelObservation withProof(ChannelObservation value, String[] proof) {
        return new ChannelObservation(
                value.observationId(), value.readiness(), value.sourceRevision(),
                value.evidenceSha256(), value.observedAt(), value.validUntil(),
                value.expectedVersion(), proof[0], proof[1]);
    }

    private String[] proof(KeyPair keys, Object claims) throws Exception {
        byte[] payload = mapper.writeValueAsBytes(claims);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(keys.getPrivate());
        signature.update(payload);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return new String[]{encoder.encodeToString(payload),
                encoder.encodeToString(signature.sign())};
    }
}
