package com.dwp.core.database.authority;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;

import static com.dwp.core.database.authority.RuntimeStartupValues.*;

/** Verifies only deployment-pinned public keys. No signing operation or private key is exposed. */
public final class RuntimeStartupSealSignatureVerifier {
    private RuntimeStartupSealSignatureVerifier() { }

    public static void verifySeal(RuntimeStreamStartupSeal seal, Map<String, PublicKey> trustedKeys) {
        verify("dwp-runtime-stream-startup-seal-claims-v1\n", seal.claims(),
                seal.keyId(), seal.signature(), trustedKeys);
    }
    public static void verifyLease(RuntimeStartupLease lease, Map<String, PublicKey> trustedKeys) {
        verify("dwp-runtime-startup-lease-claims-v1\n", lease.claims(),
                lease.keyId(), lease.signature(), trustedKeys);
    }
    static void validateSignatureEncoding(String encoded) {
        signatureEncoding(encoded);
    }
    private static void verify(String namespace, Object claims, String keyId, String signature,
            Map<String, PublicKey> trustedKeys) {
        validateSignatureEncoding(signature);
        require(trustedKeys != null && trustedKeys.containsKey(keyId), "untrusted or revoked signing key");
        try {
            PublicKey publicKey = trustedKeys.get(keyId);
            require(publicKey != null && (publicKey.getAlgorithm().equals("Ed25519")
                    || publicKey.getAlgorithm().equals("EdDSA")), "non-Ed25519 trust key rejected");
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update((namespace + RuntimeStartupSealJson.canonical(claims)).getBytes(StandardCharsets.UTF_8));
            require(verifier.verify(Base64.getUrlDecoder().decode(signature)), "signature rejected");
        } catch (Exception exception) { throw failure("signature verification rejected"); }
    }
}
