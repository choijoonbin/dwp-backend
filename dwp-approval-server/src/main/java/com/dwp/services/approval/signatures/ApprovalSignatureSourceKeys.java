package com.dwp.services.approval.signatures;

import static com.dwp.services.approval.signatures.ApprovalSignatureCanonical.*;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.*;
import java.util.List;
import org.springframework.core.env.Environment;

final class ApprovalSignatureSourceKeys {
    final RSAKey owner, transport;
    final JWKSet authority;
    ApprovalSignatureSourceKeys(Environment env) {
        String prefix = "dwp.approval.internal-signatures.source.";
        try {
            if (!env.getProperty(prefix + "key-isolation-inventory-complete", Boolean.class, false)
                    || env.getProperty(prefix + "prohibited-jwks", "").isBlank()) throw unavailable();
            owner = RSAKey.parse(env.getRequiredProperty(prefix + "owner-private-jwk"));
            transport = RSAKey.parse(env.getRequiredProperty(prefix + "transport-private-jwk"));
            authority = JWKSet.parse(env.getRequiredProperty("dwp.approval.internal-signatures.authority-public-jwks"));
            check(owner, "approval-signature-owner:", true); check(transport, "approval-signature-transport:", true);
            if (authority.getKeys().isEmpty() || authority.getKeys().size() > 8) throw unavailable();
            var ids = new java.util.HashSet<String>(); var material = new java.util.HashSet<String>();
            for (JWK key : java.util.stream.Stream.concat(java.util.stream.Stream.of(owner, transport), authority.getKeys().stream()).toList()) {
                if (!(key instanceof RSAKey rsa)) throw unavailable();
                if (key != owner && key != transport) check(rsa, "approval-signature-authority:", false);
                if (!ids.add(key.getKeyID()) || !material.add(key.toPublicJWK().computeThumbprint().toString())) throw unavailable();
            }
            var forbidden = new java.util.HashSet<String>();
            for (String name : List.of(prefix + "prohibited-jwks", "dwp.approval.internal-signatures.signing-private-jwk",
                    "dwp.approval.step-up.public-key-pem",
                    "dwp.approval.workflow-runtime-authority.owner-private-key", "dwp.approval.workflow-runtime-authority.transport-private-key",
                    "dwp.approval.workflow-runtime-authority.auth-attestation-trusted-keys", "dwp.approval.form-user-source-private-jwk",
                    "dwp.approval.policy-impact.source.owner-private-jwk", "dwp.approval.policy-impact.source.transport-private-jwk",
                    "dwp.approval.policy-impact.source.attestation-public-jwks", "dwp.approval.information-replay.owner-private-key",
                    "dwp.approval.information-replay.transport-private-key", "dwp.approval.information-replay.auth-attestation-trusted-keys"))
                ApprovalSignatureSignerFactory.collect(env.getProperty(name, ""), forbidden);
            if (material.stream().anyMatch(forbidden::contains)) throw unavailable();
        } catch (Exception invalid) { throw unavailable(); }
    }
    private static void check(RSAKey key, String prefix, boolean privateKey) {
        if (key.isPrivate() != privateKey || key.size() < 2048 || key.getKeyID() == null || !key.getKeyID().startsWith(prefix)
                || key.getKeyID().length() > 100 || !KeyUse.SIGNATURE.equals(key.getKeyUse()) || !JWSAlgorithm.RS256.equals(key.getAlgorithm())) throw unavailable();
    }
}
