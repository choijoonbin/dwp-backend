package com.dwp.services.approval.security;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.attachment.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker=true)
class ApprovalAttachmentScanPostgresTest extends ApprovalDocumentPostgresFixture {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    ApprovalAttachmentScanJobs jobs;UUID request,policy,upload;String sha;
    @BeforeEach void before() throws Exception {
        initializeDocuments(PG);var named=new NamedParameterJdbcTemplate(jdbc);
        jobs=new ApprovalAttachmentScanJobs(named,identities,new ApprovalAttachmentAudit(new AuditOutboxRecorder(named,mapper,"dwp-approval-server","test","test")));
        request=tx(()->drafts.create(body("Durable scanner fence"),"create",null)).requestId();policy=UUID.randomUUID();upload=UUID.randomUUID();sha=ApprovalAttachmentIntegrity.sha(new byte[]{1});
        var rules=new ApprovalAttachmentDtos.Rules(true,false,26214400,10,104857600,2,List.of("text/plain"),300,365);
        tx(()->{
            jdbc.update("INSERT INTO apr_attachment_policy_heads(tenant_id,resource_set_key,policy_id) VALUES(42,'RS_APPROVALS',?)",policy);
            jdbc.update("INSERT INTO apr_attachment_policy_versions(tenant_id,policy_id,revision,rules,rules_sha256) VALUES(42,?,0,?::jsonb,?)",policy,canonical.json(rules),canonical.fingerprint(rules));
            jdbc.update("INSERT INTO apr_attachment_uploads(upload_id,attachment_id,tenant_id,request_id,uploader_user_id,request_version,payload_revision,policy_id,policy_version,file_name,media_type,size_bytes,content_sha256,object_key,object_version,state,expires_at,retain_until) VALUES(?,?,42,?,99,0,1,?,0,'owner.txt','text/plain',1,?,'opaque-scan-key','actual-version-1','QUARANTINED',clock_timestamp()+INTERVAL '1 hour',clock_timestamp()+INTERVAL '365 days')",upload,UUID.randomUUID(),request,policy,sha);
            return null;
        });
    }
    @AfterEach void after(){clear();}
    @Test void expiredLeaseReclaimFencesOldWorkerAndWritesAuditExactlyOnce() {
        var old=claim();assertThat(tx(jobs::claim)).isEmpty();expire();var current=claim();
        assertThat(current.generation()).isEqualTo(old.generation()+1);
        assertThat(tx(()->jobs.finish(old,clearScan(),passive()))).isFalse();
        assertThat(tx(()->jobs.finish(current,clearScan(),passive()))).isTrue();
        assertThat(tx(()->jobs.finish(current,clearScan(),passive()))).isFalse();
        assertThat(state()).isEqualTo("AVAILABLE");assertThat(audits()).isEqualTo(1);
    }
    @Test void cancellationPreventsLateScanFromResurrectingAvailability() {
        var job=claim();jdbc.update("UPDATE apr_attachment_uploads SET state='CANCELLED',generation=generation+1,lease_token=NULL,lease_until=NULL WHERE upload_id=?",upload);
        assertThat(tx(()->jobs.finish(job,clearScan(),passive()))).isFalse();assertThat(state()).isEqualTo("CANCELLED");assertThat(audits()).isZero();
    }
    @Test void currentPermissionAndPolicyRevocationCannotProduceAvailableEvidence() {
        var job=claim();var revoked=new HashSet<>(DOC_PERMISSIONS);revoked.remove("ACTION.APPROVAL_REQUEST:UPDATE");
        when(identities.require(42,99)).thenReturn(documentSubject(99,List.of("APPROVAL_OPERATOR"),revoked));
        assertThat(tx(()->jobs.finish(job,clearScan(),passive()))).isTrue();assertThat(state()).isEqualTo("REJECTED");
        jdbc.update("UPDATE apr_attachment_uploads SET state='QUARANTINED' WHERE upload_id=?",upload);
        when(identities.require(42,99)).thenReturn(documentSubject(99,List.of("APPROVAL_OPERATOR"),DOC_PERMISSIONS));
        jdbc.update("UPDATE apr_attachment_policy_heads SET version=version+1 WHERE policy_id=?",policy);
        var policyJob=claim();assertThat(tx(()->jobs.finish(policyJob,clearScan(),passive()))).isTrue();assertThat(state()).isEqualTo("REJECTED");
    }
    @Test void lateAuthority503RollsBackStateLeaseAndAudit() {
        var job=claim();var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(identities.require(42,99)).thenAnswer(invocation->{if(calls.incrementAndGet()==2) throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,"Late resolver failure");return documentSubject(99,List.of("APPROVAL_OPERATOR"),DOC_PERMISSIONS);});
        assertThatThrownBy(()->tx(()->jobs.finish(job,clearScan(),passive()))).isInstanceOf(BaseException.class);
        assertThat(state()).isEqualTo("SCANNING");assertThat(jdbc.queryForObject("SELECT lease_token FROM apr_attachment_uploads WHERE upload_id=?",UUID.class,upload)).isEqualTo(job.token());assertThat(audits()).isZero();
    }
    @Test void contentTamperAndMissingEngineEvidenceNeverFinalize() {
        var job=claim();var wrong=new ApprovalAttachmentScanner.Result(ApprovalAttachmentScanner.Verdict.AV_CLEAR,"AV_CLEAR_NOT_SANITIZED","ClamAV test",Instant.now(),Instant.now(),"a".repeat(64));
        assertThatThrownBy(()->tx(()->jobs.finish(job,wrong,passive()))).isInstanceOf(BaseException.class);
        var incomplete=new ApprovalAttachmentScanner.Result(ApprovalAttachmentScanner.Verdict.AV_CLEAR,"AV_CLEAR_NOT_SANITIZED",null,null,Instant.now(),sha);
        assertThatThrownBy(()->tx(()->jobs.finish(job,incomplete,passive()))).isInstanceOf(BaseException.class);assertThat(state()).isEqualTo("SCANNING");assertThat(audits()).isZero();
    }
    @Test void indecisiveScannerRetriesBoundedlyAndMalwareOrActiveContentIsRejected() {
        var job=claim();var unknown=new ApprovalAttachmentScanner.Result(ApprovalAttachmentScanner.Verdict.INDETERMINATE,"SCANNER_UNAVAILABLE",null,null,Instant.now(),sha);
        assertThat(tx(()->jobs.finish(job,unknown,passive()))).isTrue();assertThat(state()).isEqualTo("QUARANTINED");
        var retry=claim();assertThat(tx(()->jobs.finish(retry,clearScan(),new ApprovalAttachmentPassiveContent.Result(false,"ACTIVE_CONTENT_REJECTED","parser-test")))).isTrue();assertThat(state()).isEqualTo("REJECTED");
        jdbc.update("UPDATE apr_attachment_uploads SET state='QUARANTINED',attempts=5 WHERE upload_id=?",upload);assertThat(tx(jobs::claim)).isEmpty();
    }
    @Test void simultaneousWorkersCannotClaimTheSameGeneration() throws Exception {
        try (var workers=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);Callable<Optional<ApprovalAttachmentScanJobs.Job>> task=()->{start.await();return tx(jobs::claim);};
            var a=workers.submit(task);var b=workers.submit(task);start.countDown();
            assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS)).stream().filter(Optional::isPresent).count()).isEqualTo(1);
        }
    }
    private ApprovalAttachmentScanJobs.Job claim(){return tx(jobs::claim).orElseThrow();}
    private void expire(){jdbc.update("UPDATE apr_attachment_uploads SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE upload_id=?",upload);}
    private String state(){return jdbc.queryForObject("SELECT state FROM apr_attachment_uploads WHERE upload_id=?",String.class,upload);}
    private long audits(){return jdbc.queryForObject("SELECT count(*) FROM sys_audit_outbox WHERE payload->>'action'='APPROVAL_ATTACHMENT_SCAN_FINALIZED'",Long.class);}
    private ApprovalAttachmentScanner.Result clearScan(){return new ApprovalAttachmentScanner.Result(ApprovalAttachmentScanner.Verdict.AV_CLEAR,"AV_CLEAR_NOT_SANITIZED","ClamAV fixture only",Instant.now(),Instant.now(),sha);}
    private ApprovalAttachmentPassiveContent.Result passive(){return new ApprovalAttachmentPassiveContent.Result(true,"PASSIVE_CONTENT_ALLOWED_NOT_SANITIZED","parser-fixture");}
}
