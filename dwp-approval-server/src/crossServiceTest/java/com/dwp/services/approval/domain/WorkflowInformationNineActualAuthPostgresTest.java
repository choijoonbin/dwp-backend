package com.dwp.services.approval.domain;

import static org.junit.jupiter.api.Assertions.*;
import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson.*;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.informationreplay.*;
import com.dwp.services.approval.security.*;
import com.dwp.services.approval.workflowauthority.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Genuine Auth9 both HTTP legs and private completion. Servlet context/task claim are fixtures; public PEP/browser/attachment activation is not asserted. */
@Testcontainers
class WorkflowInformationNineActualAuthPostgresTest {
    static final String HARNESS="com.dwp.services.auth.service.WorkflowInformationActualAuthNineHarness";
    static final long REQUESTER=910099,PRINCIPAL=910100,OTHER=910101;
    static final Set<String> PERMISSIONS=Set.of("APP.APPROVALS:VIEW","ACTION.APPROVAL_REQUEST:VIEW","ACTION.APPROVAL_REQUEST:CREATE",
            "ACTION.APPROVAL_REQUEST:UPDATE","ACTION.APPROVAL_TASK:VIEW","ACTION.APPROVAL_TASK:APPROVE","ACTION.APPROVAL_FORM:VIEW");
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    final WorkflowRuntimeJson json=new WorkflowRuntimeJson();
    static RSAKey key(String id) throws Exception {return new RSAKeyGenerator(2048).keyID(id).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();}
    @Test void originalAuthVerifiedCompletionAndReadonlyReceiptUseOneGenuineNineAuthorityAndCannotHealSource100With101() throws Exception {
        execute(false);
    }
    @Test void originalAuthVerifiedReplySealsGenuineAttachmentPinBeforeCompletionAndHistoricalReceipt() throws Exception {
        execute(true);
    }
    void execute(boolean withAttachment) throws Exception {
        WorkflowNineAttachmentFixture attachmentFixture=null;
        if(!Boolean.getBoolean("dwp.workflow.cross-service-gate")) throw new IllegalStateException("Actual coherent Auth cross-service harness is mandatory; missing is neither skip nor activation.");
        Class<?> type=Class.forName(HARNESS);
        var runtimeOwner=key("nine-runtime-owner");var runtimeTransport=key("nine-runtime-transport");var runtimeAuth=key("nine-runtime-auth");
        var replayOwner=key("nine-replay-owner");var replayTransport=key("nine-replay-transport");var replayAuth=key("nine-replay-auth");
        Object auth=type.getConstructor(RSAKey.class,RSAKey.class,RSAKey.class,RSAKey.class,RSAKey.class,RSAKey.class)
                .newInstance(runtimeOwner,runtimeTransport,runtimeAuth,replayOwner,replayTransport,replayAuth);
        try(var close=(AutoCloseable)auth) {
            long tenant=(Long)call(auth,"tenantId");assertEquals(9L,call(auth,"registryVersion"));
            assertTrue(((String)call(auth,"sealedRegistryChecksum")).matches("[a-f0-9]{64}"));
            var people=Map.of(REQUESTER,UUID.randomUUID(),PRINCIPAL,UUID.randomUUID(),OTHER,UUID.randomUUID());
            for(var person:people.entrySet()) {
                call(auth,"subject",new Class<?>[]{long.class,UUID.class},person.getKey(),person.getValue());
                call(auth,"workspace",new Class<?>[]{long.class},person.getKey());
            }
            for(var person:people.entrySet()) {
                for(String code:PERMISSIONS) {
                    int separator=code.lastIndexOf(':');
                    call(auth,"grant",new Class<?>[]{long.class,String.class,String.class,long.class},person.getKey(),code.substring(0,separator),code.substring(separator+1),REQUESTER);
                }
            }
            long role=(Long)call(auth,"role",new Class<?>[]{String.class,List.class},"NINE_REVIEWER",List.of(PRINCIPAL,OTHER));
            var dataSource=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
            var jdbc=new JdbcTemplate(dataSource);var named=new NamedParameterJdbcTemplate(jdbc);
            jdbc.queryForObject("SELECT seed_approval_tenant(?)",Object.class,tenant);
            var transactions=new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            var definition=ApprovalWorkflowQuorumDefinition.fromStages(60,List.of(new ApprovalWorkflowQuorumDefinition.Stage("REVIEW","Review","NINE_REVIEWER",
                    new ApprovalWorkflowQuorum.Rule(ApprovalWorkflowQuorum.Mode.ALL,null),15,List.of())));
            var schema=new ApprovalFormSchemaV2Compiler().compile(ApprovalFormSchemaV2CompilerTest.schema(
                    ApprovalFormSchemaV2CompilerTest.field("summary","TEXT"),ApprovalFormSchemaV2CompilerTest.field("amount","NUMBER")));
            UUID workflow=jdbc.queryForObject("SELECT workflow_id FROM apr_workflow_definitions WHERE tenant_id=? AND workflow_key='ACCESS_EXCEPTION'",UUID.class,tenant);
            UUID workflowVersion=UUID.randomUUID(),formVersion=UUID.randomUUID(),requestId=UUID.randomUUID();
            UUID form=jdbc.queryForObject("SELECT form_id FROM apr_form_workflow_bindings WHERE tenant_id=? AND workflow_id=? AND binding_type='DEFAULT'",UUID.class,tenant,workflow);
            jdbc.update("INSERT INTO apr_workflow_versions(workflow_version_id,tenant_id,workflow_id,version_number,definition,definition_sha256,lifecycle_state) VALUES(?,?,?,90,?::jsonb,?,'PUBLISHED')",
                    workflowVersion,tenant,workflow,definition.canonicalJson(),definition.sha256());
            jdbc.update("UPDATE apr_workflow_definitions SET current_version=90,sla_minutes=60 WHERE tenant_id=? AND workflow_id=?",tenant,workflow);
            jdbc.update("INSERT INTO apr_form_versions(form_version_id,tenant_id,form_id,version_number,schema_payload,schema_sha256,lifecycle_state) VALUES(?,?,?,99,?::jsonb,?,'PUBLISHED')",
                    formVersion,tenant,form,schema.canonicalJson(),schema.sha256());
            jdbc.update("INSERT INTO apr_requests(request_id,tenant_id,request_number,workflow_version_id,form_version_id,title,requester_user_id,requester_person_public_id,status,submitted_at,due_at) VALUES(?,?,?, ?,?,'Genuine information round',?,?,'IN_REVIEW',now(),now()+interval '60 minutes')",
                    requestId,tenant,"NINE-"+requestId,workflowVersion,formVersion,REQUESTER,people.get(REQUESTER));
            String payload=ApprovalFormSchemaV2Canonical.json(ApprovalFormSchemaV2Canonical.freeze(Map.of("summary","More detail required","amount","20")));
            String payloadHash=sha(payload);
            jdbc.update("INSERT INTO apr_request_payloads(tenant_id,request_id,payload,payload_sha256,schema_version) VALUES(?,?,?::jsonb,?,1)",tenant,requestId,payload,payloadHash);
            jdbc.update("INSERT INTO apr_request_payload_versions(payload_version_id,tenant_id,request_id,revision_number,payload,payload_sha256,change_type,changed_by,change_reason) VALUES(?,?,?,1,?::jsonb,?,'DRAFT_CREATED',?,'Explicit disposable source')",
                    UUID.randomUUID(),tenant,requestId,payload,payloadHash,REQUESTER);
            var requester=actor(tenant,REQUESTER,people.get(REQUESTER),false);var principal=actor(tenant,PRINCIPAL,people.get(PRINCIPAL),true);
            if(withAttachment) {
                jdbc.update("UPDATE apr_requests SET status='DRAFT',submitted_at=NULL,due_at=NULL WHERE tenant_id=? AND request_id=?",tenant,requestId);
                attachmentFixture=new WorkflowNineAttachmentFixture(named,mapper,transactions,requester,people);attachmentFixture.createBaseline(requestId);
                jdbc.update("UPDATE apr_requests SET status='IN_REVIEW',submitted_at=now(),due_at=now()+interval '60 minutes' WHERE tenant_id=? AND request_id=?",tenant,requestId);
            }
            var runtimeKeys=new WorkflowRuntimeKeys(runtimeOwner,runtimeTransport,new JWKSet(runtimeAuth.toPublicJWK()).toString());
            var issuer=new WorkflowRuntimeProofIssuer(runtimeKeys,json,Clock.systemUTC());
            var client=new WorkflowRuntimeAuthorityClient((URI)call(auth,"runtimeEndpoint"),new WorkflowRuntimeAttestationVerifier(runtimeKeys,json,Clock.systemUTC()));
            var beans=new DefaultListableBeanFactory();var contexts=new WorkflowRuntimeActionContext();
            var authority=org.mockito.Mockito.spy(new WorkflowRuntimeCurrentSource(named,beans.getBeanProvider(HttpServletRequest.class),contexts,issuer,client));
            org.mockito.Mockito.doAnswer(invocation->{
                var pool=(ApprovalWorkflowQuorum.CandidatePool)invocation.callRealMethod();
                Instant before=invocation.getArgument(4);
                System.out.println("Actual candidate timing: before="+before+", evaluated="+pool.evaluatedAt()+", expires="+pool.expiresAt()
                        +", response="+Instant.now()+", complete="+pool.complete()+", truncated="+pool.truncated());
                return pool;
            }).when(authority).activation(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any());
            var audit=new AuditOutboxRecorder(named,mapper,"dwp-approval-server","cross-service-test","test");
            var runtime=new ApprovalWorkflowQuorumRuntime(named,mapper,transactions,authority,audit);
            var submit=WorkflowNineNativeContextProbe.install(requester,"route.approvals.work.request-submit.action","/v1/requests/"+requestId+"/submit","nine-submit",new byte[]{'{','}'},current(auth,REQUESTER,"route.approvals.work.request-submit.action"));
            beans.registerSingleton("currentRequest",submit);
            var pins=runtime.canonicalPins(tenant,requestId,definition);runtime.start(tenant,requestId,pins,definition);
            var store=new ApprovalWorkflowQuorumRuntimeStore(named,mapper);var stage=store.stages(tenant,requestId,false).getFirst();
            UUID task=jdbc.queryForObject("SELECT task_id FROM apr_quorum_candidates WHERE tenant_id=? AND request_id=? AND principal_user_id=?",UUID.class,tenant,requestId,PRINCIPAL);
            // Claim setup is explicit test state, not evidence of the public claim command.
            jdbc.update("UPDATE apr_tasks SET status='CLAIMED',assignee_user_id=?,assignee_person_public_id=?,version=version+1 WHERE tenant_id=? AND task_id=?",PRINCIPAL,people.get(PRINCIPAL),tenant,task);
            long taskVersion=jdbc.queryForObject("SELECT version FROM apr_tasks WHERE task_id=?",Long.class,task);
            var publicPins=new ApprovalDtos.WorkflowRuntimePins(pins.workflowVersionId(),pins.workflowVersion(),pins.workflowDefinitionSha256(),pins.formSchemaSha256(),pins.policyVersion(),pins.policySha256());
            var expected=new ApprovalDtos.QuorumVotePrecondition(1,stage.version(),publicPins,1,payloadHash,0L);
            byte[] original=mapper.writeValueAsBytes(new ApprovalDtos.DecisionRequest("REQUEST_INFO","Provide supporting details",taskVersion,expected));
            var request=WorkflowNineNativeContextProbe.install(principal,"route.approvals.work.task-decision.action","/v1/tasks/"+task+"/decisions","nine-info",original,current(auth,PRINCIPAL,"route.approvals.work.task-decision.action"));
            beans.destroySingleton("currentRequest");beans.registerSingleton("currentRequest",request);
            var admission=new WorkflowRuntimeInformationAdmission(beans.getBeanProvider(HttpServletRequest.class),contexts,json,issuer,client);
            beans.registerSingleton("informationProof",admission);
            var metadata=new ApprovalWorkflowQuorumCommandMetadata(beans.getBeanProvider(HttpServletRequest.class),beans.getBeanProvider(ApprovalWorkflowQuorumCommandProof.class));
            metadata.prepare(ApprovalWorkflowQuorumCommandProof.Purpose.TASK_INFORMATION,task);
            var command=new ApprovalWorkflowQuorumInformationRuntime.RequestCommand(requestId,task,taskVersion,0,
                    new ApprovalWorkflowQuorumFacade.ExpectedVote(1,stage.version(),pins,1,payloadHash,0L),"nine-info","Provide supporting details",sha(original));
            var completions=new ApprovalWorkflowInformationCompletionAdmissions(beans.getBeanProvider(HttpServletRequest.class),metadata,named,mapper);
            var information=new ApprovalWorkflowQuorumInformationRuntime(named,mapper,transactions,authority,audit);
            var receipt=information.request(principal,command,()->metadata.current(principal,ApprovalWorkflowQuorumCommandProof.Purpose.TASK_INFORMATION,task),completions.request(principal,command));
            assertEquals("COMPLETED",receipt.status());assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM apr_quorum_information_admissions WHERE tenant_id=? AND request_id=?",Integer.class,tenant,requestId));
            Instant originalExpiry=((WorkflowRuntimeAttestationVerifier.Verified)request.getAttribute(WorkflowRuntimeInformationAdmission.ATTRIBUTE)).expiresAt();
            UUID attachmentId=withAttachment?attachmentFixture.attach(requestId):null;
            byte[] replyBytes=mapper.writeValueAsBytes(new ApprovalDtos.InformationResponseRequest("Verified original response",Map.of(),receipt.requestVersion(),receipt.generation()));
            var replyRequest=WorkflowNineNativeContextProbe.install(requester,"route.approvals.work.request-information-response.action",
                    "/v1/requests/"+requestId+"/information-response","nine-reply",replyBytes,current(auth,REQUESTER,"route.approvals.work.request-information-response.action"));
            beans.destroySingleton("currentRequest");beans.registerSingleton("currentRequest",replyRequest);
            metadata.prepare(ApprovalWorkflowQuorumCommandProof.Purpose.REQUEST_REPLY,requestId);
            var replyCommand=new ApprovalWorkflowQuorumInformationRuntime.ReplyCommand(requestId,receipt.requestVersion(),receipt.generation(),
                    "nine-reply","Verified original response",Map.of(),sha(replyBytes));
            ApprovalFormPayloadNormalization normalizer=(a,r,f,h,s,p,b,v)->new ApprovalFormSchemaV2Evaluator().evaluate(
                    new ApprovalFormSchemaV2Compiler().compile(new ApprovalCommandPayloadSupport(mapper).object(s,"Schema")),p,true).payload();
            var files=attachmentFixture;
            var replied=information.reply(requester,replyCommand,normalizer,
                    ()->metadata.current(requester,ApprovalWorkflowQuorumCommandProof.Purpose.REQUEST_REPLY,requestId),completions.reply(requester,replyCommand),
                    files==null?null:bound->files.replies.prepare(requestId,bound));
            assertEquals("COMPLETED",replied.status());assertEquals(withAttachment,replied.materialChange());assertEquals(receipt.generation()+1,replied.generation());
            assertEquals(withAttachment?2:1,replied.payloadRevision());assertEquals(payloadHash,replied.payloadSha256());
            if(withAttachment) {
                assertEquals(attachmentId.toString(),jdbc.queryForObject("SELECT items->0->>'attachmentId' FROM apr_attachment_manifests WHERE tenant_id=? AND request_id=? AND payload_revision=2",String.class,tenant,requestId));
                assertEquals(2,jdbc.queryForObject("SELECT consumed_revision FROM apr_attachment_preparations WHERE tenant_id=? AND request_id=?",Integer.class,tenant,requestId));
            }
            assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM apr_quorum_information_admissions WHERE tenant_id=? AND request_id=?",Integer.class,tenant,requestId));
            var receiptRequest=WorkflowNineNativeContextProbe.install(principal,InformationReceiptInstalledContext.ROUTE,"/v1/requests/"+requestId+"/information-commands/nine-info/receipt",null,new byte[]{'{','}'},current(auth,PRINCIPAL,InformationReceiptInstalledContext.ROUTE));
            var replayKeys=new InformationReplayKeys(replayOwner,replayTransport,new JWKSet(replayAuth.toPublicJWK()).toString(),new JWKSet(List.of(runtimeOwner.toPublicJWK(),runtimeTransport.toPublicJWK(),runtimeAuth.toPublicJWK())).toString());
            var replayIssuer=org.mockito.Mockito.spy(new InformationReplayProofIssuer(replayKeys,Clock.systemUTC()));
            var lastExchange=new java.util.concurrent.atomic.AtomicReference<InformationReplayProofIssuer.Exchange>();
            org.mockito.Mockito.doAnswer(invocation->{var exchange=(InformationReplayProofIssuer.Exchange)invocation.callRealMethod();lastExchange.set(exchange);return exchange;})
                    .when(replayIssuer).issue(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
            var replay=new InformationReplayRuntime(replayIssuer,new InformationReplayAuthorityClient((URI)call(auth,"replayEndpoint"),new InformationReplayAttestationVerifier(replayKeys,Clock.systemUTC())));
            var facade=new InformationReceiptFacade(true,()->replay,new InformationReceiptInstalledContext(),new ApprovalInformationReceiptSource(named,mapper),transactions.getTransactionManager(),Clock.systemUTC());
            byte[] lookup=mapper.writeValueAsBytes(new InformationReceiptBody("REQUEST_INFO",Base64.getEncoder().encodeToString(original)));
            String before=business(jdbc,tenant,requestId);
            InformationCommandReceipt result;
            try {result=facade.read(receiptRequest,requestId,"nine-info",lookup);}
            catch(BaseException denied) {
                var exchange=lastExchange.get();
                if(exchange!=null) try {call(call(auth,"replay"),"evaluate",new Class<?>[]{byte[].class,String.class},exchange.body(),exchange.token());}
                catch(Exception actualCause) {denied.addSuppressed(actualCause);}
                throw denied;
            }
            assertEquals(before,business(jdbc,tenant,requestId));
            assertEquals(receipt.generation(),result.generation());assertEquals(receipt.payloadSha256(),result.payloadSha256());
            var replyReceiptRequest=WorkflowNineNativeContextProbe.install(requester,InformationReceiptInstalledContext.ROUTE,
                    "/v1/requests/"+requestId+"/information-commands/nine-reply/receipt",null,new byte[]{'{','}'},current(auth,REQUESTER,InformationReceiptInstalledContext.ROUTE));
            byte[] replyLookup=mapper.writeValueAsBytes(new InformationReceiptBody("REPLY",Base64.getEncoder().encodeToString(replyBytes)));
            assertEquals(replied.generation(),facade.read(replyReceiptRequest,requestId,"nine-reply",replyLookup).generation());
            assertEquals(before,business(jdbc,tenant,requestId));
            approveGeneration(auth,beans,jdbc,store,runtime,tenant,requestId,people);
            assertEquals("APPROVED",jdbc.queryForObject("SELECT status FROM apr_requests WHERE tenant_id=? AND request_id=?",String.class,tenant,requestId));
            double wait=Math.max(0,originalExpiry.plusSeconds(1).toEpochMilli()-System.currentTimeMillis())/1000.0;
            jdbc.queryForObject("SELECT pg_sleep(?)",Object.class,wait);
            assertTrue(Instant.now().isAfter(originalExpiry));
            before=business(jdbc,tenant,requestId);
            receiptRequest=WorkflowNineNativeContextProbe.install(principal,InformationReceiptInstalledContext.ROUTE,
                    "/v1/requests/"+requestId+"/information-commands/nine-info/receipt",null,new byte[]{'{','}'},current(auth,PRINCIPAL,InformationReceiptInstalledContext.ROUTE));
            result=facade.read(receiptRequest,requestId,"nine-info",lookup);
            assertEquals(receipt.generation(),result.generation());assertEquals(before,business(jdbc,tenant,requestId));
            replyReceiptRequest=WorkflowNineNativeContextProbe.install(requester,InformationReceiptInstalledContext.ROUTE,
                    "/v1/requests/"+requestId+"/information-commands/nine-reply/receipt",null,new byte[]{'{','}'},current(auth,REQUESTER,InformationReceiptInstalledContext.ROUTE));
            assertEquals(replied.generation(),facade.read(replyReceiptRequest,requestId,"nine-reply",replyLookup).generation());
            assertEquals(before,business(jdbc,tenant,requestId));
            receiptRequest=WorkflowNineNativeContextProbe.install(principal,InformationReceiptInstalledContext.ROUTE,
                    "/v1/requests/"+requestId+"/information-commands/nine-info/receipt",null,new byte[]{'{','}'},current(auth,PRINCIPAL,InformationReceiptInstalledContext.ROUTE));
            var finalReceiptRequest=receiptRequest;
            byte[] wrongBody=mapper.writeValueAsBytes(new InformationReceiptBody("REQUEST_INFO",Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(
                    new ApprovalDtos.DecisionRequest("REQUEST_INFO","Different original bytes",taskVersion,expected)))));
            assertEquals(ErrorCode.FORBIDDEN,assertThrows(BaseException.class,()->facade.read(finalReceiptRequest,requestId,"nine-info",wrongBody)).getErrorCode());
            assertEquals(before,business(jdbc,tenant,requestId));
            var authJdbc=(JdbcTemplate)call(auth,"jdbc");authJdbc.update("DELETE FROM com_role_members WHERE tenant_id=? AND role_id=? AND user_id=?",tenant,role,PRINCIPAL);
            assertEquals(1,authJdbc.queryForObject("SELECT count(*) FROM com_role_members WHERE tenant_id=? AND role_id=? AND user_id=?",Integer.class,tenant,role,OTHER));
            assertEquals(ErrorCode.FORBIDDEN,assertThrows(BaseException.class,()->facade.read(finalReceiptRequest,requestId,"nine-info",lookup)).getErrorCode());assertEquals(before,business(jdbc,tenant,requestId));
        } finally {if(attachmentFixture!=null) attachmentFixture.close();WorkflowNineNativeContextProbe.clear();}
    }
    static ApprovalRequestContext.Actor actor(long tenant,long id,UUID person,boolean reviewer) {
        return new ApprovalRequestContext.Actor(id,tenant,person,"Disposable workflow subject",reviewer?Set.of("WORKSPACE_MEMBER","NINE_REVIEWER"):Set.of("WORKSPACE_MEMBER"),PERMISSIONS);
    }
    void approveGeneration(Object auth,DefaultListableBeanFactory beans,JdbcTemplate jdbc,ApprovalWorkflowQuorumRuntimeStore store,
            ApprovalWorkflowQuorumRuntime runtime,long tenant,UUID request,Map<Long,UUID> people) throws Exception {
        for(long user:List.of(PRINCIPAL,OTHER)) {
            var stage=store.stages(tenant,request,false).stream().filter(row->"IN_PROGRESS".equals(row.status())).findFirst().orElseThrow();
            UUID task=jdbc.queryForObject("SELECT task_id FROM apr_quorum_candidates WHERE tenant_id=? AND request_id=? AND generation=? AND principal_user_id=?",
                    UUID.class,tenant,request,stage.generation(),user);
            jdbc.update("UPDATE apr_tasks SET status='CLAIMED',assignee_user_id=?,assignee_person_public_id=?,version=version+1 WHERE tenant_id=? AND task_id=?",
                    user,people.get(user),tenant,task);
            long version=jdbc.queryForObject("SELECT version FROM apr_tasks WHERE tenant_id=? AND task_id=?",Long.class,tenant,task);
            var vote=WorkflowNineNativeContextProbe.install(actor(tenant,user,people.get(user),true),"route.approvals.work.task-decision.action",
                    "/v1/tasks/"+task+"/decisions","nine-approve-"+user,new byte[]{'{','}'},current(auth,user,"route.approvals.work.task-decision.action"));
            beans.destroySingleton("currentRequest");beans.registerSingleton("currentRequest",vote);
            runtime.vote(new ApprovalWorkflowQuorumRuntime.VoteCommand(tenant,request,stage.stepId(),stage.generation(),task,version,stage.version(),
                    stage.context().pins(),user,user,ApprovalWorkflowQuorum.Decision.APPROVE,""));
        }
    }
    JsonNode current(Object auth,long id,String route) throws Exception {return json.tree(call(auth,"current",new Class<?>[]{long.class,String.class},id,route));}
    static Object call(Object target,String method) throws Exception {return call(target,method,new Class<?>[0]);}
    static Object call(Object target,String method,Class<?>[] types,Object...args) throws Exception {
        try {return target.getClass().getMethod(method,types).invoke(target,args);} catch(InvocationTargetException error) {if(error.getCause() instanceof Exception cause) throw cause;throw error;}
    }
    static String business(JdbcTemplate jdbc,long tenant,UUID request) {
        var snapshot=new LinkedHashMap<String,Object>();
        for(String table:List.of("apr_requests","apr_request_payloads","apr_request_payload_versions","apr_steps","apr_tasks",
                "apr_quorum_stage_runtime","apr_quorum_candidates","apr_quorum_votes","apr_quorum_prerequisites","apr_quorum_sla_timers",
                "apr_quorum_information_rounds","apr_quorum_information_commands","apr_quorum_information_completion_transactions","apr_quorum_information_admissions",
                "apr_attachment_uploads","apr_attachment_selections","apr_attachment_preparations","apr_attachment_manifests")) {
            snapshot.put(table,jdbc.queryForList("SELECT to_jsonb(snapshot_row)::text FROM "+table+" snapshot_row WHERE tenant_id=? AND request_id=? ORDER BY to_jsonb(snapshot_row)::text",String.class,tenant,request));
        }
        for(String table:List.of("sys_audit_outbox","apr_integration_outbox")) {
            snapshot.put(table,jdbc.queryForList("SELECT to_jsonb(snapshot_row)::text FROM "+table+" snapshot_row ORDER BY to_jsonb(snapshot_row)::text",String.class));
        }
        return ApprovalFormSchemaV2Canonical.json(snapshot);
    }
}
