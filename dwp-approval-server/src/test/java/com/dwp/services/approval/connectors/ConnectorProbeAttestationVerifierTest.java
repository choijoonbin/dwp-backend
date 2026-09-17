package com.dwp.services.approval.connectors;

import com.dwp.core.common.ErrorCode;
import com.dwp.services.approval.document.ApprovalDocumentCanonical;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.approval.connectors.ConnectorModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectorProbeAttestationVerifierTest {
    private static final Instant NOW = Instant.parse("2026-09-16T02:00:00Z");
    private static final String ISSUER = "connector-owner";
    private static final String IDENTITY = "connector-probe-runtime";
    private static final String KEY_ID = "connector-key-1";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ApprovalDocumentCanonical canonical = new ApprovalDocumentCanonical(mapper);

    @Test
    void verifiesProbeRevisionRequestAndDiagnosticsExactly() throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        ConnectorProbeAttestationVerifier verifier = verifier(keys);
        Context context = context("RS_APPROVALS");
        ProbeView probe = probe();
        ProbeCompletion unsigned = unsigned();
        String[] proof = proof(keys, claims(context, probe, unsigned));
        ProbeCompletion signed = withProof(unsigned, proof);

        assertThat(verifier.verify(context, probe.connectorId(), probe, signed))
                .matches("verified:[a-f0-9]{64}");
        ProbeCompletion tampered = new ProbeCompletion(
                signed.expectedProbeVersion(), signed.state(), signed.evidenceRevision(),
                signed.evidenceSha256(), Map.of("status", 201), signed.completedAt(),
                signed.validUntil(), signed.evidencePayloadBase64Url(),
                signed.evidenceSignatureBase64Url());
        assertThatThrownBy(() -> verifier.verify(
                context, probe.connectorId(), probe, tampered))
                .isInstanceOfSatisfying(ConnectorRejected.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void missingTrustFailsUnavailable() {
        ConnectorProbeAttestationVerifier verifier = new ConnectorProbeAttestationVerifier(
                mapper, canonical, Clock.fixed(NOW, ZoneOffset.UTC), null);
        assertThatThrownBy(() -> verifier.verify(
                context("RS_APPROVALS"), UUID.randomUUID(), probe(), unsigned()))
                .isInstanceOfSatisfying(ConnectorRejected.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }

    private ConnectorProbeAttestationVerifier verifier(KeyPair keys) {
        return new ConnectorProbeAttestationVerifier(
                mapper, canonical, Clock.fixed(NOW, ZoneOffset.UTC),
                new ConnectorProbeAttestationVerifier.TrustedAttestor(
                        ISSUER, IDENTITY, KEY_ID, keys.getPublic()));
    }

    private Context context(String scope) {
        return new Context(42, scope, 17, UUID.randomUUID(), "complete-probe");
    }

    private ProbeView probe() {
        return new ProbeView(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                ProbeKind.SYNTHETIC_TEST, ProbeState.PENDING, "b".repeat(64),
                null, null, Map.of(), NOW.minusSeconds(30), null, null, 1);
    }

    private ProbeCompletion unsigned() {
        return new ProbeCompletion(
                1, ProbeState.VERIFIED, "provider-r17", "e".repeat(64),
                Map.of("status", 200), NOW, NOW.plusSeconds(300), null, null);
    }

    private ConnectorProbeAttestationVerifier.Claims claims(
            Context context,
            ProbeView probe,
            ProbeCompletion completion) {
        return new ConnectorProbeAttestationVerifier.Claims(
                ISSUER, IDENTITY, KEY_ID,
                ConnectorProbeAttestationVerifier.AUDIENCE,
                ConnectorProbeAttestationVerifier.PURPOSE,
                context.tenantId(), context.resourceSetKey(), probe.connectorId(),
                probe.probeId(), probe.revisionId(), completion.expectedProbeVersion(),
                probe.requestSha256(), completion.state().name(),
                completion.evidenceRevision(), completion.evidenceSha256(),
                canonical.fingerprint(completion.diagnostics()), completion.completedAt(),
                completion.validUntil(), NOW.minusSeconds(1), NOW.plusSeconds(120),
                "connector-proof-nonce-001");
    }

    private ProbeCompletion withProof(ProbeCompletion value, String[] proof) {
        return new ProbeCompletion(
                value.expectedProbeVersion(), value.state(), value.evidenceRevision(),
                value.evidenceSha256(), value.diagnostics(), value.completedAt(),
                value.validUntil(), proof[0], proof[1]);
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
