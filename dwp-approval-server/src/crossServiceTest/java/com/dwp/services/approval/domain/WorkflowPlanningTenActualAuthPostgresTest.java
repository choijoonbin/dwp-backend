package com.dwp.services.approval.domain;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.*;
import com.dwp.services.approval.workflowplanning.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Genuine private Approval source/PEP10 and installed Auth HTTP/DB. Public Gateway/browser activation is not asserted. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowPlanningTenActualAuthPostgresTest {
    private static final String HARNESS="com.dwp.services.auth.service.PlanningActualAuthHarness";
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private Object auth,subject;
    private long tenant,role;
    private WorkflowPlanningRuntime exchange;
    private WorkflowPlanningAuthorityClient client;
    private final AtomicInteger reads=new AtomicInteger();
    private Runnable afterFirst=()->{};
    private ApprovalWorkflowQuorumPostgresFixture fixture;
    private UUID workflow,version,requestId;
    private WorkflowPlanningBody body;
    private WorkflowPlanningFacade facade;
    private MockHttpServletRequest request;

    @BeforeAll void start() throws Exception {
        if(!Boolean.getBoolean("dwp.workflow.cross-service-gate"))
            throw new IllegalStateException("Actual coherent Auth Planning harness is mandatory; absent is neither skip nor activation.");
        var owner=key("actual-planning-ten-owner");var transport=key("actual-planning-ten-transport");
        var attestation=key("actual-planning-ten-attestation");var forbidden=key("unrelated-runtime-purpose");
        auth=Class.forName(HARNESS).getConstructor(RSAKey.class,RSAKey.class,RSAKey.class).newInstance(owner,transport,attestation);
        tenant=(Long)call(auth,"tenantId");role=(Long)call(auth,"role",new Class<?>[]{String.class,int.class},"PLANNING_APPROVER",3);
        var keys=new WorkflowPlanningKeys(owner,transport,new JWKSet(attestation.toPublicJWK()).toString(),new JWKSet(forbidden.toPublicJWK()).toString());
        client=spy(new WorkflowPlanningAuthorityClient((URI)call(auth,"endpoint"),new WorkflowPlanningAttestationVerifier(keys,Clock.systemUTC())));
        doAnswer(invocation->{
            final Object verified;
            try {verified=invocation.callRealMethod();}
            catch(BaseException failure) {
                var original=(WorkflowPlanningProofIssuer.Exchange)invocation.getArgument(0);
                try {call(call(auth,"service"),"preverify",new Class<?>[]{byte[].class,String.class},original.body(),original.token());}
                catch(Exception exactParserCause) {failure.addSuppressed(exactParserCause);}
                throw failure;
            }
            if(reads.incrementAndGet()==1) afterFirst.run();return verified;
        }).when(client).evaluate(any());
        exchange=new WorkflowPlanningRuntime(new WorkflowPlanningProofIssuer(keys,Clock.systemUTC()),client);
    }
    @AfterAll void stop() throws Exception {if(auth!=null) ((AutoCloseable)auth).close();}
    @BeforeEach void initialize() throws Exception {
        WorkflowPlanningTenContextProbe.clear();reads.set(0);afterFirst=()->{};
        subject=call(auth,"subject");callSubject("grantAppView");
        fixture=new ApprovalWorkflowQuorumPostgresFixture();fixture.initialize(PG);
        if(tenant!=ApprovalWorkflowQuorumPostgresFixture.TENANT)
            fixture.jdbc.queryForObject("SELECT seed_approval_tenant(?)",Object.class,tenant);
        var definition=definition();
        workflow=fixture.jdbc.queryForObject("SELECT workflow_id FROM apr_workflow_definitions WHERE tenant_id=? AND workflow_key='ACCESS_EXCEPTION'",UUID.class,tenant);
        version=UUID.randomUUID();UUID formVersion=UUID.randomUUID();requestId=UUID.randomUUID();
        UUID form=fixture.jdbc.queryForObject("SELECT form_id FROM apr_form_workflow_bindings WHERE tenant_id=? AND workflow_id=? AND binding_type='DEFAULT'",UUID.class,tenant,workflow);
        var schema=new ApprovalFormSchemaV2Compiler().compile(ApprovalFormSchemaV2CompilerTest.schema(
                ApprovalFormSchemaV2CompilerTest.field("summary","TEXT"),ApprovalFormSchemaV2CompilerTest.field("amount","NUMBER")));
        fixture.jdbc.update("INSERT INTO apr_workflow_versions(workflow_version_id,tenant_id,workflow_id,version_number,definition,definition_sha256,lifecycle_state) VALUES(?,?,?,90,?::jsonb,?,'PUBLISHED')",
                version,tenant,workflow,definition.canonicalJson(),definition.sha256());
        fixture.jdbc.update("UPDATE apr_workflow_definitions SET current_version=90,sla_minutes=60 WHERE tenant_id=? AND workflow_id=?",tenant,workflow);
        fixture.jdbc.update("INSERT INTO apr_form_versions(form_version_id,tenant_id,form_id,version_number,schema_payload,schema_sha256,lifecycle_state) VALUES(?,?,?,99,?::jsonb,?,'PUBLISHED')",
                formVersion,tenant,form,schema.canonicalJson(),schema.sha256());
        fixture.jdbc.update("UPDATE apr_forms SET current_version=99 WHERE tenant_id=? AND form_id=?",tenant,form);
        var identity=identity();
        fixture.jdbc.update("INSERT INTO apr_requests(request_id,tenant_id,request_number,workflow_version_id,form_version_id,title,requester_user_id,requester_person_public_id,status) VALUES(?,?,?,?,?,'Actual planning source',?,?,'DRAFT')",
                requestId,tenant,"PLANNING-"+requestId,version,formVersion,identity.path("userId").longValue(),UUID.fromString(identity.path("personPublicId").textValue()));
        String payload=ApprovalFormSchemaV2Canonical.json(ApprovalFormSchemaV2Canonical.freeze(Map.of("summary","Actual sample","amount","20")));
        fixture.jdbc.update("INSERT INTO apr_request_payloads(tenant_id,request_id,payload,payload_sha256,schema_version) VALUES(?,?,?::jsonb,?,1)",
                tenant,requestId,payload,ApprovalFormSchemaV2Canonical.sha256(payload));
        var named=new NamedParameterJdbcTemplate(fixture.jdbc);var store=new ApprovalWorkflowQuorumRuntimeStore(named,mapper);
        var policy=store.policy(tenant,requestId,false);
        long revision=fixture.jdbc.queryForObject("SELECT version FROM apr_workflow_definitions WHERE tenant_id=? AND workflow_id=?",Long.class,tenant,workflow);
        String scope=fixture.jdbc.queryForObject("SELECT management_resource_set_key FROM apr_workflow_definitions WHERE tenant_id=? AND workflow_id=?",String.class,tenant,workflow);
        body=new WorkflowPlanningBody(revision,definition.sha256(),formVersion,schema.sha256(),policy.version(),policy.sha256(),scope,
                Map.of("summary","Actual sample","amount","20.00"));
        request=WorkflowPlanningTenContextProbe.install(actor(identity),workflow,version,scope,mapper.valueToTree(callSubject("current")));
        facade=new WorkflowPlanningFacade(true,()->exchange,new WorkflowPlanningInstalledContext(),named,mapper,fixture.tx.getTransactionManager(),
                new ApprovalWorkAuthority(new NativeIdentityDirectory()));
    }
    @AfterEach void clear() {WorkflowPlanningTenContextProbe.clear();}
    @Test void actualNativePlanningAll2SignedHttpHasDecimalBranchParityAndDeterministicAllFourThresholdsWithoutWrites() throws Exception {
        String before=database();var result=simulate();assertEquals(before,database());assertEquals(2,reads.get());
        assertEquals("ROLE_POOL_PREVIEW",result.mode());assertEquals("NOT_EVALUATED",result.runtimeEligibility());assertEquals("NOT_EVALUATED",result.requesterExclusion());
        assertTrue(result.authorityRevision().matches("awp-[a-f0-9]{64}"));assertEquals(4,result.stages().size());
        var stages=new HashMap<String,WorkflowPlanningResult.Stage>();result.stages().forEach(stage->stages.put(stage.stepKey(),stage));
        assertEquals(1,stages.get("ANY_STAGE").indicativeThreshold());assertEquals(3,stages.get("ALL_STAGE").indicativeThreshold());
        assertEquals(2,stages.get("COUNT_STAGE").indicativeThreshold());assertEquals(3,stages.get("PERCENT_STAGE").indicativeThreshold());
        assertTrue(stages.get("ANY_STAGE").selected());assertFalse(stages.get("ALL_STAGE").selected());
        assertTrue(stages.get("COUNT_STAGE").selected());assertTrue(stages.get("PERCENT_STAGE").selected());
        assertEquals(List.of("ALL_STAGE","ANY_STAGE"),stages.get("COUNT_STAGE").predecessors().stream().sorted().toList());
        String raw=mapper.writeValueAsString(result);for(String forbidden:List.of("userId","personPublicId","roleId","READY")) assertFalse(raw.contains(forbidden));
    }
    @Test void eachIndependentCurrentAuthDutyRevocationDeniesNativeProjectionWithoutWrites() throws Exception {
        for(String code:List.of("APPROVAL_WORKFLOW_PLANNING","APPROVAL_FORM_REFERENCE_READ")) {
            subject=call(auth,"subject");callSubject("grantAppView");
            request=WorkflowPlanningTenContextProbe.install(actor(identity()),workflow,version,body.managementResourceSetKey(),mapper.valueToTree(callSubject("current")));
            String before=database();call(auth,"revoke",new Class<?>[]{subject.getClass(),String.class},subject,code);
            assertEquals(ErrorCode.FORBIDDEN,assertThrows(BaseException.class,this::simulate).getErrorCode());assertEquals(before,database());
        }
    }
    @Test void native64StageBodyAndComplete1000ActualAuthMembersKeepTheUnchangedFiveSecondHttpDeadline() throws Exception {
        call(auth,"role",new Class<?>[]{String.class,int.class},"PLANNING_LARGE",1000);
        var stages=new ArrayList<ApprovalWorkflowQuorumDefinition.Stage>();
        for(int index=0;index<64;index++) stages.add(new ApprovalWorkflowQuorumDefinition.Stage(String.format(Locale.ROOT,"STAGE_%02d",index),
                "Stage "+index,"PLANNING_LARGE",new ApprovalWorkflowQuorum.Rule(ApprovalWorkflowQuorum.Mode.PERCENT,67),15,List.of()));
        var definition=ApprovalWorkflowQuorumDefinition.fromStages(60,stages);version=UUID.randomUUID();
        fixture.jdbc.update("INSERT INTO apr_workflow_versions(workflow_version_id,tenant_id,workflow_id,version_number,definition,definition_sha256,lifecycle_state) VALUES(?,?,?,91,?::jsonb,?,'PUBLISHED')",
                version,tenant,workflow,definition.canonicalJson(),definition.sha256());
        fixture.jdbc.update("UPDATE apr_workflow_definitions SET current_version=91 WHERE tenant_id=? AND workflow_id=?",tenant,workflow);
        body=new WorkflowPlanningBody(body.workflowRevision(),definition.sha256(),body.formVersionId(),body.formSchemaSha256(),body.policyVersion(),
                body.policySha256(),body.managementResourceSetKey(),body.samplePayload());
        request=WorkflowPlanningTenContextProbe.install(actor(identity()),workflow,version,body.managementResourceSetKey(),mapper.valueToTree(callSubject("current")));
        String before=database();var result=simulate();assertEquals(before,database());assertEquals(2,reads.get());assertEquals(64,result.stages().size());
        for(var stage:result.stages()) {assertEquals(1000,stage.activeMemberCount());assertEquals(670,stage.indicativeThreshold());assertTrue(stage.selected());}
        assertEquals("NOT_EVALUATED",result.runtimeEligibility());assertEquals("NOT_EVALUATED",result.requesterExclusion());
    }
    @Test void authDutyRevocationAfterFirstVerifiedHttpCannotUseCachedOwnerOrReturnAProjection() {
        String before=database();afterFirst=()->unchecked(()->call(auth,"revoke",new Class<?>[]{subject.getClass(),String.class},subject,"APPROVAL_FORM_REFERENCE_READ"));
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(BaseException.class,this::simulate).getErrorCode());assertEquals(1,reads.get());assertEquals(before,database());
    }
    @Test void nativeWorkflowHeadMutationAfterRealAuthHttpIsSeenOutsideRepeatableReadAndNeverWritesRuntime() {
        var concurrent=new org.springframework.transaction.support.TransactionTemplate(fixture.tx.getTransactionManager());
        concurrent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        afterFirst=()->concurrent.execute(status->{
            fixture.jdbc.update("UPDATE apr_workflow_definitions SET version=version+1 WHERE tenant_id=? AND workflow_id=?",tenant,workflow);return null;
        });
        assertEquals(ErrorCode.RESOURCE_CONFLICT,assertThrows(BaseException.class,this::simulate).getErrorCode());assertEquals(2,reads.get());assertRuntimeEmpty();
    }
    @Test void actualRoleStatusRevokedBetweenTwoSignedReadsDeniesInsteadOfShrinkingOrHealing() throws Exception {
        var authJdbc=(org.springframework.jdbc.core.JdbcTemplate)call(auth,"jdbc");String before=database();
        afterFirst=()->authJdbc.update("UPDATE com_roles SET status='INACTIVE',version=version+1 WHERE tenant_id=? AND role_id=?",tenant,role);
        try {assertThrows(BaseException.class,this::simulate);assertEquals(before,database());assertEquals(1,reads.get());}
        finally {authJdbc.update("UPDATE com_roles SET status='ACTIVE',version=version+1 WHERE tenant_id=? AND role_id=?",tenant,role);}
    }
    @Test void staleNativePolicyAndCrossResourceSetDenyBeforeHttpAndMakeZeroWrites() {
        body=new WorkflowPlanningBody(body.workflowRevision(),body.workflowSha256(),body.formVersionId(),body.formSchemaSha256(),body.policyVersion()+1,
                body.policySha256(),body.managementResourceSetKey(),body.samplePayload());String before=database();
        assertEquals(ErrorCode.RESOURCE_CONFLICT,assertThrows(BaseException.class,this::simulate).getErrorCode());assertEquals(0,reads.get());assertEquals(before,database());
        body=new WorkflowPlanningBody(body.workflowRevision(),body.workflowSha256(),body.formVersionId(),body.formSchemaSha256(),body.policyVersion(),
                body.policySha256(),"RS_OTHER",body.samplePayload());
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(BaseException.class,this::simulate).getErrorCode());assertEquals(0,reads.get());assertEquals(before,database());
    }
    private WorkflowPlanningResult simulate() throws Exception {return facade.simulate(request,workflow,version,mapper.writeValueAsBytes(body));}
    private static ApprovalWorkflowQuorumDefinition definition() {
        return ApprovalWorkflowQuorumDefinition.fromStages(60,List.of(stage("ANY_STAGE",ApprovalWorkflowQuorum.Mode.ANY,null,List.of(),10),
                stage("ALL_STAGE",ApprovalWorkflowQuorum.Mode.ALL,null,List.of(),100),
                stage("COUNT_STAGE",ApprovalWorkflowQuorum.Mode.COUNT,2,List.of("ANY_STAGE","ALL_STAGE"),null),
                stage("PERCENT_STAGE",ApprovalWorkflowQuorum.Mode.PERCENT,67,List.of("COUNT_STAGE"),null)));
    }
    private static ApprovalWorkflowQuorumDefinition.Stage stage(String key,ApprovalWorkflowQuorum.Mode mode,Integer count,List<String> predecessors,Integer threshold) {
        return new ApprovalWorkflowQuorumDefinition.Stage(key,key,"PLANNING_APPROVER",new ApprovalWorkflowQuorum.Rule(mode,count),15,predecessors,
                threshold==null?null:Map.of("all",List.of(Map.of("field","amount","operator","GTE","value",threshold))));
    }
    private JsonNode identity() throws Exception {return mapper.valueToTree(callSubject("identity"));}
    private static ApprovalRequestContext.Actor actor(JsonNode value) {
        return new ApprovalRequestContext.Actor(value.path("userId").longValue(),value.path("tenantId").longValue(),UUID.fromString(value.path("personPublicId").textValue()),
                null,strings(value.path("roles")),strings(value.path("permissions")));
    }
    private static Set<String> strings(JsonNode array) {var values=new HashSet<String>();array.forEach(value->values.add(value.textValue()));return Set.copyOf(values);}
    private final class NativeIdentityDirectory implements ApprovalIdentityDirectory {
        @Override public Subject require(long requestedTenant,long user) {
            final JsonNode value;try {value=identity();} catch(Exception error) {throw new IllegalStateException(error);}
            if(requestedTenant!=value.path("tenantId").longValue() || user!=value.path("userId").longValue() || !"TENANT".equals(value.path("identityPlane").textValue()))
                throw new BaseException(ErrorCode.FORBIDDEN);
            return new Subject(requestedTenant,user,null,UUID.fromString(value.path("personPublicId").textValue()),null,null,null,value.path("status").textValue(),
                    strings(value.path("roles")).stream().sorted().toList(),strings(value.path("permissions")).stream().sorted().toList());
        }
        @Override public List<Subject> search(long t,String q,int limit) {throw new AssertionError("Planning cannot query public population.");}
        @Override public RoleEligibility requireRole(long t,String code) {throw new AssertionError("Planning role counts must use dedicated verified HTTP.");}
    }
    private String database() {
        var result=new TreeMap<String,Object>();
        for(String table:List.of("apr_workflow_definitions","apr_workflow_versions","apr_forms","apr_form_versions","apr_policy_rules","apr_requests","apr_request_payloads",
                "apr_steps","apr_tasks","apr_quorum_stage_runtime","apr_quorum_candidates","apr_quorum_votes","apr_quorum_sla_timers","apr_request_events","apr_integration_outbox","sys_audit_outbox"))
            result.put(table,fixture.jdbc.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(item) ORDER BY to_jsonb(item)::text),'[]'::jsonb)::text FROM "+table+" item",String.class));
        return ApprovalFormSchemaV2Canonical.json(result);
    }
    private void assertRuntimeEmpty() {
        for(String table:List.of("apr_steps","apr_tasks","apr_quorum_stage_runtime","apr_quorum_candidates","apr_quorum_votes","apr_quorum_sla_timers","apr_request_events","apr_integration_outbox","sys_audit_outbox"))
            assertEquals(0,fixture.count(table),table);
    }
    private Object callSubject(String name) throws Exception {return call(auth,name,new Class<?>[]{subject.getClass()},subject);}
    private static RSAKey key(String name) throws Exception {return new RSAKeyGenerator(2048).keyID(name).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();}
    private static Object call(Object target,String name) throws Exception {return call(target,name,new Class<?>[0]);}
    private static Object call(Object target,String name,Class<?>[] types,Object...args) throws Exception {
        try {return target.getClass().getMethod(name,types).invoke(target,args);}
        catch(InvocationTargetException error) {if(error.getCause() instanceof Exception cause) throw cause;throw error;}
    }
    @FunctionalInterface private interface Checked {void run() throws Exception;}
    private static void unchecked(Checked action) {try {action.run();} catch(Exception error) {throw new IllegalStateException(error);}}
}
