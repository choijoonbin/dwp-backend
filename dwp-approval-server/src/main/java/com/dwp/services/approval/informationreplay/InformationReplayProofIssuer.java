package com.dwp.services.approval.informationreplay;

import static com.dwp.services.approval.informationreplay.InformationReplayProtocol.*;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson.*;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.denied;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.unavailable;

import com.dwp.services.approval.domain.ApprovalInformationReceiptSource;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Signing accepts only a private, current database seal, never an arbitrary caller-authored tuple. */
public final class InformationReplayProofIssuer {
    public static final class Exchange {
        private final JsonNode bindings;
        private final UUID sourceJti,transportJti;
        private final String actor,bodySha,bindingsSha,token;
        private final Instant expires;
        private final byte[] body;
        private Exchange(JsonNode bindings,UUID sourceJti,UUID transportJti,String actor,String bodySha,String bindingsSha,
                Instant expires,byte[] body,String token) {
            this.bindings=bindings.deepCopy();this.sourceJti=sourceJti;this.transportJti=transportJti;this.actor=actor;
            this.bodySha=bodySha;this.bindingsSha=bindingsSha;this.expires=expires;this.body=body.clone();this.token=token;
        }
        public JsonNode bindings() {return bindings.deepCopy();}
        public byte[] body() {return body.clone();}
        public String token() {return token;}
        public Instant expiresAt() {return expires;}
    }
    private final InformationReplayKeys keys;
    private final Clock clock;
    private final WorkflowRuntimeJson json=new WorkflowRuntimeJson();
    public InformationReplayProofIssuer(InformationReplayKeys keys,Clock clock) {this.keys=keys;this.clock=clock;}
    public Exchange issue(ApprovalInformationReceiptSource.Seal seal,Instant deadline) {
        if(seal==null || deadline==null) throw denied();var bindings=seal.bindings();var owner=bindings.get("owner");
        var actor=ApprovalRequestContext.require();
        if(integer(owner,"actorId",1)!=actor.userId() || integer(owner,"tenantId",1)!=actor.tenantId() || !uuid(owner,"personPublicId").equals(actor.personPublicId())
                || !InformationReceiptInstalledContext.ROUTE.equals(text(owner,"routeContractKey",100))) throw denied();
        long now=clock.instant().getEpochSecond(),expires=Math.min(now+30,Math.min(deadline.getEpochSecond(),seal.deadline().getEpochSecond()));
        if(expires<=now) throw unavailable();UUID sourceJti=UUID.randomUUID(),transportJti=UUID.randomUUID();String subject=Long.toString(actor.userId());
        var claims=common(OWNER_ISSUER,OWNER_AUDIENCE,OWNER_PURPOSE,subject,sourceJti,now,expires);claims.put("version",1);claims.put("sealed",bindings);
        String source=sign(json.tree(claims),keys.owner);if(source.length()>OWNER_MAX) throw denied();
        var body=json.object();body.put("operation",OPERATION);body.put("sourceProof",source);body.set("bindings",bindings);byte[] bytes=json.bytes(body);
        if(bytes.length>BODY_MAX) throw denied();String bodySha=sha(bytes);
        var transport=common(TRANSPORT_ISSUER,TRANSPORT_AUDIENCE,TRANSPORT_PURPOSE,subject,transportJti,now,expires);
        transport.put("sourceProofJti",sourceJti.toString());transport.put("sourceProofSha256",sha(source));transport.put("bodySha256",bodySha);
        transport.put("contextKey",text(owner,"contextKey",500));transport.put("routeContractKey",InformationReceiptInstalledContext.ROUTE);
        String token=sign(json.tree(transport),keys.transport);if(token.length()>TOKEN_MAX) throw denied();
        return new Exchange(bindings,sourceJti,transportJti,subject,bodySha,sha(json.bytes(bindings)),Instant.ofEpochSecond(expires),bytes,token);
    }
    private Map<String,Object> common(String issuer,String audience,String purpose,String subject,UUID jti,long now,long expires) {
        return new TreeMap<>(Map.of("iss",issuer,"aud",audience,"purpose",purpose,"sub",subject,"iat",now,"nbf",now,"exp",expires,"jti",jti.toString()));
    }
    private String sign(JsonNode claims,RSAKey key) {
        try {var jwt=new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(),
                new Payload(new String(json.bytes(claims),StandardCharsets.UTF_8)));jwt.sign(new RSASSASigner(key));return jwt.serialize();}
        catch(Exception error) {throw unavailable();}
    }
    static UUID sourceJti(Exchange exchange) {return exchange.sourceJti;}
    static UUID transportJti(Exchange exchange) {return exchange.transportJti;}
    static String actor(Exchange exchange) {return exchange.actor;}
    static String bodySha(Exchange exchange) {return exchange.bodySha;}
    static String bindingsSha(Exchange exchange) {return exchange.bindingsSha;}
}
