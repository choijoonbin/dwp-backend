package com.dwp.services.auth.retentionexecutionauthority;

import static com.dwp.services.auth.retentionexecutionauthority.RetentionExecutionJson.*;
import static com.dwp.services.auth.retentionexecutionauthority.RetentionExecutionProtocol.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** Only this crypto verifier can construct a native-owner admission. */
public final class RetentionExecutionProofVerifier {
    private final RetentionExecutionJson json;private final RetentionExecutionKeys keys;private final Clock clock;
    public RetentionExecutionProofVerifier(RetentionExecutionJson json,RetentionExecutionKeys keys,Clock clock) {this.json=json;this.keys=keys;this.clock=clock;}
    public Verified verify(byte[] body,String token) {
        var envelope=json.parse(body,BODY_LIMIT);exact(envelope,Set.of("sourceProof","bindings"));
        var owner=jwt(text(envelope,"sourceProof",OWNER_LIMIT),OWNER_LIMIT,keys::owner,Set.of("bindings","bindingsSha256"),
                OWNER_ISSUER,OWNER_AUDIENCE,OWNER_PURPOSE);
        var transport=jwt(token,TRANSPORT_LIMIT,keys::transport,Set.of("method","path","sourceJti","bodySha256","bindingsSha256"),
                TRANSPORT_ISSUER,TRANSPORT_AUDIENCE,TRANSPORT_PURPOSE);
        var value=envelope.get("bindings");String digest=json.digest(value);
        if(!value.equals(owner.claims.get("bindings")) || !digest.equals(hash(owner.claims,"bindingsSha256"))
                || !digest.equals(hash(transport.claims,"bindingsSha256")) || !sha(body).equals(hash(transport.claims,"bodySha256"))
                || !owner.jti.equals(uuid(transport.claims,"sourceJti")) || owner.jti.equals(transport.jti)
                || !"POST".equals(text(transport.claims,"method",4)) || !PATH.equals(text(transport.claims,"path",200))
                || !text(owner.claims,"sub",20).equals(text(transport.claims,"sub",20)) || transport.expiry.isAfter(owner.expiry)) throw denied();
        var bindings=RetentionExecutionBindings.parse(value,clock.instant());
        if(!Long.toString(bindings.target().actorId()).equals(text(owner.claims,"sub",20))) throw denied();
        return new Verified(bindings,owner.jti,transport.jti,sha(body),digest,transport.expiry,owner.expiry);
    }
    private Token jwt(String raw,int limit,Function<String,RSAKey> resolver,Set<String> extras,String issuer,String audience,String purpose) {
        if(raw==null || raw.length()>limit || !raw.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")) throw denied();
        try {
            var parts=raw.split("\\.");var header=json.parse(part(parts[0]),2048);exact(header,Set.of("alg","typ","kid"));
            if(!"RS256".equals(text(header,"alg",5)) || !"JWT".equals(text(header,"typ",3))) throw denied();part(parts[2]);
            if(!SignedJWT.parse(raw).verify(new RSASSAVerifier(resolver.apply(text(header,"kid",80))))) throw denied();
            var claims=json.parse(part(parts[1]),OWNER_LIMIT);var fields=new HashSet<>(STANDARD);fields.addAll(extras);exact(claims,fields);
            if(!issuer.equals(text(claims,"iss",160)) || !audience.equals(text(claims,"aud",160)) || !purpose.equals(text(claims,"purpose",100))) throw denied();
            long issued=integer(claims,"iat",1),start=integer(claims,"nbf",1),expiry=integer(claims,"exp",1),now=clock.instant().getEpochSecond();
            if(issued!=start || issued>now || expiry<=now || expiry<=issued || expiry-issued>30) throw denied();
            return new Token(claims,uuid(claims,"jti"),Instant.ofEpochSecond(expiry));
        } catch(Exception invalid) {throw denied();}
    }
    private record Token(JsonNode claims,UUID jti,Instant expiry) { }
    public static final class Verified {
        private final RetentionExecutionBindings bindings;private final UUID sourceJti,transportJti;
        private final String bodySha256,bindingsSha256;private final Instant expiresAt,replayUntil;
        private Verified(RetentionExecutionBindings bindings,UUID sourceJti,UUID transportJti,String bodySha256,String bindingsSha256,Instant expiresAt,Instant replayUntil) {
            this.bindings=bindings;this.sourceJti=sourceJti;this.transportJti=transportJti;this.bodySha256=bodySha256;this.bindingsSha256=bindingsSha256;this.expiresAt=expiresAt;this.replayUntil=replayUntil;
        }
        public RetentionExecutionBindings bindings() {return bindings;}
        public UUID sourceJti() {return sourceJti;}
        public UUID transportJti() {return transportJti;}
        public String bodySha256() {return bodySha256;}
        public String bindingsSha256() {return bindingsSha256;}
        public Instant expiresAt() {return expiresAt;}
        public Instant replayUntil() {return replayUntil;}
    }
}
