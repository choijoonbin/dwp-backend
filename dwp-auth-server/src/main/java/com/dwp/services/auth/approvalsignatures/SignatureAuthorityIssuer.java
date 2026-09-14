package com.dwp.services.auth.approvalsignatures;

import static com.dwp.services.auth.approvalsignatures.SignatureAuthorityJson.*;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

public final class SignatureAuthorityIssuer {
    private final SignatureAuthorityKeys keys;
    private final Clock clock;
    public SignatureAuthorityIssuer(SignatureAuthorityKeys keys, Clock clock) { this.keys = keys; this.clock = clock; }
    public String issue(SignatureAuthorityProofVerifier.Verified proof, SignatureCurrentAuthority.Observation authority) {
        var b = proof.binding(); Instant now = clock.instant();
        Instant expires = authority.expiresAt().isBefore(proof.expiresAt()) ? authority.expiresAt() : proof.expiresAt();
        expires = Instant.ofEpochSecond(expires.getEpochSecond());
        if (!expires.isAfter(now) || !proof.sourceKind().equals(b.sourceKind()) || !b.registrySha256().equals(authority.registrySha256())
                || b.operation() == SignatureAuthorityProtocol.Operation.SIGN && !authority.highRiskVerified()) throw denied();
        var builder = new JWTClaimsSet.Builder().issuer("DWP_APPROVAL_SIGNATURE_AUTHORITY").audience("dwp-approval-server")
                .issueTime(Date.from(now)).expirationTime(Date.from(expires)).jwtID(UUID.randomUUID().toString())
                .claim("nonce", b.nonce().toString()).claim("contract", b.operation()==SignatureAuthorityProtocol.Operation.COMMAND_RECEIPT
                        ? "DWP_APPROVAL_SIGNATURE_COMMAND_RECEIPT_AUTHORITY_V1" : SignatureAuthorityProtocol.ATTESTATION)
                .claim("purpose", b.operation().name()).claim("method", b.operation().method()).claim("path", b.path())
                .claim("tenantId", b.tenantId()).claim("actorId", b.actorId()).claim("personPublicId", b.personPublicId().toString())
                .claim("resourceSetKey", b.resourceSetKey()).claim("contextKey", b.contextKey()).claim("contextScopeKey", b.contextScopeKey())
                .claim("decisionRevision", b.decisionRevision()).claim("registrySha256", authority.registrySha256())
                .claim("routeContractKey", b.operation().route()).claim("objectId", b.objectId().toString()).claim("bodySha256", b.bodySha256())
                .claim("grantSha256", authority.vectorSha256()).claim("installed", true).claim("signerKind", "SELF_ATTESTATION")
                .claim("accessMode", b.accessMode()).claim("highRiskVerified", authority.highRiskVerified())
                .claim("sourceSha256", b.sourceSha256()).claim("sourceKind", proof.sourceKind());
        if (b.operation().mutation()) builder.claim("objectVersion", b.objectVersion()).claim("idempotencyKey", b.idempotencyKey());
        if(b.operation()==SignatureAuthorityProtocol.Operation.COMMAND_RECEIPT) builder.claim("idempotencyKey",b.idempotencyKey())
                .claim("profileKey","approval.signature.command-receipt.v1").claim("sourceCurrent",b.source().path("sourceCurrent").booleanValue());
        try {
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(keys.signer().getKeyID()).build(), builder.build());
            jwt.sign(new RSASSASigner(keys.signer())); return jwt.serialize();
        } catch (Exception invalid) { throw unavailable(); }
    }
}
