package com.dwp.migration.control.startup.v1;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

import com.dwp.core.database.authority.RuntimeStartupLease;
import com.dwp.core.database.authority.RuntimeStartupSealJson;
import com.dwp.core.database.authority.RuntimeStartupSealSignatureVerifier;

/** External Control only. No application bean, key generation, key export or seal approval. */
public final class ControlStartupLeaseSignerV1 {
    private final String keyId;
    private final long keyRevision;
    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    public ControlStartupLeaseSignerV1(String keyId, long keyRevision,
            PrivateKey privateKey, PublicKey independentlyPinnedPublicKey) {
        if (keyId == null || !keyId.matches("[a-z][a-z0-9_.-]{0,127}") || keyRevision < 1) {
            throw rejected();
        }
        this.keyId = keyId;
        this.keyRevision = keyRevision;
        this.privateKey = Objects.requireNonNull(privateKey);
        this.publicKey = Objects.requireNonNull(independentlyPinnedPublicKey);
        try {
            Signature signature = Signature.getInstance("Ed25519");
            byte[] challenge = "dwp-external-startup-key-pair-check-v1".getBytes(StandardCharsets.UTF_8);
            signature.initSign(privateKey);
            signature.update(challenge);
            byte[] signed = signature.sign();
            signature.initVerify(publicKey);
            signature.update(challenge);
            if (!signature.verify(signed)) throw rejected();
        } catch (Exception exception) { throw rejected(); }
    }

    public long keyRevision() { return keyRevision; }

    String sign(RuntimeStartupLease.Claims claims) {
        if (claims.keyRevision() != keyRevision) throw rejected();
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(("dwp-runtime-startup-lease-claims-v1\n"
                    + RuntimeStartupSealJson.canonical(claims)).getBytes(StandardCharsets.UTF_8));
            var envelope = new RuntimeStartupLease(claims, keyId,
                    Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()));
            RuntimeStartupSealSignatureVerifier.verifyLease(envelope, Map.of(keyId, publicKey));
            return RuntimeStartupSealJson.canonical(envelope);
        } catch (Exception exception) { throw rejected(); }
    }

    private static IllegalStateException rejected() {
        return new IllegalStateException("external startup signing rejected");
    }
}
