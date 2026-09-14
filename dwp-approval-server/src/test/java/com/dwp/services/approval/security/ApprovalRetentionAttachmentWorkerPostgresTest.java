package com.dwp.services.approval.security;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.attachment.*;
import com.dwp.services.approval.attachment.binding.ApprovalAttachmentLifecycleBinding;
import com.dwp.services.approval.document.*;
import com.dwp.services.approval.documentretention.ApprovalRetentionProtocol;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.domain.ApprovalAttachmentLifecycleTestWiring;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class ApprovalRetentionAttachmentWorkerPostgresTest extends ApprovalDocumentPostgresFixture {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    ApprovalAttachmentScanJobs scans;
    ApprovalAttachmentIntakeCommands intake;
    ApprovalAttachmentViews views;
    ApprovalRetentionProtocol protocol;
    UUID request,upload,policy;
    String sha=ApprovalAttachmentIntegrity.sha(new byte[]{1});
    jakarta.validation.ValidatorFactory validation;

    @BeforeEach void before() throws Exception {
        initializeDocuments(PG);var named=new NamedParameterJdbcTemplate(jdbc);
        var audit=new ApprovalAttachmentAudit(new AuditOutboxRecorder(named,mapper,"dwp-approval-server","test","test"));
        scans=new ApprovalAttachmentScanJobs(named,identities,audit);
        var owners=new ApprovalDocumentOwnerRepository(named);
        var authority=new ApprovalDocumentAuthority(new ApprovalWorkAuthority(identities),identities,owners,canonical);
        validation=jakarta.validation.Validation.buildDefaultValidatorFactory();
        var policies=new ApprovalAttachmentPolicyRepository(named,canonical,validation.getValidator());
        var receipts=new ApprovalAttachmentCommands(named,canonical);var providers=new ApprovalAttachmentProviderGate(Optional.empty(),Optional.empty());
        intake=new ApprovalAttachmentIntakeCommands(named,authority,owners,policies,receipts,providers,audit,canonical);
        views=new ApprovalAttachmentViews(named,authority,owners,documentsRepository,policies,receipts,providers,audit,canonical);
        tx(management::policy);
        UUID template=tx(()->drafts.create(body("Retained worker template"),"template",null)).requestId();
        request=UUID.randomUUID();upload=UUID.randomUUID();policy=UUID.randomUUID();
        // Native retired-record fixture; scanner evidence below is protocol-only, not deployed provider readiness.
        jdbc.update("INSERT INTO apr_requests(request_id,tenant_id,request_number,workflow_version_id,form_version_id,title,summary,requester_user_id,status,management_resource_set_key,created_at,updated_at) SELECT ?,42,?,workflow_version_id,form_version_id,'Retained worker','Retained worker',99,'DRAFT','RS_APPROVALS',clock_timestamp()-interval '40 days',clock_timestamp()-interval '40 days' FROM apr_requests WHERE request_id=?",request,"WORKER-"+request,template);
        jdbc.update("INSERT INTO apr_request_payloads SELECT 42,?,payload,payload_sha256,schema_version,clock_timestamp()-interval '40 days',clock_timestamp()-interval '40 days' FROM apr_request_payloads WHERE request_id=?",request,template);
        jdbc.update("INSERT INTO apr_request_payload_versions(payload_version_id,tenant_id,request_id,revision_number,payload,payload_sha256,change_type,changed_by,created_at) SELECT ?,42,?,1,payload,payload_sha256,'DRAFT_CREATED',99,clock_timestamp()-interval '40 days' FROM apr_request_payloads WHERE request_id=?",UUID.randomUUID(),request,request);
        jdbc.update("INSERT INTO apr_document_heads(tenant_id,request_id,retain_until) VALUES(42,?,clock_timestamp()-interval '1 day')",request);
        var rules=new ApprovalAttachmentDtos.Rules(true,true,26214400,10,104857600,2,List.of("text/plain"),300,365);
        tx(()->{
            jdbc.update("INSERT INTO apr_attachment_policy_heads(tenant_id,resource_set_key,policy_id) VALUES(42,'RS_APPROVALS',?)",policy);
            jdbc.update("INSERT INTO apr_attachment_policy_versions(tenant_id,policy_id,revision,rules,rules_sha256) VALUES(42,?,0,?::jsonb,?)",policy,canonical.json(rules),canonical.fingerprint(rules));return null;
        });
        jdbc.update("INSERT INTO apr_attachment_uploads(upload_id,attachment_id,tenant_id,request_id,uploader_user_id,request_version,payload_revision,policy_id,policy_version,file_name,media_type,size_bytes,content_sha256,object_key,object_version,state,expires_at,retain_until,created_at) VALUES(?,?,42,?,99,0,1,?,0,'owner.txt','text/plain',1,?,'opaque-retained-key','exact-version-1','QUARANTINED',clock_timestamp()+interval '1 hour',clock_timestamp()+interval '365 days',clock_timestamp()-interval '40 days')",upload,UUID.randomUUID(),request,policy,sha);
        jdbc.execute("ALTER ROLE dwp_approval_retention_executor LOGIN PASSWORD 'disposable-only'");
        var executor=new PGSimpleDataSource();executor.setURL(PG.getJdbcUrl());executor.setUser("dwp_approval_retention_executor");executor.setPassword("disposable-only");protocol=new ApprovalRetentionProtocol(executor);
    }
    @AfterEach void after(){validation.close();clear();}

    @ParameterizedTest @ValueSource(strings={"PREPARED","IRREVERSIBLE","IRREVERSIBLE_BLOCKED","OBJECTS_CONFIRMED","LOCAL_DB_PURGED","COMPLETE"})
    void nonLiveRequestClosesClaimFinalizeAndOwnerAttachmentProjectionWithoutMutation(String state) {
        var job=tx(scans::claim).orElseThrow();
        jdbc.update("INSERT INTO apr_record_retention_heads(tenant_id,request_id,state) VALUES(42,?,?)",request,state);
        String before=rows();assertThat(tx(scans::claim)).isEmpty();
        denied(()->tx(()->scans.finish(job,av(),passive())));
        denied(()->tx(()->views.read(ApprovalDocumentDtos.OwnerType.REQUEST,request)));
        denied(()->tx(()->intake.reserve(request,new ApprovalAttachmentDtos.Reserve(0L,1,0,"new.txt","text/plain",1,sha,"closed-reserve"))));
        assertThat(rows()).isEqualTo(before);
    }
    @Test void lateStagedRetentionChangeRollsBackFinalizeStateLeaseAuditAndNewHead() {
        var job=tx(scans::claim).orElseThrow();String before=rows();var calls=new AtomicInteger();
        when(identities.require(42,99)).thenAnswer(call->{if(calls.incrementAndGet()==2)
            jdbc.update("INSERT INTO apr_record_retention_heads(tenant_id,request_id,state) VALUES(42,?,'IRREVERSIBLE')",request);
            return documentSubject(99,List.of("APPROVAL_OPERATOR"),DOC_PERMISSIONS);});
        denied(()->tx(()->scans.finish(job,av(),passive())));
        assertThat(rows()).isEqualTo(before);assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_record_retention_heads WHERE request_id=?",Long.class,request)).isZero();
    }
    @Test void actualPrivateClaimAfterCancelledLeaseRejectsLateWorkerWithoutPostClaimMutation() {
        var job=tx(scans::claim).orElseThrow();
        jdbc.update("UPDATE apr_attachment_uploads SET state='CANCELLED',generation=generation+1,lease_token=NULL,lease_until=NULL,expires_at=clock_timestamp()-interval '2 days',retain_until=clock_timestamp()-interval '2 days' WHERE upload_id=?",upload);
        jdbc.update("UPDATE apr_requests SET status='APPROVED',completed_at=clock_timestamp()-interval '40 days' WHERE request_id=?",request);
        UUID retentionPolicy=UUID.randomUUID();var rules=new com.dwp.services.approval.documentretention.ApprovalRetentionRules(true,List.of("INTERNAL","CONFIDENTIAL","RESTRICTED"),1,30,1,1,1,50000,1000);
        tx(()->{
            jdbc.update("INSERT INTO apr_retention_policy_heads(tenant_id,resource_set_key,policy_id,published_revision,version) VALUES(42,'RS_APPROVALS',?,1,2)",retentionPolicy);
            jdbc.update("INSERT INTO apr_retention_policy_versions SELECT 42,?,1,r,encode(sha256(convert_to(r::text,'UTF8')),'hex'),99,clock_timestamp() FROM (SELECT ?::jsonb r) q",retentionPolicy,canonical.json(rules));
            jdbc.update("INSERT INTO apr_retention_policy_publications VALUES(?,42,?,1,99,100,'Independent retention review',clock_timestamp())",UUID.randomUUID(),retentionPolicy);return null;
        });
        UUID claim=protocol.prepare(42,request,0);protocol.claim(claim,1);String before=rows();
        denied(()->tx(()->scans.finish(job,av(),passive())));assertThat(tx(scans::claim)).isEmpty();assertThat(rows()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT state FROM apr_record_retention_heads WHERE request_id=?",String.class,request)).isEqualTo("IRREVERSIBLE");
    }
    @Test void claimSkipsRequestLockBeforeTouchingUploadAndResumesOnceRequestTransactionEnds() throws Exception {
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);String before=rows();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var holder=pool.submit(()->tx(()->{jdbc.queryForObject("SELECT request_id FROM apr_requests WHERE request_id=? FOR UPDATE",UUID.class,request);locked.countDown();await(release);return true;}));
            try {assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();assertThat(pool.submit(()->tx(scans::claim)).get(10,TimeUnit.SECONDS)).isEmpty();
                tx(()->jdbc.queryForObject("SELECT upload_id FROM apr_attachment_uploads WHERE upload_id=? FOR UPDATE NOWAIT",UUID.class,upload));assertThat(rows()).isEqualTo(before);}
            finally {release.countDown();assertThat(holder.get(10,TimeUnit.SECONDS)).isTrue();}
        }
        assertThat(tx(scans::claim)).isPresent();
    }
    @Test void finalizeRequestLockSerializesLateRetentionHeadInsertionUntilAfterAuditCommit() throws Exception {
        var job=tx(scans::claim).orElseThrow();var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var finalizer=pool.submit(()->tx(()->{boolean done=scans.finish(job,av(),passive());locked.countDown();await(release);return done;}));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            var writer=pool.submit(()->tx(()->{jdbc.queryForObject("SELECT request_id FROM apr_requests WHERE request_id=? FOR UPDATE",UUID.class,request);
                jdbc.update("INSERT INTO apr_record_retention_heads(tenant_id,request_id,state) VALUES(42,?,'IRREVERSIBLE')",request);return true;}));
            try {blocked();assertThat(writer.isDone()).isFalse();release.countDown();assertThat(finalizer.get(10,TimeUnit.SECONDS)).isTrue();assertThat(writer.get(10,TimeUnit.SECONDS)).isTrue();}
            finally {release.countDown();}
        }
        assertThat(jdbc.queryForObject("SELECT state FROM apr_attachment_uploads WHERE upload_id=?",String.class,upload)).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_audit_outbox WHERE payload->>'action'='APPROVAL_ATTACHMENT_SCAN_FINALIZED'",Long.class)).isEqualTo(1);
        String before=rows();denied(()->tx(()->scans.finish(job,av(),passive())));assertThat(rows()).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"grant","replay","begin","finish"})
    void sealedDownloadAtEveryStageRejectsNonLiveOwnerWithoutGrantReceiptLeaseConsumptionOrAuditMutation(String stage) throws Exception {
        var fixture=downloads();var download=new ApprovalAttachmentDtos.Download(1L,2,0,"Controlled retained download","download");
        ApprovalAttachmentDtos.Grant grant="grant".equals(stage)?null:tx(()->fixture.commands().issue(ApprovalDocumentDtos.OwnerType.REQUEST,
                fixture.requestId(),fixture.attachmentId(),download));
        var pin="finish".equals(stage)?tx(()->fixture.commands().begin(grant.grantId())):null;
        jdbc.update("INSERT INTO apr_record_retention_heads(tenant_id,request_id,state) VALUES(42,?,'IRREVERSIBLE')",fixture.requestId());
        String before=downloadRows(fixture.requestId());clearInvocations(fixture.storage());
        denied(()->tx(()->{
            switch(stage) {
                case "grant","replay"->fixture.commands().issue(ApprovalDocumentDtos.OwnerType.REQUEST,fixture.requestId(),fixture.attachmentId(),download);
                case "begin"->fixture.commands().begin(grant.grantId());
                case "finish"->fixture.commands().finish(pin,new byte[]{1});
                default->throw new IllegalArgumentException();
            }return null;
        }));
        assertThat(downloadRows(fixture.requestId())).isEqualTo(before);verifyNoInteractions(fixture.storage());
    }
    private record DownloadFixture(UUID requestId,UUID attachmentId,ApprovalAttachmentDownloadCommands commands,ApprovalAttachmentStorage storage) { }
    private DownloadFixture downloads() throws Exception {
        publishRules(exportingRules());var named=new NamedParameterJdbcTemplate(jdbc);
        var owners=new ApprovalDocumentOwnerRepository(named);var authority=new ApprovalDocumentAuthority(new ApprovalWorkAuthority(identities),identities,owners,canonical);
        var policies=new ApprovalAttachmentPolicyRepository(named,canonical,validation.getValidator());
        var provider=mock(ApprovalAttachmentProviderGate.class);var storage=mock(ApprovalAttachmentStorage.class);
        when(provider.storage()).thenReturn(storage);when(storage.load(any())).thenReturn(new byte[]{1});
        UUID id=tx(()->drafts.create(body("Sealed retained download"),"download-create",null)).requestId(),attachment=UUID.randomUUID();
        tx(()->{
            jdbc.update("INSERT INTO apr_attachment_uploads(upload_id,attachment_id,tenant_id,request_id,uploader_user_id,request_version,payload_revision,policy_id,policy_version,file_name,media_type,size_bytes,content_sha256,object_key,object_version,state,av_state,passive_content_state,engine_version,definitions_at,scanned_at,parser_version,expires_at,retain_until) VALUES(?,?,42,?,99,0,1,?,0,'download.txt','text/plain',1,?,'opaque-download-fence-key','exact-version-download','AVAILABLE','AV_CLEAR','PASSIVE_ALLOWED','PG fixture',clock_timestamp(),clock_timestamp(),'PG fixture',clock_timestamp()+interval '1 hour',clock_timestamp()+interval '365 days')",UUID.randomUUID(),attachment,id,policy,sha);
            assertThat(jdbc.update("UPDATE apr_attachment_selections SET attachment_ids=?::jsonb WHERE request_id=? AND version=0",canonical.json(List.of(attachment)),id)).isEqualTo(1);return null;
        });
        ApprovalAttachmentLifecycleTestWiring.bind(commands,new ApprovalAttachmentLifecycleBinding(named,new ApprovalWorkAuthority(identities),owners,policies,
                new ApprovalAttachmentManifestFacade(named,owners,authority,canonical),provider,canonical));
        tx(()->drafts.update(id,update(queries.request(ApprovalRequestContext.require(),id),"Sealed retained download","Precisely pinned download"),"download-seal",null));
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(items) FROM apr_attachment_manifests WHERE request_id=? AND payload_revision=2",Integer.class,id)).isEqualTo(1);
        var downloadCommands=new ApprovalAttachmentDownloadCommands(named,authority,owners,documentsRepository,policies,canonical,new ApprovalAttachmentCommands(named,canonical),
                new ApprovalAttachmentAudit(new AuditOutboxRecorder(named,mapper,"dwp-approval-server","test","test")),provider);
        return new DownloadFixture(id,attachment,downloadCommands,storage);
    }
    private String downloadRows(UUID id){return jdbc.queryForObject("SELECT jsonb_build_array((SELECT jsonb_agg(row_to_json(g) ORDER BY grant_id) FROM apr_attachment_download_grants g WHERE request_id=?),(SELECT count(*) FROM apr_attachment_command_receipts),(SELECT count(*) FROM sys_audit_outbox))::text",String.class,id);}
    private ApprovalAttachmentScanner.Result av(){return new ApprovalAttachmentScanner.Result(ApprovalAttachmentScanner.Verdict.AV_CLEAR,"AV_CLEAR_NOT_SANITIZED","PG fixture only",Instant.now(),Instant.now(),sha);}
    private ApprovalAttachmentPassiveContent.Result passive(){return new ApprovalAttachmentPassiveContent.Result(true,"PASSIVE_CONTENT_ALLOWED_NOT_SANITIZED","PG fixture only");}
    private String rows(){return jdbc.queryForObject("SELECT jsonb_build_array((SELECT row_to_json(u) FROM apr_attachment_uploads u WHERE upload_id=?),(SELECT count(*) FROM apr_attachment_command_receipts),(SELECT count(*) FROM sys_audit_outbox))::text",String.class,upload);}
    private static void denied(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){assertThatThrownBy(action).isInstanceOfSatisfying(BaseException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));}
    private static void await(CountDownLatch latch){try{if(!latch.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Concurrent request timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
    private void blocked() throws Exception {for(int i=0;i<500;i++){if(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Long.class)>0)return;Thread.sleep(20);}throw new IllegalStateException("Expected actual PostgreSQL lock wait");}
}
