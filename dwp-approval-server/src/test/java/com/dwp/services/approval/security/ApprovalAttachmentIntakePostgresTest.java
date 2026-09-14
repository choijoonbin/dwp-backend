package com.dwp.services.approval.security;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.attachment.*;
import com.dwp.services.approval.document.*;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker=true)
class ApprovalAttachmentIntakePostgresTest extends ApprovalDocumentPostgresFixture {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    ApprovalAttachmentIntakeCommands intake;UUID request,policy;jakarta.validation.ValidatorFactory validation;
    @BeforeEach void before() throws Exception {
        initializeDocuments(PG);var named=new NamedParameterJdbcTemplate(jdbc);var owners=new ApprovalDocumentOwnerRepository(named);
        var authority=new ApprovalDocumentAuthority(new ApprovalWorkAuthority(identities),identities,owners,canonical);validation=Validation.buildDefaultValidatorFactory();
        var provider=mock(ApprovalAttachmentProviderGate.class);
        intake=new ApprovalAttachmentIntakeCommands(named,authority,owners,new ApprovalAttachmentPolicyRepository(named,canonical,validation.getValidator()),
                new ApprovalAttachmentCommands(named,canonical),provider,new ApprovalAttachmentAudit(new AuditOutboxRecorder(named,mapper,"dwp-approval-server","test","test")),canonical);
        request=tx(()->drafts.create(body("Bounded intake state"),"create",null)).requestId();policy=UUID.randomUUID();
        var rules=new ApprovalAttachmentDtos.Rules(true,false,26214400,5,104857600,2,List.of("text/plain"),300,365);
        tx(()->{jdbc.update("INSERT INTO apr_attachment_policy_heads(tenant_id,resource_set_key,policy_id) VALUES(42,'RS_APPROVALS',?)",policy);
            jdbc.update("INSERT INTO apr_attachment_policy_versions(tenant_id,policy_id,revision,rules,rules_sha256) VALUES(42,?,0,?::jsonb,?)",policy,canonical.json(rules),canonical.fingerprint(rules));return null;});
    }
    @AfterEach void after(){validation.close();clear();}
    @Test void reserveReplayIsImmutableAndForeignActorCannotReconcile() {
        var first=reserve("reserve");assertThat(reserve("reserve")).isEqualTo(first);
        assertThatThrownBy(()->tx(()->intake.reserve(request,input("reserve","different.txt")))).isInstanceOf(BaseException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_attachment_uploads",Long.class)).isEqualTo(1);docContext(100);
        assertThatThrownBy(()->tx(()->intake.status(first.uploadId()))).isInstanceOf(BaseException.class);
        assertThatThrownBy(()->tx(()->intake.begin(first.uploadId(),0L,"foreign",true))).isInstanceOf(BaseException.class);
    }
    @Test void unknownStorageResponseReclaimsWithoutSecondPutAndFencesOldFinalize() {
        var upload=reserve("reserve");var old=tx(()->intake.begin(upload.uploadId(),0L,"put",false)).lease();
        tx(()->{intake.uncertain(old);return null;});var uncertain=tx(()->intake.status(upload.uploadId()));assertThat(uncertain.state()).isEqualTo(ApprovalAttachmentDtos.State.STORAGE_RECONCILING);
        var current=tx(()->intake.begin(upload.uploadId(),uncertain.version(),"reconcile",true)).lease();
        assertThatThrownBy(()->tx(()->intake.stored(old,stored(old)))).isInstanceOf(BaseException.class);
        assertThat(tx(()->intake.stored(current,stored(current))).state()).isEqualTo(ApprovalAttachmentDtos.State.QUARANTINED);
        assertThat(tx(()->intake.begin(upload.uploadId(),uncertain.version(),"reconcile",true)).lease()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_attachment_command_receipts",Long.class)).isEqualTo(3);
    }
    @Test void cancelledUploadCannotBeResurrectedByLatePutOrScan() {
        var upload=reserve("reserve");var transfer=tx(()->intake.begin(upload.uploadId(),0L,"put",false));
        var cancelled=tx(()->intake.cancel(upload.uploadId(),new ApprovalAttachmentDtos.Cancel(transfer.upload().version(),"cancel")));
        assertThat(cancelled.state()).isEqualTo(ApprovalAttachmentDtos.State.CANCELLED);
        assertThatThrownBy(()->tx(()->intake.stored(transfer.lease(),stored(transfer.lease())))).isInstanceOf(BaseException.class);
        assertThat(tx(()->intake.status(upload.uploadId())).state()).isEqualTo(ApprovalAttachmentDtos.State.CANCELLED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_attachment_cleanup_journal",Long.class)).isEqualTo(1);
    }
    @Test void lateCurrentAuthority503RollsBackObjectVersionAndQuarantineAudit() {
        var upload=reserve("reserve");var lease=tx(()->intake.begin(upload.uploadId(),0L,"put",false)).lease();long audit=countAudit();
        var calls=new java.util.concurrent.atomic.AtomicInteger();when(identities.require(42,99)).thenAnswer(invocation->{if(calls.incrementAndGet()==4) throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,"Late current authority failure");return documentSubject(99,List.of("APPROVAL_OPERATOR"),DOC_PERMISSIONS);});
        assertThatThrownBy(()->tx(()->intake.stored(lease,stored(lease)))).isInstanceOf(BaseException.class);
        assertThat(jdbc.queryForObject("SELECT object_version FROM apr_attachment_uploads WHERE upload_id=?",String.class,upload.uploadId())).isNull();assertThat(countAudit()).isEqualTo(audit);
    }
    @Test void concurrentRequestVersionOrPolicyDriftPreventsQuarantineFinalization() {
        var upload=reserve("reserve");var lease=tx(()->intake.begin(upload.uploadId(),0L,"put",false)).lease();
        jdbc.update("UPDATE apr_requests SET version=version+1 WHERE request_id=?",request);
        assertThatThrownBy(()->tx(()->intake.stored(lease,stored(lease)))).isInstanceOf(BaseException.class);
        jdbc.update("UPDATE apr_requests SET version=0 WHERE request_id=?",request);jdbc.update("UPDATE apr_attachment_policy_heads SET version=version+1 WHERE policy_id=?",policy);
        assertThatThrownBy(()->tx(()->intake.stored(lease,stored(lease)))).isInstanceOf(BaseException.class);assertThat(tx(()->intake.status(upload.uploadId())).state()).isEqualTo(ApprovalAttachmentDtos.State.UPLOADING);
    }
    @Test void admissionLimitsAndUnsafeFileNamesCannotCreateReceipts() {
        for(String name:new String[]{"../unsafe.txt","x\r\nheader.txt","x\\unsafe.txt"}) assertThatThrownBy(()->tx(()->intake.reserve(request,input("unsafe",name)))).isInstanceOf(BaseException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_attachment_command_receipts",Long.class)).isZero();
        for(int i=0;i<5;i++) reserve("r"+i);assertThatThrownBy(()->reserve("r5")).isInstanceOf(BaseException.class);assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_attachment_uploads",Long.class)).isEqualTo(5);
    }
    @Test void liveLeaseAndChangedFingerprintAreConflictsNotAuthorizationRetries() {
        var upload=reserve("reserve");tx(()->intake.begin(upload.uploadId(),0L,"put",false));
        assertThatThrownBy(()->tx(()->intake.begin(upload.uploadId(),0L,"put",false))).isInstanceOf(BaseException.class);
        assertThatThrownBy(()->tx(()->intake.begin(upload.uploadId(),1L,"put",false))).isInstanceOf(BaseException.class);
        assertThat(jdbc.queryForObject("SELECT generation FROM apr_attachment_uploads WHERE upload_id=?",Long.class,upload.uploadId())).isEqualTo(1);
    }
    private ApprovalAttachmentDtos.Upload reserve(String key){return tx(()->intake.reserve(request,input(key,"owner.txt")));}
    private ApprovalAttachmentDtos.Reserve input(String key,String name){return new ApprovalAttachmentDtos.Reserve(0L,1,0,name,"text/plain",1,ApprovalAttachmentIntegrity.sha(new byte[]{1}),key);}
    private ApprovalAttachmentStorage.Stored stored(ApprovalAttachmentIntakeCommands.Lease lease){return new ApprovalAttachmentStorage.Stored(lease.objectKey(),"actual-version-1",lease.sizeBytes(),lease.sha256());}
    private long countAudit(){return jdbc.queryForObject("SELECT count(*) FROM sys_audit_outbox",Long.class);}
}
