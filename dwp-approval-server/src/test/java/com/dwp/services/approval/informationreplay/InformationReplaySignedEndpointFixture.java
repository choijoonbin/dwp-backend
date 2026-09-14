package com.dwp.services.approval.informationreplay;

import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson.*;
import static org.junit.jupiter.api.Assertions.*;

import com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Signature/transport fixture only. It does not claim actual Auth database or installed canonical-9 authority. */
public final class InformationReplaySignedEndpointFixture implements AutoCloseable {
    public final InformationReplayKeys keys;
    public final AtomicInteger requests=new AtomicInteger();
    public volatile int status=200;
    public volatile Consumer<ObjectNode> mutation=ignored->{};
    public volatile Runnable afterFirst=()->{},afterSecond=()->{};
    public volatile Throwable failure;
    private final HttpServer server;
    private final RSAKey owner,transport,auth;
    private final WorkflowRuntimeJson json=new WorkflowRuntimeJson();
    public InformationReplaySignedEndpointFixture() throws Exception {
        owner=key("receipt-owner");transport=key("receipt-transport");auth=key("receipt-auth");
        keys=new InformationReplayKeys(owner,transport,new JWKSet(auth.toPublicJWK()).toString(),new JWKSet().toString());
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext(InformationReplayProtocol.PATH,exchange->{
            int count=requests.incrementAndGet();
            try {
                assertEquals("POST",exchange.getRequestMethod());assertEquals(InformationReplayProtocol.PATH,exchange.getRequestURI().toString());
                assertEquals("dwp-approval-server",exchange.getRequestHeaders().getFirst("X-DWP-Service-Identity"));
                for(String forbidden:Set.of("Authorization","Cookie","X-DWP-Approval-Workflow-Runtime-Token","X-DWP-User-ID","X-DWP-Tenant-ID"))
                    assertNull(exchange.getRequestHeaders().getFirst(forbidden));
                byte[] request=exchange.getRequestBody().readNBytes(InformationReplayProtocol.BODY_MAX+1);
                byte[] response=response(request,exchange.getRequestHeaders().getFirst(InformationReplayProtocol.HEADER));
                if(count==1) afterFirst.run();else if(count==2) afterSecond.run();
                exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,response.length);
                exchange.getResponseBody().write(response);
            } catch(Throwable error) {failure=error;try {exchange.sendResponseHeaders(500,-1);} catch(Exception ignored) { }}
            finally {exchange.close();}
        });server.start();
    }
    public InformationReplayRuntime runtime() {
        var clock=Clock.systemUTC();return new InformationReplayRuntime(new InformationReplayProofIssuer(keys,clock),
                new InformationReplayAuthorityClient(endpoint(),new InformationReplayAttestationVerifier(keys,clock)));
    }
    public URI endpoint() {return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+InformationReplayProtocol.PATH);}
    private byte[] response(byte[] bytes,String credential) throws Exception {
        var body=json.parse(bytes,InformationReplayProtocol.BODY_MAX);exact(body,Set.of("operation","sourceProof","bindings"));
        assertEquals(InformationReplayProtocol.OPERATION,text(body,"operation",40));
        String source=text(body,"sourceProof",InformationReplayProtocol.OWNER_MAX);
        var proof=verified(source,owner,Set.of("iss","aud","sub","iat","nbf","exp","jti","purpose","version","sealed"));
        assertEquals(InformationReplayProtocol.OWNER_ISSUER,text(proof,"iss",160));assertEquals(InformationReplayProtocol.OWNER_AUDIENCE,text(proof,"aud",160));
        assertEquals(InformationReplayProtocol.OWNER_PURPOSE,text(proof,"purpose",100));assertEquals(1,integer(proof,"version",1));
        var bindings=body.get("bindings");assertArrayEquals(json.bytes(bindings),json.bytes(proof.get("sealed")));
        var token=verified(credential,transport,Set.of("iss","aud","sub","iat","nbf","exp","jti","purpose","sourceProofJti","sourceProofSha256","bodySha256","contextKey","routeContractKey"));
        assertEquals(InformationReplayProtocol.TRANSPORT_ISSUER,text(token,"iss",160));assertEquals(InformationReplayProtocol.TRANSPORT_AUDIENCE,text(token,"aud",160));
        assertEquals(InformationReplayProtocol.TRANSPORT_PURPOSE,text(token,"purpose",100));assertEquals(uuid(proof,"jti"),uuid(token,"sourceProofJti"));
        assertEquals(sha(source),hash(token,"sourceProofSha256"));assertEquals(sha(bytes),hash(token,"bodySha256"));
        assertEquals(text(proof,"sub",20),text(token,"sub",20));
        assertEquals(text(bindings.get("owner"),"contextKey",500),text(token,"contextKey",500));
        assertEquals(InformationReceiptInstalledContext.ROUTE,text(token,"routeContractKey",100));
        long now=Instant.now().getEpochSecond(),expires=Math.min(integer(proof,"exp",1),integer(token,"exp",1));
        var result=json.object();for(String field:Set.of("receiptSha256","commandSha256","admissionSha256")) result.set(field,bindings.get("admission").get(field));
        result.set("role",json.tree(Map.of("roleCode",text(bindings.get("source"),"candidateRole",50),"roleId",701L,"roleVersion",42L)));
        result.set("originalActor",subject(bindings,"actorId","actorPersonPublicId"));result.set("principal",subject(bindings,"principalId","principalPersonPublicId"));
        var authority=json.tree(Map.of("ownerAuthRevision","auth-current","ownerPolicyRevision","policy-current","sourceRevision","air-"+"a".repeat(64),
                "sourceVectorSha256","a".repeat(64),"evaluatedAt",now,"expiresAt",expires));
        var claims=json.object();claims.put("iss",InformationReplayProtocol.OWNER_AUDIENCE);claims.put("aud",InformationReplayProtocol.OWNER_ISSUER);
        claims.put("sub",text(proof,"sub",20));claims.put("purpose",InformationReplayProtocol.ATTESTATION_PURPOSE);claims.put("operation",InformationReplayProtocol.OPERATION);
        claims.put("iat",now);claims.put("nbf",now);claims.put("exp",expires);claims.put("jti",UUID.randomUUID().toString());
        claims.set("sourceProofJti",proof.get("jti"));claims.set("transportProofJti",token.get("jti"));claims.put("bodySha256",sha(bytes));
        claims.put("bindingsSha256",sha(json.bytes(bindings)));claims.set("authority",authority);claims.set("result",result);mutation.accept(claims);
        var signed=new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(auth.getKeyID()).build(),
                new Payload(new String(json.bytes(claims),StandardCharsets.UTF_8)));signed.sign(new RSASSASigner(auth));
        var envelope=json.object();envelope.put("attestation",signed.serialize());return json.bytes(envelope);
    }
    private JsonNode verified(String token,RSAKey key,Set<String> fields) throws Exception {
        var jwt=SignedJWT.parse(token);assertTrue(jwt.verify(new RSASSAVerifier(key.toPublicJWK())));
        var claims=json.parse(part(token.split("\\.")[1]),InformationReplayProtocol.BODY_MAX);exact(claims,fields);
        long now=Instant.now().getEpochSecond(),issued=integer(claims,"iat",1),expires=integer(claims,"exp",1);
        assertEquals(issued,integer(claims,"nbf",1));assertTrue(issued<=now && expires>now && expires-issued<=30);return claims;
    }
    private JsonNode subject(JsonNode bindings,String id,String person) {
        return json.tree(Map.of("tenantId",integer(bindings.get("owner"),"tenantId",1),"userId",integer(bindings.get("target"),id,1),
                "personPublicId",uuid(bindings.get("target"),person).toString(),"identityPlane","TENANT","status","ACTIVE","roleIds",java.util.List.of(701L),"canApprove",true));
    }
    private static RSAKey key(String id) throws Exception {
        return new RSAKeyGenerator(2048).keyID(id).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();
    }
    @Override public void close() {server.stop(0);if(failure!=null) throw new AssertionError("Signed endpoint fixture failed",failure);}
}
