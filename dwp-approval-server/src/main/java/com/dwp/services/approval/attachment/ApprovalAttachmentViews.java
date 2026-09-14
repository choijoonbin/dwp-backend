package com.dwp.services.approval.attachment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.document.*;
import com.dwp.services.approval.security.ApprovalRequestContext;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import static com.dwp.services.approval.attachment.ApprovalAttachmentDtos.*;
import static com.dwp.services.approval.document.ApprovalDocumentDtos.OwnerType;
import static com.dwp.services.approval.document.ApprovalDocumentOwnerRepository.params;

@Service
public class ApprovalAttachmentViews {
    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalDocumentAuthority authority;
    private final ApprovalDocumentOwnerRepository owners;
    private final ApprovalDocumentRepository documents;
    private final ApprovalAttachmentPolicyRepository policies;
    private final ApprovalAttachmentCommands commands;
    private final ApprovalAttachmentProviderGate provider;
    private final ApprovalAttachmentAudit audit;
    private final ApprovalDocumentCanonical canonical;
    public ApprovalAttachmentViews(NamedParameterJdbcTemplate jdbc,ApprovalDocumentAuthority authority,ApprovalDocumentOwnerRepository owners,ApprovalDocumentRepository documents,
            ApprovalAttachmentPolicyRepository policies,ApprovalAttachmentCommands commands,ApprovalAttachmentProviderGate provider,ApprovalAttachmentAudit audit,ApprovalDocumentCanonical canonical) {
        this.jdbc=jdbc;this.authority=authority;this.owners=owners;this.documents=documents;this.policies=policies;this.commands=commands;this.provider=provider;this.audit=audit;this.canonical=canonical;
    }
    @Transactional public Attachments read(OwnerType type,UUID id) {
        var actor=authority.actor((type==OwnerType.REQUEST?"ACTION.APPROVAL_REQUEST":"ACTION.APPROVAL_TASK")+":VIEW");
        var owner=owners.lock(actor,type,id);authority.require(owner,"VIEW");owners.requireImmutablePayload(actor,owner);
        var p=params(actor).addValue("request",owner.requestId()).addValue("revision",owner.payloadRevision()).addValue("sha",owner.payloadSha256());
        Boolean configured=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM apr_attachment_policy_heads WHERE tenant_id=:tenant AND resource_set_key=:scope)",p.addValue("scope",owner.resourceSetKey()),Boolean.class);
        ApprovalAttachmentPolicyRepository.Head policy=Boolean.TRUE.equals(configured)?policies.current(actor,owner.resourceSetKey(),null,false):null;
        long selection=jdbc.query("SELECT version FROM apr_attachment_selections WHERE tenant_id=:tenant AND request_id=:request",p,r->r.next()?r.getLong(1):0L);
        String[] stored=jdbc.query("SELECT manifest_sha256,items::text FROM apr_attachment_manifests WHERE tenant_id=:tenant AND request_id=:request AND payload_revision=:revision AND payload_sha256=:sha",p,r->r.next()?new String[]{r.getString(1),r.getString(2)}:null);
        if(stored!=null && !canonical.fingerprint(canonical.read(stored[1],List.class)).equals(stored[0])) throw ApprovalDocumentCanonical.unavailable("Attachment manifest integrity failed.");
        List<Item> items=stored==null?List.of():items(stored[1]);boolean sealed=stored!=null;
        if(type==OwnerType.REQUEST && List.of("DRAFT","NEEDS_INFO").contains(owner.status())) {
            String chosen=jdbc.query("SELECT attachment_ids::text FROM apr_attachment_selections WHERE tenant_id=:tenant AND request_id=:request",p,r->r.next()?r.getString(1):"[]");
            var selected=List.of(canonical.read(chosen,UUID[].class));
            items=selected.stream().map(attachment->item(actor,owner.requestId(),attachment)).toList();sealed=stored!=null && storedIds(stored[1]).equals(new HashSet<>(selected));
        }
        var docPolicy=documents.policy(actor,owner.resourceSetKey(),false);var head=documents.head(actor,owner.requestId(),docPolicy.published().rules().evidenceRetentionDays());
        boolean retained=head.effectiveHold() || head.retainUntil().isAfter(Instant.now());
        String readiness=provider.readiness();boolean update=policy!=null && policy.rules().allowUpload() && type==OwnerType.REQUEST
                && List.of("DRAFT","NEEDS_INFO").contains(owner.status()) && permitted(owner,"UPDATE") && "COMPONENTS_VERIFIED_NOT_SANITIZED".equals(readiness);
        boolean download=policy!=null && policy.rules().allowDownload() && retained && sealed && permitted(owner,"EXPORT") && docPolicy.published().rules().allowedClassifications().contains(owner.classification())
                && "VERSIONING_VERIFIED".equals(provider.downloadReadiness());
        if(download) {
            for(var item:items) {
                Boolean current=jdbc.query("SELECT state='AVAILABLE' AND av_state='AV_CLEAR' AND passive_content_state='PASSIVE_ALLOWED' AND (retain_until>clock_timestamp() OR :hold) FROM apr_attachment_uploads WHERE tenant_id=:tenant AND request_id=:request AND attachment_id=:attachment",p.addValue("attachment",item.attachmentId()).addValue("hold",head.effectiveHold()),r->r.next() && r.getBoolean(1));
                if(!Boolean.TRUE.equals(current) || item.sizeBytes()>policy.rules().maxFileBytes() || !policy.rules().allowedMediaTypes().contains(item.mediaType())) {download=false;break;}
            }
        }
        authority.require(owner,"VIEW");
        return new Attachments(new Manifest(owner.payloadRevision(),owner.payloadSha256(),sealed?stored[0]:null,items,selection,sealed,readiness),policy==null?null:policy.policyId(),policy==null?0:policy.version(),
                new Tool(update,update?"ALLOWED":"UPLOAD_POLICY_AUTHORITY_OR_PROVIDER_UNAVAILABLE"),new Tool(download,download?"ALLOWED":"DOWNLOAD_POLICY_AUTHORITY_OR_RETENTION_PROHIBITED"),
                policy==null?0:policy.rules().maxFileBytes(),policy==null?0:policy.rules().maxFiles(),policy==null?0:policy.rules().maxRequestBytes(),policy==null?List.of():policy.rules().allowedMediaTypes(),Instant.now());
    }
    @Transactional public Attachments select(UUID id,Selection input) {
        var actor=authority.actor("ACTION.APPROVAL_REQUEST:UPDATE");var owner=owners.lock(actor,OwnerType.REQUEST,id);authority.require(owner,"UPDATE");
        var policy=policies.current(actor,owner.resourceSetKey(),null,false);String route="/v1/requests/"+id+"/attachments";
        if(!policy.rules().allowUpload() || !List.of("DRAFT","NEEDS_INFO").contains(owner.status())) throw ApprovalDocumentCanonical.forbidden();
        var prior=commands.replay(actor,route,input.idempotencyKey(),input);if(prior!=null){authority.require(owner,"UPDATE");return read(OwnerType.REQUEST,id);}
        if(input.expectedVersion()==null || input.expectedVersion()!=owner.requestVersion() || input.expectedPayloadRevision()!=owner.payloadRevision() || input.expectedPolicyVersion()!=policy.version()
                || input.attachmentIds()==null || input.attachmentIds().size()>policy.rules().maxFiles() || new HashSet<>(input.attachmentIds()).size()!=input.attachmentIds().size() || input.attachmentIds().stream().anyMatch(Objects::isNull)) throw ApprovalDocumentCanonical.conflict();
        long total=0;for(UUID attachment:input.attachmentIds().stream().sorted().toList()) {
            var row=jdbc.queryForList("SELECT * FROM apr_attachment_uploads WHERE tenant_id=:tenant AND request_id=:request AND attachment_id=:attachment AND policy_id=:policy AND state='AVAILABLE' AND av_state='AV_CLEAR' AND passive_content_state='PASSIVE_ALLOWED' FOR SHARE",params(actor).addValue("request",id).addValue("attachment",attachment).addValue("policy",policy.policyId()));
            if(row.isEmpty()) throw ApprovalDocumentCanonical.conflict();long bytes=((Number)row.getFirst().get("size_bytes")).longValue();
            if(bytes<1 || bytes>policy.rules().maxFileBytes() || !policy.rules().allowedMediaTypes().contains(row.getFirst().get("media_type"))) throw ApprovalDocumentCanonical.forbidden();
            try{total=Math.addExact(total,bytes);}catch(ArithmeticException overflow){throw ApprovalDocumentCanonical.conflict();}
        }
        if(total>policy.rules().maxRequestBytes()) throw ApprovalDocumentCanonical.conflict();var p=params(actor).addValue("request",id);
        jdbc.update("INSERT INTO apr_attachment_selections(tenant_id,request_id) VALUES(:tenant,:request) ON CONFLICT DO NOTHING",p);
        Long version=jdbc.queryForObject("SELECT version FROM apr_attachment_selections WHERE tenant_id=:tenant AND request_id=:request FOR UPDATE",p,Long.class);
        if(input.expectedSelectionVersion()!=version) throw ApprovalDocumentCanonical.conflict();
        jdbc.update("UPDATE apr_attachment_selections SET attachment_ids=CAST(:items AS jsonb),version=version+1 WHERE tenant_id=:tenant AND request_id=:request",p.addValue("items",canonical.json(input.attachmentIds().stream().sorted().toList())));
        authority.require(owner,"UPDATE");var metadata=Map.<String,Object>of("requestId",id,"selectionVersion",version+1);
        commands.complete(actor,route,input.idempotencyKey(),input,metadata,policy.rules().retentionDays());audit.record(actor.tenantId(),actor.userId(),id,"APPROVAL_ATTACHMENT_SELECTION_UPDATED",input.idempotencyKey(),metadata);return read(OwnerType.REQUEST,id);
    }
    private boolean permitted(ApprovalDocumentOwnerRepository.Owner owner,String action) {
        try{authority.require(owner,action);return true;}catch(BaseException denied){if(denied.getErrorCode()==ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE) throw denied;return false;}
    }
    private Item item(ApprovalRequestContext.Actor actor,UUID request,UUID attachment){
        return jdbc.query("SELECT * FROM apr_attachment_uploads WHERE tenant_id=:tenant AND request_id=:request AND attachment_id=:attachment",params(actor).addValue("request",request).addValue("attachment",attachment),r->{
            if(!r.next()) throw ApprovalDocumentOwnerRepository.hidden();return new Item(attachment,r.getString("file_name"),r.getString("media_type"),r.getLong("size_bytes"),r.getString("content_sha256"),r.getString("av_state"),r.getString("passive_content_state"));});
    }
    private List<Item> items(String json){@SuppressWarnings("unchecked") List<Map<String,Object>> rows=canonical.read(json,List.class);return rows.stream().map(row->new Item(UUID.fromString(row.get("attachmentId").toString()),row.get("fileName").toString(),row.get("mediaType").toString(),((Number)row.get("sizeBytes")).longValue(),row.get("sha256").toString(),"AV_CLEAR","PASSIVE_ALLOWED")).toList();}
    private Set<UUID> storedIds(String json){return new HashSet<>(items(json).stream().map(Item::attachmentId).toList());}
}
