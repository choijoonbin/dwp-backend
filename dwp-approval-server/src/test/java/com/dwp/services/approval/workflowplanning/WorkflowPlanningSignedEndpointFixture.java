package com.dwp.services.approval.workflowplanning;

import static com.dwp.services.approval.workflowplanning.WorkflowPlanningProtocol.*;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson.*;

import com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Real bounded HTTP/RS256 fixture only; current management and Auth role population are not production authorities. */
public final class WorkflowPlanningSignedEndpointFixture implements AutoCloseable {
    final RSAKey owner=key("planning-owner"),transport=key("planning-transport"),auth=key("planning-auth"),unrelated=key("planning-forbidden");
    final HttpServer http;
    final WorkflowRuntimeJson json=new WorkflowRuntimeJson();
    public final AtomicInteger requests=new AtomicInteger();
    public int count=3;
    public long sourceExpiresAt;
    public Runnable afterFirst=()->{};
    public Consumer<ObjectNode> mutation=claims->{};
    public WorkflowPlanningSignedEndpointFixture() throws Exception {
        http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);http.createContext(PATH,request->{
            try {
                byte[] raw=request.getRequestBody().readNBytes(BODY_MAX+1);var envelope=json.parse(raw,BODY_MAX);var source=SignedJWT.parse(text(envelope,"sourceProof",OWNER_MAX));
                var workload=SignedJWT.parse(request.getRequestHeaders().getFirst(HEADER));
                if(!source.verify(new com.nimbusds.jose.crypto.RSASSAVerifier(owner.toPublicJWK())) || !workload.verify(new com.nimbusds.jose.crypto.RSASSAVerifier(transport.toPublicJWK()))) throw new IllegalArgumentException();
                var ownerClaims=json.parse(part(source.serialize().split("\\.")[1]),BODY_MAX);var transportClaims=json.parse(part(workload.serialize().split("\\.")[1]),BODY_MAX);
                if(!hash(transportClaims,"bodySha256").equals(sha(raw)) || !hash(transportClaims,"sourceProofSha256").equals(sha(source.serialize()))) throw new IllegalArgumentException();
                var bindings=envelope.get("bindings");int number=requests.incrementAndGet();long now=Instant.now().getEpochSecond(),expires=integer(ownerClaims,"exp",1);
                sourceExpiresAt=expires;
                var claims=json.object();claims.put("iss",OWNER_AUDIENCE);claims.put("aud",OWNER_ISSUER);claims.put("purpose",ATTESTATION_PURPOSE);claims.put("operation",OPERATION);
                claims.set("sub",ownerClaims.get("sub"));claims.put("iat",now);claims.put("nbf",now);claims.put("exp",expires);claims.put("jti",java.util.UUID.randomUUID().toString());
                claims.set("sourceProofJti",ownerClaims.get("jti"));claims.set("transportProofJti",transportClaims.get("jti"));claims.put("bodySha256",sha(raw));claims.put("bindingsSha256",sha(json.bytes(bindings)));
                var authority=json.object();authority.put("ownerAuthRevision","fixture-current-auth");authority.put("ownerPolicyRevision","fixture-policy10");
                authority.put("sourceVectorSha256","b".repeat(64));authority.put("sourceRevision","awp-"+"b".repeat(64));authority.put("evaluatedAt",now);authority.put("expiresAt",expires);claims.set("authority",authority);
                var result=json.object();result.set("snapshotSha256",bindings.get("source").get("snapshotSha256"));var roles=result.putArray("roles");long id=1;
                for(var code:bindings.get("source").get("roleCodes")) {var role=roles.addObject();role.set("roleCode",code);role.put("roleId",id++);role.put("roleVersion",0);role.put("activeMemberCount",count);}
                claims.set("result",result);mutation.accept(claims);
                var jwt=new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(auth.getKeyID()).build(),new Payload(new String(json.bytes(claims),StandardCharsets.UTF_8)));
                jwt.sign(new RSASSASigner(auth));var response=json.object();response.put("attestation",jwt.serialize());byte[] bytes=json.bytes(response);
                if(number==1) afterFirst.run();request.getResponseHeaders().add("Content-Type","application/json");request.sendResponseHeaders(200,bytes.length);request.getResponseBody().write(bytes);
            } catch(Exception error) {request.sendResponseHeaders(403,-1);} finally {request.close();}
        });http.start();
    }
    public WorkflowPlanningRuntime runtime() {
        var keys=new WorkflowPlanningKeys(owner,transport,new JWKSet(auth.toPublicJWK()).toString(),new JWKSet(unrelated.toPublicJWK()).toString());
        return new WorkflowPlanningRuntime(new WorkflowPlanningProofIssuer(keys,Clock.systemUTC()),new WorkflowPlanningAuthorityClient(
                URI.create("http://127.0.0.1:"+http.getAddress().getPort()+PATH),new WorkflowPlanningAttestationVerifier(keys,Clock.systemUTC())));
    }
    static RSAKey key(String id) {try {return new RSAKeyGenerator(2048).keyID(id).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();} catch(Exception error) {throw new AssertionError(error);}}
    @Override public void close() {http.stop(0);}
}
