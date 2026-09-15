package com.dwp.services.approval.forms;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.forms.ApprovalFormLifecycleDtos.*;
import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApprovalFormLifecycleFacade {
    private final ApprovalFormWorkspaceRepository repo;
    private final ApprovalFormLifecycleStore store;
    private final ApprovalFormLifecycleAuthority authority;
    private final ApprovalFormManagedPublicationProof proof;
    private final ApprovalFormVersionDiff diff;
    private final ApprovalFormPublishReviewRepository reviewRequests;
    private final ApprovalFormPublishReviewService publishReviews;
    private final AuditOutboxRecorder audit;
    public ApprovalFormLifecycleFacade(ApprovalFormWorkspaceRepository repo,ApprovalFormLifecycleStore store,
            ApprovalFormLifecycleAuthority authority,ApprovalFormManagedPublicationProof proof,ApprovalFormVersionDiff diff,
            ApprovalFormPublishReviewRepository reviewRequests,ApprovalFormPublishReviewService publishReviews,
            AuditOutboxRecorder audit) {
        this.repo=repo;this.store=store;this.authority=authority;this.proof=proof;this.diff=diff;
        this.reviewRequests=reviewRequests;this.publishReviews=publishReviews;this.audit=audit;
    }
    @Transactional(readOnly=true) public Workspace workingDraft(UUID form) {
        return read("form-working-draft.data",form,()->repo.read(actor(),form));
    }
    @Transactional(readOnly=true) public History history(UUID form,int size) {
        return read("form-version-history.data",form,()->repo.history(actor(),form,size));
    }
    @Transactional(readOnly=true) public Version version(UUID form,UUID version) {
        return read("form-version-detail.data",form,()->repo.version(actor(),form,version));
    }
    @Transactional(readOnly=true) public Diff diff(UUID form,UUID from,UUID to) {
        return read("form-version-diff.data",form,()->diff.compare(repo.version(actor(),form,from),repo.version(actor(),form,to)));
    }
    private <T> T read(String leaf,UUID form,Supplier<T> query) {
        var window=authority.require(leaf,"VIEW");var head=repo.head(actor(),form,false);
        T result=query.get();repo.unchanged(actor(),head);authority.unchanged(window,leaf,"VIEW");return result;
    }
    @Transactional public Workspace branch(UUID form,UUID source,Branch body,String key,String correlation) {
        return mutate("form-version-branch.action",form,Map.of("sourceVersionId",source,"request",body),key,correlation,"BRANCH",()->{
            var head=repo.head(actor(),form,true);store.expected(head,body.expectedFormRevision(),body.expectedWorkspaceRevision());
            store.branch(actor(),head,repo.version(actor(),form,source));
        });
    }
    @Transactional public Workspace update(UUID form,UpdateWorkingDraft body,String key,String correlation) {
        return mutate("form-working-draft-update.action",form,body,key,correlation,"UPDATE_DRAFT",()->{
            var head=repo.head(actor(),form,true);store.expected(head,body.expectedFormRevision(),body.expectedWorkspaceRevision());
            store.update(actor(),head,body);reviewRequests.supersedePending(actor(),form);
        });
    }
    @Transactional public Workspace retire(UUID form,AvailabilityChange body,String key,String correlation) {
        return availability(form,body,key,correlation,CatalogAvailability.RETIRED);
    }
    @Transactional public Workspace reinstate(UUID form,AvailabilityChange body,String key,String correlation) {
        return availability(form,body,key,correlation,CatalogAvailability.ACTIVE);
    }
    private Workspace availability(UUID form,AvailabilityChange body,String key,String correlation,CatalogAvailability target) {
        String leaf=target==CatalogAvailability.RETIRED?"form-retire.action":"form-reinstate.action";
        return mutate(leaf,form,body,key,correlation,target==CatalogAvailability.RETIRED?"RETIRE":"REINSTATE",()->{
            var head=repo.head(actor(),form,true);store.expected(head,body.expectedFormRevision(),body.expectedWorkspaceRevision());store.availability(actor(),head,target);
        });
    }
    @Transactional(readOnly=true) public Review review(UUID form) {
        String leaf="form-publish-review.data";var window=authority.require(leaf,"VIEW","PUBLISH");
        var head=repo.head(actor(),form,false);if(head.workspaceRevision()==null||head.draft()==null) throw ApprovalFormWorkspaceRepository.conflict();
        var draft=repo.version(actor(),form,head.draft());
        var current=repo.policy(actor(),form,store.metadata(draft.metadata()),UUID.fromString((String)draft.route().get("workflowId")),false);
        if(!current.metadata().equals(draft.metadata())||!current.route().equals(draft.route())) throw ApprovalFormWorkspaceRepository.conflict();
        var latest=reviewRequests.latest(actor(),form);
        if(latest==null) throw new BaseException(ErrorCode.NOT_FOUND);
        var request=reviewRequests.pending(actor(),form,latest.reviewRequestId(),false);
        publishReviews.requireCurrent(actor(),head,request,request.version());
        var result=new Review(form,head.revision(),head.workspaceRevision(),draft.formVersionId(),head.published(),draft.schemaSha256(),
                ApprovalFormReviewMaterial.digest(repo,head,draft),draft.createdBy(),head.editor(),draft.createdBy()!=null&&head.editor()!=null
                    &&!actor().userId().equals(draft.createdBy())&&!actor().userId().equals(head.editor()),window.evidence().validUntil(),request);
        repo.unchanged(actor(),head);authority.unchanged(window,leaf,"VIEW","PUBLISH");return result;
    }
    @Transactional public Workspace publish(UUID form,PublishReviewed body,ApprovalStepUpHeaders headers,String correlation) {
        String leaf="form-reviewed-publish.action";var window=authority.require(leaf,"VIEW","PUBLISH");
        String key=headers==null?null:headers.idempotencyKey();validateKey(key);
        var head=repo.head(actor(),form,true);
        String requestDigest=requestDigest(form,leaf,body);
        var cached=store.prior(actor(),form,window.evidence().contextScopeKey(),leaf,key,requestDigest);
        if(cached!=null) { authority.unchanged(window,leaf,"VIEW","PUBLISH");return repo.codec.project(cached,Workspace.class); }
        store.expected(head,body.expectedFormRevision(),body.expectedWorkspaceRevision());
        if(!Objects.equals(head.draft(),body.draftFormVersionId())||!Objects.equals(head.published(),body.basePublishedVersionId())) throw ApprovalFormWorkspaceRepository.conflict();
        var draft=repo.version(actor(),form,head.draft());
        var request=reviewRequests.pending(actor(),form,body.reviewRequestId(),true);
        publishReviews.requireCurrent(actor(),head,request,body.expectedReviewRequestVersion());
        if(draft.createdBy()==null||head.editor()==null) throw ApprovalFormWorkspaceRepository.unavailable();
        if(actor().userId().equals(draft.createdBy())||actor().userId().equals(head.editor())) throw new BaseException(ErrorCode.SOD_CONFLICT);
        if(!draft.schemaSha256().equals(body.schemaSha256())||!ApprovalFormReviewMaterial.digest(repo,head,draft).equals(body.reviewContentDigest())
                ||!request.reviewContentDigest().equals(body.reviewContentDigest())
                ||body.reviewComment()==null||!body.reviewComment().equals(body.reviewComment().strip())
                ||body.reviewComment().length()<10||body.reviewComment().length()>1000
                ||body.reviewComment().codePoints().anyMatch(Character::isISOControl)) throw ApprovalFormWorkspaceRepository.conflict();
        var permit=proof.begin(window,form,body,headers);
        store.publish(actor(),head,draft);
        reviewRequests.transition(actor(),request,"PUBLISHED",body.reviewComment());
        authority.unchanged(window,leaf,"VIEW","PUBLISH");proof.complete(permit);
        return finish(form,leaf,key,requestDigest,"PUBLISH",correlation,window);
    }
    private Workspace mutate(String leaf,UUID form,Object body,String key,String correlation,String action,Runnable command) {
        validateKey(key);var window=authority.require(leaf,"VIEW","UPDATE");repo.head(actor(),form,true);
        String digest=requestDigest(form,leaf,body);
        var cached=store.prior(actor(),form,window.evidence().contextScopeKey(),leaf,key,digest);
        if(cached!=null) { authority.unchanged(window,leaf,"VIEW","UPDATE");return repo.codec.project(cached,Workspace.class); }
        command.run();authority.unchanged(window,leaf,"VIEW","UPDATE");return finish(form,leaf,key,digest,action,correlation,window);
    }
    private Workspace finish(UUID form,String leaf,String key,String digest,String action,String correlation,ApprovalFormLifecycleAuthority.Window window) {
        var outcome=repo.read(actor(),form);
        UUID version=outcome.workingDraft()==null?outcome.published().formVersionId():outcome.workingDraft().formVersionId();
        var event=Map.<String,Object>of("idempotencyKey",key,"requestDigest",digest,"routeContractKey",window.evidence().routeContractKey());
        store.journal(actor(),form,action,version,event);
        audit.record(AuditEvent.builder().tenantId(actor().tenantId()).category("ADMIN_CHANGE").action("approval.form.workspace."+action.toLowerCase(java.util.Locale.ROOT))
                .outcome("SUCCESS").severity("INFO").actorType("USER").actorId(actor().userId().toString()).actorRoles(List.copyOf(actor().roles()))
                .sourceService("dwp-approval-server").sourceModule("approval-forms").targetType("APPROVAL_FORM").targetId(form.toString())
                .correlationId(correlation).afterState(event).retentionClass("EXTENDED").build());
        store.receipt(actor(),form,window.evidence().contextScopeKey(),leaf,key,digest,outcome);
        authority.unchanged(window,leaf,"VIEW",action.equals("PUBLISH")?"PUBLISH":"UPDATE");return outcome;
    }
    private String requestDigest(UUID form,String leaf,Object body) { return repo.codec.sha(repo.codec.json(Map.of("formId",form.toString(),"route",leaf,"body",body))); }
    private void validateKey(String key) {
        if(key==null||key.isBlank()||!key.equals(key.trim())||key.length()>200) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,"The original idempotency key is required.");
    }
    private com.dwp.services.approval.security.ApprovalRequestContext.Actor actor() { return com.dwp.services.approval.security.ApprovalRequestContext.require(); }
}
