package com.dwp.services.approval.signatures;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ProductSurfaceStepUpChallengeContract;
import com.dwp.services.approval.security.*;
import com.dwp.services.approval.signatures.ApprovalSignatureAuthority.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Real MFA + real atomic replay SQL. The installed PEP seal is a fixture, not production10 activation. */
@Testcontainers
class ApprovalSignatureHighRiskPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine").withLabel("dwp.approval.owner","apr16b-signature-high");
    static RSAKey mfa; static final UUID PERSON=UUID.randomUUID();
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    final ApprovalSignatureCanonical json=new ApprovalSignatureCanonical(mapper);
    JdbcTemplate jdbc; TransactionTemplate tx; ApprovalStepUpReplayRepository replay;
    Clock clock=Clock.systemUTC(); Binding binding; Verified proof; MockHttpServletRequest request;
    ApprovalSignatureInstalledSource installed; ApprovalSignatureHighRiskGuard guard;
    @BeforeAll static void key() throws Exception { mfa=new RSAKeyGenerator(2048).keyID("auth-step-up:signature-test").generate(); }
    @BeforeEach void setup() throws Exception {
        var ds=new PGSimpleDataSource(); ds.setURL(PG.getJdbcUrl());ds.setUser(PG.getUsername());ds.setPassword(PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();jdbc=new JdbcTemplate(ds); jdbc.update("DELETE FROM apr_step_up_replay_ledger");
        replay=new ApprovalStepUpReplayRepository(new NamedParameterJdbcTemplate(ds),mapper); tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        ApprovalRequestContext.set(99L,42L,PERSON,Set.of(),Set.of("APP.APPROVALS:VIEW","ACTION.APPROVAL_REQUEST:VIEW","ACTION.APPROVAL_SIGNATURE:SIGN"));
        var body=Map.of("expectedVersion",1L,"sourceDigest","a".repeat(64),"consentReceiptId",UUID.randomUUID().toString(),"idempotencyKey","sign-original");
        binding=new Binding(Operation.SIGN,UUID.randomUUID(),1L,json.digest(body),"sign-original",mapper.valueToTree(body));
        var port=new ApprovalSignatureTestAuthority(clock); proof=port.verifier(json).require(binding);
        request=new MockHttpServletRequest("POST",Operation.SIGN.path(binding.objectId()));request.addHeader("X-DWP-Step-Up-Challenge",token(claims()));
        installed=mock(ApprovalSignatureInstalledSource.class);
        when(installed.capture(request,binding)).thenReturn(new ApprovalSignatureInstalledSource.Seal(ApprovalRequestContext.require(),
                new ApprovalDecisionRevisionContext.Evidence("psr-"+"a".repeat(64),OffsetDateTime.now().plusMinutes(5),"context","opaque",Operation.SIGN.route(),"110"),"NORMAL","b".repeat(64)));
        String pem="-----BEGIN PUBLIC KEY-----\n"+Base64.getEncoder().encodeToString(mfa.toRSAPublicKey().getEncoded())+"\n-----END PUBLIC KEY-----";
        guard=new ApprovalSignatureHighRiskGuard(installed,()->request,new ApprovalStepUpVerifier(mapper,pem,"https://auth.example.test","dwp-approval-server",mfa.getKeyID(),"urn:dwp:acr:mfa",600,900),replay,clock);
    }
    @AfterEach void clear() { ApprovalRequestContext.clear(); }
    Map<String,Object> claims() {
        var c=new LinkedHashMap<String,Object>(); long now=Instant.now().getEpochSecond();
        c.put("iss","https://auth.example.test");c.put("aud","dwp-approval-server");c.put("sub","99");c.put("tenant_id",42L);
        c.put("iat",now);c.put("nbf",now);c.put("exp",now+300);c.put("auth_time",now);c.put("acr","urn:dwp:acr:mfa");c.put("amr",List.of("pwd","mfa"));
        c.put("jti","original-challenge");c.put("nonce","original-nonce");c.put("owner_service_key","approval");c.put("command_contract_key",Operation.SIGN.route());
        c.put("activation_policy","STEPUP-MGMT-HIGH-V1");c.put("capability_contract_key","approvals.work.signature.sign");c.put("context_key","context");c.put("scope_ref","opaque");
        c.put("target_type","APPROVAL_SIGNATURE_REQUEST");c.put("target_id",binding.objectId().toString());c.put("target_version",1L);
        c.put("command_method","POST");c.put("command_path","/api/approvals"+Operation.SIGN.path(binding.objectId()));
        c.put("idempotency_key",binding.idempotencyKey());c.put("payload_sha256",binding.bodySha256());c.put("decision_revision","psr-"+"a".repeat(64));
        c.put("command_sha256",ProductSurfaceStepUpChallengeContract.commandSha256(new ProductSurfaceStepUpChallengeContract.CommandMaterial(Operation.SIGN.route(),
                "approval","dwp-approval-server","POST",(String)c.get("command_path"),"context","opaque","APPROVAL_SIGNATURE_REQUEST",binding.objectId().toString(),1L,
                binding.idempotencyKey(),binding.bodySha256(),"psr-"+"a".repeat(64))));return c;
    }
    String token(Map<String,Object> c) throws Exception { var jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(mfa.getKeyID()).type(JOSEObjectType.JWT).build(),JWTClaimsSet.parse(c));jwt.sign(new RSASSASigner(mfa));return jwt.serialize(); }
    int rows() { return jdbc.queryForObject("SELECT count(*) FROM apr_step_up_replay_ledger",Integer.class); }
    @Test void actualCommandVerificationDoesNotConsumeUntilFinalTransactionStepThenConsumesExactlyOnce() {
        tx.execute(status->{var original=guard.verify(proof,binding);assertThat(rows()).isZero();guard.consume(proof,original);return null;});assertThat(rows()).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT target_type,command_path,payload_sha256,capability_contract_key FROM apr_step_up_replay_ledger"))
                .containsEntry("target_type","APPROVAL_SIGNATURE_REQUEST").containsEntry("command_path","/api/approvals"+Operation.SIGN.path(binding.objectId()))
                .containsEntry("payload_sha256",binding.bodySha256()).containsEntry("capability_contract_key","approvals.work.signature.sign");
        assertThatThrownBy(()->tx.execute(status->{guard.consume(proof,guard.verify(proof,binding));return null;})).isInstanceOf(BaseException.class);assertThat(rows()).isEqualTo(1);
    }
    @Test void failedLateBusinessTransactionRollsBackNonceAndOriginalTokenCanRecover() {
        assertThatThrownBy(()->tx.execute(status->{guard.consume(proof,guard.verify(proof,binding));throw new IllegalStateException("late source fence");})).isInstanceOf(IllegalStateException.class);
        assertThat(rows()).isZero();tx.execute(status->{guard.consume(proof,guard.verify(proof,binding));return null;});assertThat(rows()).isEqualTo(1);
    }
    @Test void actualMfaRejectsOtherPurposeTargetBodyCasExpiredOrUnsignedBeforeSql() throws Exception {
        for (var mismatch:Map.<String,Object>of("command_contract_key","route.approvals.admin.policy-publish.action","target_type","APPROVAL_FORM",
                "target_version",2L,"payload_sha256","0".repeat(64),"scope_ref","wrong-opaque","exp",Instant.now().minusSeconds(1).getEpochSecond()).entrySet()) {
            var c=claims();c.put(mismatch.getKey(),mismatch.getValue());request.removeHeader("X-DWP-Step-Up-Challenge");request.addHeader("X-DWP-Step-Up-Challenge",token(c));
            assertThatThrownBy(()->tx.execute(status->{guard.consume(proof,guard.verify(proof,binding));return null;})).as(mismatch.getKey()).isInstanceOf(BaseException.class);assertThat(rows()).isZero();
        }
        request.removeHeader("X-DWP-Step-Up-Challenge");request.addHeader("X-DWP-Step-Up-Challenge","true");
        assertThatThrownBy(()->tx.execute(status->{guard.verify(proof,binding);return null;})).isInstanceOf(BaseException.class);assertThat(rows()).isZero();
    }
    @Test void sourceDriftAfterVerificationAndReadonlyOrNoTransactionCannotConsume() {
        assertThatThrownBy(()->guard.verify(proof,binding)).isInstanceOf(BaseException.class);
        var read=new TransactionTemplate(tx.getTransactionManager());read.setReadOnly(true);
        assertThatThrownBy(()->read.execute(status->{guard.verify(proof,binding);return null;})).isInstanceOf(BaseException.class);
        assertThatThrownBy(()->tx.execute(status->{var original=guard.verify(proof,binding);when(installed.capture(request,binding)).thenThrow(ApprovalSignatureCanonical.conflict());guard.consume(proof,original);return null;})).isInstanceOf(BaseException.class);assertThat(rows()).isZero();
    }
    @Test void concurrentOriginalNonceConsumptionHasOnlyOneCommittedWinner() throws Exception {
        var start=new CyclicBarrier(2);var winners=new AtomicInteger();var losers=new AtomicInteger();var pool=Executors.newFixedThreadPool(2);
        try { var work=new ArrayList<Future<?>>();for(int i=0;i<2;i++)work.add(pool.submit(()->{
            try { tx.execute(status->{var original=guard.verify(proof,binding);try{start.await(10,TimeUnit.SECONDS);}catch(Exception failure){throw new IllegalStateException(failure);}guard.consume(proof,original);return null;});winners.incrementAndGet(); }
            catch(BaseException conflict){losers.incrementAndGet();}
        }));for(var future:work)future.get(15,TimeUnit.SECONDS); }
        finally{pool.shutdownNow();}assertThat(winners).hasValue(1);assertThat(losers).hasValue(1);assertThat(rows()).isEqualTo(1);
    }
}
