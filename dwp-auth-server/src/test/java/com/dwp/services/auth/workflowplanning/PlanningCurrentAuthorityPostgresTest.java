package com.dwp.services.auth.workflowplanning;

import static org.assertj.core.api.Assertions.*;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.service.PlanningActualAuthHarness;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanningCurrentAuthorityPostgresTest {
    private final PlanningProofTestFixture fixture=new PlanningProofTestFixture();
    private PlanningActualAuthHarness auth;
    private long role;
    @BeforeAll void start() throws Exception {
        auth=new PlanningActualAuthHarness(fixture.owner,fixture.transport,fixture.attestation);role=auth.role("PLANNING_APPROVER",1000);
    }
    @AfterAll void stop() {if(auth!=null) auth.close();}
    private PlanningProofTestFixture.Exchange exchange(PlanningActualAuthHarness.Subject subject,int stages) {
        var current=auth.current(subject);assertThat(current.decision()).as(current.reasonCode()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(current.effectiveReadOnly()).isTrue();
        return fixture.issue(fixture.bindings(auth.tenantId(),subject.userId(),subject.personPublicId(),current.contextKey(),current.scopes().getFirst().key(),
                Instant.now().plusSeconds(60).getEpochSecond(),stages));
    }
    @Test void realCurrentAll2AuthAnd64StagesAnd1000MembersUseInstalledHttpChain() throws Exception {
        var source=exchange(auth.subject(),64);var response=post(source,"application/json");assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var jwt=SignedJWT.parse(fixture.json.parse(response.body().getBytes(java.nio.charset.StandardCharsets.UTF_8),524288).path("attestation").asText());
        assertThat(jwt.verify(new RSASSAVerifier(fixture.attestation.toPublicJWK()))).isTrue();
        var claims=fixture.json.parse(PlanningJson.part(jwt.serialize().split("\\.")[1]),524288);assertThat(claims.size()).isEqualTo(15);
        assertThat(claims.path("purpose").asText()).isEqualTo(PlanningProtocol.ATTESTATION_PURPOSE);
        assertThat(claims.path("authority").size()).isEqualTo(6);assertThat(claims.at("/authority/ownerAuthRevision").asText()).matches("auth-[a-f0-9]{64}");
        assertThat(claims.at("/authority/ownerPolicyRevision").asText()).startsWith("policy-10-");
        assertThat(claims.at("/authority/sourceRevision").asText()).isEqualTo("awp-"+claims.at("/authority/sourceVectorSha256").asText());
        var counted=claims.at("/result/roles/0");assertThat(counted.size()).isEqualTo(4);assertThat(counted.path("roleId").asLong()).isEqualTo(role);
        assertThat(counted.path("activeMemberCount").asInt()).isEqualTo(1000);assertThat(counted.has("users")).isFalse();
        assertThat(source.transport().length()).isLessThan(2048);assertThat(source.body().length).isGreaterThan(8192);
        assertThat(post(source,"application/json").statusCode()).isEqualTo(403);
    }
    @Test void jointHarnessIdentityUsesCurrentAuthWithoutInventingScopedPermissions() {
        var subject=auth.subject();var before=auth.identity(subject);
        assertThat(before.tenantId()).isEqualTo(auth.tenantId());assertThat(before.personPublicId()).isEqualTo(subject.personPublicId());
        assertThat(before.status()).isEqualTo("ACTIVE");assertThat(before.identityPlane()).isEqualTo("TENANT");
        assertThat(before.permissions()).contains("ADMIN.APPROVAL_WORKFLOW:UPDATE","ACTION.APPROVAL_FORM:VIEW").doesNotContain("APP.APPROVALS:VIEW");
        assertThat(auth.jdbc().queryForObject("SELECT count(*) FROM com_role_members WHERE tenant_id=? AND user_id=?",Long.class,auth.tenantId(),subject.userId())).isZero();
        assertThat(auth.jdbc().queryForObject("SELECT count(*) FROM com_principal_resource_grants WHERE tenant_id=? AND principal_type='USER' AND principal_ref=?",
                Long.class,auth.tenantId(),Long.toString(subject.userId()))).isZero();
        auth.grantAppView(subject);var after=auth.identity(subject);
        assertThat(after.permissions()).contains("APP.APPROVALS:VIEW","ADMIN.APPROVAL_WORKFLOW:UPDATE","ACTION.APPROVAL_FORM:VIEW");
        assertThat(auth.jdbc().queryForObject("""
                SELECT count(*) FROM com_principal_resource_grants grant_record JOIN com_resources resource USING(resource_id)
                WHERE grant_record.tenant_id=? AND grant_record.principal_type='USER' AND grant_record.principal_ref=?
                  AND resource.key<>'APP.APPROVALS'
                """,Long.class,auth.tenantId(),Long.toString(subject.userId()))).isZero();
        assertThat(after.revision()).isNotEqualTo(before.revision());
        assertThat(auth.current(subject).decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        auth.revoke(subject,"APPROVAL_FORM_REFERENCE_READ");
        assertThat(auth.current(subject).decision()).isNotEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(auth.identity(subject).permissions()).contains("APP.APPROVALS:VIEW","ADMIN.APPROVAL_WORKFLOW:UPDATE").doesNotContain("ACTION.APPROVAL_FORM:VIEW");
    }
    @Test void eachIndependentCurrentSourceRevocationDeniesFreshRead() throws Exception {
        for(String code:new String[]{"APPROVAL_WORKFLOW_PLANNING","APPROVAL_FORM_REFERENCE_READ"}) {
            var subject=auth.subject();var source=exchange(subject,1);auth.revoke(subject,code);assertThat(post(source,"application/json").statusCode()).isEqualTo(403);
        }
    }
    @Test void actualRoleRevocationDuringPrePostReadsDeniesInsteadOfSigning() {
        var source=exchange(auth.subject(),1);var bridge=auth.bridge();var reads=new AtomicInteger();
        var service=auth.observing(new PlanningAuthorityPort() {
            @Override public void requireRegistered() {bridge.requireRegistered();}
            @Override public Owner requireCurrent(PlanningProofVerifier.Verified proof) {
                var owner=bridge.requireCurrent(proof);if(reads.incrementAndGet()==2) auth.jdbc().update("UPDATE com_roles SET status='INACTIVE',version=version+1 WHERE tenant_id=? AND role_id=?",auth.tenantId(),role);
                return owner;
            }
        });
        try {assertThatThrownBy(()->service.evaluate(service.preverify(source.body(),source.transport()))).isInstanceOf(BaseException.class);}
        finally {auth.jdbc().update("UPDATE com_roles SET status='ACTIVE',version=version+1 WHERE tenant_id=? AND role_id=?",auth.tenantId(),role);}
    }
    @Test void invalidPurposeBodyAndMediaTypesNeverReachCurrentAuth() throws Exception {
        var source=exchange(auth.subject(),1);var reads=new AtomicInteger();
        var service=auth.observing(new PlanningAuthorityPort() {
            @Override public void requireRegistered() {reads.incrementAndGet();throw new AssertionError("Auth must not be reached.");}
            @Override public Owner requireCurrent(PlanningProofVerifier.Verified proof) {throw new AssertionError("Auth must not be reached.");}
        });
        assertThatThrownBy(()->service.preverify("{}".getBytes(),source.transport())).isInstanceOf(BaseException.class);assertThat(reads).hasValue(0);
        for(String type:new String[]{"application/*","application/json;charset=ISO-8859-1","text/json"}) assertThat(post(source,type).statusCode()).isEqualTo(403);
        try(var disabled=new PlanningEmbeddedServer(auth.service(),false)) {
            var request=HttpRequest.newBuilder(disabled.endpoint()).header("Content-Type","application/json").header("X-DWP-Service-Identity","dwp-approval-server")
                    .header(PlanningProtocol.HEADER,source.transport()).POST(HttpRequest.BodyPublishers.ofByteArray(source.body())).build();
            assertThat(HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(503);
        }
    }
    @Test void complete1001MembersFailClosedWithoutShrinkingCount() throws Exception {
        var subject=auth.subject();
        auth.jdbc().update("INSERT INTO com_role_members(tenant_id,role_id,user_id) VALUES(?,?,?)",auth.tenantId(),role,subject.userId());
        try {var source=exchange(subject,1);assertThat(post(source,"application/json").statusCode()).isEqualTo(503);}
        finally {auth.jdbc().update("DELETE FROM com_role_members WHERE tenant_id=? AND role_id=? AND user_id=?",auth.tenantId(),role,subject.userId());}
    }
    @Test void sameCountGroupSourceIsInVectorAndBoundsNextExpiry() {
        var codes=fixture.json.tree(java.util.List.of("PLANNING_APPROVER"));var before=auth.roles().current(auth.tenantId(),codes);
        long user=auth.jdbc().queryForObject("SELECT min(user_id) FROM com_role_members WHERE tenant_id=? AND role_id=?",Long.class,auth.tenantId(),role);
        long group=auth.jdbc().queryForObject("INSERT INTO com_groups(tenant_id,group_key,display_name,status) VALUES(?,?,?,'ACTIVE') RETURNING group_id",Long.class,
                auth.tenantId(),"planning-source-"+UUID.randomUUID(),"Private fixture group");
        auth.jdbc().update("INSERT INTO com_group_members(tenant_id,group_id,user_id) VALUES(?,?,?)",auth.tenantId(),group,user);
        var until=OffsetDateTime.now().plusSeconds(15);
        auth.jdbc().update("""
                INSERT INTO com_group_role_assignments(tenant_id,group_id,role_id,assignment_type,scope_type,lifecycle_state,valid_from,valid_to)
                VALUES(?,?,?,'ACTIVE','TENANT','ACTIVE',?,?)
                """,auth.tenantId(),group,role,OffsetDateTime.now().minusSeconds(1),until);
        try {
            var grouped=auth.roles().current(auth.tenantId(),codes);assertThat(grouped.roles()).isEqualTo(before.roles());assertThat(grouped.vector()).isNotEqualTo(before.vector());
            assertThat(grouped.deadline()).isNotNull().isBeforeOrEqualTo(until.toInstant());
            auth.jdbc().update("UPDATE com_groups SET status='INACTIVE',version=version+1 WHERE tenant_id=? AND group_id=?",auth.tenantId(),group);
            assertThat(auth.roles().current(auth.tenantId(),codes)).isEqualTo(before);
        } finally {
            auth.jdbc().update("DELETE FROM com_group_role_assignments WHERE tenant_id=? AND group_id=?",auth.tenantId(),group);
            auth.jdbc().update("DELETE FROM com_group_members WHERE tenant_id=? AND group_id=?",auth.tenantId(),group);
            auth.jdbc().update("DELETE FROM com_groups WHERE tenant_id=? AND group_id=?",auth.tenantId(),group);
        }
    }
    @Test void genuineGroupDeadlineCannotExpireConsumedProofWhileOriginalJwtIsValid() throws Exception {
        var subject=auth.subject();
        var prepared=exchange(subject,1);
        auth.service().evaluate(auth.service().preverify(prepared.body(),prepared.transport()));
        long user=auth.jdbc().queryForObject("SELECT min(user_id) FROM com_role_members WHERE tenant_id=? AND role_id=?",Long.class,auth.tenantId(),role);
        long group=auth.jdbc().queryForObject("INSERT INTO com_groups(tenant_id,group_key,display_name,status) VALUES(?,?,?,'ACTIVE') RETURNING group_id",Long.class,
                auth.tenantId(),"planning-replay-"+UUID.randomUUID(),"Private replay fixture group");
        auth.jdbc().update("INSERT INTO com_group_members(tenant_id,group_id,user_id) VALUES(?,?,?)",auth.tenantId(),group,user);
        // The disposable bulk membership fixture has not reached autovacuum's analyze threshold.
        auth.jdbc().execute("ANALYZE com_users,com_role_members,com_groups,com_group_members,com_group_role_assignments,com_active_privileged_grants");
        var until=OffsetDateTime.now().plusSeconds(7);
        auth.jdbc().update("""
                INSERT INTO com_group_role_assignments(tenant_id,group_id,role_id,assignment_type,scope_type,lifecycle_state,valid_from,valid_to)
                VALUES(?,?,?,'ACTIVE','TENANT','ACTIVE',?,?)
                """,auth.tenantId(),group,role,OffsetDateTime.now().minusSeconds(1),until);
        try {
            var source=exchange(subject,1);var proof=fixture.verifier().verify(source.body(),source.transport());
            var response=post(source,"application/json");assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            var key="dwp:auth:workflow-planning:{"+auth.tenantId()+':'+PlanningProtocol.OWNER_PURPOSE+"}:owner:"+proof.sourceJti();
            Long ttl=auth.redis().getExpire(key,java.util.concurrent.TimeUnit.MILLISECONDS);assertThat(ttl).isPositive();
            System.out.println("Planning consumed proof retention observed millis="+ttl);
            long wait=Duration.between(Instant.now(),until.toInstant().plusMillis(200)).toMillis();if(wait>0) Thread.sleep(wait);
            assertThat(proof.expiresAt()).isAfter(Instant.now());
            assertThat(auth.roles().current(auth.tenantId(),fixture.json.tree(java.util.List.of("PLANNING_APPROVER"))).roles().getFirst().activeMemberCount()).isEqualTo(1000);
            assertThat(post(source,"application/json").statusCode()).isEqualTo(403);
        } finally {
            auth.jdbc().update("DELETE FROM com_group_role_assignments WHERE tenant_id=? AND group_id=?",auth.tenantId(),group);
            auth.jdbc().update("DELETE FROM com_group_members WHERE tenant_id=? AND group_id=?",auth.tenantId(),group);
            auth.jdbc().update("DELETE FROM com_groups WHERE tenant_id=? AND group_id=?",auth.tenantId(),group);
        }
    }
    @Test void twoIndependentRedisStoresAllowExactlyOneWinnerWithFullVerifiedRetention() throws Exception {
        var source=exchange(auth.subject(),1);var proof=fixture.verifier().verify(source.body(),source.transport());
        var first=new PlanningReplayStore(auth.redis(),Clock.systemUTC());var second=new PlanningReplayStore(auth.redis(),Clock.systemUTC());
        var start=new java.util.concurrent.CountDownLatch(1);var workers=java.util.concurrent.Executors.newFixedThreadPool(2);
        var deadline=Instant.now().plusSeconds(2);
        try {
            var futures=java.util.List.of(first,second).stream().map(store->workers.submit(()->{start.await();try {store.consume(proof,deadline);return 1;}
                catch(BaseException denied) {assertThat(denied.getErrorCode()).isEqualTo(com.dwp.core.common.ErrorCode.FORBIDDEN);return 0;}})).toList();
            start.countDown();int winners=0;for(var future:futures) winners+=future.get(5,java.util.concurrent.TimeUnit.SECONDS);assertThat(winners).isEqualTo(1);
            String key="dwp:auth:workflow-planning:{"+auth.tenantId()+':'+PlanningProtocol.OWNER_PURPOSE+"}:owner:"+proof.sourceJti();
            assertThat(auth.redis().getExpire(key,java.util.concurrent.TimeUnit.MILLISECONDS)).isGreaterThan(2000).isLessThanOrEqualTo(30000);
        } finally {workers.shutdownNow();}
        assertThatThrownBy(()->new PlanningReplayStore(null,Clock.systemUTC()).requireReady()).isInstanceOfSatisfying(BaseException.class,
                error->assertThat(error.getErrorCode()).isEqualTo(com.dwp.core.common.ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }
    @Test void signedWrongPersonAndScopeNeverBecomeCurrentAuthority() throws Exception {
        var subject=auth.subject();var current=auth.current(subject);
        for(String target:new String[]{"person","scope"}) {
            var binding=fixture.bindings(auth.tenantId(),subject.userId(),subject.personPublicId(),current.contextKey(),current.scopes().getFirst().key(),
                    Instant.now().plusSeconds(60).getEpochSecond(),1);
            if(target.equals("person")) binding.withObject("/owner").put("personPublicId",UUID.randomUUID().toString());
            else binding.withObject("/owner").put("contextScopeKey","borrowed-cross-resource-set-scope");
            assertThat(post(fixture.issue(binding),"application/json").statusCode()).isEqualTo(403);
        }
    }
    private HttpResponse<String> post(PlanningProofTestFixture.Exchange exchange,String type) throws Exception {
        var request=HttpRequest.newBuilder(auth.endpoint()).timeout(Duration.ofSeconds(5)).header("Content-Type",type)
                .header("X-DWP-Service-Identity","dwp-approval-server").header(PlanningProtocol.HEADER,exchange.transport())
                .POST(HttpRequest.BodyPublishers.ofByteArray(exchange.body())).build();
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build().send(request,HttpResponse.BodyHandlers.ofString());
    }
}
