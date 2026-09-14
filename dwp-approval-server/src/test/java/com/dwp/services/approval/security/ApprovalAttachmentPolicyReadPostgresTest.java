package com.dwp.services.approval.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.attachment.*;
import com.dwp.services.approval.document.*;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import jakarta.validation.Validation;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class ApprovalAttachmentPolicyReadPostgresTest extends ApprovalDocumentPostgresFixture {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    ApprovalAttachmentManagementService service;
    ApprovalAttachmentPolicyRepository repository;
    jakarta.validation.ValidatorFactory validation;
    @BeforeEach void before() throws Exception {
        initializeDocuments(PG);validation=Validation.buildDefaultValidatorFactory();var named=new NamedParameterJdbcTemplate(jdbc);
        var owners=new ApprovalDocumentOwnerRepository(named);
        var authority=new ApprovalDocumentAuthority(new ApprovalWorkAuthority(identities),identities,owners,canonical);
        repository=new ApprovalAttachmentPolicyRepository(named,canonical,validation.getValidator());
        var provider=mock(ApprovalAttachmentProviderGate.class);
        when(provider.readiness()).thenReturn("DISABLED");when(provider.downloadReadiness()).thenReturn("DISABLED");
        service=new ApprovalAttachmentManagementService(authority,repository,new ApprovalAttachmentCommands(named,canonical),
                new ApprovalDocumentPublishGuard(verifier,new ApprovalStepUpReplayRepository(named,mapper)),provider,
                new ApprovalAttachmentAudit(new AuditOutboxRecorder(named,mapper,"dwp-approval-server","test","test")));
    }
    @AfterEach void after(){validation.close();clear();}
    @Test void missingHeadDataReadNeverCreatesAPolicyOrReceipt() {
        long audit=count("sys_audit_outbox");var failure=catchThrowable(()->tx(service::policy));
        assertThat(count("apr_attachment_policy_heads")).isZero();assertThat(count("apr_attachment_policy_versions")).isZero();
        assertThat(count("apr_attachment_command_receipts")).isZero();assertThat(count("sys_audit_outbox")).isEqualTo(audit);
        assertThat(failure).isInstanceOfSatisfying(BaseException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_AVAILABLE));
    }
    @Test void configuredDataReadSucceedsWhenTheDatabaseForbidsAllPolicyDml() {
        var head=tx(()->repository.initializeAbsent(ApprovalRequestContext.require(),"RS_APPROVALS"));
        var before=jdbc.queryForList("SELECT * FROM apr_attachment_policy_heads ORDER BY policy_id");
        var versions=jdbc.queryForList("SELECT * FROM apr_attachment_policy_versions ORDER BY policy_id,revision");
        jdbc.execute("CREATE FUNCTION attachment_test_reject_read_dml() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Policy DATA must not write' USING ERRCODE='42501'; END $$");
        for(String table:List.of("apr_attachment_policy_heads","apr_attachment_policy_versions"))
            jdbc.execute("CREATE TRIGGER attachment_test_read_only BEFORE INSERT OR UPDATE OR DELETE ON "+table+" FOR EACH ROW EXECUTE FUNCTION attachment_test_reject_read_dml()");
        long audit=count("sys_audit_outbox");var read=tx(service::policy);
        assertThat(read.policyId()).isEqualTo(head.policyId());assertThat(read.published().allowUpload()).isFalse();assertThat(read.published().allowDownload()).isFalse();
        assertThat(read.providerReadiness()).isEqualTo("DISABLED");assertThat(read.publishEligible()).isFalse();
        assertThat(jdbc.queryForList("SELECT * FROM apr_attachment_policy_heads ORDER BY policy_id")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM apr_attachment_policy_versions ORDER BY policy_id,revision")).isEqualTo(versions);
        assertThat(count("apr_attachment_command_receipts")).isZero();assertThat(count("sys_audit_outbox")).isEqualTo(audit);
    }
    @Test void anotherSelectedResourceSetCannotReadOrInitializeAnExistingPolicy() {
        tx(()->repository.initializeAbsent(ApprovalRequestContext.require(),"RS_APPROVALS"));
        ApprovalManagementScopeContext.set("scope-other","RS_OTHER_APPROVALS");
        unavailable(()->tx(service::policy));
        assertThat(count("apr_attachment_policy_heads")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_attachment_policy_heads WHERE resource_set_key='RS_OTHER_APPROVALS'",Long.class)).isZero();
    }
    @Test void revokedCurrentManagementPermissionCannotReadOrProvisionPolicy() {
        var permissions=new HashSet<>(DOC_PERMISSIONS);permissions.remove("ADMIN.APPROVAL_POLICY:VIEW");
        when(identities.require(42,99)).thenReturn(documentSubject(99,List.of("APPROVAL_OPERATOR"),permissions));
        assertThatThrownBy(()->tx(service::policy)).isInstanceOfSatisfying(BaseException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(count("apr_attachment_policy_heads")).isZero();assertThat(count("apr_attachment_policy_versions")).isZero();
    }
    private void unavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action){
        assertThatThrownBy(action).isInstanceOfSatisfying(BaseException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_AVAILABLE));
    }
}
