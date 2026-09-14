package com.dwp.services.approval.signatures;

import static org.assertj.core.api.Assertions.*;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;

class ApprovalSignatureAuthorityClientTest {
    static RSAKey owner,transport,authority,other;
    static final ApprovalSignatureCanonical json=new ApprovalSignatureCanonical(new ObjectMapper());
    HttpServer server; ExecutorService executor; ApprovalSignatureSourceKeys keys;
    @BeforeAll static void keyFamilies() throws Exception {
        owner=key("approval-signature-owner:test"); transport=key("approval-signature-transport:test"); authority=key("approval-signature-authority:test"); other=key("other-purpose");
    }
    static RSAKey key(String id) throws Exception { return new RSAKeyGenerator(2048).keyID(id).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate(); }
    @BeforeEach void setup() throws Exception {
        var env=new MockEnvironment().withProperty("dwp.approval.internal-signatures.source.key-isolation-inventory-complete","true")
                .withProperty("dwp.approval.internal-signatures.source.prohibited-jwks",new JWKSet(other.toPublicJWK()).toString())
                .withProperty("dwp.approval.internal-signatures.source.owner-private-jwk",owner.toJSONString())
                .withProperty("dwp.approval.internal-signatures.source.transport-private-jwk",transport.toJSONString())
                .withProperty("dwp.approval.internal-signatures.authority-public-jwks",new JWKSet(authority.toPublicJWK()).toString());
        keys=new ApprovalSignatureSourceKeys(env); server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        executor=Executors.newCachedThreadPool(r->{var t=new Thread(r);t.setDaemon(true);return t;}); server.setExecutor(executor);
    }
    @AfterEach void cleanup() { server.stop(0); executor.shutdownNow(); }
    ApprovalSignatureAuthorityClient client() { return new ApprovalSignatureAuthorityClient(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+ApprovalSignatureSourceExchange.PATH),keys,json); }
    ApprovalSignatureSourceExchange.Exchange exchange() {
        var b=new LinkedHashMap<String,Object>();
        for (String key:List.of("contextKey","contextScopeKey","resourceSetKey","decisionRevision","registrySha256","sourceSha256","bodySha256")) b.put(key,"original-"+key);
        b.put("nonce",UUID.randomUUID().toString());
        return new ApprovalSignatureSourceExchange(keys,json,Clock.systemUTC()).issue(b,Instant.now().plusSeconds(30));
    }
    String assertion(Map<String,Object> b) throws Exception {
        var c=new JWTClaimsSet.Builder(); b.forEach(c::claim);
        var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(authority.getKeyID()).build(),c.build());
        jwt.sign(new RSASSASigner(authority)); return jwt.serialize();
    }
    @Test void actualFixedPurposePostNeverForwardsCallerCredentialsAndReturnsOnlyBoundSignedAssertion() throws Exception {
        var e=exchange(); String token=assertion(e.bindings()); var seen=new CompletableFuture<byte[]>();
        server.createContext(ApprovalSignatureSourceExchange.PATH,r->{
            try {
                assertThat(r.getRequestMethod()).isEqualTo("POST"); assertThat(r.getRequestURI().getRawQuery()).isNull();
                assertThat(r.getRequestHeaders().getFirst(ApprovalSignatureSourceExchange.HEADER)).isEqualTo(e.token());
                assertThat(r.getRequestHeaders().getFirst("X-DWP-Service-Identity")).isEqualTo("dwp-approval-server");
                for (String h:List.of("Authorization","Cookie","X-DWP-Approval-Workflow-Runtime-Token","X-DWP-Approval-Policy-Impact-Token","X-DWP-Step-Up-Challenge")) assertThat(r.getRequestHeaders().get(h)).isNull();
                seen.complete(r.getRequestBody().readAllBytes()); byte[] response=json.json(Map.of("status","SUCCESS","data",Map.of("assertion",token))).getBytes(StandardCharsets.UTF_8);
                r.getResponseHeaders().set("Content-Type","application/json"); r.sendResponseHeaders(200,response.length); r.getResponseBody().write(response);
            } catch (Throwable failure) { seen.completeExceptionally(failure); } finally { r.close(); }
        }); server.start(); assertThat(client().evaluate(e)).isEqualTo(token); assertThat(seen.get(1,TimeUnit.SECONDS)).isEqualTo(e.body());
        assertThat(SignedJWT.parse(e.token()).getJWTClaimsSet().getStringClaim("purpose")).isEqualTo("DWP_APPROVAL_SIGNATURE_TRANSPORT_V1");
    }
    @Test void redirectIsNotFollowedAndNonJsonOrOversizedResponseFailsClosed() {
        var redirects=new AtomicInteger(); server.createContext("/redirect",r->{redirects.incrementAndGet();r.close();});
        server.createContext(ApprovalSignatureSourceExchange.PATH,r->{r.getResponseHeaders().set("Location","/redirect");r.sendResponseHeaders(302,-1);r.close();});
        server.start(); assertThatThrownBy(()->client().evaluate(exchange())).isInstanceOf(BaseException.class); assertThat(redirects).hasValue(0);
        server.removeContext(ApprovalSignatureSourceExchange.PATH);
        server.createContext(ApprovalSignatureSourceExchange.PATH,r->{r.getResponseHeaders().set("Content-Type","application/json");r.sendResponseHeaders(200,32769);r.getResponseBody().write(new byte[32769]);r.close();});
        assertThatThrownBy(()->client().evaluate(exchange())).isInstanceOf(BaseException.class);
        server.removeContext(ApprovalSignatureSourceExchange.PATH);
        server.createContext(ApprovalSignatureSourceExchange.PATH,r->{r.getResponseHeaders().set("Content-Type","text/html");r.sendResponseHeaders(200,2);r.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));r.close();});
        assertThatThrownBy(()->client().evaluate(exchange())).isInstanceOf(BaseException.class);
    }
    @Test void slowResponseBodyIsIncludedInFiveSecondDeadlineAndCanceled() {
        server.createContext(ApprovalSignatureSourceExchange.PATH,r->{
            try { r.getResponseHeaders().set("Content-Type","application/json");r.sendResponseHeaders(200,0);r.getResponseBody().write('{');r.getResponseBody().flush();Thread.sleep(10000); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); } finally {r.close();}
        });server.start(); long start=System.nanoTime(); assertThatThrownBy(()->client().evaluate(exchange())).isInstanceOf(BaseException.class);
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isBetween(Duration.ofSeconds(4),Duration.ofSeconds(8));
    }
    @Test void signedAssertionWithRetargetedNonceOrSourceHashIsRejected() throws Exception {
        var e=exchange(); var changed=new LinkedHashMap<>(e.bindings());changed.put("sourceSha256","wrong-source");String token=assertion(changed);
        server.createContext(ApprovalSignatureSourceExchange.PATH,r->{byte[] b=json.json(Map.of("status","SUCCESS","data",Map.of("assertion",token))).getBytes(StandardCharsets.UTF_8);
            r.getResponseHeaders().set("Content-Type","application/json");r.sendResponseHeaders(200,b.length);r.getResponseBody().write(b);r.close();});server.start();
        assertThatThrownBy(()->client().evaluate(e)).isInstanceOf(BaseException.class);
    }
    @Test void nonLoopbackCleartextAliasesAndAmbientEndpointComponentsAreRejectedBeforeTransport() {
        for (String url:List.of("http://example.test"+ApprovalSignatureSourceExchange.PATH,"https://auth.test"+ApprovalSignatureSourceExchange.PATH+"/",
                "https://user:pass@auth.test"+ApprovalSignatureSourceExchange.PATH,"https://auth.test"+ApprovalSignatureSourceExchange.PATH+"?alias=1",
                "https://auth.test"+ApprovalSignatureSourceExchange.PATH+"#fragment"))
            assertThatThrownBy(()->new ApprovalSignatureAuthorityClient(URI.create(url),keys,json)).isInstanceOf(BaseException.class);
    }
}
