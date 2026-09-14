package com.dwp.services.approval.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.document.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.dwp.services.approval.document.ApprovalDocumentDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalDocumentPostgresTest extends ApprovalDocumentPostgresFixture {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @BeforeEach void setUp() throws Exception { initializeDocuments(POSTGRES); }
    @AfterEach void after() { clear(); }

    @Test void defaultsAreManagedButNeverAutoGrantExports() {
        var request = tx(() -> drafts.create(body("Owner"), "create", null));
        var tools = tx(() -> documents.tools(OwnerType.REQUEST, request.requestId()));
        assertThat(tools.copyIdentifier().allowed()).isTrue(); assertThat(tools.history().allowed()).isTrue();
        assertThat(tools.print().allowed()).isFalse(); assertThat(tools.jsonExport().allowed()).isFalse();
        var input = new Export(request.version(), tools.payloadRevision(), tools.policyVersion(), Intent.DOWNLOAD, "Owner export", "export");
        denied(ErrorCode.FORBIDDEN, () -> tx(() -> documents.export(OwnerType.REQUEST, request.requestId(), input)));
        assertThat(count("apr_document_command_receipts")).isZero();
        assertThat(tx(management::policy).published().rules().allowArchiveExport()).isFalse();
    }
    @Test void appendIsImmutableSequencedIdempotentAndNeverReplaysAfterRevocation() {
        var request = tx(() -> drafts.create(body("Comment owner"), "create", null));
        var input = new AppendComment(request.version(), 0L, "comment", "Append only note");
        var first = tx(() -> documents.append(OwnerType.REQUEST, request.requestId(), input));
        long audit = count("sys_audit_outbox");
        var replay = tx(() -> documents.append(OwnerType.REQUEST, request.requestId(), input));
        assertThat(replay).isEqualTo(first); assertThat(count("sys_audit_outbox")).isEqualTo(audit);
        assertThat(count("apr_document_comments")).isEqualTo(1);
        assertThat(tx(() -> documents.comments(OwnerType.REQUEST, request.requestId(), 0, 25)).commentsVersion()).isEqualTo(1);
        denied(ErrorCode.RESOURCE_CONFLICT, () -> tx(() -> documents.append(OwnerType.REQUEST, request.requestId(),
                new AppendComment(request.version(), 0L, "comment", "Different fingerprint"))));
        assertThatThrownBy(() -> jdbc.update("UPDATE apr_document_comments SET comment_text='altered' WHERE comment_id=?", first.commentId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        var revoked = new HashSet<>(DOC_PERMISSIONS); revoked.remove("ACTION.APPROVAL_REQUEST:UPDATE");
        when(identities.require(42, 99)).thenReturn(documentSubject(99, List.of("APPROVAL_OPERATOR"), revoked));
        denied(ErrorCode.FORBIDDEN, () -> tx(() -> documents.append(OwnerType.REQUEST, request.requestId(), input)));
        assertThat(count("apr_document_comments")).isEqualTo(1);
    }
    @Test void ownerAndTenantAreCheckedForEveryChildRead() {
        var request = tx(() -> drafts.create(body("Private"), "create", null));
        tx(() -> documents.append(OwnerType.REQUEST, request.requestId(), new AppendComment(request.version(), 0L, "comment", "Private comment")));
        docContext(100);
        denied(ErrorCode.RESOURCE_NOT_AVAILABLE, () -> tx(() -> documents.comments(OwnerType.REQUEST, request.requestId(), 0, 25)));
        ApprovalRequestContext.set(99L, 43L, null, "Wrong tenant", Set.of("APPROVAL_OPERATOR"), DOC_PERMISSIONS);
        when(identities.require(43, 99)).thenReturn(new com.dwp.services.approval.integration.ApprovalIdentityDirectory.Subject(
                43L,99L,null,null,"Owner","owner@test",null,"ACTIVE",List.of("APPROVAL_OPERATOR"),List.copyOf(DOC_PERMISSIONS)));
        denied(ErrorCode.RESOURCE_NOT_AVAILABLE, () -> tx(() -> documents.comments(OwnerType.REQUEST, request.requestId(), 0, 25)));
    }
    @Test void policyNeedsIndependentRealSignedPublicationAndReplayIsMetadataOnly() throws Exception {
        var policy = tx(management::policy); var save = new SavePolicy(policy.version(), "save", exportingRules());
        var pending = tx(() -> management.save(policy.policyId(), save));
        assertThat(pending.published().rules().allowJsonExport()).isFalse(); assertThat(pending.pending()).isNotNull();
        var input = new PublishPolicy(pending.version(), "publish", "Independent review required");
        exactPublish(99, ApprovalDocumentManagementService.POLICY_PUBLISH, "110");
        var makerHeaders = signed(ApprovalDocumentManagementService.POLICY_PUBLISH, "DOCUMENT_POLICY", policy.policyId(), input.expectedVersion(),
                "/v1/admin/document-tools/policies/" + policy.policyId() + "/publish", input.idempotencyKey(), input);
        denied(ErrorCode.FORBIDDEN, () -> tx(() -> management.publish(policy.policyId(), input, makerHeaders)));
        assertThat(count("apr_document_policy_publications")).isZero(); assertThat(count("apr_step_up_replay_ledger")).isZero();
        exactPublish(100, ApprovalDocumentManagementService.POLICY_PUBLISH, "111");
        var headers = signed(ApprovalDocumentManagementService.POLICY_PUBLISH, "DOCUMENT_POLICY", policy.policyId(), input.expectedVersion(),
                "/v1/admin/document-tools/policies/" + policy.policyId() + "/publish", input.idempotencyKey(), input);
        var published = tx(() -> management.publish(policy.policyId(), input, headers));
        assertThat(published.published().rules().allowJsonExport()).isTrue(); assertThat(published.pending()).isNull();
        assertThat(tx(() -> management.publish(policy.policyId(), input, headers))).isEqualTo(published);
        assertThat(count("apr_document_policy_publications")).isEqualTo(1); assertThat(count("apr_step_up_replay_ledger")).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE apr_document_policy_versions SET rules='{}'::jsonb WHERE revision=1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
    @ParameterizedTest @ValueSource(strings={"000","100"})
    void managementPublishHasNoCompatibilityShortcut(String state) {
        var policy = tx(management::policy); var pending = tx(() -> management.save(policy.policyId(), new SavePolicy(policy.version(), "save", exportingRules())));
        docContext(100);
        denied(ErrorCode.STEP_UP_REQUIRED, () -> tx(() -> management.publish(policy.policyId(), new PublishPolicy(pending.version(), "publish", "No bare publish allowed"), null)));
        assertThat(count("apr_document_policy_publications")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"000","100","110","111"})
    void policyPathCannotBorrowAnotherSelectedResourceSetEvenBeforeReplayOrChallenge(String state) {
        var policy=tx(management::policy);
        var save=new SavePolicy(policy.version(),"save",exportingRules());
        tx(()->management.save(policy.policyId(),save));
        ApprovalManagementScopeContext.set("scope-other","RS_OTHER");
        var other=tx(management::policy);
        assertThat(other.version()).isEqualTo(policy.version());
        var before=policyEffects();
        denied(ErrorCode.FORBIDDEN,()->tx(()->management.save(policy.policyId(),save)));
        exactPublish(100,ApprovalDocumentManagementService.POLICY_PUBLISH,state);
        ApprovalManagementScopeContext.set("scope-other","RS_OTHER");
        denied(ErrorCode.FORBIDDEN,()->tx(()->management.publish(policy.policyId(),new PublishPolicy(1L,"publish","Wrong selected resource set"),null)));
        assertThat(policyEffects()).isEqualTo(before);
    }
    @Test void missingPolicyHeadNeverBootstrapsOnEitherMutation() {
        var before=policyEffects(); UUID absent=UUID.randomUUID();
        denied(ErrorCode.NOT_FOUND,()->tx(()->management.save(absent,new SavePolicy(0L,"save",exportingRules()))));
        exactPublish(100,ApprovalDocumentManagementService.POLICY_PUBLISH,"110");
        denied(ErrorCode.NOT_FOUND,()->tx(()->management.publish(absent,new PublishPolicy(0L,"publish","No lazy mutation bootstrap"),null)));
        assertThat(policyEffects()).isEqualTo(before);
    }
    @Test void randomOrCrossTenantPolicyTargetNeverMutatesCurrentHead() {
        var policy=tx(management::policy);
        queries.ensureTenant(43);
        UUID foreign=UUID.randomUUID();
        tx(()->{
            jdbc.update("INSERT INTO apr_document_policy_heads(tenant_id,resource_set_key,policy_id) VALUES(43,'RS_APPROVALS',?)",foreign);
            var rules=ApprovalDocumentPolicy.defaults();
            jdbc.update("INSERT INTO apr_document_policy_versions(tenant_id,policy_id,revision,rules,rules_sha256) VALUES(43,?,0,?::jsonb,?)",foreign,canonical.json(rules),canonical.fingerprint(rules));
            return null;
        });
        var before=policyEffects();
        for(UUID id:List.of(foreign,UUID.randomUUID())) {
            denied(ErrorCode.FORBIDDEN,()->tx(()->management.save(id,new SavePolicy(policy.version(),"save",exportingRules()))));
            denied(ErrorCode.FORBIDDEN,()->tx(()->management.publish(id,new PublishPolicy(policy.version(),"publish","Target tenant does not match"),null)));
        }
        assertThat(policyEffects()).isEqualTo(before);
    }
    @Test void inactiveTenantCannotReplayPreviouslySuccessfulPolicySave() {
        var policy=tx(management::policy);var input=new SavePolicy(policy.version(),"save",exportingRules());
        tx(()->management.save(policy.policyId(),input));
        jdbc.update("UPDATE apr_tenants SET lifecycle_state='SUSPENDED' WHERE tenant_id=42");
        var before=policyEffects();
        denied(ErrorCode.NOT_FOUND,()->tx(()->management.save(policy.policyId(),input)));
        denied(ErrorCode.NOT_FOUND,()->tx(()->management.publish(policy.policyId(),new PublishPolicy(1L,"publish","Inactive tenant cannot publish"),null)));
        assertThat(policyEffects()).isEqualTo(before);
    }
    @Test void correctPathSaveReplaysOnceButStaleVersionAndDifferentFingerprintConflict() {
        var policy=tx(management::policy);var input=new SavePolicy(policy.version(),"save",exportingRules());
        var pending=tx(()->management.save(policy.policyId(),input));var before=policyEffects();
        assertThat(tx(()->management.save(policy.policyId(),input))).isEqualTo(pending);
        denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->management.save(policy.policyId(),new SavePolicy(policy.version(),"stale",exportingRules()))));
        denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->management.save(policy.policyId(),new SavePolicy(pending.version(),"save",exportingRules()))));
        assertThat(policyEffects()).isEqualTo(before);
    }
    @Test void signedPublishRequiresCurrentPathUuidTargetAndVersion() throws Exception {
        var policy=tx(management::policy);
        var pending=tx(()->management.save(policy.policyId(),new SavePolicy(policy.version(),"save",exportingRules())));
        var input=new PublishPolicy(pending.version(),"publish","Exact path target publication");
        exactPublish(100,ApprovalDocumentManagementService.POLICY_PUBLISH,"110");
        String path="/v1/admin/document-tools/policies/"+policy.policyId()+"/publish";
        var before=policyEffects();
        var wrongTarget=signed(ApprovalDocumentManagementService.POLICY_PUBLISH,"DOCUMENT_POLICY",UUID.randomUUID(),input.expectedVersion(),path,input.idempotencyKey(),input);
        denied(ErrorCode.STEP_UP_CHALLENGE_MISMATCH,()->tx(()->management.publish(policy.policyId(),input,wrongTarget)));
        var wrongPath=signed(ApprovalDocumentManagementService.POLICY_PUBLISH,"DOCUMENT_POLICY",policy.policyId(),input.expectedVersion(),"/v1/admin/document-tools/policy/publish",input.idempotencyKey(),input);
        denied(ErrorCode.STEP_UP_CHALLENGE_MISMATCH,()->tx(()->management.publish(policy.policyId(),input,wrongPath)));
        var stale=new PublishPolicy(pending.version()-1,"stale","Stale expected policy version");
        var staleHeaders=signed(ApprovalDocumentManagementService.POLICY_PUBLISH,"DOCUMENT_POLICY",policy.policyId(),stale.expectedVersion(),path,stale.idempotencyKey(),stale);
        denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->management.publish(policy.policyId(),stale,staleHeaders)));
        assertThat(policyEffects()).isEqualTo(before);
        var headers=signed(ApprovalDocumentManagementService.POLICY_PUBLISH,"DOCUMENT_POLICY",policy.policyId(),input.expectedVersion(),path,input.idempotencyKey(),input);
        var published=tx(()->management.publish(policy.policyId(),input,headers));var after=policyEffects();
        assertThat(tx(()->management.publish(policy.policyId(),input,headers))).isEqualTo(published);
        assertThat(policyEffects()).isEqualTo(after);
    }
    private Map<String,Object> policyEffects() {
        var result=new LinkedHashMap<String,Object>();
        for(String table:List.of("apr_document_policy_versions","apr_document_policy_publications","apr_document_command_receipts","apr_step_up_replay_ledger","sys_audit_outbox")) result.put(table,count(table));
        result.put("heads",jdbc.queryForList("SELECT tenant_id,resource_set_key,policy_id,version,published_revision,pending_revision FROM apr_document_policy_heads ORDER BY tenant_id,resource_set_key"));
        return result;
    }
    @Test void generatedJsonIsFieldAllowlistedDigestExactAndUnknownResponseReplayStable() throws Exception {
        var policy = publishRules(exportingRules());
        var request = tx(() -> drafts.create(body("Generated owner document"), "create", null));
        var input = new Export(request.version(), 1, policy.version(), Intent.DOWNLOAD, "Owner document export", "export");
        var result = tx(() -> documents.export(OwnerType.REQUEST, request.requestId(), input));
        assertThat(result.format()).isEqualTo("JSON"); assertThat(result.content()).contains("systemName").doesNotContain("compensatingControl", "accessRole");
        assertThat(result.sha256()).isEqualTo(ApprovalDocumentCanonical.sha(result.content()));
        assertThat(result.sizeBytes()).isEqualTo(result.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        long audit = count("sys_audit_outbox");
        assertThat(tx(() -> documents.export(OwnerType.REQUEST, request.requestId(), input))).isEqualTo(result);
        assertThat(count("sys_audit_outbox")).isEqualTo(audit);
        var metadata = jdbc.queryForObject("SELECT metadata::text FROM apr_document_command_receipts WHERE idempotency_key='export'", String.class);
        assertThat(metadata).doesNotContain("Generated owner document", "compensatingControl", "\"content\"", "\"payload\"");
        assertThat(metadata).contains("sourcePath", "requestedVersions", "payloadRevision");
        assertThat(result.expiresAt()).isBefore(result.retainUntil());
    }
    @Test void controlledHtmlEscapesStoredTextAndIsNotClaimedAsPdf() throws Exception {
        var policy = publishRules(exportingRules());
        var initial = body("<img src=x onerror=alert(1)>");
        var values = new HashMap<>(initial.payload()); values.put("systemName", "<script>alert(1)</script>");
        var create = new com.dwp.services.approval.domain.ApprovalDtos.CreateRequest(workflowId, formId, initial.title(), initial.summary(), "NORMAL", values);
        var request = tx(() -> drafts.create(create, "create", null));
        var result = tx(() -> documents.export(OwnerType.REQUEST, request.requestId(), new Export(request.version(), 1, policy.version(), Intent.PRINT, "Controlled print", "print")));
        assertThat(result.format()).isEqualTo("HTML"); assertThat(result.mediaType()).isEqualTo("text/html");
        assertThat(result.content()).contains("&lt;script&gt;", "&lt;img", "default-src 'none'").doesNotContain("<script", "<img");
    }
    @Test void viewAndManageNeverSubstituteForExplicitCurrentExportGrant() throws Exception {
        var policy = publishRules(exportingRules()); var request = tx(() -> drafts.create(body("Export guarded"), "create", null));
        var input = new Export(request.version(), 1, policy.version(), Intent.DOWNLOAD, "Sensitive export", "export");
        var onlyView = new HashSet<>(DOC_PERMISSIONS); onlyView.remove("ACTION.APPROVAL_REQUEST:EXPORT"); onlyView.add("ACTION.APPROVAL_REQUEST:MANAGE");
        ApprovalRequestContext.set(99L, 42L, null, "Owner", Set.of("APPROVAL_OPERATOR"), onlyView);
        denied(ErrorCode.FORBIDDEN, () -> tx(() -> documents.export(OwnerType.REQUEST, request.requestId(), input)));
        docContext(99); when(identities.require(42,99)).thenReturn(documentSubject(99,List.of("APPROVAL_OPERATOR"),onlyView));
        denied(ErrorCode.FORBIDDEN, () -> tx(() -> documents.export(OwnerType.REQUEST, request.requestId(), input)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_document_command_receipts WHERE idempotency_key='export'",Long.class)).isZero();
    }
    @Test void currentVersionsAndPolicyClassificationAreUnconditional() throws Exception {
        var policy = publishRules(exportingRules()); var request = tx(() -> drafts.create(body("Version guard"), "create", null));
        denied(ErrorCode.RESOURCE_CONFLICT, () -> tx(() -> documents.export(OwnerType.REQUEST,request.requestId(),new Export(99L,1,policy.version(),Intent.DOWNLOAD,"Version mismatch","v"))));
        denied(ErrorCode.RESOURCE_CONFLICT, () -> tx(() -> documents.export(OwnerType.REQUEST,request.requestId(),new Export(request.version(),2,policy.version(),Intent.DOWNLOAD,"Revision mismatch","r"))));
        denied(ErrorCode.RESOURCE_CONFLICT, () -> tx(() -> documents.export(OwnerType.REQUEST,request.requestId(),new Export(request.version(),1,policy.version()-1,Intent.DOWNLOAD,"Policy mismatch","p"))));
        jdbc.update("UPDATE apr_requests SET data_classification='CONFIDENTIAL' WHERE request_id=?",request.requestId());
        denied(ErrorCode.FORBIDDEN, () -> tx(() -> documents.export(OwnerType.REQUEST,request.requestId(),new Export(request.version(),1,policy.version(),Intent.DOWNLOAD,"Restricted document","e"))));
    }
    @Test void holdPlacementAndReleaseUseIndependentCheckersAndAppendOnlyJournal() throws Exception {
        var request = tx(() -> drafts.create(body("Held evidence"),"create",null));
        var proposal = new HoldProposal(0L,HoldOperation.PLACE,"Preserve pending legal review","hold-place");
        var pending = tx(() -> management.propose(request.requestId(),proposal)); assertThat(pending.active()).isFalse(); assertThat(pending.preservationPending()).isTrue();
        var input = new PublishHold(pending.version(),pending.pending().proposalId(),"Independent hold approval","hold-approve");
        String path="/v1/admin/document-tools/holds/"+request.requestId()+"/publish";
        exactPublish(99,ApprovalDocumentManagementService.HOLD_PUBLISH,"110");
        var maker=signed(ApprovalDocumentManagementService.HOLD_PUBLISH,"DOCUMENT_HOLD",request.requestId(),input.expectedVersion(),path,input.idempotencyKey(),input);
        denied(ErrorCode.FORBIDDEN,()->tx(()->management.publishHold(request.requestId(),input,maker)));
        exactPublish(100,ApprovalDocumentManagementService.HOLD_PUBLISH,"111");
        var signed=signed(ApprovalDocumentManagementService.HOLD_PUBLISH,"DOCUMENT_HOLD",request.requestId(),input.expectedVersion(),path,input.idempotencyKey(),input);
        var held=tx(()->management.publishHold(request.requestId(),input,signed)); assertThat(held.active()).isTrue(); assertThat(held.journal()).hasSize(1);
        docContext(99); var release=tx(()->management.propose(request.requestId(),new HoldProposal(held.version(),HoldOperation.RELEASE,"Legal review has completed","release")));
        assertThat(release.active()).isTrue();
        exactPublish(100,ApprovalDocumentManagementService.HOLD_PUBLISH,"110");
        var releaseInput=new PublishHold(release.version(),release.pending().proposalId(),"Independent release review","release-approve");
        var releaseHeaders=signed(ApprovalDocumentManagementService.HOLD_PUBLISH,"DOCUMENT_HOLD",request.requestId(),release.version(),path,releaseInput.idempotencyKey(),releaseInput);
        var released=tx(()->management.publishHold(request.requestId(),releaseInput,releaseHeaders));
        assertThat(released.active()).isFalse(); assertThat(released.journal()).hasSize(2); assertThat(released.purgeState()).isEqualTo("PURGE_WORKER_NOT_IMPLEMENTED");
        var journalBefore=jdbc.queryForList("SELECT * FROM apr_document_hold_journal WHERE request_id=? ORDER BY version",request.requestId());
        var deletion=catchThrowable(()->jdbc.update("DELETE FROM apr_document_hold_journal WHERE request_id=?",request.requestId()));
        assertThat(deletion).isNotNull();
        assertThat(org.springframework.core.NestedExceptionUtils.getMostSpecificCause(deletion))
                .isInstanceOfSatisfying(org.postgresql.util.PSQLException.class,e->assertThat(e.getSQLState()).isEqualTo("42501"));
        assertThat(jdbc.queryForList("SELECT * FROM apr_document_hold_journal WHERE request_id=? ORDER BY version",request.requestId())).isEqualTo(journalBefore);
    }
    @Test void lateCurrentAuthFailureRollsBackCommentSequenceAuditAndReceipt() {
        var request=tx(()->drafts.create(body("Late authority"),"create",null)); long audits=count("sys_audit_outbox");
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(identities.require(42,99)).thenAnswer(i->{if(calls.incrementAndGet()>=4)throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,"Auth unavailable");return documentSubject(99,List.of("APPROVAL_OPERATOR"),DOC_PERMISSIONS);});
        denied(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,()->tx(()->documents.append(OwnerType.REQUEST,request.requestId(),new AppendComment(request.version(),0L,"append","Must roll back"))));
        assertThat(count("apr_document_comments")).isZero(); assertThat(count("apr_document_command_receipts")).isZero(); assertThat(count("sys_audit_outbox")).isEqualTo(audits);
    }
    @Test void simultaneousUnknownResponseRetriesAppendExactlyOneComment() throws Exception {
        var request=tx(()->drafts.create(body("Concurrent owner"),"create",null)); var input=new AppendComment(request.version(),0L,"same-key","One immutable comment");
        var start=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Comment> task=()->{docContext(99);try{assertThat(start.await(10,TimeUnit.SECONDS)).isTrue();return tx(()->documents.append(OwnerType.REQUEST,request.requestId(),input));}finally{clear();}};
            var first=pool.submit(task);var second=pool.submit(task);start.countDown();
            assertThat(first.get(15,TimeUnit.SECONDS)).isEqualTo(second.get(15,TimeUnit.SECONDS));
            assertThat(count("apr_document_comments")).isEqualTo(1);assertThat(count("apr_document_command_receipts")).isEqualTo(1);
        } finally {start.countDown();pool.shutdownNow();}
    }
    @ParameterizedTest @ValueSource(strings={"000","100","110","111"})
    void currentOriginalDelegatorCannotBeReplacedForAnyDocumentTool(String state) throws Exception {
        var policy=publishRules(exportingRules()); docContext(100);
        var request=tx(()->drafts.create(body("Claim source continuity"),"create",null));
        tx(()->{approvals.submit(request.requestId(),request.version(),null);return null;});
        UUID task=jdbc.queryForObject("SELECT task_id FROM apr_tasks WHERE request_id=? ORDER BY created_at LIMIT 1",UUID.class,request.requestId());
        jdbc.update("INSERT INTO apr_delegations(delegation_id,tenant_id,delegator_user_id,delegate_user_id,scope_type,delegated_role_codes,starts_at,ends_at,reason,created_by,updated_by,delegate_display_name) VALUES(?,42,100,99,'ALL','[\"APPROVAL_OPERATOR\"]'::jsonb,CURRENT_TIMESTAMP-INTERVAL '1 minute',CURRENT_TIMESTAMP+INTERVAL '1 day','Original source',100,100,'Delegate')",UUID.randomUUID());
        docContext(99);long version=jdbc.queryForObject("SELECT version FROM apr_tasks WHERE task_id=?",Long.class,task);
        tx(()->approvals.claim(task,version,null));
        var tools=tx(()->documents.tools(OwnerType.TASK,task));
        assertThat(jdbc.queryForObject("SELECT delegated_from_user_id FROM apr_tasks WHERE task_id=?",Long.class,task)).isEqualTo(100);
        tx(()->documents.append(OwnerType.TASK,task,new AppendComment(tools.taskVersion(),0L,"comment-before","Original source comment")));
        long comments=count("apr_document_comments"),audits=count("sys_audit_outbox"),receipts=count("apr_document_command_receipts");
        jdbc.update("UPDATE apr_delegations SET delegated_role_codes='[]'::jsonb WHERE tenant_id=42 AND delegator_user_id=100");
        when(identities.require(42,101)).thenReturn(documentSubject(101,List.of("APPROVAL_OPERATOR"),DOC_PERMISSIONS));
        jdbc.update("INSERT INTO apr_delegations(delegation_id,tenant_id,delegator_user_id,delegate_user_id,scope_type,delegated_role_codes,starts_at,ends_at,reason,created_by,updated_by,delegate_display_name) VALUES(?,42,101,99,'ALL','[\"APPROVAL_OPERATOR\"]'::jsonb,CURRENT_TIMESTAMP-INTERVAL '1 minute',CURRENT_TIMESTAMP+INTERVAL '1 day','Alternate source',101,101,'Delegate')",UUID.randomUUID());
        if(state.charAt(1)=='1') {
            ApprovalDecisionRevisionContext.set("psr-"+"b".repeat(64),java.time.OffsetDateTime.now().plusMinutes(5),"work","own","route.approvals.work.task-document-tools.data",state);
            ApprovalPilotAuthorizationContext.set(List.of(new ApprovalPilotPepRegistry.RouteAuthority("route.approvals.work.task-document-tools.data","DATA","owner",false,Set.of("predicate.approval-task-readable.v1"),null,null,null,false,null,null)));
        }
        denied(ErrorCode.RESOURCE_NOT_AVAILABLE,()->tx(()->documents.tools(OwnerType.TASK,task)));
        denied(ErrorCode.RESOURCE_NOT_AVAILABLE,()->tx(()->documents.comments(OwnerType.TASK,task,0,25)));
        denied(ErrorCode.RESOURCE_NOT_AVAILABLE,()->tx(()->documents.append(OwnerType.TASK,task,new AppendComment(tools.taskVersion(),1L,"denied-comment","Must not append"))));
        denied(ErrorCode.RESOURCE_NOT_AVAILABLE,()->tx(()->documents.export(OwnerType.TASK,task,new Export(tools.taskVersion(),tools.payloadRevision(),policy.version(),Intent.DOWNLOAD,"Must not export","denied-export"))));
        assertThat(count("apr_document_comments")).isEqualTo(comments);assertThat(count("apr_document_command_receipts")).isEqualTo(receipts);assertThat(count("sys_audit_outbox")).isEqualTo(audits);
    }
    @Test void retentionExpiryClosesGeneratedDocumentsButPendingHoldPreservesEvidence() throws Exception {
        var policy=publishRules(exportingRules());var request=tx(()->drafts.create(body("Retention bounded"),"create",null));
        tx(()->documents.tools(OwnerType.REQUEST,request.requestId()));
        jdbc.update("UPDATE apr_document_heads SET retain_until=CURRENT_TIMESTAMP-INTERVAL '1 day' WHERE request_id=?",request.requestId());
        var input=new Export(request.version(),1,policy.version(),Intent.DOWNLOAD,"Retained document","export");
        denied(ErrorCode.FORBIDDEN,()->tx(()->documents.export(OwnerType.REQUEST,request.requestId(),input)));
        tx(()->management.propose(request.requestId(),new HoldProposal(0L,HoldOperation.PLACE,"Pending preservation review","hold")));
        var preserved=tx(()->documents.export(OwnerType.REQUEST,request.requestId(),input));
        assertThat(preserved.content()).contains("\"preservationPending\":true","\"legalHold\":false");
        assertThat(tx(()->documents.export(OwnerType.REQUEST,request.requestId(),input))).isEqualTo(preserved);
        assertThat(tx(()->management.hold(request.requestId())).purgeState()).isEqualTo("LEGAL_HOLD_PENDING_APPROVAL");
    }
    @Test void archiveUsesExplicitOwnCompletedSetAndRejectsWholeBatchOnForeignItem() throws Exception {
        var policy=publishRules(exportingRules());var first=tx(()->drafts.create(body("Own completed"),"first",null));
        docContext(100);var second=tx(()->drafts.create(body("Other completed"),"second",null));
        jdbc.update("UPDATE apr_requests SET status='WITHDRAWN',completed_at=CURRENT_TIMESTAMP WHERE request_id IN (?,?)",first.requestId(),second.requestId());
        docContext(99);var input=new ArchiveExport(List.of(new ArchiveItem(first.requestId(),first.version(),1),new ArchiveItem(second.requestId(),second.version(),1)),policy.version(),"Bounded archive export","archive",policy.policyId(),policy.resourceSetKey());
        long receipts=count("apr_document_command_receipts"),audits=count("sys_audit_outbox");
        denied(ErrorCode.RESOURCE_NOT_AVAILABLE,()->tx(()->documents.archive(input)));
        assertThat(count("apr_document_command_receipts")).isEqualTo(receipts);assertThat(count("sys_audit_outbox")).isEqualTo(audits);
        var own=new ArchiveExport(List.of(new ArchiveItem(first.requestId(),first.version(),1)),policy.version(),"Own archive export","own",policy.policyId(),policy.resourceSetKey());
        assertThat(tx(()->documents.archive(own)).format()).isEqualTo("JSON");
    }
    @Test void jsonPermissionNeverImpliesArchiveAndPolicyIdentityCannotDrift() throws Exception {
        var base=exportingRules();
        var rules=new Rules(base.allowComments(),base.allowPrint(),true,false,base.includeComments(),base.includeEvidence(),base.allowedClassifications(),base.fields(),base.maxBatchItems(),base.maxBytes(),base.snapshotTtlSeconds(),base.evidenceRetentionDays());
        var policy=publishRules(rules);var request=tx(()->drafts.create(body("Archive separately governed"),"create",null));
        jdbc.update("UPDATE apr_requests SET status='WITHDRAWN',completed_at=CURRENT_TIMESTAMP WHERE request_id=?",request.requestId());
        var tools=tx(()->documents.tools(OwnerType.REQUEST,request.requestId()));
        assertThat(tools.jsonExport().allowed()).isTrue();assertThat(tools.archiveExport().allowed()).isFalse();
        assertThat(tools.policyId()).isEqualTo(policy.policyId());assertThat(tools.maxBatchItems()).isEqualTo(20);
        var item=new ArchiveItem(request.requestId(),request.version(),1);
        denied(ErrorCode.FORBIDDEN,()->tx(()->documents.archive(new ArchiveExport(List.of(item),policy.version(),"Archive disabled","archive",policy.policyId(),policy.resourceSetKey()))));
        denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->documents.archive(new ArchiveExport(List.of(item),policy.version(),"Wrong policy identity","wrong-id",UUID.randomUUID(),policy.resourceSetKey()))));
        denied(ErrorCode.RESOURCE_CONFLICT,()->tx(()->documents.archive(new ArchiveExport(List.of(item),policy.version(),"Wrong policy scope","wrong-scope",policy.policyId(),"RS_OTHER"))));
    }
    @Test void typedDecimalStringsAndRepeatedRowsRenderWithoutPrecisionLossAndHtmlInjection() throws Exception {
        String decimal="1234567890123456789012345678";
        var rowFields=List.of(new FieldRule("amount",FieldType.DECIMAL_STRING,40,List.of()),new FieldRule("note",FieldType.STRING,200,List.of()));
        var rules=new Rules(true,true,true,false,false,false,List.of("RESTRICTED"),List.of(new FieldRule("total",FieldType.DECIMAL_STRING,40,List.of()),new FieldRule("rows",FieldType.OBJECT_LIST,200,rowFields,50)),20,1048576,300,365);
        var policy=publishRules(rules);var request=tx(()->drafts.create(body("Typed source export"),"create",null));
        var schema=Map.of("schemaContract",com.dwp.services.approval.domain.ApprovalFormSchemaV2.CONTRACT,"schemaVersion",2,"fields",List.of(
                Map.of("key","total","type","NUMBER","labelKo","Total","labelEn","Total"),
                Map.of("key","rows","type","REPEATING_GROUP","labelKo","Rows","labelEn","Rows","maxRows",50,"fields",List.of(
                        Map.of("key","amount","type","NUMBER","labelKo","Amount","labelEn","Amount"),Map.of("key","note","type","TEXT","labelKo","Note","labelEn","Note")))));
        var compiled=new com.dwp.services.approval.domain.ApprovalFormSchemaV2Compiler().compile(schema);
        String schemaJson=compiled.canonicalJson();UUID version=UUID.randomUUID();
        jdbc.update("INSERT INTO apr_form_versions(form_version_id,tenant_id,form_id,version_number,schema_payload,schema_sha256,lifecycle_state) SELECT ?,42,?,MAX(version_number)+1,?::jsonb,?,'PUBLISHED' FROM apr_form_versions WHERE tenant_id=42 AND form_id=?",version,formId,schemaJson,compiled.sha256(),formId);
        jdbc.update("UPDATE apr_requests SET form_version_id=? WHERE request_id=?",version,request.requestId());
        var evaluated=new com.dwp.services.approval.domain.ApprovalFormSchemaV2Evaluator().evaluate(compiled,Map.of("total",decimal,"rows",List.of(Map.of("amount",decimal,"note","<script>blocked</script>"))),false);
        String payload=canonical.json(evaluated.payload());
        // This disposable PG fixture represents the immutable canonical TypedV2 transport, not a legacy create shortcut.
        jdbc.update("UPDATE apr_request_payloads SET payload=?::jsonb,payload_sha256=?,schema_version=2 WHERE request_id=?",payload,ApprovalDocumentCanonical.sha(payload),request.requestId());
        jdbc.update("INSERT INTO apr_request_payload_versions(payload_version_id,tenant_id,request_id,revision_number,payload,payload_sha256,change_type,changed_by,change_reason) VALUES(?,42,?,2,?::jsonb,?,'DRAFT_UPDATED',99,'TypedV2 canonical fixture')",UUID.randomUUID(),request.requestId(),payload,ApprovalDocumentCanonical.sha(payload));
        var json=tx(()->documents.export(OwnerType.REQUEST,request.requestId(),new Export(request.version(),2,policy.version(),Intent.DOWNLOAD,"Typed precision proof","json")));
        assertThat(json.content()).contains("\"stringValue\":\""+decimal+"\"","OBJECT_LIST");
        assertThat(json.sha256()).isEqualTo(ApprovalDocumentCanonical.sha(json.content()));
        var html=tx(()->documents.export(OwnerType.REQUEST,request.requestId(),new Export(request.version(),2,policy.version(),Intent.PRINT,"Typed controlled print","html")));
        assertThat(html.content()).contains(decimal,"rows[1].amount","&lt;script&gt;blocked&lt;/script&gt;").doesNotContain("<script");
        assertThat(html.sha256()).isEqualTo(ApprovalDocumentCanonical.sha(html.content()));
        assertThat(html.sizeBytes()).isEqualTo(html.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }
    @Test void exportFailsClosedWhenMutablePayloadNoLongerMatchesImmutableRevision() throws Exception {
        var policy=publishRules(exportingRules());var request=tx(()->drafts.create(body("Immutable source evidence"),"create",null));
        String changed=canonical.json(Map.of("systemName","Unrecorded replacement"));
        jdbc.update("UPDATE apr_request_payloads SET payload=?::jsonb,payload_sha256=? WHERE request_id=?",changed,ApprovalDocumentCanonical.sha(changed),request.requestId());
        long receipts=count("apr_document_command_receipts"),audits=count("sys_audit_outbox");
        denied(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,()->tx(()->documents.export(OwnerType.REQUEST,request.requestId(),new Export(request.version(),1,policy.version(),Intent.DOWNLOAD,"No unrecorded export","export"))));
        assertThat(count("apr_document_command_receipts")).isEqualTo(receipts);assertThat(count("sys_audit_outbox")).isEqualTo(audits);
    }
    private void denied(ErrorCode code, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BaseException.class,e->assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
