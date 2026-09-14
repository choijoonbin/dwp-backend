package com.dwp.services.approval.attachment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.document.ApprovalDocumentAuthority;
import com.dwp.services.approval.document.ApprovalDocumentCanonical;
import com.dwp.services.approval.document.ApprovalDocumentDtos.OwnerType;
import com.dwp.services.approval.document.ApprovalDocumentOwnerRepository;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static com.dwp.services.approval.document.ApprovalDocumentOwnerRepository.params;
import static com.dwp.services.approval.attachment.ApprovalAttachmentDtos.*;

/** Explicit lifecycle seam: no AOP and no mutation of the existing approval lifecycle. */
@Service
public class ApprovalAttachmentManifestFacade {
    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalDocumentOwnerRepository owners;
    private final ApprovalDocumentAuthority authority;
    private final ApprovalDocumentCanonical canonical;
    public ApprovalAttachmentManifestFacade(NamedParameterJdbcTemplate jdbc, ApprovalDocumentOwnerRepository owners,
            ApprovalDocumentAuthority authority, ApprovalDocumentCanonical canonical) {
        this.jdbc=jdbc; this.owners=owners; this.authority=authority; this.canonical=canonical;
    }
    @Transactional
    public Prepared prepare(UUID requestId, long expectedVersion, int sourceRevision, long selectionVersion, UUID policyId, long policyVersion) {
        var actor=authority.actor("ACTION.APPROVAL_REQUEST:UPDATE");
        var owner=owners.lock(actor,OwnerType.REQUEST,requestId); authority.require(owner,"UPDATE"); owners.requireImmutablePayload(actor,owner);
        if (owner.requestVersion()!=expectedVersion || owner.payloadRevision()!=sourceRevision || !List.of("DRAFT","NEEDS_INFO").contains(owner.status())) throw conflict();
        var p=params(actor).addValue("request",requestId).addValue("policy",policyId).addValue("policyVersion",policyVersion).addValue("scope",owner.resourceSetKey());
        Rules rules=canonical.read(requirePolicy(p),Rules.class);
        String selection=jdbc.query("SELECT attachment_ids::text FROM apr_attachment_selections WHERE tenant_id=:tenant AND request_id=:request AND version=:selection FOR UPDATE",
                p.addValue("selection",selectionVersion),r->r.next()?r.getString(1):null);
        if (selection==null) throw conflict();
        List<UUID> ids=readIds(selection); var items=new ArrayList<Map<String,Object>>();
        if (!ids.isEmpty() && !rules.allowUpload()) throw forbidden();
        if (ids.size()>rules.maxFiles()) throw conflict();
        long total=0;
        for (UUID id:ids) {
            var item=requireItem(p.addValue("attachment",id)); long size=((Number)item.get("sizeBytes")).longValue();
            if (size<1 || size>rules.maxFileBytes() || !rules.allowedMediaTypes().contains(item.get("mediaType"))) throw forbidden();
            try{total=Math.addExact(total,size);}catch(ArithmeticException overflow){throw conflict();}
            items.add(item);
        }
        if (total>rules.maxRequestBytes()) throw forbidden();
        String json=canonical.json(items); String digest=ApprovalDocumentCanonical.sha(json); UUID preparation=UUID.randomUUID();
        Instant expires=Instant.now().plusSeconds(300);
        jdbc.update("""
                INSERT INTO apr_attachment_preparations(preparation_id,tenant_id,request_id,source_request_version,source_payload_revision,source_payload_sha256,
                  manifest_sha256,selection_version,policy_id,policy_version,actor_user_id,items,expires_at)
                VALUES(:preparation,:tenant,:request,:requestVersion,:revision,:sha,:manifest,:selection,:policy,:policyVersion,:actor,CAST(:items AS jsonb),:expires)
                """,p.addValue("preparation",preparation).addValue("requestVersion",expectedVersion).addValue("revision",sourceRevision).addValue("sha",owner.payloadSha256())
                .addValue("manifest",digest).addValue("items",json).addValue("expires",Timestamp.from(expires)));
        authority.require(owner,"UPDATE");
        return new Prepared(preparation,requestId,expectedVersion,sourceRevision,owner.payloadSha256(),digest,selectionVersion,policyId,policyVersion,expires);
    }
    @Transactional
    public String seal(Prepared expected, int targetRevision, String targetPayloadSha256) {
        var actor=authority.actor("ACTION.APPROVAL_REQUEST:UPDATE");
        var owner=owners.lock(actor,OwnerType.REQUEST,expected.requestId()); authority.require(owner,"UPDATE"); owners.requireImmutablePayload(actor,owner);
        var p=params(actor).addValue("request",expected.requestId()).addValue("preparation",expected.preparationId());
        var stored=jdbc.query("SELECT * FROM apr_attachment_preparations WHERE tenant_id=:tenant AND request_id=:request AND preparation_id=:preparation AND actor_user_id=:actor FOR UPDATE",p,r->{
            if (!r.next()) throw forbidden();
            return new Pin(r.getLong("source_request_version"),r.getInt("source_payload_revision"),r.getString("source_payload_sha256"),r.getString("manifest_sha256"),
                    r.getLong("selection_version"),r.getObject("policy_id",UUID.class),r.getLong("policy_version"),r.getString("items"),
                    r.getTimestamp("expires_at").toInstant(),(Integer)r.getObject("consumed_revision"));
        });
        if (stored.requestVersion()!=expected.sourceRequestVersion() || stored.revision()!=expected.sourcePayloadRevision() || !stored.sha().equals(expected.sourcePayloadSha256())
                || !stored.manifest().equals(expected.manifestSha256()) || stored.selection()!=expected.selectionVersion()
                || !stored.policy().equals(expected.policyId()) || stored.policyVersion()!=expected.policyVersion()) throw conflict();
        requirePolicy(p.addValue("policy",stored.policy()).addValue("policyVersion",stored.policyVersion()).addValue("scope",owner.resourceSetKey()));
        Long selection=jdbc.query("SELECT version FROM apr_attachment_selections WHERE tenant_id=:tenant AND request_id=:request FOR UPDATE",p,r->r.next()?r.getLong(1):null);
        if (selection==null || selection!=stored.selection()) throw conflict();
        if (owner.requestVersion()!=stored.requestVersion()+1 || owner.payloadRevision()!=targetRevision || !owner.payloadSha256().equals(targetPayloadSha256)
                || (targetRevision!=stored.revision() && targetRevision!=stored.revision()+1)) throw conflict();
        if (stored.consumed()!=null) {
            if (stored.consumed()!=targetRevision) throw conflict();
            String manifest=jdbc.query("SELECT manifest_sha256 FROM apr_attachment_manifests WHERE tenant_id=:tenant AND request_id=:request AND payload_revision=:target AND payload_sha256=:targetSha",p.addValue("target",targetRevision).addValue("targetSha",targetPayloadSha256),r->r.next()?r.getString(1):null);
            if (!stored.manifest().equals(manifest)) throw conflict();
            authority.require(owner,"UPDATE"); return manifest;
        }
        if (!stored.expires().isAfter(Instant.now())) throw conflict();
        List<Map<String,Object>> items=readItems(stored.items());
        if (!stored.manifest().equals(canonical.fingerprint(items))) throw conflict();
        for (var item:items) {
            var current=requireItem(p.addValue("attachment",UUID.fromString(item.get("attachmentId").toString())));
            if (!canonical.fingerprint(current).equals(canonical.fingerprint(item))) throw conflict();
        }
        p.addValue("target",targetRevision).addValue("targetSha",targetPayloadSha256).addValue("manifest",stored.manifest()).addValue("items",stored.items());
        String previous=jdbc.query("SELECT manifest_sha256 FROM apr_attachment_manifests WHERE tenant_id=:tenant AND request_id=:request AND payload_revision=:target",p,r->r.next()?r.getString(1):null);
        if (targetRevision==stored.revision() && previous==null && !stored.manifest().equals(ApprovalDocumentCanonical.sha("[]")))
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,"Attachment changes require a new payload revision.");
        if (previous!=null && !previous.equals(stored.manifest())) throw new BaseException(ErrorCode.RESOURCE_CONFLICT,"Attachment changes require a new payload revision.");
        if (previous==null) jdbc.update("INSERT INTO apr_attachment_manifests(tenant_id,request_id,payload_revision,payload_sha256,manifest_sha256,items) VALUES(:tenant,:request,:target,:targetSha,:manifest,CAST(:items AS jsonb))",p);
        jdbc.update("UPDATE apr_attachment_preparations SET consumed_revision=:target WHERE preparation_id=:preparation AND consumed_revision IS NULL",p);
        authority.require(owner,"UPDATE"); return stored.manifest();
    }
    private String requirePolicy(org.springframework.jdbc.core.namedparam.MapSqlParameterSource p) {
        return jdbc.query("""
                SELECT v.rules::text,v.rules_sha256 FROM apr_attachment_policy_heads h JOIN apr_attachment_policy_versions v
                  ON v.tenant_id=h.tenant_id AND v.policy_id=h.policy_id AND v.revision=h.published_revision
                 WHERE h.tenant_id=:tenant AND h.resource_set_key=:scope AND h.policy_id=:policy AND h.version=:policyVersion
                 FOR SHARE OF h,v
                """,p,r->{
            if (!r.next()) throw conflict();
            var rules=canonical.read(r.getString(1),Rules.class);
            if (!canonical.fingerprint(rules).equals(r.getString(2))) throw conflict();
            return r.getString(1);
        });
    }
    private Map<String,Object> requireItem(org.springframework.jdbc.core.namedparam.MapSqlParameterSource p) {
        return jdbc.query("""
                SELECT * FROM apr_attachment_uploads WHERE tenant_id=:tenant AND request_id=:request AND attachment_id=:attachment
                   AND policy_id=:policy AND state='AVAILABLE' AND av_state='AV_CLEAR' AND passive_content_state='PASSIVE_ALLOWED'
                 FOR SHARE
                """,p,r->{
            if (!r.next()) throw conflict();
            var item=new TreeMap<String,Object>(); item.put("attachmentId",r.getString("attachment_id"));
            item.put("fileName",r.getString("file_name"));item.put("mediaType",r.getString("media_type"));
            item.put("sizeBytes",r.getLong("size_bytes"));item.put("sha256",r.getString("content_sha256"));
            item.put("objectVersion",r.getString("object_version"));item.put("objectKey",r.getString("object_key"));
            return item;
        });
    }
    private List<UUID> readIds(String json) {
        UUID[] values=canonical.read(json,UUID[].class);
        if (values.length>10 || new HashSet<>(List.of(values)).size()!=values.length) throw conflict();
        return Arrays.stream(values).sorted().toList();
    }
    private List<Map<String,Object>> readItems(String json) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,new TypeReference<List<Map<String,Object>>>(){}); }
        catch (Exception failure) { throw conflict(); }
    }
    private record Pin(long requestVersion,int revision,String sha,String manifest,long selection,UUID policy,long policyVersion,String items,Instant expires,Integer consumed) { }
    private BaseException conflict() { return new BaseException(ErrorCode.RESOURCE_CONFLICT,"Attachment manifest binding changed."); }
    private BaseException forbidden() { return new BaseException(ErrorCode.FORBIDDEN,"Attachment manifest is unavailable."); }
}
