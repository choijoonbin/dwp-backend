package com.dwp.services.approval.signatures;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.*;
import java.util.HashSet;
import java.util.List;
import org.springframework.core.env.Environment;

/** One key-isolation implementation shared by source capture and the actual artifact signer. */
final class ApprovalSignatureSignerFactory {
    private ApprovalSignatureSignerFactory() { }
    static ApprovalSignatureEvidenceSigner create(Environment env, ObjectMapper mapper, ApprovalSignatureCanonical canonical,
            JWKSet trust, String privateJwk, String prohibited, boolean inventory) throws Exception {
        var forbidden = new HashSet<String>();
        collect(prohibited, forbidden);
        for (var key : trust.getKeys()) forbidden.add(key.toPublicJWK().computeThumbprint().toString());
        for (String property : List.of("dwp.approval.step-up.public-key-pem", "dwp.approval.workflow-runtime-authority.owner-private-key",
                "dwp.approval.workflow-runtime-authority.transport-private-key", "dwp.approval.workflow-runtime-authority.auth-attestation-trusted-keys",
                "dwp.approval.workflow-runtime-authority.prohibited-trusted-keys", "dwp.approval.form-user-source-private-jwk",
                "dwp.approval.policy-impact.source.owner-private-jwk", "dwp.approval.policy-impact.source.transport-private-jwk",
                "dwp.approval.policy-impact.source.attestation-public-jwks", "dwp.approval.information-replay.owner-private-key",
                "dwp.approval.information-replay.transport-private-key", "dwp.approval.information-replay.auth-attestation-trusted-keys",
                "dwp.approval.information-replay.prohibited-trusted-keys", "dwp.approval.internal-signatures.source.owner-private-jwk",
                "dwp.approval.internal-signatures.source.transport-private-jwk")) collect(env.getProperty(property, ""), forbidden);
        var key = privateJwk.isBlank() ? null : RSAKey.parse(privateJwk);
        return new ApprovalSignatureEvidenceSigner(key, forbidden, inventory && !prohibited.isBlank(), canonical);
    }
    static void collect(String raw, java.util.Set<String> forbidden) throws Exception {
        if (raw == null || raw.isBlank()) return; if (raw.length() > 65536) throw ApprovalSignatureCanonical.unavailable();
        List<JWK> keys;
        if (raw.stripLeading().startsWith("{")) keys = raw.contains("\"keys\"") ? JWKSet.parse(raw).getKeys() : List.of(JWK.parse(raw));
        else keys = List.of(ApprovalSignatureRsaKeyReader.read(raw));
        for (var key : keys) forbidden.add(key.toPublicJWK().computeThumbprint().toString());
    }
}
