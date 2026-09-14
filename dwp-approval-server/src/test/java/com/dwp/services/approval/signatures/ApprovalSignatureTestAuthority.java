package com.dwp.services.approval.signatures;

import com.dwp.services.approval.security.ApprovalRequestContext;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import java.time.Clock;
import java.util.Date;
import java.util.UUID;
import java.util.function.Consumer;

final class ApprovalSignatureTestAuthority implements ApprovalSignatureAuthority.Source {
    final RSAKey key;
    final Clock clock;
    boolean installed=true,highRisk=true;
    String grant="a".repeat(64),scope="RS_APPROVALS";
    Consumer<JWTClaimsSet.Builder> change=builder -> { };
    ApprovalSignatureTestAuthority(Clock clock) {
        this.clock=clock;
        try { key=new RSAKeyGenerator(2048).keyID("approval-signature-authority:test").keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate(); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    public String currentSignedAssertion(ApprovalSignatureAuthority.Binding binding,UUID nonce) {
        return token(binding,nonce,change);
    }
    String token(ApprovalSignatureAuthority.Binding binding,UUID nonce,Consumer<JWTClaimsSet.Builder> mutate) {
        var actor=ApprovalRequestContext.require(); var now=clock.instant();
        var b=new JWTClaimsSet.Builder().issuer("DWP_APPROVAL_SIGNATURE_AUTHORITY").audience("dwp-approval-server")
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(30))).jwtID(UUID.randomUUID().toString())
                .claim("nonce",nonce.toString()).claim("contract","DWP_APPROVAL_SIGNATURE_AUTHORITY_V1").claim("purpose",binding.operation().name())
                .claim("method",binding.operation().method).claim("path",binding.operation().path(binding.objectId()))
                .claim("tenantId",actor.tenantId()).claim("actorId",actor.userId()).claim("personPublicId",actor.personPublicId().toString())
                .claim("resourceSetKey",scope).claim("contextKey","context").claim("contextScopeKey","opaque")
                .claim("decisionRevision","psr-"+"a".repeat(64)).claim("registrySha256","b".repeat(64))
                .claim("routeContractKey",binding.operation().route()).claim("objectId",binding.objectId().toString())
                .claim("bodySha256",binding.bodySha256()).claim("grantSha256",grant).claim("installed",installed)
                .claim("signerKind","SELF_ATTESTATION").claim("accessMode","NORMAL").claim("highRiskVerified",highRisk)
                .claim("sourceKind","ARTIFACT").claim("sourceSha256","a".repeat(64));
        if (binding.expectedVersion()!=null) b.claim("objectVersion",binding.expectedVersion());
        if (binding.idempotencyKey()!=null) b.claim("idempotencyKey",binding.idempotencyKey());
        mutate.accept(b);
        try {
            var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(),b.build());
            jwt.sign(new RSASSASigner(key)); return jwt.serialize();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    ApprovalSignatureAuthority verifier(ApprovalSignatureCanonical canonical) { return new ApprovalSignatureAuthority(this,new JWKSet(key.toPublicJWK()),clock,canonical); }
}
