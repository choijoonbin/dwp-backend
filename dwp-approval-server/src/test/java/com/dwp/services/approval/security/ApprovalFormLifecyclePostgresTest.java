package com.dwp.services.approval.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalFormSchemaV2Compiler;
import com.dwp.services.approval.domain.ApprovalQueryRepository;
import com.dwp.services.approval.domain.ApprovalCommandRepository;
import com.dwp.services.approval.domain.ApprovalDtos;
import com.dwp.services.approval.domain.ApprovalFormLegacySchemaValidationConfig;
import com.dwp.services.approval.forms.*;
import com.dwp.services.approval.forms.ApprovalFormLifecycleDtos.*;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ApprovalFormLifecyclePostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine")
            .withLabel("dwp.approval.owner","apr12-v23-forms");
    private static final Set<String> PERMISSIONS=Set.of("ADMIN.APPROVAL_DESIGN:VIEW","ADMIN.APPROVAL_DESIGN:UPDATE","ADMIN.APPROVAL_DESIGN:PUBLISH");
    private static final String REV="psr-"+"a".repeat(64);
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private ApprovalFormLifecycleFacade forms;
    private ApprovalFormPublishReviewService publishReviews;
    private ApprovalFormWorkspaceRepository repo;
    private ApprovalIdentityDirectory identities;
    private ApprovalStepUpVerifier verifier;
    private UUID formId,publishedId,oldRequest,otherWorkflow;
    private Map<String,Object> schema;
    private MetadataInput metadata;
    private long formRevision;
    private String originalLegacyHash;
    private jakarta.validation.ValidatorFactory validation;
    @BeforeEach void setUp(TestInfo info) {
        var source=new PGSimpleDataSource();source.setURL(POSTGRES.getJdbcUrl());source.setUser(POSTGRES.getUsername());source.setPassword(POSTGRES.getPassword());
        new JdbcTemplate(source).execute("DROP SCHEMA IF EXISTS apr_retention_internal CASCADE");
        new JdbcTemplate(source).execute("DROP SCHEMA IF EXISTS apr_signature_native CASCADE");
        var flyway=Flyway.configure().dataSource(source).locations("classpath:db/migration").cleanDisabled(false).load();flyway.clean();flyway.migrate();
        jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        var named=new NamedParameterJdbcTemplate(source);var mapper=new ObjectMapper().findAndRegisterModules();
        new ApprovalQueryRepository(named,mapper).ensureTenant(42);
        formId=jdbc.queryForObject("SELECT form_id FROM apr_forms WHERE tenant_id=42 AND form_key='ACCESS_EXCEPTION_FORM'",UUID.class);
        publishedId=jdbc.queryForObject("SELECT v.form_version_id FROM apr_form_versions v JOIN apr_forms f ON f.tenant_id=v.tenant_id AND f.form_id=v.form_id "
                +"AND f.current_version=v.version_number WHERE f.form_id=?",UUID.class,formId);
        formRevision=jdbc.queryForObject("SELECT version FROM apr_forms WHERE form_id=?",Long.class,formId);
        originalLegacyHash=jdbc.queryForObject("SELECT schema_sha256 FROM apr_form_versions WHERE form_version_id=?",String.class,publishedId);
        schema=Map.of("schemaContract","DWP_APPROVAL_FORM_TYPED_V2","schemaVersion",2,"fields",List.of(field("summary","TEXTAREA")));
        var compiled=new ApprovalFormSchemaV2Compiler().compile(schema);
        if(!info.getTestMethod().orElseThrow().getName().equals("legacyCapturePreservesOriginalHashAndLabelsActualCaptureTime"))
            jdbc.update("UPDATE apr_form_versions SET schema_payload=?::jsonb,schema_sha256=? WHERE form_version_id=?",compiled.canonicalJson(),compiled.sha256(),publishedId);
        otherWorkflow=jdbc.queryForObject("SELECT workflow_id FROM apr_workflow_definitions WHERE tenant_id=42 AND workflow_key<>'ACCESS_EXCEPTION' AND lifecycle_state='PUBLISHED' LIMIT 1",UUID.class);
        UUID workflowVersion=jdbc.queryForObject("SELECT v.workflow_version_id FROM apr_workflow_definitions w JOIN apr_workflow_versions v "
                +"ON v.tenant_id=w.tenant_id AND v.workflow_id=w.workflow_id AND v.version_number=w.current_version "
                +"WHERE w.tenant_id=42 AND w.workflow_key='ACCESS_EXCEPTION'",UUID.class);
        oldRequest=UUID.randomUUID();
        jdbc.update("INSERT INTO apr_requests(request_id,tenant_id,request_number,workflow_version_id,form_version_id,title,requester_user_id,status) "
                +"VALUES (?,42,'APR12-PIN',?,?,'Original request',99,'DRAFT')",oldRequest,workflowVersion,publishedId);
        identities=mock(ApprovalIdentityDirectory.class);when(identities.require(eq(42L),anyLong())).thenAnswer(call->subject(call.getArgument(1),true));
        var http=new MockHttpServletRequest();http.addHeader("X-DWP-Active-Access-Mode","NORMAL");
        @SuppressWarnings("unchecked") ObjectProvider<HttpServletRequest> requests=mock(ObjectProvider.class);when(requests.getIfAvailable()).thenReturn(http);
        validation=jakarta.validation.Validation.buildDefaultValidatorFactory();
        var codec=new ApprovalFormMaterialCodec(mapper,new ApprovalFormLegacySchemaValidationConfig()
                .approvalFormLegacySchemaValidator(mapper,validation.getValidator()));
        repo=new ApprovalFormWorkspaceRepository(named,codec);
        verifier=mock(ApprovalStepUpVerifier.class);
        when(verifier.payloadSha256(any())).thenAnswer(call->codec.sha(codec.json(call.getArgument(0))));
        when(verifier.verify(anyString(),any())).thenAnswer(call->new ApprovalStepUpVerifier.VerifiedChallenge(UUID.randomUUID().toString(),UUID.randomUUID().toString(),
                "test-issued",call.getArgument(1),Instant.now().plusSeconds(30)));
        var store=new ApprovalFormLifecycleStore(repo);
        var authority=new ApprovalFormLifecycleAuthority(identities,requests);
        var reviewRepo=new ApprovalFormPublishReviewRepository(repo);
        var audit=new AuditOutboxRecorder(named,mapper,"dwp-approval-server","test","test");
        publishReviews=new ApprovalFormPublishReviewService(repo,store,reviewRepo,authority,identities,audit);
        forms=new ApprovalFormLifecycleFacade(repo,store,authority,
                new ApprovalFormManagedPublicationProof(verifier,new ApprovalStepUpReplayRepository(named,mapper)),new ApprovalFormVersionDiff(),
                reviewRepo,publishReviews,audit);
        context(99,"form-working-draft.data",false);
        metadata=repo.head(ApprovalRequestContext.require(),formId,false).metadata();
    }
    @AfterEach void clear() { if(validation!=null) validation.close();ApprovalRequestContext.clear();ApprovalManagementScopeContext.clear();ApprovalDecisionRevisionContext.clear();ApprovalPilotAuthorizationContext.clear(); }
    @Test void branchCreatesNewUuidAndNumberWithoutChangingLivePublishedOrRequestPins() {
        String before=legacyState();Workspace result=branch();
        assertThat(result.published().formVersionId()).isEqualTo(publishedId);
        assertThat(result.workingDraft().formVersionId()).isNotEqualTo(publishedId);
        assertThat(result.workingDraft().versionNumber()).isGreaterThan(result.published().versionNumber());
        assertThat(result.workspaceRevision()).isEqualTo(1L);assertThat(result.catalogPolicyEligible()).isTrue();assertThat(legacyState()).isEqualTo(before);
    }
    @Test void sameOriginalKeyReturnsSameCommitWithoutSecondVersionAuditOrJournal() {
        Workspace first=branch();String before=state();
        Workspace retry=run(()->forms.branch(formId,publishedId,new Branch(formRevision,null),"branch-original","corr"));
        assertThat(retry).isEqualTo(first);assertThat(state()).isEqualTo(before);
    }
    @Test void changedBodyOnOriginalKeyAndSecondBranchDoNotOverwriteWorkingDraft() {
        Workspace first=branch();String before=state();
        denied(()->forms.branch(formId,publishedId,new Branch(formRevision,first.workspaceRevision()),"branch-original","corr"));
        denied(()->forms.branch(formId,publishedId,new Branch(formRevision,first.workspaceRevision()),"another-branch","corr"));
        assertThat(state()).isEqualTo(before);
    }
    @Test void updateChangesOnlyWorkingSchemaAndVersionOwnedMetadataRoute() {
        Workspace first=branch();String before=legacyState();
        Workspace updated=update(first);
        assertThat(updated.workingDraft().schema()).containsKey("schemaContract");
        assertThat(updated.workingDraft().route()).containsEntry("workflowId",otherWorkflow.toString());
        assertThat(updated.workingDraft().metadata()).containsEntry("nameKo","Changed");
        assertThat(updated.published().formVersionId()).isEqualTo(publishedId);assertThat(legacyState()).isEqualTo(before);
    }
    @Test void staleRevisionAlternateDraftUnknownSchemaAndComputedCycleAreZeroWrite() {
        Workspace first=branch();String before=state();context(99,"form-working-draft-update.action",false);
        denied(()->forms.update(formId,new UpdateWorkingDraft(UUID.randomUUID(),formRevision,first.workspaceRevision(),schema,metadata,otherWorkflow),"wrong-draft","corr"));
        denied(()->forms.update(formId,new UpdateWorkingDraft(first.workingDraft().formVersionId(),formRevision,0L,schema,metadata,otherWorkflow),"stale","corr"));
        denied(()->forms.update(formId,new UpdateWorkingDraft(first.workingDraft().formVersionId(),formRevision,first.workspaceRevision(),Map.of("schemaContract","UNKNOWN"),metadata,otherWorkflow),"unknown","corr"));
        assertThat(state()).isEqualTo(before);
    }
    @Test void reviewReadIsVisibleOnlyToTheDesignatedIndependentReviewerAndHasNoWrites() {
        Workspace first=branch();PublishReviewRequest assigned=requestReview(first,100);String before=state();
        context(99,"form-publish-review.data",false);denied(()->forms.review(formId));
        context(100,"form-publish-review.data",false);Review reviewer=forms.review(formId);
        assertThat(reviewer.independentCheckerEligible()).isTrue();assertThat(reviewer.draftFormVersionId()).isEqualTo(first.workingDraft().formVersionId());
        assertThat(reviewer.reviewRequest()).isEqualTo(assigned);
        assertThat(state()).isEqualTo(before);
    }
    @Test void reviewRequestReplayQueueAndCurrentProjectionStayBoundToTheAssignedReviewer() {
        Workspace draft=branch();RequestPublishReview input=reviewRequest(draft,100);
        context(99,"form-publish-review-request.action",false);
        PublishReviewRequest first=run(()->publishReviews.request(formId,input,"assign-reviewer","corr"));String after=state();
        PublishReviewRequest replay=run(()->publishReviews.request(formId,input,"assign-reviewer","corr"));
        assertThat(replay).isEqualTo(first);assertThat(state()).isEqualTo(after);
        denied(()->publishReviews.request(formId,input,"stale-reviewer-reassignment","corr"));
        assertThat(state()).isEqualTo(after);
        context(100,"form-publish-review-queue.data",false);
        var queue=publishReviews.queue(50);assertThat(queue.items()).singleElement().satisfies(item->{
            assertThat(item.request()).isEqualTo(first);assertThat(item.formKey()).isEqualTo("ACCESS_EXCEPTION_FORM");
        });
        context(100,"form-publish-review-request.data",false);
        assertThat(publishReviews.latest(formId)).isEqualTo(first);
        context(101,"form-publish-review-queue.data",false);assertThat(publishReviews.queue(50).items()).isEmpty();
    }
    @Test void reviewerRevocationRejectAndDraftSupersessionAreFailClosedAndDurable() {
        Workspace draft=branch();RequestPublishReview input=reviewRequest(draft,100);
        context(99,"form-publish-review-request.action",false);String before=state();
        when(identities.require(42,100)).thenReturn(subject(100,true),subject(100,false));
        denied(()->publishReviews.request(formId,input,"revoked-reviewer","corr"));assertThat(state()).isEqualTo(before);
        when(identities.require(42,100)).thenAnswer(call->subject(100,true));
        PublishReviewRequest assigned=run(()->publishReviews.request(formId,input,"valid-reviewer","corr"));
        context(101,"form-publish-review-reject.action",true);String assignedState=state();
        denied(()->publishReviews.reject(formId,assigned.reviewRequestId(),
                new RejectPublishReview(assigned.formRevision(),assigned.workspaceRevision(),assigned.version(),"A different reviewer cannot reject this request."),"wrong-reviewer","corr"));
        assertThat(state()).isEqualTo(assignedState);
        context(100,"form-publish-review-reject.action",true);
        PublishReviewRequest rejected=run(()->publishReviews.reject(formId,assigned.reviewRequestId(),
                new RejectPublishReview(assigned.formRevision(),assigned.workspaceRevision(),assigned.version(),"The form requires additional compliance controls."),"reject-review","corr"));
        assertThat(rejected.status()).isEqualTo("REJECTED");assertThat(rejected.version()).isEqualTo(1);
        context(100,"form-publish-review.data",false);denied(()->forms.review(formId));

        Workspace next=update(draft);PublishReviewRequest pending=requestReview(next,101);
        context(next.lastEditorUserId(),"form-working-draft-update.action",false);
        Workspace changed=run(()->forms.update(formId,new UpdateWorkingDraft(next.workingDraft().formVersionId(),formRevision,next.workspaceRevision(),
                schema,metadata,otherWorkflow),"supersede-review","corr"));
        context(101,"form-publish-review-request.data",false);
        assertThat(publishReviews.latest(formId)).satisfies(result->{
            assertThat(result.reviewRequestId()).isEqualTo(pending.reviewRequestId());assertThat(result.status()).isEqualTo("SUPERSEDED");
        });
        assertThat(changed.workspaceRevision()).isGreaterThan(next.workspaceRevision());
    }
    @Test void makerAndLatestEditorCannotPublishAndCannotConsumeProof() {
        Workspace first=branch();Workspace edited=update(first);Review review=review(edited,100);
        context(99,"form-reviewed-publish.action",true);String before=state();
        denied(()->forms.publish(formId,body(review),headers("maker"),"corr"));
        assertThat(state()).isEqualTo(before);verify(verifier,never()).verify(anyString(),any());
    }
    @Test void independentPublishedVersionHasOriginalPinsAndActiveLegacyBindingsUnchanged() {
        Workspace edited=update(branch());String bindings=bindings();UUID oldPin=pin();Review review=review(edited,100);
        context(100,"form-reviewed-publish.action",true);
        Workspace published=run(()->forms.publish(formId,body(review),headers("publish-original"),"corr"));
        assertThat(published.workingDraft()).isNull();assertThat(published.published().formVersionId()).isEqualTo(edited.workingDraft().formVersionId());
        assertThat(published.formRevision()).isEqualTo(formRevision+1);assertThat(published.catalogPolicyEligible()).isTrue();
        assertThat(published.published().metadataProvenance()).isEqualTo("PUBLISH_SNAPSHOT");
        assertThat(pin()).isEqualTo(oldPin);assertThat(bindings()).isEqualTo(bindings);
        assertThat(jdbc.queryForObject("SELECT lifecycle_state FROM apr_form_versions WHERE form_version_id=?",String.class,publishedId)).isEqualTo("PUBLISHED");
    }
    @Test void digestSchemaBaseAndHeaderTamperAreZeroWrite() {
        Workspace draft=branch();Review review=review(draft,100);context(100,"form-reviewed-publish.action",true);String before=state();
        denied(()->forms.publish(formId,new PublishReviewed(review.draftFormVersionId(),review.basePublishedVersionId(),review.formRevision(),review.workspaceRevision(),review.schemaSha256(),"b".repeat(64),review.reviewRequest().reviewRequestId(),review.reviewRequest().version(),"Independent reviewer approved this publication."),headers("digest"),"corr"));
        denied(()->forms.publish(formId,new PublishReviewed(review.draftFormVersionId(),UUID.randomUUID(),review.formRevision(),review.workspaceRevision(),review.schemaSha256(),review.reviewContentDigest(),review.reviewRequest().reviewRequestId(),review.reviewRequest().version(),"Independent reviewer approved this publication."),headers("base"),"corr"));
        denied(()->forms.publish(formId,new PublishReviewed(review.draftFormVersionId(),review.basePublishedVersionId(),review.formRevision(),review.workspaceRevision(),review.schemaSha256(),review.reviewContentDigest(),UUID.randomUUID(),review.reviewRequest().version(),"Independent reviewer approved this publication."),headers("request"),"corr"));
        denied(()->forms.publish(formId,new PublishReviewed(review.draftFormVersionId(),review.basePublishedVersionId(),review.formRevision(),review.workspaceRevision(),review.schemaSha256(),review.reviewContentDigest(),review.reviewRequest().reviewRequestId(),review.reviewRequest().version()+1,"Independent reviewer approved this publication."),headers("request-version"),"corr"));
        denied(()->forms.publish(formId,body(review),new ApprovalStepUpHeaders("signed","bad-header",REV,formRevision+1),"corr"));
        assertThat(state()).isEqualTo(before);
    }
    @Test void roleRevokedOnPostCheckRollsBackAllWritesIncludingProofAuditAndReceipt() {
        Workspace draft=branch();Review review=review(draft,100);context(100,"form-reviewed-publish.action",true);String before=state();
        when(identities.require(42,100)).thenReturn(subject(100,true),subject(100,false));
        denied(()->forms.publish(formId,body(review),headers("revoked"),"corr"));assertThat(state()).isEqualTo(before);
    }
    @Test void finalPostCheckRevocationAlsoRollsBackJournalAuditReceiptAndConsumedNonce() {
        Workspace draft=branch();Review review=review(draft,100);context(100,"form-reviewed-publish.action",true);String before=state();
        when(identities.require(42,100)).thenReturn(subject(100,true),subject(100,true),subject(100,false));
        denied(()->forms.publish(formId,body(review),headers("late-revoke"),"corr"));assertThat(state()).isEqualTo(before);
    }
    @Test void retireOnlyChangesCatalogAndRestoreChecksCurrentPolicy() {
        Workspace draft=branch();String before=legacyState();context(99,"form-retire.action",false);
        Workspace retired=run(()->forms.retire(formId,new AvailabilityChange(formRevision,draft.workspaceRevision()),"retire","corr"));
        assertThat(retired.catalogAvailability()).isEqualTo(CatalogAvailability.RETIRED);assertThat(retired.catalogPolicyEligible()).isFalse();
        assertThat(legacyState()).isEqualTo(before);context(99,"form-reinstate.action",false);
        jdbc.update("UPDATE apr_form_categories SET lifecycle_state='INACTIVE' WHERE category_id=?",metadata.categoryId());String disabled=state();
        denied(()->forms.reinstate(formId,new AvailabilityChange(formRevision,retired.workspaceRevision()),"restore-denied","corr"));assertThat(state()).isEqualTo(disabled);
        jdbc.update("UPDATE apr_form_categories SET lifecycle_state='ACTIVE' WHERE category_id=?",metadata.categoryId());
        Workspace restored=run(()->forms.reinstate(formId,new AvailabilityChange(formRevision,retired.workspaceRevision()),"restore","corr"));
        assertThat(restored.published().formVersionId()).isEqualTo(publishedId);assertThat(restored.catalogPolicyEligible()).isTrue();
    }
    @Test void catalogRetiredDoesNotRetargetOrDeleteOriginalPinnedRequest() {
        Workspace draft=branch();context(99,"form-retire.action",false);
        run(()->forms.retire(formId,new AvailabilityChange(formRevision,draft.workspaceRevision()),"retire-pin","corr"));
        assertThat(pin()).isEqualTo(publishedId);assertThat(jdbc.queryForObject("SELECT status FROM apr_requests WHERE request_id=?",String.class,oldRequest)).isEqualTo("DRAFT");
    }
    @Test void historicalMetadataIsUnknownRatherThanInventedAsPublishTime() {
        context(99,"form-version-detail.data",false);Version result=forms.version(formId,publishedId);
        assertThat(result.metadata()).isEmpty();assertThat(result.metadataProvenance()).isEqualTo("UNRECORDED_HISTORICAL_METADATA");assertThat(result.capturedAt()).isNull();
    }
    @Test void tenantResourceSetOversightWrongRouteProviderAndMissingSourcePermissionDenyZeroWrite() {
        String before=state();context(99,"form-version-branch.action",false);
        ApprovalManagementScopeContext.set("scope","RS_OTHER");denied(()->forms.branch(formId,publishedId,new Branch(formRevision,null),"other-scope","corr"));
        context(99,"form-working-draft.data",false);denied(()->forms.branch(formId,publishedId,new Branch(formRevision,null),"wrong-route","corr"));
        context(99,"form-version-branch.action",false);when(identities.require(42,99)).thenReturn(subject(99,false));
        denied(()->forms.branch(formId,publishedId,new Branch(formRevision,null),"no-permission","corr"));assertThat(state()).isEqualTo(before);
    }
    @Test void publishedSchemaMaterialAndLineageCannotBeUpdatedDeletedOrReidentified() {
        Workspace draft=branch();Review review=review(draft,100);context(100,"form-reviewed-publish.action",true);
        Workspace published=run(()->forms.publish(formId,body(review),headers("immutable"),"corr"));UUID id=published.published().formVersionId();String before=state();
        assertThatThrownBy(()->jdbc.update("UPDATE apr_form_version_material SET metadata_payload='{}' WHERE form_version_id=?",id)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM apr_form_version_material WHERE form_version_id=?",id)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE apr_form_versions SET form_id=? WHERE form_version_id=?",UUID.randomUUID(),id)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM apr_form_version_lineage WHERE form_version_id=?",id)).isInstanceOf(RuntimeException.class);
        assertThat(state()).isEqualTo(before);
    }
    @Test void currentCategoryOrWorkflowAdvanceInvalidatesReviewBeforeAnyWrite() {
        Workspace draft=branch();Review review=review(draft,100);context(100,"form-reviewed-publish.action",true);
        jdbc.update("UPDATE apr_form_categories SET version=version+1 WHERE category_id=?",metadata.categoryId());String before=state();
        denied(()->forms.publish(formId,body(review),headers("changed-policy"),"corr"));assertThat(state()).isEqualTo(before);
    }
    @Test void missingOriginalIdempotencyAndForeignSourceVersionCannotCreateDraft() {
        context(99,"form-version-branch.action",false);String before=state();
        denied(()->forms.branch(formId,publishedId,new Branch(formRevision,null),null,"corr"));
        denied(()->forms.branch(formId,UUID.randomUUID(),new Branch(formRevision,null),"bad-source","corr"));assertThat(state()).isEqualTo(before);
    }
    @Test void actionCannotBorrowDataReadOnlyOrDifferentCapabilityProfile() {
        String before=state();String leaf="form-version-branch.action";
        for(String kind:List.of("DATA","ACTION")) {
            context(99,leaf,false);String route="route.approvals.admin."+leaf;
            ApprovalPilotAuthorizationContext.set(List.of(new ApprovalPilotPepRegistry.RouteAuthority(route,kind,"full-management",false,
                    Set.of(),"approvals.design.read",null,null,false,null,null)));
            denied(()->forms.branch(formId,publishedId,new Branch(formRevision,null),"bad-profile-"+kind,"corr"));
        }
        context(99,leaf,false);
        ApprovalPilotAuthorizationContext.set(List.of(new ApprovalPilotPepRegistry.RouteAuthority("route.approvals.admin."+leaf,"ACTION","full-management",true,
                Set.of(),"approvals.design.update",null,null,false,null,null)));
        denied(()->forms.branch(formId,publishedId,new Branch(formRevision,null),"readonly-profile","corr"));assertThat(state()).isEqualTo(before);
    }
    @Test void freshProviderIdentityCannotBorrowTenantManagementPermissions() {
        context(99,"form-version-branch.action",false);String before=state();
        when(identities.require(42,99)).thenReturn(new ApprovalIdentityDirectory.Subject(42L,99L,null,null,"Provider",null,null,"ACTIVE",
                List.of("PROVIDER_SUPPORT"),List.copyOf(PERMISSIONS)));
        denied(()->forms.branch(formId,publishedId,new Branch(formRevision,null),"provider-current","corr"));assertThat(state()).isEqualTo(before);
    }
    @Test void observedWorkspaceRevisionCannotSurviveChangedDatabaseHead() {
        context(99,"form-working-draft.data",false);var observed=repo.head(ApprovalRequestContext.require(),formId,false);
        jdbc.update("UPDATE apr_forms SET version=version+1 WHERE form_id=?",formId);
        assertThatThrownBy(()->repo.unchanged(ApprovalRequestContext.require(),observed)).isInstanceOf(BaseException.class);
    }
    @Test void initialUnpublishedDraftAdoptsVersionOwnedMaterialAndPublishesWithIndependentChecker() {
        UUID initial=initialDraft();context(99,"form-working-draft-update.action",false);
        Workspace adopted=run(()->forms.update(formId,new UpdateWorkingDraft(initial,0L,null,schema,metadata,otherWorkflow),"initial-update","corr"));
        assertThat(adopted.published()).isNull();assertThat(adopted.workingDraft().formVersionId()).isEqualTo(initial);
        assertThat(adopted.workspaceRevision()).isEqualTo(1);assertThat(adopted.catalogPolicyEligible()).isFalse();
        Review review=review(adopted,100);assertThat(review.makerUserId()).isEqualTo(99L);
        assertThat(review.basePublishedVersionId()).isNull();assertThat(review.independentCheckerEligible()).isTrue();
        context(100,"form-reviewed-publish.action",true);
        Workspace published=run(()->forms.publish(formId,body(review),headers("initial-publish"),"corr"));
        assertThat(published.published().formVersionId()).isEqualTo(initial);assertThat(published.published().sourceVersionId()).isNull();
        assertThat(published.catalogPolicyEligible()).isTrue();assertThat(published.workingDraft()).isNull();
    }
    @Test void initialDraftCannotSkipCasOrAdoptAlternateUuid() {
        UUID initial=initialDraft();context(99,"form-working-draft-update.action",false);String before=state();
        denied(()->forms.update(formId,new UpdateWorkingDraft(initial,0L,0L,schema,metadata,otherWorkflow),"initial-wrong-cas","corr"));
        denied(()->forms.update(formId,new UpdateWorkingDraft(UUID.randomUUID(),0L,null,schema,metadata,otherWorkflow),"initial-wrong-id","corr"));
        assertThat(state()).isEqualTo(before);
    }
    @Test void distinctDraftMakerAndLatestEditorAreBothExcludedFromReviewedPublication() {
        Workspace draft=branch();context(100,"form-working-draft-update.action",false);
        Workspace edited=run(()->forms.update(formId,new UpdateWorkingDraft(draft.workingDraft().formVersionId(),formRevision,draft.workspaceRevision(),
                schema,metadata,otherWorkflow),"different-editor","corr"));
        Review review=review(edited,101);assertThat(review.makerUserId()).isEqualTo(99L);assertThat(review.lastEditorUserId()).isEqualTo(100L);
        String before=state();
        for(long id:List.of(99L,100L)) {
            context(id,"form-reviewed-publish.action",true);
            denied(()->forms.publish(formId,body(review),headers("excluded-"+id),"corr"));
        }
        assertThat(state()).isEqualTo(before);verify(verifier,never()).verify(anyString(),any());
    }
    @Test void managedLegacyCounterTwoPublishesImmutableMaterialWithoutChangingSchemaCounter() {
        Workspace published=publishManagedLegacy();UUID id=published.published().formVersionId();String before=state();
        assertThat(published.published().schema()).containsEntry("schemaVersion",2).doesNotContainKey("schemaContract");
        assertThatThrownBy(()->jdbc.update("UPDATE apr_form_versions SET schema_sha256=? WHERE form_version_id=?","a".repeat(64),id))
                .hasMessageContaining("New managed published form version is immutable");
        assertThatThrownBy(()->jdbc.update("DELETE FROM apr_form_versions WHERE form_version_id=?",id))
                .hasMessageContaining("New managed published form version is immutable");
        assertThat(state()).isEqualTo(before);
    }
    @Test void actualLegacyCommandsCannotModifyOrRepublishManagedLivePublishedForm() {
        Workspace published=publishManagedLegacy();context(99,"form-working-draft-update.action",false);String before=state();
        var commands=new ApprovalCommandRepository(repoJdbc(),new ObjectMapper().findAndRegisterModules());
        var fields=List.of(new ApprovalDtos.FormFieldInput("summary","Summary","Summary",null,null,"TEXTAREA",true,List.of()));
        denied(()->{commands.updateFormDraft(ApprovalRequestContext.require(),formId,new ApprovalDtos.UpdateFormDraftRequest(metadata.categoryId(),
                "Bypass","Bypass",metadata.descriptionKo(),metadata.descriptionEn(),metadata.ownerGroupRef(),otherWorkflow,fields,published.formRevision()));return null;});
        denied(()->{commands.publishForm(ApprovalRequestContext.require(),formId,published.formRevision());return null;});
        assertThat(state()).isEqualTo(before);
    }
    @Test void actualLegacyCommandsCannotBypassInitialManagedDraftContract() {
        UUID initial=initialDraft();context(99,"form-working-draft-update.action",false);
        Workspace draft=run(()->forms.update(formId,new UpdateWorkingDraft(initial,0L,null,schema,metadata,otherWorkflow),"managed-initial","corr"));
        var commands=new ApprovalCommandRepository(repoJdbc(),new ObjectMapper().findAndRegisterModules());String before=state();
        var fields=List.of(new ApprovalDtos.FormFieldInput("summary","Summary","Summary",null,null,"TEXTAREA",true,List.of()));
        denied(()->{commands.updateFormDraft(ApprovalRequestContext.require(),formId,new ApprovalDtos.UpdateFormDraftRequest(metadata.categoryId(),
                "Bypass","Bypass",metadata.descriptionKo(),metadata.descriptionEn(),metadata.ownerGroupRef(),otherWorkflow,fields,draft.formRevision()));return null;});
        assertThat(state()).isEqualTo(before);
    }
    @Test void unmanagedDraftKeepsActualLegacyUpdateAndIndependentPublishPath() {
        UUID initial=initialDraft();context(99,"form-working-draft-update.action",false);
        var commands=new ApprovalCommandRepository(repoJdbc(),new ObjectMapper().findAndRegisterModules());
        var fields=List.of(new ApprovalDtos.FormFieldInput("summary","Summary","Summary",null,null,"TEXTAREA",true,List.of()));
        run(()->{commands.updateFormDraft(ApprovalRequestContext.require(),formId,new ApprovalDtos.UpdateFormDraftRequest(metadata.categoryId(),
                "Legacy","Legacy",metadata.descriptionKo(),metadata.descriptionEn(),metadata.ownerGroupRef(),otherWorkflow,fields,0L));return null;});
        assertThat(jdbc.queryForObject("SELECT version FROM apr_forms WHERE form_id=?",Long.class,formId)).isEqualTo(1L);
        context(100,"form-reviewed-publish.action",true);
        run(()->{commands.publishForm(ApprovalRequestContext.require(),formId,1L);return null;});
        assertThat(jdbc.queryForObject("SELECT lifecycle_state FROM apr_form_versions WHERE form_version_id=?",String.class,initial)).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT lifecycle_state FROM apr_forms WHERE form_id=?",String.class,formId)).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_form_workspaces WHERE form_id=?",Integer.class,formId)).isZero();
        assertThat(jdbc.queryForObject("SELECT schema_payload->>'schemaVersion' FROM apr_form_versions WHERE form_version_id=?",String.class,initial)).isEqualTo("1");
    }
    @Test void legacyCapturePreservesOriginalHashAndLabelsActualCaptureTime() {
        Workspace result=branch();var published=result.published();
        assertThat(published.metadataProvenance()).isEqualTo("LEGACY_CAPTURE_TIME");
        assertThat(published.capturedAt()).isNotNull();assertThat(published.capturedBy()).isEqualTo(99L);
        assertThat(published.schemaSha256()).isEqualTo(originalLegacyHash);assertThat(published.schema()).doesNotContainKey("schemaContract");
        assertThat(jdbc.queryForObject("SELECT schema_sha256 FROM apr_form_versions WHERE form_version_id=?",String.class,publishedId)).isEqualTo(originalLegacyHash);
        String before=state();
        assertThatThrownBy(()->jdbc.update("UPDATE apr_form_versions SET schema_sha256=? WHERE form_version_id=?","a".repeat(64),publishedId))
                .hasMessageContaining("New managed published form version is immutable");
        assertThat(state()).isEqualTo(before);
    }
    @Test void legacyGuardCannotReadOrWriteAnInitialDraftOutsideCurrentResourceSet() {
        initialDraft();context(99,"form-working-draft-update.action",false);String before=state();
        ApprovalManagementScopeContext.set("scope","RS_OTHER");
        var commands=new ApprovalCommandRepository(repoJdbc(),new ObjectMapper().findAndRegisterModules());
        assertThatThrownBy(()->run(()->{commands.publishForm(ApprovalRequestContext.require(),formId,0L);return null;}))
                .isInstanceOfSatisfying(BaseException.class,error->assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(state()).isEqualTo(before);
    }
    @Test void legacyWriteAndInitialAdoptionSerializeOnTheSameFormLockAndCas() throws Exception {
        UUID initial=initialDraft();var locked=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);var attempting=new java.util.concurrent.CountDownLatch(1);
        var commands=new ApprovalCommandRepository(repoJdbc(),new ObjectMapper().findAndRegisterModules());
        var fields=List.of(new ApprovalDtos.FormFieldInput("summary","Summary","Summary",null,null,"TEXTAREA",true,List.of()));
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var legacy=workers.submit(()->{context(99,"form-working-draft-update.action",false);
                try { return run(()->{commands.updateFormDraft(ApprovalRequestContext.require(),formId,new ApprovalDtos.UpdateFormDraftRequest(metadata.categoryId(),
                        "Legacy winner","Legacy winner",metadata.descriptionKo(),metadata.descriptionEn(),metadata.ownerGroupRef(),otherWorkflow,fields,0L));
                    locked.countDown();await(release);return true;}); } finally { clearContexts(); }
            });
            try {
                assertThat(locked.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var adoption=workers.submit(()->{context(99,"form-working-draft-update.action",false);attempting.countDown();
                    try { return run(()->forms.update(formId,new UpdateWorkingDraft(initial,0L,null,schema,metadata,otherWorkflow),"racing-adoption","corr")); }
                    finally { clearContexts(); }
                });
                assertThat(attempting.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(adoption.isDone()).isFalse();release.countDown();
                assertThat(legacy.get(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->adoption.get(10,java.util.concurrent.TimeUnit.SECONDS))
                        .hasCauseInstanceOf(BaseException.class);
            } finally { release.countDown(); }
        }
        assertThat(jdbc.queryForObject("SELECT version FROM apr_forms WHERE form_id=?",Long.class,formId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_form_workspaces WHERE form_id=?",Integer.class,formId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_form_lifecycle_events WHERE form_id=?",Integer.class,formId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_form_command_receipts WHERE form_id=?",Integer.class,formId)).isZero();
    }
    @Test void initialAdoptionFirstPreventsAWaitingLegacyWriteFromUsingItsOldJoinSnapshot() throws Exception {
        UUID initial=initialDraft();var adopted=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var committed=new java.util.concurrent.atomic.AtomicReference<String>();
        var commands=new ApprovalCommandRepository(repoJdbc(),new ObjectMapper().findAndRegisterModules());
        var fields=List.of(new ApprovalDtos.FormFieldInput("summary","Summary","Summary",null,null,"TEXTAREA",true,List.of()));
        try(var workers=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var adoption=workers.submit(()->{context(99,"form-working-draft-update.action",false);
                try { return run(()->{var result=forms.update(formId,new UpdateWorkingDraft(initial,0L,null,schema,metadata,otherWorkflow),"adoption-first","corr");
                    committed.set(state());adopted.countDown();await(release);return result;}); } finally { clearContexts(); }
            });
            try {
                assertThat(adopted.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var legacy=workers.submit(()->{context(99,"form-working-draft-update.action",false);
                    try { return run(()->{jdbc.execute("SET LOCAL application_name='apr12-legacy-adoption-follower'");
                        commands.updateFormDraft(ApprovalRequestContext.require(),formId,new ApprovalDtos.UpdateFormDraftRequest(metadata.categoryId(),
                            "Must not overwrite","Must not overwrite",metadata.descriptionKo(),metadata.descriptionEn(),metadata.ownerGroupRef(),otherWorkflow,fields,0L));return true;}); }
                    finally { clearContexts(); }
                });
                boolean blocked=false;long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while(System.nanoTime()<deadline&&!blocked) {
                    blocked=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE application_name='apr12-legacy-adoption-follower' AND wait_event_type='Lock')",Boolean.class));
                    if(!blocked) Thread.sleep(20);
                }
                assertThat(blocked).isTrue();release.countDown();assertThat(adoption.get(10,java.util.concurrent.TimeUnit.SECONDS)).isNotNull();
                assertThatThrownBy(()->legacy.get(10,java.util.concurrent.TimeUnit.SECONDS)).hasCauseInstanceOf(BaseException.class);
            } finally { release.countDown(); }
        }
        assertThat(state()).isEqualTo(committed.get());
        assertThat(jdbc.queryForObject("SELECT version FROM apr_forms WHERE form_id=?",Long.class,formId)).isZero();
    }
    private void await(java.util.concurrent.CountDownLatch latch) {
        try { if(!latch.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for the test transaction."); }
        catch(InterruptedException error) { Thread.currentThread().interrupt();throw new IllegalStateException(error); }
    }
    private void clearContexts() {
        ApprovalRequestContext.clear();ApprovalManagementScopeContext.clear();ApprovalDecisionRevisionContext.clear();ApprovalPilotAuthorizationContext.clear();
    }
    private NamedParameterJdbcTemplate repoJdbc() { return new NamedParameterJdbcTemplate(jdbc.getDataSource()); }
    private Workspace publishManagedLegacy() {
        UUID initial=initialDraft();context(99,"form-working-draft-update.action",false);
        var legacy=Map.<String,Object>of("schemaVersion",2,"fields",List.of(Map.of("key","summary","labelKo","Summary","labelEn","Summary","type","TEXTAREA","required",true)));
        Workspace draft=run(()->forms.update(formId,new UpdateWorkingDraft(initial,0L,null,legacy,metadata,otherWorkflow),"legacy-initial-update","corr"));
        Review review=review(draft,100);context(100,"form-reviewed-publish.action",true);
        return run(()->forms.publish(formId,body(review),headers("legacy-reviewed-publish"),"corr"));
    }
    private UUID initialDraft() {
        UUID id=UUID.randomUUID(),version=UUID.randomUUID();var compiled=new ApprovalFormSchemaV2Compiler().compile(schema);
        jdbc.update("INSERT INTO apr_forms(form_id,tenant_id,form_key,category_id,name_ko,name_en,description_ko,description_en,owner_group_ref,form_kind,"
                +"management_resource_set_key,lifecycle_state,current_version,created_by,updated_by) SELECT ?,42,?,category_id,name_ko,name_en,description_ko,description_en,"
                +"owner_group_ref,form_kind,management_resource_set_key,'DRAFT',1,99,99 FROM apr_forms WHERE form_id=?",id,"APR12_INITIAL",formId);
        jdbc.update("INSERT INTO apr_form_versions(form_version_id,tenant_id,form_id,version_number,lifecycle_state,schema_payload,schema_sha256,created_by) "
                +"VALUES (?,42,?,1,'DRAFT',?::jsonb,?,99)",version,id,compiled.canonicalJson(),compiled.sha256());
        formId=id;formRevision=0;return version;
    }
    private Workspace branch() { context(99,"form-version-branch.action",false);return run(()->forms.branch(formId,publishedId,new Branch(formRevision,null),"branch-original","corr")); }
    private Workspace update(Workspace first) {
        context(99,"form-working-draft-update.action",false);
        var changed=new MetadataInput(metadata.categoryId(),"Changed",metadata.nameEn(),metadata.descriptionKo(),metadata.descriptionEn(),metadata.ownerGroupRef(),metadata.formKind());
        return run(()->forms.update(formId,new UpdateWorkingDraft(first.workingDraft().formVersionId(),formRevision,first.workspaceRevision(),schema,changed,otherWorkflow),"update","corr"));
    }
    private PublishReviewRequest requestReview(Workspace workspace,long reviewer) {
        context(workspace.lastEditorUserId(),"form-publish-review-request.action",false);
        return run(()->publishReviews.request(formId,reviewRequest(workspace,reviewer),
                "review-request-"+reviewer+"-"+workspace.workspaceRevision(),"corr"));
    }
    private RequestPublishReview reviewRequest(Workspace workspace,long reviewer) {
        var current=jdbc.query("SELECT review_request_id,request_version FROM apr_form_publish_review_requests WHERE tenant_id=42 AND form_id=? ORDER BY requested_at DESC,review_request_id DESC LIMIT 1",
                (row,number)->Map.entry(row.getObject(1,UUID.class),row.getLong(2)),formId);
        return new RequestPublishReview(workspace.workingDraft().formVersionId(),
                workspace.published()==null?null:workspace.published().formVersionId(),workspace.formRevision(),
                workspace.workspaceRevision(),workspace.workingDraft().schemaSha256(),reviewer,person(reviewer),
                current.isEmpty()?null:current.getFirst().getKey(),current.isEmpty()?null:current.getFirst().getValue(),
                "Please independently review this exact form version.");
    }
    private Review review(Workspace workspace,long actor) {
        requestReview(workspace,actor);context(actor,"form-publish-review.data",false);return forms.review(formId);
    }
    private PublishReviewed body(Review r) { return new PublishReviewed(r.draftFormVersionId(),r.basePublishedVersionId(),r.formRevision(),r.workspaceRevision(),r.schemaSha256(),r.reviewContentDigest(),r.reviewRequest().reviewRequestId(),r.reviewRequest().version(),"Independent reviewer approved this publication."); }
    private ApprovalStepUpHeaders headers(String key) { return new ApprovalStepUpHeaders("signed-test-proof",key,REV,formRevision); }
    private void context(long actor,String leaf,boolean high) {
        ApprovalRequestContext.set(actor,42L,null,"Designer",Set.of("APPROVAL_OPERATOR"),PERMISSIONS);
        ApprovalManagementScopeContext.set("scope","RS_APPROVALS");String route="route.approvals.admin."+leaf;
        ApprovalDecisionRevisionContext.set(REV,OffsetDateTime.now().plusMinutes(5),"management","scope",route,"110");
        ApprovalPilotAuthorizationContext.set(List.of(new ApprovalPilotPepRegistry.RouteAuthority(route,leaf.endsWith(".action")?"ACTION":"DATA","full-management",!leaf.endsWith(".action"),
                Set.of(),high?"approvals.design.publish":leaf.endsWith(".action")?"approvals.design.update":"approvals.design.read",
                high?"STEPUP-MGMT-HIGH-V1":null,null,high,null,null)));
    }
    private ApprovalIdentityDirectory.Subject subject(long id,boolean allowed) {
        return new ApprovalIdentityDirectory.Subject(42L,id,new UUID(1,id),person(id),"Designer "+id,"designer"+id+"@example.test",null,"ACTIVE",List.of("APPROVAL_OPERATOR"),allowed?List.copyOf(PERMISSIONS):List.of());
    }
    private UUID person(long id) { return new UUID(2,id); }
    private Map<String,Object> field(String key,String type) { return Map.of("key",key,"type",type,"labelKo","Summary","labelEn","Summary"); }
    private <T> T run(Supplier<T> body) { return tx.execute(ignored->body.get()); }
    private void denied(Supplier<?> body) { assertThatThrownBy(()->run(body)).isInstanceOf(BaseException.class); }
    private UUID pin() { return jdbc.queryForObject("SELECT form_version_id FROM apr_requests WHERE request_id=?",UUID.class,oldRequest); }
    private String bindings() { return jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(b) ORDER BY binding_id)::text FROM apr_form_workflow_bindings b",String.class); }
    private String legacyState() {
        return jdbc.queryForObject("SELECT jsonb_build_object('forms',(SELECT jsonb_agg(to_jsonb(f)ORDER BY form_id) FROM apr_forms f),"
                +"'pins',(SELECT jsonb_agg(to_jsonb(r)ORDER BY request_id) FROM apr_requests r),'bindings',(SELECT jsonb_agg(to_jsonb(b)ORDER BY binding_id) FROM apr_form_workflow_bindings b))::text",String.class);
    }
    private String state() {
        var tables=List.of("apr_forms","apr_form_versions","apr_requests","apr_form_workspaces","apr_form_version_material","apr_form_version_lineage",
                "apr_form_publish_review_requests","apr_form_lifecycle_events","apr_form_command_receipts","sys_audit_outbox","apr_high_risk_idempotency_ledger","apr_step_up_replay_ledger");
        return tables.stream().map(table->jdbc.queryForObject("SELECT COALESCE(jsonb_agg(row ORDER BY row::text),'[]'::jsonb)::text FROM (SELECT to_jsonb(t) AS row FROM "+table+" t) x",String.class))
                .collect(java.util.stream.Collectors.joining("\n"));
    }
}
