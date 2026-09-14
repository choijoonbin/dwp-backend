package com.dwp.services.approval.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.core.security.ScopedAuthorityToken;
import com.dwp.services.approval.document.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static com.dwp.services.approval.document.ApprovalDocumentDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalDocumentRuntimePostgresTest extends ApprovalDocumentPostgresFixture {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private ApprovalDocumentController controller;
    private ApprovalDocumentManagementController admin;

    @BeforeEach void setUp() throws Exception {
        initializeDocuments(POSTGRES); controller=new ApprovalDocumentController(documents);
        admin=new ApprovalDocumentManagementController(management);
    }
    @AfterEach void after() { clear(); }

    @ParameterizedTest @ValueSource(strings={"000","100","110","111"})
    void allNineWorkOperationsUseActualRegistryControllerAndCurrentOwnerPostgres(String state) throws Exception {
        var policy=publishRules(exportingRules());var request=tx(()->drafts.create(body("Runtime owner"),"request",null));
        String base="/v1/requests/"+request.requestId();
        var tools=invoke(work("GET",base+"/document-tools","request-document-tools.data",state),()->controller.requestTools(request.requestId()).getData());
        assertThat(tools.status()).isEqualTo(200);assertThat(tools.value().jsonExport().allowed()).isTrue();
        var appended=invoke(work("POST",base+"/comments","request-comment.action",state),()->controller.appendRequestComment(request.requestId(),new AppendComment(request.version(),0L,"request-comment","Runtime immutable note")).getData());
        assertThat(appended.status()).isEqualTo(200);
        var comments=invoke(work("GET",base+"/comments","request-comments.data",state),()->controller.requestComments(request.requestId(),0,25).getData());
        assertThat(comments.status()).isEqualTo(200);assertThat(comments.value().totalElements()).isEqualTo(1);
        var exported=invoke(work("POST",base+"/document-exports","request-document-export.action",state),()->controller.exportRequest(request.requestId(),new Export(request.version(),1,policy.version(),Intent.DOWNLOAD,"Runtime governed export","request-export")).getData());
        assertThat(exported.status()).isEqualTo(200);assertThat(exported.value().format()).isEqualTo("JSON");
        var replay=invoke(work("POST",base+"/document-exports","request-document-export.action",state),()->controller.exportRequest(request.requestId(),new Export(request.version(),1,policy.version(),Intent.DOWNLOAD,"Runtime governed export","request-export")).getData());
        assertThat(replay.value()).isEqualTo(exported.value());
        var printed=invoke(work("POST",base+"/document-exports","request-document-export.action",state),()->controller.exportRequest(request.requestId(),new Export(request.version(),1,policy.version(),Intent.PRINT,"Runtime controlled print","request-print")).getData());
        assertThat(printed.status()).isEqualTo(200);assertThat(printed.value().format()).isEqualTo("HTML");

        docContext(100);var source=tx(()->drafts.create(body("Task source"),"task-source",null));
        tx(()->{approvals.submit(source.requestId(),source.version(),null);return null;});docContext(99);
        UUID task=jdbc.queryForObject("SELECT task_id FROM apr_tasks WHERE request_id=? ORDER BY created_at LIMIT 1",UUID.class,source.requestId());
        long version=jdbc.queryForObject("SELECT version FROM apr_tasks WHERE task_id=?",Long.class,task);
        tx(()->approvals.claim(task,version,null));String taskBase="/v1/tasks/"+task;
        var taskTools=invoke(work("GET",taskBase+"/document-tools","task-document-tools.data",state),()->controller.taskTools(task).getData());
        assertThat(taskTools.status()).isEqualTo(200);
        var taskComment=invoke(work("POST",taskBase+"/comments","task-comment.action",state),()->controller.appendTaskComment(task,new AppendComment(taskTools.value().taskVersion(),0L,"task-comment","Runtime task comment")).getData());
        assertThat(taskComment.status()).isEqualTo(200);
        var taskComments=invoke(work("GET",taskBase+"/comments","task-comments.data",state),()->controller.taskComments(task,0,25).getData());
        assertThat(taskComments.status()).isEqualTo(200);assertThat(taskComments.value().totalElements()).isEqualTo(1);
        var taskExport=invoke(work("POST",taskBase+"/document-exports","task-document-export.action",state),()->controller.exportTask(task,new Export(taskTools.value().taskVersion(),1,policy.version(),Intent.DOWNLOAD,"Task governed export","task-export")).getData());
        assertThat(taskExport.status()).isEqualTo(200);
        // Only this disposable source fixture is terminalized; archive still uses the actual all-or-nothing owner path.
        jdbc.update("UPDATE apr_requests SET status='WITHDRAWN',completed_at=CURRENT_TIMESTAMP WHERE request_id=?",request.requestId());
        var archive=new ArchiveExport(List.of(new ArchiveItem(request.requestId(),request.version(),1)),policy.version(),"Runtime own archive","archive",policy.policyId(),policy.resourceSetKey());
        var result=invoke(work("POST","/v1/requests/archive/document-exports","archive-document-export.action",state),()->controller.archive(archive).getData());
        assertThat(result.status()).isEqualTo(200);assertThat(result.value().format()).isEqualTo("JSON");
    }

    @ParameterizedTest @ValueSource(strings={"000","100","110","111"})
    void currentIdentityPermissionOwnerVersionAndAuthorityFailureStayClosed(String state) throws Exception {
        var policy=publishRules(exportingRules());var request=tx(()->drafts.create(body("Private runtime body"),"create",null));
        String base="/v1/requests/"+request.requestId();
        var input=new Export(request.version(),1,policy.version(),Intent.DOWNLOAD,"Private export","export");
        long receipts=count("apr_document_command_receipts"),audits=count("sys_audit_outbox");
        var other=work("GET",base+"/comments","request-comments.data",state);replace(other,"X-DWP-User-ID","100");
        assertThat(invoke(other,()->controller.requestComments(request.requestId(),0,25).getData()).status()).isEqualTo(404);
        when(identities.require(43,99)).thenReturn(new com.dwp.services.approval.integration.ApprovalIdentityDirectory.Subject(43L,99L,null,null,"Owner","owner@test",null,"ACTIVE",List.of("APPROVAL_OPERATOR"),List.copyOf(DOC_PERMISSIONS)));
        var tenant=work("GET",base+"/comments","request-comments.data",state);replace(tenant,"X-DWP-Tenant-ID","43");
        assertThat(invoke(tenant,()->controller.requestComments(request.requestId(),0,25).getData()).status()).isEqualTo(404);
        var mode=work("GET",base+"/document-tools","request-document-tools.data",state);replace(mode,"X-DWP-Active-Access-Mode","PROVIDER_SUPPORT");
        assertThat(invoke(mode,()->controller.requestTools(request.requestId()).getData()).status()).isEqualTo(403);
        var duplicate=work("GET",base+"/comments","request-comments.data",state);duplicate.addHeader("X-DWP-User-ID","99");
        assertThat(invoke(duplicate,()->controller.requestComments(request.requestId(),0,25).getData()).status()).isEqualTo(401);
        var permission=work("POST",base+"/document-exports","request-document-export.action",state);replace(permission,"X-DWP-Permissions","APP.APPROVALS:VIEW,ACTION.APPROVAL_REQUEST:VIEW,ACTION.APPROVAL_REQUEST:MANAGE");
        assertThat(invoke(permission,()->controller.exportRequest(request.requestId(),input).getData()).status()).isEqualTo(403);
        assertThat(invoke(work("POST",base+"/document-exports","request-document-export.action",state),()->controller.exportRequest(request.requestId(),new Export(999L,1,policy.version(),Intent.DOWNLOAD,"Wrong version","wrong-version")).getData()).status()).isEqualTo(409);
        when(identities.require(42,99)).thenThrow(new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,"Current Auth unavailable"));
        assertThat(invoke(work("GET",base+"/comments","request-comments.data",state),()->controller.requestComments(request.requestId(),0,25).getData()).status()).isEqualTo(503);
        assertThat(count("apr_document_command_receipts")).isEqualTo(receipts);assertThat(count("sys_audit_outbox")).isEqualTo(audits);assertThat(count("apr_document_comments")).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"000","100","110","111"})
    void postClaimSourceCannotBorrowSameRoleAlternateDelegatorAcrossActualRuntime(String state) throws Exception {
        var policy=publishRules(exportingRules());docContext(100);var source=tx(()->drafts.create(body("Original delegated private body"),"source",null));
        tx(()->{approvals.submit(source.requestId(),source.version(),null);return null;});docContext(99);
        UUID task=jdbc.queryForObject("SELECT task_id FROM apr_tasks WHERE request_id=? ORDER BY created_at LIMIT 1",UUID.class,source.requestId());
        delegation(100);long version=jdbc.queryForObject("SELECT version FROM apr_tasks WHERE task_id=?",Long.class,task);tx(()->approvals.claim(task,version,null));
        String base="/v1/tasks/"+task;
        var before=invoke(work("GET",base+"/document-tools","task-document-tools.data",state),()->controller.taskTools(task).getData());
        assertThat(before.status()).isEqualTo(200);assertThat(jdbc.queryForObject("SELECT delegated_from_user_id FROM apr_tasks WHERE task_id=?",Long.class,task)).isEqualTo(100);
        jdbc.update("UPDATE apr_delegations SET delegated_role_codes='[]'::jsonb WHERE delegator_user_id=100 AND tenant_id=42");
        when(identities.require(42,101)).thenReturn(documentSubject(101,List.of("APPROVAL_OPERATOR"),DOC_PERMISSIONS));delegation(101);
        long receipts=count("apr_document_command_receipts"),audits=count("sys_audit_outbox"),events=count("apr_request_events"),outbox=count("apr_integration_outbox");
        assertThat(invoke(work("GET",base+"/document-tools","task-document-tools.data",state),()->controller.taskTools(task).getData()).status()).isEqualTo(404);
        assertThat(invoke(work("GET",base+"/comments","task-comments.data",state),()->controller.taskComments(task,0,25).getData()).status()).isEqualTo(404);
        assertThat(invoke(work("POST",base+"/comments","task-comment.action",state),()->controller.appendTaskComment(task,new AppendComment(before.value().taskVersion(),0L,"denied-comment","No source borrowing")).getData()).status()).isEqualTo(404);
        assertThat(invoke(work("POST",base+"/document-exports","task-document-export.action",state),()->controller.exportTask(task,new Export(before.value().taskVersion(),1,policy.version(),Intent.DOWNLOAD,"No source borrowing","denied-export")).getData()).status()).isEqualTo(404);
        assertThat(count("apr_document_command_receipts")).isEqualTo(receipts);assertThat(count("sys_audit_outbox")).isEqualTo(audits);assertThat(count("apr_document_comments")).isZero();
        assertThat(count("apr_request_events")).isEqualTo(events);assertThat(count("apr_integration_outbox")).isEqualTo(outbox);
        assertThat(jdbc.queryForObject("SELECT status FROM apr_tasks WHERE task_id=?",String.class,task)).isEqualTo("CLAIMED");
    }

    @ParameterizedTest @ValueSource(strings={"000","100","110","111"})
    void allSixManagedOperationsUseScopedAuthorityAndPublishOnlyWithExactSignedIndependentChecker(String state) throws Exception {
        var source=tx(()->drafts.create(body("Managed hold source"),"source",null));
        var policy=invoke(managed("GET","/v1/admin/document-tools/policy","document-policy.data","VIEW",99,state),()->admin.policy().getData());
        assertThat(policy.status()).isEqualTo(200);assertThat(policy.value().published().rules().allowJsonExport()).isFalse();
        String policyPath="/v1/admin/document-tools/policies/"+policy.value().policyId();
        var pending=invoke(managed("PUT",policyPath+"/draft","document-policy-draft.action","UPDATE",99,state),()->admin.draft(policy.value().policyId(),new SavePolicy(policy.value().version(),"draft",ApprovalDocumentPolicy.defaults())).getData());
        assertThat(pending.status()).isEqualTo(200);
        var input=new PublishPolicy(pending.value().version(),"publish","Independent managed policy review");
        var publishRequest=managed("POST",policyPath+"/publish","document-policy-publish.action","PUBLISH",100,state);
        var headers=signFor(publishRequest,"DOCUMENT_POLICY",policy.value().policyId(),input.expectedVersion(),input.idempotencyKey(),input);
        var result=invoke(publishRequest,()->admin.publish(policy.value().policyId(),input,headers.challenge(),headers.idempotencyKey(),headers.decisionRevision(),headers.expectedObjectVersion()).getData());
        assertThat(result.status()).isEqualTo(state.charAt(1)=='1'?200:403);
        String path="/v1/admin/document-tools/holds/"+source.requestId();
        var hold=invoke(managed("GET",path,"document-hold.data","VIEW",99,state),()->admin.hold(source.requestId()).getData());
        assertThat(hold.status()).isEqualTo(200);
        var proposed=invoke(managed("POST",path+"/proposals","document-hold-proposal.action","UPDATE",99,state),()->admin.propose(source.requestId(),new HoldProposal(hold.value().version(),HoldOperation.PLACE,"Preserve managed source evidence","hold")).getData());
        assertThat(proposed.status()).isEqualTo(200);assertThat(proposed.value().active()).isFalse();assertThat(proposed.value().preservationPending()).isTrue();
        var holdInput=new PublishHold(proposed.value().version(),proposed.value().pending().proposalId(),"Independent managed hold review","hold-publish");
        var holdRequest=managed("POST",path+"/publish","document-hold-publish.action","PUBLISH",100,state);
        var holdHeaders=signFor(holdRequest,"DOCUMENT_HOLD",source.requestId(),holdInput.expectedVersion(),holdInput.idempotencyKey(),holdInput);
        var held=invoke(holdRequest,()->admin.publishHold(source.requestId(),holdInput,holdHeaders.challenge(),holdHeaders.idempotencyKey(),holdHeaders.decisionRevision(),holdHeaders.expectedObjectVersion()).getData());
        assertThat(held.status()).isEqualTo(state.charAt(1)=='1'?200:403);
        assertThat(count("apr_document_policy_publications")).isEqualTo(state.charAt(1)=='1'?1:0);
        assertThat(count("apr_document_hold_journal")).isEqualTo(state.charAt(1)=='1'?1:0);
    }

    @ParameterizedTest @ValueSource(strings={"110","111"})
    void exactDocumentRouteCannotBorrowGenericV7OrStaleDecisionRevision(String state) throws Exception {
        var source=tx(()->drafts.create(body("Exact binding"),"source",null));String base="/v1/requests/"+source.requestId();
        var wrong=work("GET",base+"/comments","request-comments.data",state);replace(wrong,"X-DWP-Route-Contract-Key","route.approvals.work.request-detail.data");
        assertThat(invoke(wrong,()->controller.requestComments(source.requestId(),0,25).getData()).status()).isEqualTo(403);
        var stale=work("POST",base+"/comments","request-comment.action",state);replace(stale,"X-DWP-Expected-Decision-Revision","psr-"+"c".repeat(64));
        assertThat(invoke(stale,()->controller.appendRequestComment(source.requestId(),new AppendComment(source.version(),0L,"stale","No stale authority")).getData()).status()).isEqualTo(409);
        assertThat(count("apr_document_comments")).isZero();assertThat(count("apr_document_command_receipts")).isZero();
    }

    private ApprovalStepUpHeaders signFor(MockHttpServletRequest request,String type,UUID target,long version,String key,Object payload) throws Exception {
        String route=request.getHeader("X-DWP-Route-Contract-Key");
        docContext(Long.parseLong(request.getHeader("X-DWP-User-ID")));
        ApprovalDecisionRevisionContext.set("psr-"+"b".repeat(64),OffsetDateTime.now().plusMinutes(5),"approvals.admin",request.getHeader("X-DWP-Context-Scope-Key"),route,"110");
        var headers=signed(route,type,target,version,request.getRequestURI(),key,payload);
        request.addHeader("X-DWP-Step-Up-Challenge",headers.challenge());request.addHeader("Idempotency-Key",key);request.addHeader("X-DWP-Expected-Object-Version",version);
        return headers;
    }
    private MockHttpServletRequest work(String method,String path,String leaf,String state) { return request(method,path,"route.approvals.work."+leaf,99,state); }
    private MockHttpServletRequest managed(String method,String path,String leaf,String action,long actor,String state) {
        var request=request(method,path,"route.approvals.admin."+leaf,actor,state);
        String capability="approvals.policy."+(action.equals("VIEW")?"read":action.equals("UPDATE")?"update":"publish");
        request.addHeader("X-DWP-Resource-Roles","APP_CONFIG_ADMIN@RS_APPROVALS,"+ScopedAuthorityToken.wireToken(capability,"ADMIN.APPROVAL_POLICY:"+action,"RS_APPROVALS"));
        replace(request,"X-DWP-Context-Key","approvals.admin");
        replace(request,"X-DWP-Context-Scope-Key",ProductSurfaceScopeKey.resourceSet(42,actor,"approvals","approvals.admin","RS_APPROVALS"));
        return request;
    }
    private MockHttpServletRequest request(String method,String path,String route,long actor,String state) {
        var request=new MockHttpServletRequest(method,path);
        request.addHeader("X-DWP-Service-Token","trusted");request.addHeader("X-DWP-User-ID",actor);request.addHeader("X-DWP-Tenant-ID","42");
        request.addHeader("X-DWP-Identity-Plane","TENANT");request.addHeader("X-DWP-Roles","APPROVAL_OPERATOR");request.addHeader("X-DWP-Permissions",String.join(",",DOC_PERMISSIONS));
        request.addHeader("X-DWP-Rollout-State",state);request.addHeader("X-DWP-Rollout-Cohort","full");request.addHeader("X-DWP-Rollout-Revision","rollout-"+"a".repeat(64));
        request.addHeader("X-DWP-Route-Contract-Key",route);request.addHeader("X-DWP-Context-Key","approvals.work");request.addHeader("X-DWP-Context-Scope-Key","own");
        request.addHeader("X-DWP-Active-Access-Mode","NORMAL");request.addHeader("X-DWP-Current-Decision-Revision","psr-"+"b".repeat(64));request.addHeader("X-DWP-Expected-Decision-Revision","psr-"+"b".repeat(64));
        request.addHeader("X-DWP-Current-Revalidate-At",OffsetDateTime.now().plusMinutes(5).toString());return request;
    }
    private <T> Invocation<T> invoke(MockHttpServletRequest request,Supplier<T> action) throws Exception {
        var response=new MockHttpServletResponse();var value=new AtomicReference<T>();
        clear();
        boolean exact=request.getHeader("X-DWP-Rollout-State").charAt(1)=='1';
        var ownerFilter=new ApprovalSecurityFilter("trusted","runtime",exact,new ObjectMapper().findAndRegisterModules());
        var guard=new ApprovalDocumentTrustedHeaderFilter(mapper);
        try { guard.doFilter(request,response,(req,res)->ownerFilter.doFilter(req,res,(r,s)->value.set(tx(action)))); }
        catch(BaseException e) { response.setStatus(e.getErrorCode().getHttpStatus().value()); }
        assertThat(ApprovalDecisionRevisionContext.current()).isEmpty();assertThat(ApprovalPilotAuthorizationContext.current()).isEmpty();
        return new Invocation<>(response.getStatus(),value.get());
    }
    private void delegation(long source) {
        jdbc.update("INSERT INTO apr_delegations(delegation_id,tenant_id,delegator_user_id,delegate_user_id,scope_type,delegated_role_codes,starts_at,ends_at,reason,created_by,updated_by,delegate_display_name) VALUES(?,42,?,99,'ALL','[\"APPROVAL_OPERATOR\"]'::jsonb,CURRENT_TIMESTAMP-INTERVAL '1 minute',CURRENT_TIMESTAMP+INTERVAL '1 day','Current source continuity',?,?,'Delegate')",UUID.randomUUID(),source,source,source);
    }
    private void replace(MockHttpServletRequest request,String header,Object value) { request.removeHeader(header);request.addHeader(header,value); }
    private record Invocation<T>(int status,T value) { }
}
