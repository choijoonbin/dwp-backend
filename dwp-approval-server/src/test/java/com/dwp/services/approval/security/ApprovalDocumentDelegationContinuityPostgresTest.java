package com.dwp.services.approval.security;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.attachment.*;
import com.dwp.services.approval.attachment.binding.ApprovalAttachmentLifecycleBinding;
import com.dwp.services.approval.document.*;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.domain.ApprovalAttachmentLifecycleTestWiring;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import static com.dwp.services.approval.document.ApprovalDocumentDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker=true)
class ApprovalDocumentDelegationContinuityPostgresTest extends ApprovalDocumentPostgresFixture {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    UUID request,task,attachment,attachmentPolicy;
    long taskVersion,documentPolicyVersion;
    ApprovalAttachmentViews views;
    ApprovalAttachmentDownloadCommands downloads;
    jakarta.validation.ValidatorFactory validation;
    final byte[] bytes="Current owner evidence".getBytes(StandardCharsets.UTF_8);

    @BeforeEach void before() throws Exception {
        initializeDocuments(PG);documentPolicyVersion=publishRules(exportingRules()).version();docContext(100);
        var named=new NamedParameterJdbcTemplate(jdbc);var owners=new ApprovalDocumentOwnerRepository(named);
        var authority=new ApprovalDocumentAuthority(new ApprovalWorkAuthority(identities),identities,owners,canonical);
        validation=Validation.buildDefaultValidatorFactory();
        var policies=new ApprovalAttachmentPolicyRepository(named,canonical,validation.getValidator());
        var commands=new ApprovalAttachmentCommands(named,canonical);
        var audit=new ApprovalAttachmentAudit(new AuditOutboxRecorder(named,mapper,"dwp-approval-server","test","test"));
        downloads=new ApprovalAttachmentDownloadCommands(named,authority,owners,documentsRepository,policies,canonical,commands,audit,mock(ApprovalAttachmentProviderGate.class));
        views=new ApprovalAttachmentViews(named,authority,owners,documentsRepository,policies,commands,new ApprovalAttachmentProviderGate(Optional.empty(),Optional.empty()),audit,canonical);
        request=tx(()->drafts.create(body("Immutable claim source"),"create",null)).requestId();attachmentPolicy=UUID.randomUUID();attachment=UUID.randomUUID();
        // Owner ACL tests seed previously scanned evidence; actual AV/S3 interoperability has separate suites.
        var rules=new ApprovalAttachmentDtos.Rules(true,true,1024,2,2048,1,List.of("text/plain"),300,365);
        tx(()->{
            jdbc.update("INSERT INTO apr_attachment_policy_heads(tenant_id,resource_set_key,policy_id) VALUES(42,'RS_APPROVALS',?)",attachmentPolicy);
            jdbc.update("INSERT INTO apr_attachment_policy_versions(tenant_id,policy_id,revision,rules,rules_sha256) VALUES(42,?,0,?::jsonb,?)",attachmentPolicy,canonical.json(rules),canonical.fingerprint(rules));
            jdbc.update("INSERT INTO apr_attachment_uploads(upload_id,attachment_id,tenant_id,request_id,uploader_user_id,request_version,payload_revision,policy_id,policy_version,file_name,media_type,size_bytes,content_sha256,object_key,object_version,state,av_state,passive_content_state,expires_at,retain_until) VALUES(?,?,42,?,100,0,1,?,0,'evidence.txt','text/plain',?,?,'opaque-continuity-object','exact-version','AVAILABLE','AV_CLEAR','PASSIVE_ALLOWED',clock_timestamp()+interval '1 hour',clock_timestamp()+interval '365 days')",UUID.randomUUID(),attachment,request,attachmentPolicy,bytes.length,ApprovalAttachmentIntegrity.sha(bytes));
            assertThat(jdbc.update("UPDATE apr_attachment_selections SET attachment_ids=?::jsonb WHERE tenant_id=42 AND request_id=? AND version=0",canonical.json(List.of(attachment)),request)).isEqualTo(1);
            jdbc.update("UPDATE apr_attachment_uploads SET engine_version='PG fixture',definitions_at=clock_timestamp(),scanned_at=clock_timestamp(),parser_version='PG fixture' WHERE attachment_id=?",attachment);return null;
        });
        var provider=mock(ApprovalAttachmentProviderGate.class);var storage=mock(ApprovalAttachmentStorage.class);
        when(provider.storage()).thenReturn(storage);when(storage.load(any())).thenReturn(bytes);
        ApprovalAttachmentLifecycleTestWiring.bind(this.commands,new ApprovalAttachmentLifecycleBinding(named,new ApprovalWorkAuthority(identities),owners,policies,
                new ApprovalAttachmentManifestFacade(named,owners,authority,canonical),provider,canonical));
        tx(()->drafts.update(request,update(queries.request(ApprovalRequestContext.require(),request),"Immutable claim source","Owner evidence bound"),"bind-evidence",null));
        tx(()->{approvals.submit(request,1,null);return null;});
        task=jdbc.queryForObject("SELECT task_id FROM apr_tasks WHERE request_id=? ORDER BY created_at LIMIT 1",UUID.class,request);
        delegation(100);when(identities.require(42,200)).thenReturn(documentSubject(200,List.of(),DOC_PERMISSIONS));actor("000","route.approvals.work.task-document-tools.data");
    }
    @AfterEach void after(){validation.close();clear();}

