package com.dwp.services.approval.signatures;

import static com.dwp.services.approval.signatures.ApprovalSignatureCanonical.*;
import static com.dwp.services.approval.signatures.ApprovalSignatureCommandReceiptDtos.*;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.signatures.ApprovalSignatureCommandReceiptDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Independent private metadata-only factory; it cannot produce ApprovalSignatureAuthority.Verified. */
final class ApprovalSignatureCommandReceiptAuthority {
    static final String CONTRACT="DWP_APPROVAL_SIGNATURE_COMMAND_RECEIPT_AUTHORITY_V1";
    interface Source { String assertion(Query query,UUID nonce); }
    private static final Set<String> CLAIMS=Set.of("iss","aud","iat","exp","jti","nonce","contract","purpose","method","path","tenantId","actorId","personPublicId",
            "resourceSetKey","contextKey","contextScopeKey","decisionRevision","registrySha256","routeContractKey","objectId","bodySha256","grantSha256","installed",
            "signerKind","accessMode","highRiskVerified","sourceSha256","sourceKind","sourceCurrent","profileKey","idempotencyKey");
    private final Source source;private final JWKSet trust;private final Clock clock;private final ApprovalSignatureCanonical json;
    ApprovalSignatureCommandReceiptAuthority(Source source,JWKSet trust,Clock clock,ApprovalSignatureCanonical json){this.source=source;this.trust=trust;this.clock=clock;this.json=json;}
    static final class Verified {
        private final Query query;private final UUID request;private final String sourceSha,rs,digest;private final boolean current;private final Instant expires;
        private Verified(Query query,UUID request,String sourceSha,String rs,String digest,boolean current,Instant expires){this.query=query;this.request=request;this.sourceSha=sourceSha;this.rs=rs;this.digest=digest;this.current=current;this.expires=expires;}
        String sourceSha(){return sourceSha;}String resourceSetKey(){return rs;}UUID requestId(){return request;}boolean sourceCurrent(){return current;}
    }
    Verified require(Query query) {
        if(source==null || trust==null || trust.getKeys().isEmpty())throw unavailable();
        final ApprovalRequestContext.Actor actor;try{actor=ApprovalRequestContext.require();}catch(IllegalStateException missing){throw unavailable();}
        if(actor.tenantId()==null || actor.tenantId()<=0 || actor.userId()==null || actor.userId()<=0 || actor.personPublicId()==null
                || actor.roles().stream().anyMatch(r->r.startsWith("PROVIDER_")))throw denied();
        try {
            var nonce=UUID.randomUUID();String raw=source.assertion(query,nonce);if(raw==null || raw.length()>16384)throw unavailable();
            String[] parts=raw.split("\\.",-1);if(parts.length!=3)throw denied();
            var h=json.read(new String(Base64.getUrlDecoder().decode(parts[0]),java.nio.charset.StandardCharsets.UTF_8),JsonNode.class);
            var c=json.read(new String(Base64.getUrlDecoder().decode(parts[1]),java.nio.charset.StandardCharsets.UTF_8),JsonNode.class);
            var fields=new HashSet<String>();c.fieldNames().forEachRemaining(fields::add);var header=new HashSet<String>();h.fieldNames().forEachRemaining(header::add);
            if(!header.equals(Set.of("alg","kid","typ")) || !"RS256".equals(text(h,"alg")) || !"JWT".equals(text(h,"typ")) || !fields.equals(CLAIMS))throw denied();
            var key=trust.getKeyByKeyId(text(h,"kid"));
            if(!(key instanceof RSAKey rsa) || rsa.isPrivate() || rsa.size()<2048 || !rsa.getKeyID().startsWith("approval-signature-authority:")
                    || !KeyUse.SIGNATURE.equals(rsa.getKeyUse()) || !JWSAlgorithm.RS256.equals(rsa.getAlgorithm()) || !SignedJWT.parse(raw).verify(new RSASSAVerifier(rsa)))throw denied();
            if(!"DWP_APPROVAL_SIGNATURE_AUTHORITY".equals(text(c,"iss")) || !"dwp-approval-server".equals(text(c,"aud")) || !CONTRACT.equals(text(c,"contract"))
                    || !"COMMAND_RECEIPT".equals(text(c,"purpose")) || !"COMMAND_RECEIPT".equals(text(c,"sourceKind")) || !"GET".equals(text(c,"method"))
                    || !query.path().equals(text(c,"path")) || !ROUTE.equals(text(c,"routeContractKey")) || !PROFILE.equals(text(c,"profileKey"))
                    || !nonce.toString().equals(text(c,"nonce")) || !query.idempotencyKey().equals(text(c,"idempotencyKey")) || !json.digest(query.body()).equals(hash(c,"bodySha256"))
                    || integer(c,"tenantId")!=actor.tenantId() || integer(c,"actorId")!=actor.userId() || !actor.personPublicId().toString().equals(text(c,"personPublicId"))
                    || !c.path("installed").isBoolean() || !c.path("installed").booleanValue() || !c.path("highRiskVerified").isBoolean() || c.path("highRiskVerified").booleanValue()
                    || !c.path("sourceCurrent").isBoolean() || !"SELF_ATTESTATION".equals(text(c,"signerKind")) || !Set.of("NORMAL","ELEVATED").contains(text(c,"accessMode"))
                    || !text(c,"decisionRevision").matches("psr-[a-f0-9]{64}") || !text(c,"resourceSetKey").matches("RS_[A-Z0-9_]{1,76}"))throw denied();
            hash(c,"registrySha256");hash(c,"grantSha256");text(c,"contextKey");text(c,"contextScopeKey");UUID.fromString(text(c,"jti"));
            UUID request=UUID.fromString(text(c,"objectId"));if(!request.toString().equals(text(c,"objectId")))throw denied();
            Instant now=clock.instant(),issued=Instant.ofEpochSecond(integer(c,"iat")),expires=Instant.ofEpochSecond(integer(c,"exp"));
            if(issued.isAfter(now.plusSeconds(5)) || issued.isBefore(now.minusSeconds(60)) || !expires.isAfter(now) || !expires.isAfter(issued) || expires.isAfter(issued.plusSeconds(30)))throw denied();
            var stable=(com.fasterxml.jackson.databind.node.ObjectNode)c.deepCopy();stable.remove(List.of("iat","exp","jti","nonce"));
            return new Verified(query,request,hash(c,"sourceSha256"),text(c,"resourceSetKey"),json.digest(stable),c.path("sourceCurrent").booleanValue(),expires);
        }catch(com.dwp.core.exception.BaseException invalid){throw invalid;}catch(Exception invalid){throw denied();}
    }
    void unchanged(Verified original){if(!original.expires.isAfter(clock.instant()) || !original.digest.equals(require(original.query).digest))throw conflict();}
    private static String text(JsonNode n,String k){var v=n.get(k);if(v==null || !v.isTextual() || v.textValue().isBlank() || v.textValue().length()>512)throw denied();return v.textValue();}
    private static String hash(JsonNode n,String k){String v=text(n,k);if(!v.matches("[a-f0-9]{64}"))throw denied();return v;}
    private static long integer(JsonNode n,String k){var v=n.get(k);if(v==null || !v.isIntegralNumber() || !v.canConvertToLong() || v.longValue()<0 || v.longValue()>ApprovalSignatureDtos.MAX_VERSION)throw denied();return v.longValue();}
}
