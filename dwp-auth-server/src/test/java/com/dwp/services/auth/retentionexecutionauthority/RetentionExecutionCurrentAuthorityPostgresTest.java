package com.dwp.services.auth.retentionexecutionauthority;

import static org.assertj.core.api.Assertions.*;
import static com.dwp.services.auth.retentionexecutionauthority.RetentionExecutionProtocol.*;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.service.RetentionExecutionActualAuthHarness;
import java.net.http.*;
import java.security.Signature;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetentionExecutionCurrentAuthorityPostgresTest {
    private final RetentionExecutionProofFixture fixture=new RetentionExecutionProofFixture();
    private RetentionExecutionActualAuthHarness auth;
    @BeforeAll void start() throws Exception {auth=new RetentionExecutionActualAuthHarness(fixture.json);}
    @AfterAll void stop() {if(auth!=null) auth.close();}
    private RetentionExecutionAuthorityService service(RetentionExecutionAuthorityPort authority,RetentionExecutionReplayStore replay) {
        return new RetentionExecutionAuthorityService(fixture::verifier,authority,replay,new RetentionExecutionAuthorityProducer(fixture.json,()->fixture.keys,Clock.systemUTC()),Clock.systemUTC(),true);
    }
    private RetentionExecutionAuthorityService service() {return service(auth.bridge(),new RetentionExecutionReplayStore(auth.redis(),Clock.systemUTC()));}
    private RetentionExecutionProofFixture.Exchange source(RetentionExecutionActualAuthHarness.Subject subject) {return fixture.issue(fixture.bindings(auth.tenantId(),subject.actor(),"RS_APPROVALS"));}
    @Test void genuineCurrentAuthOpsAndInstalledHttpProducesExistingEd25519NineClaims() throws Exception {
        var source=source(auth.subject());var actual=service();
        try(var server=new RetentionExecutionEmbeddedServer(actual,true)) {
            var response=post(server,source,null);assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            var wrapper=fixture.json.parse(response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8),24576);assertThat(wrapper.size()).isEqualTo(2);
            byte[] payload=RetentionExecutionJson.part(wrapper.path("payloadBase64Url").asText());var verifier=Signature.getInstance("Ed25519");
            verifier.initVerify(fixture.execution.getPublic());verifier.update(payload);assertThat(verifier.verify(RetentionExecutionJson.part(wrapper.path("signatureBase64Url").asText()))).isTrue();
            var claims=fixture.json.parse(payload,8192);assertThat(claims.size()).isEqualTo(9);assertThat(claims.path("target").size()).isEqualTo(12);
            assertThat(claims.path("purpose").asText()).isEqualTo("APPROVAL_RETENTION_EXECUTE_V1");assertThat(claims.path("nonce").asText()).isEqualTo(source.ownerJti().toString());
            assertThat(claims.path("target")).isEqualTo(fixture.json.parse(source.body(),BODY_LIMIT).at("/bindings/target"));
            assertThat(claims.has("authRevision")).isFalse();assertThat(claims.has("personPublicId")).isFalse();
            assertThat(post(server,source,null).statusCode()).isEqualTo(403);
        }
    }
    @Test void currentSelectedScopeMissingOrConflictingDutyNeverIssues() {
        var subject=auth.subject();var source=source(subject);var proof=fixture.verifier().verify(source.body(),source.token());
        auth.revoke(subject);assertThatThrownBy(()->auth.bridge().requireCurrent(proof)).isInstanceOf(BaseException.class);
        var different=fixture.issue(fixture.bindings(auth.tenantId(),auth.subject().actor(),"RS_OTHER_APPROVALS"));
        assertThatThrownBy(()->auth.bridge().requireCurrent(fixture.verifier().verify(different.body(),different.token()))).isInstanceOf(BaseException.class);
        var ambiguous=auth.subject();auth.ambiguousResponsibility(ambiguous);var duplicated=source(ambiguous);
        assertThatThrownBy(()->auth.bridge().requireCurrent(fixture.verifier().verify(duplicated.body(),duplicated.token()))).isInstanceOf(BaseException.class);
    }
    @Test void realRevocationBetweenPrePostAndMissingRedisProduceZeroSignatures() {
        var subject=auth.subject();var source=source(subject);var reads=new AtomicInteger();var signatures=new AtomicInteger();
        RetentionExecutionAuthorityPort actual=proof->{var result=auth.bridge().requireCurrent(proof);if(reads.incrementAndGet()==1) auth.revoke(subject);return result;};
        var service=new RetentionExecutionAuthorityService(fixture::verifier,actual,new RetentionExecutionReplayStore(auth.redis(),Clock.systemUTC()),
                current->{signatures.incrementAndGet();throw new AssertionError();},Clock.systemUTC(),true);
        assertThatThrownBy(()->service.evaluate(service.preverify(source.body(),source.token()))).isInstanceOf(BaseException.class);assertThat(signatures).hasValue(0);
        var missing=service(auth.bridge(),new RetentionExecutionReplayStore(null,Clock.systemUTC()));var another=source(auth.subject());
        assertThatThrownBy(()->missing.evaluate(missing.preverify(another.body(),another.token()))).isInstanceOf(BaseException.class);
    }
    @Test void actualRedisRetainsConsumedProofAfterShortAdmissionDeadlineAndAcrossReplicas() throws Exception {
        var source=fixture.issue(fixture.bindings(auth.tenantId(),auth.subject().actor(),"RS_APPROVALS"),30,20);var proof=fixture.verifier().verify(source.body(),source.token());
        var first=new RetentionExecutionReplayStore(auth.redis(),Clock.systemUTC());var second=new RetentionExecutionReplayStore(auth.redis(),Clock.systemUTC());
        first.consume(proof,Instant.now().plusMillis(100));String key="dwp:auth:retention-execution:{"+auth.tenantId()+':'+OWNER_PURPOSE+"}:owner:"+proof.sourceJti();
        assertThat(auth.redis().getExpire(key,TimeUnit.MILLISECONDS)).isGreaterThan(20000L);
        Thread.sleep(150);assertThatThrownBy(()->second.consume(proof,Instant.now().plusSeconds(1))).isInstanceOf(BaseException.class);
        var concurrent=source(auth.subject());var current=fixture.verifier().verify(concurrent.body(),concurrent.token());var start=new CountDownLatch(1);
        try(var threads=Executors.newFixedThreadPool(2)) {
            Callable<Boolean> a=()->{start.await();try {first.consume(current,current.expiresAt());return true;} catch(BaseException denied) {return false;}};
            Callable<Boolean> b=()->{start.await();try {second.consume(current,current.expiresAt());return true;} catch(BaseException denied) {return false;}};
            var left=threads.submit(a);var right=threads.submit(b);start.countDown();assertThat((left.get()?1:0)+(right.get()?1:0)).isEqualTo(1);
        }
    }
    @Test void defaultFalseWrongServiceBorrowedHeadersAliasesAndDuplicatesNeverReachController() throws Exception {
        var source=source(auth.subject());
        try(var server=new RetentionExecutionEmbeddedServer(service(),false)) {assertThat(post(server,source,null).statusCode()).isEqualTo(503);}
        try(var server=new RetentionExecutionEmbeddedServer(service(),true)) {
            assertThat(post(server,source,"X-DWP-Approval-System-Sla-Token").statusCode()).isEqualTo(403);
            var wrongService=HttpRequest.newBuilder(server.endpoint()).header("Content-Type","application/json").header("X-DWP-Service-Identity","dwp-notification-server")
                    .header(HEADER,source.token()).POST(HttpRequest.BodyPublishers.ofByteArray(source.body())).build();
            assertThat(HttpClient.newHttpClient().send(wrongService,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
            var duplicate=request(server,source).header(HEADER,source.token()).build();
            assertThat(HttpClient.newHttpClient().send(duplicate,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
            var alias=HttpRequest.newBuilder(java.net.URI.create(server.endpoint()+"?x=1")).header("Content-Type","application/json").header("X-DWP-Service-Identity","dwp-approval-server")
                    .header(HEADER,source.token()).POST(HttpRequest.BodyPublishers.ofByteArray(source.body())).build();
            assertThat(HttpClient.newHttpClient().send(alias,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        }
    }
    private HttpRequest.Builder request(RetentionExecutionEmbeddedServer server,RetentionExecutionProofFixture.Exchange source) {
        return HttpRequest.newBuilder(server.endpoint()).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json")
                .header("X-DWP-Service-Identity","dwp-approval-server").header(HEADER,source.token()).POST(HttpRequest.BodyPublishers.ofByteArray(source.body()));
    }
    private HttpResponse<String> post(RetentionExecutionEmbeddedServer server,RetentionExecutionProofFixture.Exchange source,String borrowed) throws Exception {
        var request=request(server,source);if(borrowed!=null) request.header(borrowed,"not-borrowed-authority");return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
}
