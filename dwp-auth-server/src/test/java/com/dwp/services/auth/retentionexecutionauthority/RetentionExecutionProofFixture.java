package com.dwp.services.auth.retentionexecutionauthority;

import static com.dwp.services.auth.retentionexecutionauthority.RetentionExecutionProtocol.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.*;
import java.time.*;
import java.util.*;

final class RetentionExecutionProofFixture {
    final RetentionExecutionJson json=new RetentionExecutionJson();
    final RSAKey owner,transport;final KeyPair execution;final RetentionExecutionKeys keys;
    RetentionExecutionProofFixture() {
        try {
            owner=new RSAKeyGenerator(2048).keyID("isolated-retention-owner").generate();transport=new RSAKeyGenerator(2048).keyID("isolated-retention-transport").generate();
            execution=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            keys=new RetentionExecutionKeys(json,ring(owner),ring(transport),privateKey(),publicKey(),"isolated-current-retention-auth","isolated-retention-execution",List.of());
        } catch(Exception invalid) {throw new IllegalStateException(invalid);}
    }
    String privateKey() {return Base64.getEncoder().encodeToString(execution.getPrivate().getEncoded());}
    String publicKey() {return Base64.getEncoder().encodeToString(execution.getPublic().getEncoded());}
    String ring(RSAKey key) {return new JWKSet(key.toPublicJWK()).toString();}
    ObjectNode bindings(long tenant,long actor,String resourceSet) {
        return (ObjectNode)json.tree(new RetentionExecutionBindings(new RetentionExecutionBindings.Target(UUID.randomUUID(),tenant,actor,UUID.randomUUID(),resourceSet,
                4,UUID.randomUUID(),2,0,"a".repeat(64),"b".repeat(64),0),new RetentionExecutionBindings.Context("NORMAL","CURRENT_AUTH_MANAGEMENT"),
                new RetentionExecutionBindings.NativeSource("QUEUED","c".repeat(64),Instant.now().minusSeconds(1))));
    }
    Exchange issue(ObjectNode bindings) {return issue(bindings,30,30);}
    Exchange issue(ObjectNode bindings,int ownerTtl,int transportTtl) {
        long now=Instant.now().getEpochSecond();UUID source=UUID.randomUUID(),exchange=UUID.randomUUID();String sub=bindings.at("/target/actorId").asText();
        var claims=standard(OWNER_ISSUER,OWNER_AUDIENCE,OWNER_PURPOSE,sub,source,now,ownerTtl);
        claims.set("bindings",bindings);claims.put("bindingsSha256",json.digest(bindings));String proof=sign(owner,claims);
        byte[] body=json.bytes(Map.of("sourceProof",proof,"bindings",bindings));
        var trans=standard(TRANSPORT_ISSUER,TRANSPORT_AUDIENCE,TRANSPORT_PURPOSE,sub,exchange,now,transportTtl);
        trans.put("method","POST");trans.put("path",PATH);trans.put("sourceJti",source.toString());trans.put("bodySha256",RetentionExecutionJson.sha(body));trans.put("bindingsSha256",json.digest(bindings));
        return new Exchange(body,sign(transport,trans),proof,source,exchange);
    }
    ObjectNode standard(String iss,String aud,String purpose,String sub,UUID jti,long now,int ttl) {
        return (ObjectNode)json.tree(Map.of("iss",iss,"aud",aud,"purpose",purpose,"sub",sub,"jti",jti.toString(),"iat",now,"nbf",now,"exp",now+ttl));
    }
    String sign(RSAKey key,ObjectNode claims) {
        try {
            var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(),JWTClaimsSet.parse(claims.toString()));
            jwt.sign(new RSASSASigner(key));return jwt.serialize();
        } catch(Exception invalid) {throw new IllegalStateException(invalid);}
    }
    RetentionExecutionProofVerifier verifier() {return new RetentionExecutionProofVerifier(json,keys,Clock.systemUTC());}
    record Exchange(byte[] body,String token,String proof,UUID ownerJti,UUID transportJti) {
        Exchange {body=body.clone();}
        @Override public byte[] body() {return body.clone();}
    }
}