    @ParameterizedTest @ValueSource(strings={"000","100","110","111"})
    void revokedOriginalRoleCannotBorrowAlternateSourceForAnyDocumentOrAttachmentOperation(String state) {
        claim();actor(state,"route.approvals.work.task-document-tools.data");
        var tools=tx(()->documents.tools(OwnerType.TASK,task));
        tx(()->documents.append(OwnerType.TASK,task,new AppendComment(taskVersion,0L,"before","Original claim comment")));
        var grant=issue("before");var pin=tx(()->downloads.begin(grant.grantId()));
        withdrawOriginalRole();delegation(101);
        var before=counts();String taskBefore=taskSnapshot();
        reject(()->tx(()->documents.tools(OwnerType.TASK,task)));
        reject(()->tx(()->documents.comments(OwnerType.TASK,task,0,25)));
        reject(()->tx(()->documents.append(OwnerType.TASK,task,new AppendComment(taskVersion,1L,"denied-comment","Do not append"))));
        reject(()->tx(()->documents.export(OwnerType.TASK,task,new Export(taskVersion,tools.payloadRevision(),documentPolicyVersion,Intent.DOWNLOAD,"Do not export","denied-export"))));
        actor(state,"route.approvals.work.task-attachments.data");reject(()->tx(()->views.read(OwnerType.TASK,task)));
        actor(state,"route.approvals.work.task-attachment-download.action");reject(()->issue("denied-download"));reject(()->issue("before"));
        reject(()->tx(()->downloads.begin(grant.grantId())));reject(()->tx(()->{downloads.finish(pin,bytes);return null;}));
        assertThat(counts()).isEqualTo(before);assertThat(taskSnapshot()).isEqualTo(taskBefore);
        assertThat(jdbc.queryForObject("SELECT consumed_at IS NULL FROM apr_attachment_download_grants WHERE grant_id=?",Boolean.class,grant.grantId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT generation FROM apr_attachment_download_grants WHERE grant_id=?",Long.class,grant.grantId())).isEqualTo(pin.generation());
    }
    @ParameterizedTest @ValueSource(strings={"000","100","110","111"})
    void claimedOriginalWorkflowScopeCannotBorrowAlternateAllScope(String state) {
        claim();delegation(101);
        jdbc.update("UPDATE apr_delegations SET lifecycle_state='REVOKED' WHERE tenant_id=42 AND delegator_user_id=100");
        assertThat(jdbc.update("INSERT INTO apr_delegations(delegation_id,tenant_id,delegator_user_id,delegate_user_id,scope_type,workflow_id,workflow_key,delegated_role_codes,starts_at,ends_at,reason,created_by,updated_by,delegate_display_name) SELECT ?,42,100,200,'WORKFLOW',workflow_id,workflow_key,'[\"APPROVAL_OPERATOR\"]'::jsonb,clock_timestamp()-interval '1 minute',clock_timestamp()+interval '1 day','Other workflow',100,100,'Delegate' FROM apr_workflow_definitions WHERE tenant_id=42 AND workflow_id<>? LIMIT 1",UUID.randomUUID(),workflowId)).isEqualTo(1);
        actor(state,"route.approvals.work.task-document-tools.data");var before=counts();
        reject(()->tx(()->documents.tools(OwnerType.TASK,task)));reject(()->tx(()->views.read(OwnerType.TASK,task)));reject(()->issue("scope-denied"));
        assertThat(counts()).isEqualTo(before);
    }
    @Test void preclaimMaySelectAlternateEligibleSourceButClaimKeepsThatExactSource() {
        withdrawOriginalRole();delegation(101);
        assertThat(tx(()->documents.tools(OwnerType.TASK,task)).taskVersion()).isZero();claim();
        assertThat(jdbc.queryForObject("SELECT delegated_from_user_id FROM apr_tasks WHERE task_id=?",Long.class,task)).isEqualTo(101);
        assertThat(tx(()->views.read(OwnerType.TASK,task)).manifest().sealed()).isTrue();assertThat(issue("own-alternate").grantId()).isNotNull();
    }
    @Test void directAssignedNullAuthorityRoleDoesNotAcquireARoleMembershipRequirement() {
        jdbc.update("UPDATE apr_tasks SET assignee_user_id=200,delegated_from_user_id=100,delegated_authority_role_code=NULL,status='CLAIMED',version=version+1 WHERE task_id=?",task);
        taskVersion=1;withdrawOriginalRole();jdbc.update("UPDATE apr_delegations SET delegated_role_codes='[]'::jsonb WHERE delegator_user_id=100");
        assertThat(tx(()->documents.tools(OwnerType.TASK,task)).taskVersion()).isEqualTo(1);assertThat(issue("direct-source").grantId()).isNotNull();
    }
    @Test void directAssignmentWithoutDelegationRemainsReadableWithoutCandidateRole() {
        jdbc.update("UPDATE apr_tasks SET assignee_user_id=200,status='CLAIMED',version=version+1 WHERE task_id=?",task);taskVersion=1;withdrawOriginalRole();
        assertThat(tx(()->documents.tools(OwnerType.TASK,task)).taskVersion()).isEqualTo(1);assertThat(issue("direct-assigned").grantId()).isNotNull();
    }
    private void claim(){long version=jdbc.queryForObject("SELECT version FROM apr_tasks WHERE task_id=?",Long.class,task);tx(()->approvals.claim(task,version,null));taskVersion=version+1;
        assertThat(jdbc.queryForObject("SELECT delegated_from_user_id IS NOT NULL FROM apr_tasks WHERE task_id=?",Boolean.class,task)).isTrue();}
    private void withdrawOriginalRole(){when(identities.require(42,100)).thenReturn(documentSubject(100,List.of(),DOC_PERMISSIONS));}
    private void delegation(long source) {
        if(source==101) when(identities.require(42,101)).thenReturn(documentSubject(101,List.of("APPROVAL_OPERATOR"),DOC_PERMISSIONS));
        jdbc.update("INSERT INTO apr_delegations(delegation_id,tenant_id,delegator_user_id,delegate_user_id,scope_type,delegated_role_codes,starts_at,ends_at,reason,created_by,updated_by,delegate_display_name) VALUES(?,42,?,200,'ALL','[\"APPROVAL_OPERATOR\"]'::jsonb,clock_timestamp()-interval '1 minute',clock_timestamp()+interval '1 day','Current source',?,?, 'Delegate')",UUID.randomUUID(),source,source,source);
    }
    private void actor(String state,String route) {
        clear();ApprovalRequestContext.set(200L,42L,null,"Delegate",Set.of(),DOC_PERMISSIONS);
        if(state.equals("110")||state.equals("111")) {
            ApprovalDecisionRevisionContext.set("psr-"+"b".repeat(64),OffsetDateTime.now().plusMinutes(5),"work","own",route,state);
            ApprovalPilotAuthorizationContext.set(List.of(new ApprovalPilotPepRegistry.RouteAuthority(route,"DATA","owner",false,Set.of("predicate.approval-task-readable.v1"),null,null,null,false,null,null)));
        }
    }
    private ApprovalAttachmentDtos.Grant issue(String key){return tx(()->downloads.issue(OwnerType.TASK,task,attachment,new ApprovalAttachmentDtos.Download(taskVersion,2,0,"Current source download",key)));}
    private Map<String,Long> counts(){var counts=new LinkedHashMap<String,Long>();for(String table:List.of("apr_document_comments","apr_document_command_receipts","apr_attachment_download_grants","apr_attachment_command_receipts","sys_audit_outbox","sys_domain_event_outbox")) counts.put(table,jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class));return counts;}
    private String taskSnapshot(){return jdbc.queryForObject("SELECT to_jsonb(t)::text FROM apr_tasks t WHERE task_id=?",String.class,task);}
    private void reject(org.assertj.core.api.ThrowableAssert.ThrowingCallable call){assertThatThrownBy(call).isInstanceOfSatisfying(BaseException.class,error->assertThat(error.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_AVAILABLE));}
}
