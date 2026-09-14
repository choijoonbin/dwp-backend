package com.dwp.services.approval.signatures;

import static com.dwp.services.approval.signatures.ApprovalSignatureCanonical.*;
import com.dwp.services.approval.signatures.ApprovalSignatureDtos.Evidence;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.*;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A service-signed self-attestation receipt, not a personal certificate or legal e-signature. */
public final class ApprovalSignatureEvidenceSigner {
    private final RSAKey key;
    private final ApprovalSignatureCanonical canonical;
    public ApprovalSignatureEvidenceSigner(RSAKey key, Set<String> prohibitedThumbprints,
            boolean keyIsolationInventoryComplete, ApprovalSignatureCanonical canonical) {
        this.canonical=canonical;
        if (key==null || !keyIsolationInventoryComplete) { this.key=null; return; }
        try {
            if (!key.isPrivate() || key.size()<2048 || key.getKeyID()==null || !key.getKeyID().startsWith("approval-self-attestation:")
                    || !KeyUse.SIGNATURE.equals(key.getKeyUse()) || !JWSAlgorithm.RS256.equals(key.getAlgorithm())
                    || prohibitedThumbprints.contains(key.toPublicJWK().computeThumbprint().toString())) throw unavailable();
            var test=new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),new Payload("key-pair-verification"));
            test.sign(new RSASSASigner(key)); if (!test.verify(new RSASSAVerifier(key.toPublicJWK()))) throw unavailable();
            this.key=key;
        } catch (Exception error) { throw unavailable(); }
    }
    public String readiness() { return key==null?"NOT_VERIFIED":"VERIFIED_INTERNAL_KEY"; }
    public String keySha256() {
        if (key==null) return null;
        try { return java.util.HexFormat.of().formatHex(key.toPublicJWK().computeThumbprint().decode()); } catch (Exception error) { throw unavailable(); }
    }
    public Evidence sign(UUID ceremonyId, long actorId, String sourceDigest, String artifactSha256,
            UUID consentId, String authorityDigest, Instant at) {
        if (key==null) throw unavailable();
        try {
            UUID id=UUID.randomUUID();
            var payload=Map.of("contract","DWP_SELF_ATTESTATION_EVIDENCE_V1","signerKind","SELF_ATTESTATION",
                    "evidenceId",id,"signatureRequestId",ceremonyId,"signerUserId",actorId,"sourceDigest",sourceDigest,
                    "artifactSha256",artifactSha256,"consentReceiptId",consentId,"authorityDigest",authorityDigest,"attestedAt",at);
            var jws=new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID())
                    .type(new JOSEObjectType("dwp-self-attestation+jws")).build(),new Payload(canonical.json(payload)));
            jws.sign(new RSASSASigner(key)); if (!jws.verify(new RSASSAVerifier(key.toPublicJWK()))) throw unavailable();
            return new Evidence(id,"SELF_ATTESTATION",key.getKeyID(),key.toPublicJWK().toJSONString(),artifactSha256,sourceDigest,jws.serialize(),at);
        } catch (Exception error) { throw unavailable(); }
    }
}
