package com.dwp.services.approval.workflowauthority;

import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.*;
import static org.junit.jupiter.api.Assertions.*;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalWorkflowQuorum;
import com.dwp.services.approval.domain.ApprovalWorkflowQuorumDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Real RSA and default-JDK HTTP bounds. The responder is a protocol fixture, not current Auth/PG authority evidence. */
class WorkflowRuntimeTransportTest {
    static RSAKey owner,transport,auth,other;
    static final Instant NOW=Instant.parse("2026-09-14T05:00:00Z");
    static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    static final UUID PERSON=UUID.fromString("00000000-0000-0000-0000-000000000099"),TARGET=UUID.fromString("00000000-0000-0000-0000-000000000100");
    final WorkflowRuntimeJson json=new WorkflowRuntimeJson();
    WorkflowRuntimeKeys keys() { return new WorkflowRuntimeKeys(owner,transport,new JWKSet(auth.toPublicJWK()).toString()); }
    WorkflowRuntimeProofIssuer issuer() { return new WorkflowRuntimeProofIssuer(keys(),json,CLOCK); }
    WorkflowRuntimeAttestationVerifier verifier() { return new WorkflowRuntimeAttestationVerifier(keys(),json,CLOCK); }
    HttpServer server;
    @BeforeAll static void keysInitialize() throws Exception {
        owner=key("owner-new");transport=key("transport-new");auth=key("auth-new");other=key("unregistered-new");
    }
    static RSAKey key(String id) throws Exception { return new RSAKeyGenerator(2048).keyID(id).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate(); }
    @AfterEach void close() { if(server!=null) server.stop(0); }
    ObjectNode command() {
        var value=json.object();value.put("tenantId",42);value.put("actorId",99);value.put("personPublicId",PERSON.toString());
        value.put("commandPurpose","TASK_INFORMATION");value.put("targetId",TARGET.toString());value.put("routeContractKey","route.approvals.work.task-decision.action");
        value.put("method","POST");value.put("path","/v1/tasks/"+TARGET+"/decisions");value.put("idempotencyKey","original-key-1");value.put("rawBodySha256","b".repeat(64));
        value.put("contextKey","context-work");value.put("contextScopeKey","scope-work");value.put("decisionRevision","psr-"+"a".repeat(64));
        value.put("accessMode","NORMAL");value.put("rolloutState","110");value.put("authorityValidUntil",NOW.plusSeconds(20).getEpochSecond());
        value.put("expectedVersion",0);value.putNull("sourceGeneration");return value;
    }
    WorkflowRuntimeProofIssuer.Exchange information() {
        var bindings=json.object();bindings.set("command",command());return issuer().issue(Operation.INFORMATION_ADMISSION,99,bindings,NOW.plusSeconds(20));
    }
    ObjectNode infoResult(WorkflowRuntimeProofIssuer.Exchange exchange) {
        var result=json.object();for(String field:INFO_RESULT_FIELDS) result.set(field,exchange.bindings().get("command").get(field));return result;
    }
    byte[] response(WorkflowRuntimeProofIssuer.Exchange exchange,JsonNode result,RSAKey signing,long expiry,boolean extra) throws Exception {
        var authority=json.object();authority.put("ownerAuthRevision","auth-current");authority.put("ownerPolicyRevision","policy-current");
        authority.put("sourceRevision","awr-"+"d".repeat(64));authority.put("sourceVectorSha256","e".repeat(64));
        authority.put("evaluatedAt",NOW.getEpochSecond());authority.put("expiresAt",expiry);
        var claims=new JWTClaimsSet.Builder().issuer(exchange.operation().attestationIssuer()).claim("aud",exchange.operation().attestationAudience())
                .subject(exchange.actorId()).issueTime(Date.from(NOW)).notBeforeTime(Date.from(NOW)).expirationTime(new Date(expiry*1000)).jwtID(UUID.randomUUID().toString())
                .claim("purpose",exchange.operation().attestationPurpose()).claim("operation",exchange.operation().name()).claim("sourceProofJti",exchange.ownerJti().toString())
                .claim("transportProofJti",exchange.transportJti().toString()).claim("bodySha256",exchange.bodySha256()).claim("bindingsSha256",exchange.bindingsSha256())
                .claim("authority",new com.fasterxml.jackson.databind.ObjectMapper().convertValue(authority,Object.class))
                .claim("result",new com.fasterxml.jackson.databind.ObjectMapper().convertValue(result,Object.class));
        if(extra) claims.claim("permission","invented");
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(signing.getKeyID()).build(),claims.build());
        jwt.sign(new RSASSASigner(signing));var body=json.object();body.put("attestation",jwt.serialize());return json.bytes(body);
    }
    @Test void ownerAndTransportHaveExactDistinctProfilesAndOriginalDigests() throws Exception {
        var exchange=information();var envelope=json.parse(exchange.body(),BODY_MAX);
        assertEquals(Set.of("operation","sourceProof","bindings"),fields(envelope));
        var source=json.parse(WorkflowRuntimeJson.part(envelope.get("sourceProof").textValue().split("\\.")[1]),OWNER_MAX);
        var transportClaims=json.parse(WorkflowRuntimeJson.part(exchange.transportToken().split("\\.")[1]),2048);
        assertEquals(10,source.size());assertEquals(13,transportClaims.size());assertTrue(source.get("aud").isTextual());assertTrue(transportClaims.get("aud").isTextual());
        assertEquals(exchange.bodySha256(),WorkflowRuntimeJson.sha(exchange.body()));
        assertEquals(WorkflowRuntimeJson.sha(envelope.get("sourceProof").textValue()),transportClaims.get("sourceProofSha256").textValue());
        assertNotEquals(exchange.ownerJti(),exchange.transportJti());assertTrue(exchange.transportToken().length()<=2048);
        assertEquals(NOW.plusSeconds(20),exchange.expiresAt());
    }
    @Test void actualCryptoAdmitsExactCurrentInfoPins() throws Exception {
        var exchange=information();var verified=verifier().verify(response(exchange,infoResult(exchange),auth,exchange.expiresAt().getEpochSecond(),false),exchange);
        assertEquals(42,verified.result().get("tenantId").intValue());assertEquals(PERSON.toString(),verified.result().get("personPublicId").textValue());
        assertEquals(exchange.expiresAt(),verified.expiresAt());
    }
    @Test void nestedInfoCannotBorrowAnotherTenantOrPerson() throws Exception {
        var exchange=information();var result=infoResult(exchange);result.put("tenantId",43);
        byte[] wrongTenant=response(exchange,result,auth,exchange.expiresAt().getEpochSecond(),false);
        assertThrows(BaseException.class,()->verifier().verify(wrongTenant,exchange));result=infoResult(exchange);result.put("personPublicId",TARGET.toString());
        byte[] wrongPerson=response(exchange,result,auth,exchange.expiresAt().getEpochSecond(),false);assertThrows(BaseException.class,()->verifier().verify(wrongPerson,exchange));
    }
    @Test void signaturesUnknownClaimsAndExchangeSubstitutionAreDenied() throws Exception {
        var exchange=information();byte[] unknown=response(exchange,infoResult(exchange),other,exchange.expiresAt().getEpochSecond(),false);
        byte[] extra=response(exchange,infoResult(exchange),auth,exchange.expiresAt().getEpochSecond(),true);
        byte[] valid=response(exchange,infoResult(exchange),auth,exchange.expiresAt().getEpochSecond(),false);
        assertThrows(BaseException.class,()->verifier().verify(unknown,exchange));assertThrows(BaseException.class,()->verifier().verify(extra,exchange));
        assertThrows(BaseException.class,()->verifier().verify(valid,information()));
    }
    @Test void attestationCannotExtendSourceExpiryOrThirtySeconds() throws Exception {
        var exchange=information();byte[] tooLate=response(exchange,infoResult(exchange),auth,NOW.plusSeconds(21).getEpochSecond(),false);
        byte[] expired=response(exchange,infoResult(exchange),auth,NOW.getEpochSecond(),false);
        assertThrows(BaseException.class,()->verifier().verify(tooLate,exchange));assertThrows(BaseException.class,()->verifier().verify(expired,exchange));
        assertThrows(BaseException.class,()->issuer().issue(Operation.INFORMATION_ADMISSION,99,exchange.bindings(),NOW.plusMillis(999)));
    }
    @Test void keysCannotReuseOwnerTransportAuthOrFrozenKeyIdentity() {
        assertThrows(BaseException.class,()->new WorkflowRuntimeKeys(owner,owner,new JWKSet(auth.toPublicJWK()).toString()));
        assertThrows(BaseException.class,()->new WorkflowRuntimeKeys(owner,transport,new JWKSet(owner.toPublicJWK()).toString()));
        assertThrows(BaseException.class,()->new WorkflowRuntimeKeys(owner,transport,new JWKSet(auth.toPublicJWK()).toString(),new JWKSet(owner.toPublicJWK()).toString()));
    }
    @Test void strictJsonRejectsDuplicateTrailingFractionalAndOversizedInputs() {
        assertThrows(BaseException.class,()->json.parse("{\"x\":1,\"x\":2}".getBytes(),BODY_MAX));
        assertThrows(BaseException.class,()->json.parse("{} {}".getBytes(),BODY_MAX));
        assertThrows(BaseException.class,()->json.bytes(json.tree(java.util.Map.of("x",1.1))));
        assertThrows(BaseException.class,()->json.parse(new byte[BODY_MAX+1],BODY_MAX));
    }
    @Test void defaultHttpPassesLegal64StagesAnd1000SignedMembersWithoutHeaderWaiver() throws Exception {
        var stages=new java.util.ArrayList<ApprovalWorkflowQuorumDefinition.Stage>();
        for(int i=0;i<64;i++) stages.add(new ApprovalWorkflowQuorumDefinition.Stage("S"+i,"Stage "+i,"FINANCE_REVIEWER",new ApprovalWorkflowQuorum.Rule(ApprovalWorkflowQuorum.Mode.ANY,null),15,List.of()));
        var definition=ApprovalWorkflowQuorumDefinition.fromStages(60,stages);
        var source=json.object();source.put("tenantId",42);source.put("actorId",99);source.put("personPublicId",PERSON.toString());source.put("requestId",TARGET.toString());source.put("requestVersion",0);
        source.put("workflowVersionId",UUID.randomUUID().toString());source.put("workflowVersion",1);source.put("workflowDefinitionSha256",definition.sha256());source.put("formVersionId",UUID.randomUUID().toString());
        source.put("formSchemaSha256","a".repeat(64));source.put("payloadRevision",1);source.put("payloadSha256","b".repeat(64));source.put("policyVersion",1);source.put("policySha256","c".repeat(64));
        source.put("contextKey","work");source.put("contextScopeKey","scope");source.put("decisionRevision","psr-"+"a".repeat(64));source.put("accessMode","NORMAL");
        source.put("routeContractKey","route.approvals.work.request-submit.action");source.put("managementResourceSetKey","RS_APPROVALS");source.putArray("roleCodes").add("FINANCE_REVIEWER");
        source.put("publishedDefinition",definition.canonicalJson());source.put("method","POST");source.put("path","/v1/requests/"+TARGET+"/submit");source.put("idempotencyKey","original-submit");
        var stage=json.object();stage.put("poolMode","INITIAL");stage.put("stepKey","S0");stage.put("generation",1);stage.put("requesterUserId",99);stage.put("requesterPersonPublicId",PERSON.toString());stage.put("candidateRole","FINANCE_REVIEWER");
        stage.put("sourceStageRevision",0);stage.put("rejectCommentMinLength",4);
        for(String field:List.of("snapshotSha256","candidateSetSha256","candidateCount","requiredVotes","admissionAttestationSha256","admissionAttestation","commandRawBodySha256")) stage.putNull(field);
        var bindings=json.object();bindings.set("owner",source);bindings.set("stage",stage);var exchange=issuer().issue(Operation.CANDIDATES,99,bindings,NOW.plusSeconds(30));
        var result=json.object();var role=json.object();role.put("roleCode","FINANCE_REVIEWER");role.put("roleId",10);role.put("roleVersion",0);result.set("role",role);result.put("complete",true);result.put("truncated",false);
        var members=result.putArray("members");
        for(int i=100;i<1100;i++) { var member=json.object();member.put("tenantId",42);member.put("userId",i);member.put("personPublicId",UUID.nameUUIDFromBytes(Integer.toString(i).getBytes()).toString());
            member.put("identityPlane","TENANT");member.put("status","ACTIVE");member.putArray("roleIds").add(10);member.put("canApprove",true);members.add(member); }
        result.put("memberSetSha256",WorkflowRuntimeJson.sha(json.bytes(members)));byte[] response=response(exchange,result,auth,exchange.expiresAt().getEpochSecond(),false);
        assertTrue(response.length<BODY_MAX);server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();
        server.createContext(PATH,request->{calls.incrementAndGet();assertEquals(exchange.transportToken(),request.getRequestHeaders().getFirst(HEADER));
            assertArrayEquals(exchange.body(),request.getRequestBody().readAllBytes());request.getResponseHeaders().set("Content-Type","application/json");request.sendResponseHeaders(200,response.length);
            request.getResponseBody().write(response);request.close();});server.start();
        var client=new WorkflowRuntimeAuthorityClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+PATH),verifier());
        assertEquals(1000,client.evaluate(exchange).result().get("members").size());assertEquals(1,calls.get());
    }
    @Test void httpRedirectAndOversizedResponseFailClosed() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var mode=new AtomicInteger();var calls=new AtomicInteger();
        server.createContext(PATH,request->{calls.incrementAndGet();if(mode.get()==0) {request.getResponseHeaders().set("Location","/arbitrary-auth");request.sendResponseHeaders(302,-1);}
            else {request.getResponseHeaders().set("Content-Type","application/json");request.sendResponseHeaders(200,BODY_MAX+1);request.getResponseBody().write(new byte[BODY_MAX+1]);}request.close();});server.start();
        var client=new WorkflowRuntimeAuthorityClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+PATH),verifier());
        assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,assertThrows(BaseException.class,()->client.evaluate(information())).getErrorCode());assertEquals(1,calls.get());
        mode.set(1);assertEquals(ErrorCode.FORBIDDEN,assertThrows(BaseException.class,()->client.evaluate(information())).getErrorCode());
    }
    @Test void endpointCannotBeGenericAuthOrCarryAmbientCredentials() {
        for(String uri:List.of("http://example.com"+PATH,"http://localhost/internal/users","http://user@localhost"+PATH,"http://localhost"+PATH+"?tenant=42"))
            assertThrows(BaseException.class,()->new WorkflowRuntimeAuthorityClient(URI.create(uri),verifier()));
    }
    @Test void actualRequestPropagatesOnlyChildTraceAndCorrelationNotAmbientAuthority() throws Exception {
        var exchange=information();byte[] response=response(exchange,infoResult(exchange),auth,exchange.expiresAt().getEpochSecond(),false);
        var captured=new java.util.concurrent.atomic.AtomicReference<com.sun.net.httpserver.Headers>();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext(PATH,request->{captured.set(request.getRequestHeaders());
            request.getResponseHeaders().set("Content-Type","application/json");request.sendResponseHeaders(200,response.length);
            request.getResponseBody().write(response);request.close();});server.start();
        var ambient=new org.springframework.mock.web.MockHttpServletRequest();
        String parent="00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";
        ambient.addHeader("traceparent",parent);ambient.addHeader("tracestate","vendor=opaque");ambient.addHeader("X-Correlation-ID","joint-correlation");
        for(String name:List.of("Authorization","Cookie","X-DWP-Service-Token","X-DWP-Tenant-ID",HEADER)) ambient.addHeader(name,"ambient-untrusted");
        var previous=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(ambient));
        try {
            var client=new WorkflowRuntimeAuthorityClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+PATH),verifier());
            assertEquals(PERSON.toString(),client.evaluate(exchange).result().get("personPublicId").textValue());
        } finally {org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(previous);}
        var headers=captured.get();assertNotNull(headers);
        assertEquals("joint-correlation",headers.getFirst("X-Correlation-ID"));assertEquals("vendor=opaque",headers.getFirst("tracestate"));
        String child=headers.getFirst("traceparent");assertNotNull(child);assertEquals(parent.substring(0,36),child.substring(0,36));assertNotEquals(parent,child);
        assertEquals(List.of(exchange.transportToken()),headers.get(HEADER));assertEquals("dwp-approval-server",headers.getFirst("X-DWP-Service-Identity"));
        for(String name:List.of("Authorization","Cookie","X-DWP-Service-Token","X-DWP-Tenant-ID")) assertNull(headers.getFirst(name));
    }
    @Test void wholeExchangeDeadlineCancelsAStalledBodyAfterHeaders() throws Exception {
        var release=new java.util.concurrent.CountDownLatch(1);server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext(PATH,request->{request.getResponseHeaders().set("Content-Type","application/json");request.sendResponseHeaders(200,1);
            try {release.await(10,java.util.concurrent.TimeUnit.SECONDS);} catch(InterruptedException error) {Thread.currentThread().interrupt();}
            finally {request.close();}});server.start();
        var client=new WorkflowRuntimeAuthorityClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+PATH),verifier());
        long started=System.nanoTime();
        try {assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,assertThrows(BaseException.class,()->client.evaluate(information())).getErrorCode());
            assertTrue(java.time.Duration.ofNanos(System.nanoTime()-started).toMillis()<6500);}
        finally {release.countDown();}
    }
    static Set<String> fields(JsonNode value) {var names=new java.util.HashSet<String>();value.fieldNames().forEachRemaining(names::add);return names;}
}
