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
public class ApprovalAttachmentDownloadCommands {
    public record Materialization(UUID grantId,UUID token,long generation,OwnerType ownerType,UUID ownerId,UUID requestId,UUID attachmentId,
            long ownerVersion,int payloadRevision,String payloadSha,String manifestSha,UUID policyId,long policyVersion,
            ApprovalAttachmentStorage.Stored stored,String fileName) { }
    private record Bound(ApprovalDocumentOwnerRepository.Owner owner,ApprovalAttachmentPolicyRepository.Head policy,
                         Map<String,Object> item,String manifestSha) { }
    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalDocumentAuthority authority;
    private final ApprovalDocumentOwnerRepository owners;
    private final ApprovalDocumentRepository documents;
    private final ApprovalAttachmentPolicyRepository policies;
    private final ApprovalDocumentCanonical canonical;
    private final ApprovalAttachmentCommands commands;
    private final ApprovalAttachmentAudit audit;
    private final ApprovalAttachmentProviderGate provider;
    public ApprovalAttachmentDownloadCommands(NamedParameterJdbcTemplate jdbc,ApprovalDocumentAuthority authority,ApprovalDocumentOwnerRepository owners,
            ApprovalDocumentRepository documents,ApprovalAttachmentPolicyRepository policies,ApprovalDocumentCanonical canonical,ApprovalAttachmentCommands commands,ApprovalAttachmentAudit audit,ApprovalAttachmentProviderGate provider) {
        this.jdbc=jdbc;this.authority=authority;this.owners=owners;this.documents=documents;this.policies=policies;this.canonical=canonical;this.commands=commands;this.audit=audit;this.provider=provider;
    }
    @Transactional public Grant issue(OwnerType type,UUID id,UUID attachment,Download input) {
        var actor=authority.actor(resource(type)+":EXPORT");var bound=bound(actor,type,id,attachment);
        if(input.expectedVersion()==null || input.expectedVersion()!=bound.owner().version() || input.expectedPayloadRevision()!=bound.owner().payloadRevision()
                || input.expectedPolicyVersion()!=bound.policy().version()) throw ApprovalDocumentCanonical.conflict();
        String route="/v1/"+(type==OwnerType.REQUEST?"requests/":"tasks/")+id+"/attachments/"+attachment+"/downloads";
        var prior=commands.replay(actor,route,input.idempotencyKey(),input);
        if(prior!=null) {var result=grant(actor,UUID.fromString(prior.get("grantId").toString()));authority.require(bound.owner(),"EXPORT");return result;}
        UUID grant=UUID.randomUUID();Instant expires=Instant.now().plusSeconds(bound.policy().rules().grantTtlSeconds());
        var p=params(actor).addValue("grant",grant).addValue("request",bound.owner().requestId()).addValue("attachment",attachment)
                .addValue("type",type.name()).addValue("owner",id).addValue("version",bound.owner().version()).addValue("revision",bound.owner().payloadRevision())
                .addValue("payload",bound.owner().payloadSha256()).addValue("manifest",bound.manifestSha()).addValue("policy",bound.policy().policyId()).addValue("policyVersion",bound.policy().version())
                .addValue("sha",bound.item().get("sha256")).addValue("objectVersion",bound.item().get("objectVersion")).addValue("expires",java.sql.Timestamp.from(expires));
        jdbc.update("""
                INSERT INTO apr_attachment_download_grants(grant_id,tenant_id,request_id,attachment_id,actor_user_id,owner_type,owner_id,
                  expected_owner_version,payload_revision,payload_sha256,manifest_sha256,policy_id,policy_version,content_sha256,object_version,expires_at)
                VALUES(:grant,:tenant,:request,:attachment,:actor,:type,:owner,:version,:revision,:payload,:manifest,:policy,:policyVersion,:sha,:objectVersion,:expires)
                """,p);
        authority.require(bound.owner(),"EXPORT");var metadata=Map.<String,Object>of("grantId",grant,"attachmentId",attachment,"sha256",bound.item().get("sha256"));
        commands.complete(actor,route,input.idempotencyKey(),input,metadata,bound.policy().rules().retentionDays());
        audit.record(actor.tenantId(),actor.userId(),bound.owner().requestId(),"APPROVAL_ATTACHMENT_DOWNLOAD_GRANTED",input.idempotencyKey(),metadata);
        return grant(actor,grant);
    }
    @Transactional public Materialization begin(UUID id) {
        var actor=authority.actor("APP.APPROVALS:VIEW");var raw=raw(actor,id,false);
        var bound=bound(actor,OwnerType.valueOf(raw.get("owner_type").toString()),(UUID)raw.get("owner_id"),(UUID)raw.get("attachment_id"));
        verify(raw,bound);raw=raw(actor,id,true);verify(raw,bound);
        if(raw.get("consumed_at")!=null || !((java.sql.Timestamp)raw.get("expires_at")).toInstant().isAfter(Instant.now())) throw ApprovalDocumentCanonical.conflict();
        var until=(java.sql.Timestamp)raw.get("lease_until");if(until!=null && until.toInstant().isAfter(Instant.now())) throw ApprovalDocumentCanonical.conflict();
        UUID token=UUID.randomUUID();long generation=((Number)raw.get("generation")).longValue()+1;
        jdbc.update("UPDATE apr_attachment_download_grants SET lease_token=:token,lease_until=clock_timestamp()+INTERVAL '60 seconds',generation=:generation WHERE grant_id=:id AND tenant_id=:tenant AND actor_user_id=:actor",params(actor).addValue("id",id).addValue("token",token).addValue("generation",generation));
        authority.require(bound.owner(),"EXPORT");
        return new Materialization(id,token,generation,OwnerType.valueOf(raw.get("owner_type").toString()),(UUID)raw.get("owner_id"),bound.owner().requestId(),(UUID)raw.get("attachment_id"),
                bound.owner().version(),bound.owner().payloadRevision(),bound.owner().payloadSha256(),bound.manifestSha(),bound.policy().policyId(),bound.policy().version(),
                new ApprovalAttachmentStorage.Stored(bound.item().get("objectKey").toString(),bound.item().get("objectVersion").toString(),((Number)bound.item().get("sizeBytes")).longValue(),bound.item().get("sha256").toString()),bound.item().get("fileName").toString());
    }
    @Transactional public void finish(Materialization pin,byte[] bytes) {
        var actor=authority.actor(resource(pin.ownerType())+":EXPORT");var bound=bound(actor,pin.ownerType(),pin.ownerId(),pin.attachmentId());
        var raw=raw(actor,pin.grantId(),true);verify(raw,bound);
        if(pin.ownerVersion()!=bound.owner().version() || pin.payloadRevision()!=bound.owner().payloadRevision() || !pin.payloadSha().equals(bound.owner().payloadSha256())
                || !pin.manifestSha().equals(bound.manifestSha()) || !pin.policyId().equals(bound.policy().policyId()) || pin.policyVersion()!=bound.policy().version()
                || !Objects.equals(raw.get("lease_token"),pin.token()) || ((Number)raw.get("generation")).longValue()!=pin.generation()) throw ApprovalDocumentCanonical.conflict();
        ApprovalAttachmentIntegrity.require(bytes,pin.stored().sizeBytes(),pin.stored().sha256());
        int count=jdbc.update("""
                UPDATE apr_attachment_download_grants SET consumed_at=clock_timestamp(),lease_token=NULL,lease_until=NULL
                 WHERE grant_id=:id AND tenant_id=:tenant AND actor_user_id=:actor AND consumed_at IS NULL
                   AND lease_token=:token AND generation=:generation AND lease_until>clock_timestamp() AND expires_at>clock_timestamp()
                """,params(actor).addValue("id",pin.grantId()).addValue("token",pin.token()).addValue("generation",pin.generation()));
        if(count!=1) throw ApprovalDocumentCanonical.conflict();authority.require(bound.owner(),"EXPORT");
        audit.record(actor.tenantId(),actor.userId(),pin.requestId(),"APPROVAL_ATTACHMENT_DOWNLOADED",pin.grantId().toString(),Map.of("grantId",pin.grantId(),"sha256",pin.stored().sha256(),"sizeBytes",pin.stored().sizeBytes()));
    }
    private Bound bound(ApprovalRequestContext.Actor actor,OwnerType type,UUID id,UUID attachment) {
        var owner=owners.lock(actor,type,id);authority.require(owner,"EXPORT");owners.requireImmutablePayload(actor,owner);
        var policy=policies.current(actor,owner.resourceSetKey(),null,false);
        if(!policy.rules().allowDownload()) throw ApprovalDocumentCanonical.forbidden();
        var documentPolicy=documents.policy(actor,owner.resourceSetKey(),false);
        if(!documentPolicy.published().rules().allowedClassifications().contains(owner.classification())) throw ApprovalDocumentCanonical.forbidden();
        var head=documents.head(actor,owner.requestId(),documentPolicy.published().rules().evidenceRetentionDays());
        if(!head.effectiveHold() && !head.retainUntil().isAfter(Instant.now())) throw ApprovalDocumentCanonical.forbidden();
        var p=params(actor).addValue("request",owner.requestId()).addValue("revision",owner.payloadRevision()).addValue("payload",owner.payloadSha256());
        var manifest=jdbc.query("SELECT manifest_sha256,items::text FROM apr_attachment_manifests WHERE tenant_id=:tenant AND request_id=:request AND payload_revision=:revision AND payload_sha256=:payload",p,r->r.next()?new String[]{r.getString(1),r.getString(2)}:null);
        if(manifest==null) throw ApprovalDocumentCanonical.conflict();
        @SuppressWarnings("unchecked") List<Map<String,Object>> items=canonical.read(manifest[1],List.class);
        if(!canonical.fingerprint(items).equals(manifest[0])) throw ApprovalDocumentCanonical.unavailable("Attachment manifest integrity failed.");
        var item=items.stream().filter(value->attachment.toString().equals(value.get("attachmentId").toString())).findFirst().orElseThrow(ApprovalDocumentOwnerRepository::hidden);
        if(!policy.rules().allowedMediaTypes().contains(item.get("mediaType")) || ((Number)item.get("sizeBytes")).longValue()>policy.rules().maxFileBytes()) throw ApprovalDocumentCanonical.forbidden();
        Boolean current=jdbc.query("""
                SELECT state='AVAILABLE' AND av_state='AV_CLEAR' AND passive_content_state='PASSIVE_ALLOWED'
                  AND content_sha256=:sha AND object_version=:objectVersion AND size_bytes=:bytes
                  AND (retain_until>clock_timestamp() OR :hold)
                 FROM apr_attachment_uploads WHERE tenant_id=:tenant AND request_id=:request AND attachment_id=:attachment FOR SHARE
                """,p.addValue("attachment",attachment).addValue("sha",item.get("sha256")).addValue("objectVersion",item.get("objectVersion")).addValue("bytes",item.get("sizeBytes")).addValue("hold",head.effectiveHold()),r->r.next() && r.getBoolean(1));
        if(!Boolean.TRUE.equals(current)) throw ApprovalDocumentCanonical.forbidden();provider.requireDownload();return new Bound(owner,policy,item,manifest[0]);
    }
    private void verify(Map<String,Object> raw,Bound bound) {
        if(((Number)raw.get("expected_owner_version")).longValue()!=bound.owner().version() || ((Number)raw.get("payload_revision")).intValue()!=bound.owner().payloadRevision()
                || !raw.get("payload_sha256").equals(bound.owner().payloadSha256()) || !raw.get("manifest_sha256").equals(bound.manifestSha())
                || !raw.get("policy_id").equals(bound.policy().policyId()) || ((Number)raw.get("policy_version")).longValue()!=bound.policy().version()
                || !raw.get("content_sha256").equals(bound.item().get("sha256")) || !raw.get("object_version").equals(bound.item().get("objectVersion"))) throw ApprovalDocumentCanonical.conflict();
    }
    private Map<String,Object> raw(ApprovalRequestContext.Actor actor,UUID id,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM apr_attachment_download_grants WHERE tenant_id=:tenant AND actor_user_id=:actor AND grant_id=:id"+(lock?" FOR UPDATE":""),params(actor).addValue("id",id));
        if(rows.isEmpty()) throw ApprovalDocumentOwnerRepository.hidden();return rows.getFirst();
    }
    private Grant grant(ApprovalRequestContext.Actor actor,UUID id){
        var row=raw(actor,id,false);if(!((java.sql.Timestamp)row.get("expires_at")).toInstant().isAfter(Instant.now())) throw ApprovalDocumentCanonical.conflict();
        return new Grant(id,((java.sql.Timestamp)row.get("expires_at")).toInstant(),row.get("content_sha256").toString(),jdbc.queryForObject("SELECT size_bytes FROM apr_attachment_uploads WHERE tenant_id=:tenant AND attachment_id=:attachment",params(actor).addValue("attachment",row.get("attachment_id")),Long.class));
    }
    private static String resource(OwnerType type){return type==OwnerType.REQUEST?"ACTION.APPROVAL_REQUEST":"ACTION.APPROVAL_TASK";}
}
