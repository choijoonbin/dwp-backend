package com.dwp.services.approval.domain;

import static org.mockito.Mockito.*;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.approval.attachment.*;
import com.dwp.services.approval.attachment.binding.ApprovalAttachmentLifecycleBinding;
import com.dwp.services.approval.document.*;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.security.WorkflowNineNativeContextProbe;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import java.io.ByteArrayInputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual attachment database protocol; identity, AV/storage are fixtures, never provider or installed public-PEP evidence. */
final class WorkflowNineAttachmentFixture implements AutoCloseable {
    final ApprovalWorkflowInformationAttachments replies;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ApprovalRequestContext.Actor requester;
    private final ApprovalAttachmentLifecycleBinding binding;
    private final ApprovalAttachmentIntakeCommands commands;
    private final ApprovalAttachmentIntake intake;
    private final ApprovalAttachmentViews views;
    private final ApprovalAttachmentScanJobs scans;
    private final ApprovalAttachmentInput input=new ApprovalAttachmentInput();
    private final jakarta.validation.ValidatorFactory validation=Validation.buildDefaultValidatorFactory();
    private final byte[] content="Auth9 coupled attachment evidence".getBytes(StandardCharsets.UTF_8);
    private final String sha=ApprovalAttachmentIntegrity.sha(content);

    WorkflowNineAttachmentFixture(NamedParameterJdbcTemplate jdbc,ObjectMapper mapper,TransactionTemplate tx,
            ApprovalRequestContext.Actor requester,Map<Long,UUID> people) {
        this.jdbc=jdbc;this.tx=tx;this.requester=requester;var canonical=new ApprovalDocumentCanonical(mapper);
        var identities=mock(ApprovalIdentityDirectory.class);
        when(identities.require(anyLong(),anyLong())).thenAnswer(call->{long tenant=call.getArgument(0),user=call.getArgument(1);
            return new ApprovalIdentityDirectory.Subject(tenant,user,null,people.get(user),"Fixture attachment subject","subject@example.test",null,"ACTIVE",
                    List.copyOf(requester.roles()),List.copyOf(requester.permissions()));});
        var work=new ApprovalWorkAuthority(identities);var owners=new ApprovalDocumentOwnerRepository(jdbc);
        var authority=new ApprovalDocumentAuthority(work,identities,owners,canonical);
        var policies=new ApprovalAttachmentPolicyRepository(jdbc,canonical,validation.getValidator());var writes=new ApprovalAttachmentCommands(jdbc,canonical);
        var audit=new ApprovalAttachmentAudit(new AuditOutboxRecorder(jdbc,mapper,"dwp-approval-server","cross-service-test","test"));
        var provider=mock(ApprovalAttachmentProviderGate.class);var storage=mock(ApprovalAttachmentStorage.class);
        when(provider.storage()).thenReturn(storage);when(provider.readiness()).thenReturn("COMPONENTS_VERIFIED_NOT_SANITIZED");when(storage.load(any())).thenReturn(content);
        when(storage.put(anyString(),any(byte[].class),anyString())).thenAnswer(call->new ApprovalAttachmentStorage.Stored(call.getArgument(0),"nine-fixture-object-version",((byte[])call.getArgument(1)).length,call.getArgument(2)));
        commands=new ApprovalAttachmentIntakeCommands(jdbc,authority,owners,policies,writes,provider,audit,canonical);intake=new ApprovalAttachmentIntake(commands,provider,input);
        views=new ApprovalAttachmentViews(jdbc,authority,owners,new ApprovalDocumentRepository(jdbc,canonical),policies,writes,provider,audit,canonical);
        scans=new ApprovalAttachmentScanJobs(jdbc,identities,audit);
        binding=new ApprovalAttachmentLifecycleBinding(jdbc,work,owners,policies,new ApprovalAttachmentManifestFacade(jdbc,owners,authority,canonical),provider,canonical);
        replies=new ApprovalWorkflowInformationAttachments(binding);
    }
    void createBaseline(UUID request) {
        actor();tx.execute(status->{binding.initializeCreated(request,0);
            UUID policy=UUID.randomUUID();var rules=new ApprovalAttachmentDtos.Rules(true,false,26214400,10,104857600,2,List.of("text/plain"),300,365);
            var canonical=new ApprovalDocumentCanonical(new ObjectMapper().findAndRegisterModules());
            var p=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("tenant",requester.tenantId()).addValue("policy",policy)
                    .addValue("rules",canonical.json(rules)).addValue("sha",canonical.fingerprint(rules));
            jdbc.update("INSERT INTO apr_attachment_policy_heads(tenant_id,resource_set_key,policy_id) VALUES(:tenant,'RS_APPROVALS',:policy)",p);
            jdbc.update("INSERT INTO apr_attachment_policy_versions(tenant_id,policy_id,revision,rules,rules_sha256) VALUES(:tenant,:policy,0,CAST(:rules AS jsonb),:sha)",p);return null;
        });
    }
    UUID attach(UUID request) {
        // Attachment owner context is an explicit fixture, not a borrowed information-response or public attachment admission.
        WorkflowNineNativeContextProbe.clear();actor();
        var reserved=tx.execute(status->commands.reserve(request,new ApprovalAttachmentDtos.Reserve(1L,1,0,"evidence.txt","text/plain",content.length,sha,"nine-file")));
        tx.execute(status->{try {return intake.upload(reserved.uploadId(),reserved.version(),"nine-file-content",new ByteArrayInputStream(content));}
            catch(java.io.IOException failure) {throw new UncheckedIOException(failure);}});
        var job=tx.execute(status->scans.claim()).orElseThrow();
        if(!Boolean.TRUE.equals(tx.execute(status->scans.finish(job,new ApprovalAttachmentScanner.Result(ApprovalAttachmentScanner.Verdict.AV_CLEAR,"AV_CLEAR_NOT_SANITIZED","Cross fixture",Instant.now(),Instant.now(),sha),
                new ApprovalAttachmentPassiveContent.Result(true,"PASSIVE_ALLOWED_NOT_SANITIZED","Cross fixture"))))) throw new AssertionError("Actual attachment scan lease did not complete");
        tx.execute(status->views.select(request,new ApprovalAttachmentDtos.Selection(1L,1,0,0,List.of(reserved.attachmentId()),"nine-file-selection")));return reserved.attachmentId();
    }
    private void actor() {ApprovalRequestContext.set(requester.userId(),requester.tenantId(),requester.personPublicId(),requester.displayName(),requester.roles(),requester.permissions());}
    @Override public void close() {input.close();validation.close();}
}
