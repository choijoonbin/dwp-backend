package com.dwp.services.approval.attachment;

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
public class ApprovalAttachmentIntakeCommands {
    public record Lease(UUID uploadId,UUID token,long generation,long requestVersion,int payloadRevision,UUID policyId,long policyVersion,
                        String objectKey,long sizeBytes,String sha256) { }
    public record Transfer(Upload upload,Lease lease) { }
    private record Owner(ApprovalRequestContext.Actor actor,ApprovalDocumentOwnerRepository.Owner request,ApprovalAttachmentPolicyRepository.Head policy) { }
    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalDocumentAuthority authority;
    private final ApprovalDocumentOwnerRepository owners;
    private final ApprovalAttachmentPolicyRepository policies;
    private final ApprovalAttachmentCommands commands;
    private final ApprovalAttachmentProviderGate provider;
    private final ApprovalAttachmentAudit audit;
    private final ApprovalDocumentCanonical canonical;
    public ApprovalAttachmentIntakeCommands(NamedParameterJdbcTemplate jdbc,ApprovalDocumentAuthority authority,ApprovalDocumentOwnerRepository owners,
            ApprovalAttachmentPolicyRepository policies,ApprovalAttachmentCommands commands,ApprovalAttachmentProviderGate provider,ApprovalAttachmentAudit audit,ApprovalDocumentCanonical canonical) {
        this.jdbc=jdbc;this.authority=authority;this.owners=owners;this.policies=policies;this.commands=commands;this.provider=provider;this.audit=audit;this.canonical=canonical;
    }
    @Transactional public Upload reserve(UUID request,Reserve input) {
        var owner=owner(request,true);String route="/v1/requests/"+request+"/attachment-uploads";
        var prior=commands.replay(owner.actor(),route,input.idempotencyKey(),input);
        if(prior!=null){var result=upload(owner.actor(),UUID.fromString(prior.get("uploadId").toString()),false);authority.require(owner.request(),"UPDATE");return projection(result);}
        requirePins(owner,input.expectedVersion(),input.expectedPayloadRevision(),input.expectedPolicyVersion());requireUpload(owner);provider.requireIngestion();
        var rules=owner.policy().rules();
        if(input.fileName()==null || input.fileName().isBlank() || input.fileName().length()>160 || input.fileName().codePoints().anyMatch(Character::isISOControl)
                || input.fileName().contains("/") || input.fileName().contains("\\") || !rules.allowedMediaTypes().contains(input.mediaType())
                || input.sizeBytes()<1 || input.sizeBytes()>rules.maxFileBytes() || input.sha256()==null || !input.sha256().matches("[a-f0-9]{64}")) throw ApprovalDocumentCanonical.forbidden();
        var p=params(owner.actor()).addValue("request",request);
        var aggregate=jdbc.queryForMap("SELECT count(*) AS files,COALESCE(sum(size_bytes),0) AS bytes FROM apr_attachment_uploads WHERE tenant_id=:tenant AND request_id=:request AND state NOT IN('CANCELLED','REJECTED') AND expires_at>clock_timestamp()",p);
        if(((Number)aggregate.get("files")).longValue()>=rules.maxFiles() || ((Number)aggregate.get("bytes")).longValue()+input.sizeBytes()>rules.maxRequestBytes()) throw ApprovalDocumentCanonical.conflict();
        UUID upload=UUID.randomUUID(),attachment=UUID.randomUUID();String key="t"+owner.actor().tenantId()+"/"+UUID.randomUUID();
        jdbc.update("""
                INSERT INTO apr_attachment_uploads(upload_id,attachment_id,tenant_id,request_id,uploader_user_id,request_version,payload_revision,
                  policy_id,policy_version,file_name,media_type,size_bytes,content_sha256,object_key,state,expires_at,retain_until)
                VALUES(:upload,:attachment,:tenant,:request,:actor,:version,:revision,:policy,:policyVersion,:name,:media,:bytes,:sha,:key,'RESERVED',
                  clock_timestamp()+INTERVAL '1 hour',clock_timestamp()+make_interval(days=>:days))
                """,p.addValue("upload",upload).addValue("attachment",attachment).addValue("version",owner.request().requestVersion()).addValue("revision",owner.request().payloadRevision())
                .addValue("policy",owner.policy().policyId()).addValue("policyVersion",owner.policy().version()).addValue("name",input.fileName()).addValue("media",input.mediaType())
                .addValue("bytes",input.sizeBytes()).addValue("sha",input.sha256()).addValue("key",key).addValue("days",rules.retentionDays()));
        finish(owner,route,input.idempotencyKey(),input,Map.of("uploadId",upload,"attachmentId",attachment),"APPROVAL_ATTACHMENT_RESERVED");return projection(upload(owner.actor(),upload,false));
    }
    @Transactional public Upload status(UUID id) {
        var actor=authority.actor("ACTION.APPROVAL_REQUEST:VIEW");var row=upload(actor,id,false);var owner=owner((UUID)row.get("request_id"),false);
        authority.require(owner.request(),"VIEW");return projection(row);
    }
    @Transactional public Transfer begin(UUID id,Long expectedVersion,String key,boolean reconcile) {
        var actor=authority.actor("ACTION.APPROVAL_REQUEST:UPDATE");var initial=upload(actor,id,false);var owner=owner((UUID)initial.get("request_id"),true);
        var row=upload(actor,id,true);String route="/v1/attachment-uploads/"+id+(reconcile?"/reconcile":"/content");
        if(expectedVersion==null || expectedVersion<0) throw ApprovalDocumentCanonical.conflict();
        var fingerprint=Map.of("expectedVersion",expectedVersion,"sha256",row.get("content_sha256"),"sizeBytes",row.get("size_bytes"));
        var prior=commands.replay(actor,route,key,fingerprint);
        requirePins(owner,((Number)row.get("request_version")).longValue(),((Number)row.get("payload_revision")).intValue(),((Number)row.get("policy_version")).longValue());
        requireUpload(owner);
        if(!row.get("policy_id").equals(owner.policy().policyId()) || !((java.sql.Timestamp)row.get("expires_at")).toInstant().isAfter(Instant.now())) throw ApprovalDocumentCanonical.conflict();
        if(prior!=null && row.get("object_version")!=null && List.of("QUARANTINED","SCANNING","AVAILABLE").contains(row.get("state"))) return new Transfer(projection(row),null);
        if(prior==null && expectedVersion.longValue()!=((Number)row.get("version")).longValue()) throw ApprovalDocumentCanonical.conflict();
        String state=row.get("state").toString();
        if(List.of("AVAILABLE","SCANNING","QUARANTINED","CANCELLED","REJECTED").contains(state)) throw ApprovalDocumentCanonical.conflict();
        var until=(java.sql.Timestamp)row.get("lease_until");if(until!=null && until.toInstant().isAfter(Instant.now())) throw ApprovalDocumentCanonical.conflict();
        if(!reconcile && !"RESERVED".equals(state)) throw ApprovalDocumentCanonical.conflict();
        Long active=jdbc.queryForObject("SELECT count(*) FROM apr_attachment_uploads WHERE tenant_id=:tenant AND uploader_user_id=:actor AND state='UPLOADING' AND lease_until>clock_timestamp()",params(actor),Long.class);
        if(active>=owner.policy().rules().maxConcurrentUploads()) throw ApprovalDocumentCanonical.conflict();provider.requireIngestion();
        UUID token=UUID.randomUUID();long generation=((Number)row.get("generation")).longValue()+1;
        jdbc.update("UPDATE apr_attachment_uploads SET state='UPLOADING',lease_token=:token,lease_until=clock_timestamp()+INTERVAL '60 seconds',generation=:generation,version=version+1 WHERE upload_id=:id AND tenant_id=:tenant",params(actor).addValue("id",id).addValue("token",token).addValue("generation",generation));
        if(prior==null) commands.complete(actor,route,key,fingerprint,Map.of("uploadId",id),owner.policy().rules().retentionDays());authority.require(owner.request(),"UPDATE");
        return new Transfer(projection(upload(actor,id,false)),new Lease(id,token,generation,owner.request().requestVersion(),owner.request().payloadRevision(),owner.policy().policyId(),owner.policy().version(),
                row.get("object_key").toString(),((Number)row.get("size_bytes")).longValue(),row.get("content_sha256").toString()));
    }
    @Transactional public Upload stored(Lease lease,ApprovalAttachmentStorage.Stored stored) {
        var actor=authority.actor("ACTION.APPROVAL_REQUEST:UPDATE");var initial=upload(actor,lease.uploadId(),false);var owner=owner((UUID)initial.get("request_id"),true);
        requirePins(owner,lease.requestVersion(),lease.payloadRevision(),lease.policyVersion());
        requireUpload(owner);
        if(!lease.policyId().equals(owner.policy().policyId()) || !lease.sha256().equals(stored.sha256()) || lease.sizeBytes()!=stored.sizeBytes()
                || !lease.objectKey().equals(stored.objectKey()) || stored.versionId()==null || stored.versionId().isBlank() || "null".equals(stored.versionId())) throw ApprovalDocumentCanonical.conflict();
        int changed=jdbc.update("""
                UPDATE apr_attachment_uploads SET state='QUARANTINED',object_version=:objectVersion,version=version+1,lease_token=NULL,lease_until=NULL,reason='AWAITING_SCAN'
                 WHERE tenant_id=:tenant AND uploader_user_id=:actor AND upload_id=:id AND state='UPLOADING' AND lease_token=:token AND generation=:generation
                   AND lease_until>clock_timestamp() AND expires_at>clock_timestamp() AND object_version IS NULL
                """,params(actor).addValue("id",lease.uploadId()).addValue("objectVersion",stored.versionId()).addValue("token",lease.token()).addValue("generation",lease.generation()));
        if(changed!=1) throw ApprovalDocumentCanonical.conflict();authority.require(owner.request(),"UPDATE");
        audit.record(actor.tenantId(),actor.userId(),owner.request().requestId(),"APPROVAL_ATTACHMENT_QUARANTINED",lease.token().toString(),Map.of("uploadId",lease.uploadId(),"sha256",lease.sha256()));
        return projection(upload(actor,lease.uploadId(),false));
    }
    @Transactional public void uncertain(Lease lease) {
        var actor=authority.actor("ACTION.APPROVAL_REQUEST:UPDATE");var row=upload(actor,lease.uploadId(),false);var owner=owner((UUID)row.get("request_id"),true);
        jdbc.update("UPDATE apr_attachment_uploads SET state='STORAGE_RECONCILING',generation=generation+1,version=version+1,lease_token=NULL,lease_until=NULL,reason='STORAGE_RESULT_UNKNOWN' WHERE tenant_id=:tenant AND upload_id=:id AND state='UPLOADING' AND generation=:generation AND lease_token=:token",params(actor).addValue("id",lease.uploadId()).addValue("generation",lease.generation()).addValue("token",lease.token()));
        authority.require(owner.request(),"UPDATE");
    }
    @Transactional public void inputRejected(Lease lease) {
        var actor=authority.actor("ACTION.APPROVAL_REQUEST:UPDATE");var row=upload(actor,lease.uploadId(),false);var owner=owner((UUID)row.get("request_id"),true);
        jdbc.update("UPDATE apr_attachment_uploads SET state='REJECTED',generation=generation+1,version=version+1,lease_token=NULL,lease_until=NULL,reason='INPUT_REJECTED_BEFORE_STORAGE' WHERE tenant_id=:tenant AND upload_id=:id AND state='UPLOADING' AND generation=:generation AND lease_token=:token",params(actor).addValue("id",lease.uploadId()).addValue("generation",lease.generation()).addValue("token",lease.token()));
        authority.require(owner.request(),"UPDATE");
    }
    @Transactional public Upload cancel(UUID id,Cancel input) {
        var actor=authority.actor("ACTION.APPROVAL_REQUEST:UPDATE");var initial=upload(actor,id,false);var owner=owner((UUID)initial.get("request_id"),true);var row=upload(actor,id,true);
        String route="/v1/attachment-uploads/"+id+"/cancel";var prior=commands.replay(actor,route,input.idempotencyKey(),input);
        if(prior!=null){authority.require(owner.request(),"UPDATE");return projection(row);}
        if(input.expectedVersion()==null || input.expectedVersion()!=((Number)row.get("version")).longValue()) throw ApprovalDocumentCanonical.conflict();
        Boolean bound=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM apr_attachment_manifests WHERE tenant_id=:tenant AND request_id=:request AND jsonb_path_exists(items,'$[*] ? (@.attachmentId == $id)',jsonb_build_object('id',CAST(:attachment AS text))))",params(actor).addValue("request",owner.request().requestId()).addValue("attachment",row.get("attachment_id")),Boolean.class);
        if(Boolean.TRUE.equals(bound)) throw ApprovalDocumentCanonical.forbidden();
        jdbc.update("UPDATE apr_attachment_uploads SET state='CANCELLED',version=version+1,generation=generation+1,lease_token=NULL,lease_until=NULL,reason='CANCELLED_BY_OWNER' WHERE tenant_id=:tenant AND upload_id=:id",params(actor).addValue("id",id));
        jdbc.update("INSERT INTO apr_attachment_cleanup_journal(cleanup_id,tenant_id,upload_id,object_key,object_version,reason) VALUES(:cleanup,:tenant,:id,:key,:objectVersion,'OWNER_CANCELLED')",params(actor).addValue("cleanup",UUID.randomUUID()).addValue("id",id).addValue("key",row.get("object_key")).addValue("objectVersion",row.get("object_version")));
        finish(owner,route,input.idempotencyKey(),input,Map.of("uploadId",id),"APPROVAL_ATTACHMENT_CANCELLED");return projection(upload(actor,id,false));
    }
    private Owner owner(UUID id,boolean update) {
        var actor=authority.actor("ACTION.APPROVAL_REQUEST:"+(update?"UPDATE":"VIEW"));var request=owners.lock(actor,OwnerType.REQUEST,id);authority.require(request,update?"UPDATE":"VIEW");
        if(update && !List.of("DRAFT","NEEDS_INFO").contains(request.status())) throw ApprovalDocumentCanonical.forbidden();
        var policy=policies.current(actor,request.resourceSetKey(),null,false);return new Owner(actor,request,policy);
    }
    private void requireUpload(Owner owner){if(!owner.policy().rules().allowUpload()) throw ApprovalDocumentCanonical.forbidden();}
    private void requirePins(Owner owner,Long version,int revision,long policyVersion){if(version==null || version!=owner.request().requestVersion() || revision!=owner.request().payloadRevision() || policyVersion!=owner.policy().version()) throw ApprovalDocumentCanonical.conflict();}
    private Map<String,Object> upload(ApprovalRequestContext.Actor actor,UUID id,boolean lock){
        var rows=jdbc.queryForList("SELECT * FROM apr_attachment_uploads WHERE tenant_id=:tenant AND uploader_user_id=:actor AND upload_id=:id"+(lock?" FOR UPDATE":""),params(actor).addValue("id",id));
        if(rows.isEmpty()) throw ApprovalDocumentOwnerRepository.hidden();return rows.getFirst();
    }
    private Upload projection(Map<String,Object> row){return new Upload((UUID)row.get("upload_id"),(UUID)row.get("attachment_id"),State.valueOf(row.get("state").toString()),((Number)row.get("version")).longValue(),(String)row.get("reason"),row.get("av_state").toString(),row.get("passive_content_state").toString(),((Number)row.get("size_bytes")).longValue(),row.get("content_sha256").toString(),((java.sql.Timestamp)row.get("expires_at")).toInstant());}
    private void finish(Owner owner,String route,String key,Object input,Map<String,Object> metadata,String action){authority.require(owner.request(),"UPDATE");commands.complete(owner.actor(),route,key,input,metadata,owner.policy().rules().retentionDays());audit.record(owner.actor().tenantId(),owner.actor().userId(),owner.request().requestId(),action,key,metadata);}
}
