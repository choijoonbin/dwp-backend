package com.dwp.services.approval.signatures;

import static org.assertj.core.api.Assertions.*;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.signatures.ApprovalSignatureCommandReceiptDtos.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;

class ApprovalSignatureCommandReceiptAuthorityTest {
    static RSAKey key;static final UUID PERSON=UUID.randomUUID(),REQUEST=UUID.randomUUID();
    final ApprovalSignatureCanonical json=new ApprovalSignatureCanonical(new ObjectMapper());
    final Clock clock=Clock.systemUTC();Consumer<JWTClaimsSet.Builder> change=b->{ };
    @BeforeAll static void keys() throws Exception{key=new RSAKeyGenerator(2048).keyID("approval-signature-authority:metadata-test").keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();}
    @BeforeEach void actor(){ApprovalRequestContext.set(99L,42L,PERSON,Set.of(),Set.of("APP.APPROVALS:VIEW","ACTION.APPROVAL_REQUEST:VIEW"));}
    @AfterEach void clear(){ApprovalRequestContext.clear();}
    Query query(){return new Query("original-key",OriginalOperation.SIGN,UUID.randomUUID(),"a".repeat(64));}
    String token(Query q,UUID nonce){
        Instant now=clock.instant();var b=new JWTClaimsSet.Builder().issuer("DWP_APPROVAL_SIGNATURE_AUTHORITY").claim("aud","dwp-approval-server")
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(30))).jwtID(UUID.randomUUID().toString()).claim("nonce",nonce.toString())
                .claim("contract",ApprovalSignatureCommandReceiptAuthority.CONTRACT).claim("purpose","COMMAND_RECEIPT").claim("method","GET").claim("path",q.path())
                .claim("tenantId",42L).claim("actorId",99L).claim("personPublicId",PERSON.toString()).claim("resourceSetKey","RS_APPROVALS").claim("contextKey","context").claim("contextScopeKey","opaque")
                .claim("decisionRevision","psr-"+"a".repeat(64)).claim("registrySha256","b".repeat(64)).claim("routeContractKey",ApprovalSignatureCommandReceiptDtos.ROUTE)
                .claim("objectId",REQUEST.toString()).claim("bodySha256",json.digest(q.body())).claim("grantSha256","c".repeat(64)).claim("installed",true)
                .claim("signerKind","SELF_ATTESTATION").claim("accessMode","NORMAL").claim("highRiskVerified",false).claim("sourceSha256","d".repeat(64))
                .claim("sourceKind","COMMAND_RECEIPT").claim("sourceCurrent",true).claim("profileKey",ApprovalSignatureCommandReceiptDtos.PROFILE).claim("idempotencyKey",q.idempotencyKey());change.accept(b);
        try{var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(),b.build());jwt.sign(new RSASSASigner(key));return jwt.serialize();}
        catch(Exception error){throw new IllegalStateException(error);}
    }
    ApprovalSignatureCommandReceiptAuthority authority(){return new ApprovalSignatureCommandReceiptAuthority(this::token,new JWKSet(key.toPublicJWK()),clock,json);}
    @Test void actualSignedMetadataPrivateProofAcceptsKnownTrueOrFalseNotUnknown(){
        for(boolean current:List.of(true,false)){change=b->b.claim("sourceCurrent",current);var proof=authority().require(query());assertThat(proof.sourceCurrent()).isEqualTo(current);assertThat(proof.requestId()).isEqualTo(REQUEST);}
        change=b->b.claim("sourceCurrent","UNKNOWN");assertThatThrownBy(()->authority().require(query())).isInstanceOf(BaseException.class);
    }
    @Test void metadataProofCannotGrantArtifactContextOrSignAuthority(){
        var q=query();var artifact=new ApprovalSignatureAuthority((binding,nonce)->token(q,nonce),new JWKSet(key.toPublicJWK()),clock,json);
        var body=q.body();var binding=new ApprovalSignatureAuthority.Binding(ApprovalSignatureAuthority.Operation.GET,q.targetId(),null,json.digest(body),null,new ObjectMapper().valueToTree(body));
        assertThatThrownBy(()->artifact.require(binding)).isInstanceOf(BaseException.class);
        change=b->b.claim("sourceKind","ARTIFACT");assertThatThrownBy(()->authority().require(q)).isInstanceOf(BaseException.class);
        change=b->b.claim("highRiskVerified",true);assertThatThrownBy(()->authority().require(q)).isInstanceOf(BaseException.class);
    }
    @Test void receiptBodyOriginalKeyActorRequestAndExpiryAreExactAndUnchanged(){
        for(var bad:Map.<String,Object>of("bodySha256","0".repeat(64),"idempotencyKey","other-key","actorId",100L,"profileKey","full-work","method","HEAD").entrySet()){
            change=b->b.claim(bad.getKey(),bad.getValue());assertThatThrownBy(()->authority().require(query())).as(bad.getKey()).isInstanceOf(BaseException.class);
        }
        change=b->{ };var trusted=authority();var proof=trusted.require(query());change=b->b.claim("grantSha256","0".repeat(64));assertThatThrownBy(()->trusted.unchanged(proof)).isInstanceOf(BaseException.class);
        change=b->b.expirationTime(Date.from(clock.instant().minusSeconds(1)));assertThatThrownBy(()->trusted.require(query())).isInstanceOf(BaseException.class);
    }
    @Test void dotKeysNonCanonicalHashOrMissingTargetNeverReachAReceiptLookup(){
        for(String key:List.of(".","..","with/slash"))assertThatThrownBy(()->new Query(key,OriginalOperation.SIGN,UUID.randomUUID(),"a".repeat(64))).isInstanceOf(BaseException.class);
        assertThatThrownBy(()->new Query("original",OriginalOperation.SIGN,null,"a".repeat(64))).isInstanceOf(BaseException.class);
        assertThatThrownBy(()->new Query("original",OriginalOperation.SIGN,UUID.randomUUID(),"A".repeat(64))).isInstanceOf(BaseException.class);
    }
}
