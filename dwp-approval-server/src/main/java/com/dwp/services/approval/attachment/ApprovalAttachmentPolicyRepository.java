package com.dwp.services.approval.attachment;

import com.dwp.services.approval.document.ApprovalDocumentCanonical;
import com.dwp.services.approval.document.ApprovalDocumentOwnerRepository;
import com.dwp.services.approval.security.ApprovalRequestContext;
import jakarta.validation.Validator;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;
import static com.dwp.services.approval.attachment.ApprovalAttachmentDtos.*;
import static com.dwp.services.approval.document.ApprovalDocumentOwnerRepository.params;

@Repository
public class ApprovalAttachmentPolicyRepository {
    public record Head(UUID policyId,String scope,long version,int publishedRevision,Integer pendingRevision,Long maker,Rules rules,Rules pending,
            String publishedRulesSha256,String pendingRulesSha256) {
        public Policy projection(long actorUserId,String readiness,String downloadReadiness){
            String reason=pending==null?"PENDING_POLICY_REQUIRED":Objects.equals(actorUserId,maker)?"MAKER_CANNOT_PUBLISH"
                :pending.allowUpload() && !"COMPONENTS_VERIFIED_NOT_SANITIZED".equals(readiness)?"UPLOAD_PROVIDER_UNAVAILABLE"
                :pending.allowDownload() && !"VERSIONING_VERIFIED".equals(downloadReadiness)?"DOWNLOAD_PROVIDER_UNAVAILABLE":"ALLOWED";
            return new Policy(policyId,scope,version,rules,pending,readiness,publishedRevision,pendingRevision,maker,
                publishedRulesSha256,pendingRulesSha256,downloadReadiness,"ALLOWED".equals(reason),reason);
        }
    }
    private static final Set<String> MEDIA=Set.of("text/plain","application/pdf","application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","application/vnd.openxmlformats-officedocument.presentationml.presentation");
    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalDocumentCanonical canonical;
    private final Validator validator;
    public ApprovalAttachmentPolicyRepository(NamedParameterJdbcTemplate jdbc,ApprovalDocumentCanonical canonical,Validator validator) {this.jdbc=jdbc;this.canonical=canonical;this.validator=validator;}
    public void validate(Rules rules) {
        if (rules==null || !validator.validate(rules).isEmpty() || rules.allowedMediaTypes().stream().anyMatch(media->!MEDIA.contains(media))
                || new HashSet<>(rules.allowedMediaTypes()).size()!=rules.allowedMediaTypes().size() || rules.maxRequestBytes()<rules.maxFileBytes()) throw ApprovalDocumentCanonical.forbidden();
    }
    public Head current(ApprovalRequestContext.Actor actor,String scope,UUID target,boolean mutation) {
        if (scope==null || !scope.matches("RS_[A-Z0-9_]{1,76}")) throw ApprovalDocumentCanonical.forbidden();
        var p=params(actor).addValue("scope",scope);
        var head=jdbc.query("""
                SELECT h.*,v.rules::text,v.rules_sha256,v.revision AS published_record_revision,v.maker_user_id AS published_maker,
                  p.rules::text AS pending_rules,p.rules_sha256 AS pending_rules_sha256,p.revision AS pending_record_revision,p.maker_user_id
                  FROM apr_attachment_policy_heads h JOIN apr_tenants t ON t.tenant_id=h.tenant_id
                  LEFT JOIN apr_attachment_policy_versions v ON v.tenant_id=h.tenant_id AND v.policy_id=h.policy_id AND v.revision=h.published_revision
                  LEFT JOIN apr_attachment_policy_versions p ON p.tenant_id=h.tenant_id AND p.policy_id=h.policy_id AND p.revision=h.pending_revision
                 WHERE h.tenant_id=:tenant AND h.resource_set_key=:scope AND t.lifecycle_state='ACTIVE'
                """+(mutation?" FOR UPDATE OF h FOR SHARE OF t":" FOR SHARE OF h,t"),p,r->{
            if (!r.next()) throw ApprovalDocumentOwnerRepository.hidden();
            UUID id=r.getObject("policy_id",UUID.class);
            if (target!=null && !target.equals(id)) throw ApprovalDocumentCanonical.forbidden();
            int published=r.getInt("published_revision");Integer pending=(Integer)r.getObject("pending_revision");
            Long maker=(Long)r.getObject("maker_user_id"),publishedMaker=(Long)r.getObject("published_maker");
            if(!Objects.equals(published,r.getObject("published_record_revision"))
                || (published==0?publishedMaker!=null:publishedMaker==null || publishedMaker<=0)
                || (pending!=null && (pending<=published || !pending.equals(r.getObject("pending_record_revision")) || maker==null || maker<=0)))
                throw ApprovalDocumentCanonical.unavailable("Attachment policy revision or maker integrity failed.");
            String sha=r.getString("rules_sha256"),pendingSha=r.getString("pending_rules_sha256");
            Rules rules=verified(r.getString("rules"),sha),pendingRules=pending==null?null:verified(r.getString("pending_rules"),pendingSha);
            return new Head(id,scope,r.getLong("version"),published,pending,maker,rules,pendingRules,sha,pendingSha);
        });return head;
    }
    private Rules verified(String json,String sha) {
        try {
            if(json==null || sha==null || !sha.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Missing policy proof");
            Rules rules=canonical.read(json,Rules.class);validate(rules);
            if(!canonical.fingerprint(rules).equals(sha)) throw new IllegalArgumentException("Policy hash mismatch");
            return rules;
        } catch(RuntimeException malformed) {throw ApprovalDocumentCanonical.unavailable("Attachment policy integrity failed.");}
    }
    public Head initializeAbsent(ApprovalRequestContext.Actor actor,String scope) {
        if (scope==null || !scope.matches("RS_[A-Z0-9_]{1,76}")) throw ApprovalDocumentCanonical.forbidden();
        var p=params(actor).addValue("scope",scope).addValue("id",UUID.randomUUID());
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM apr_attachment_policy_heads WHERE tenant_id=:tenant AND resource_set_key=:scope)",p,Boolean.class)))
            throw ApprovalDocumentCanonical.conflict();
        // Serialize absent-head creation without provisioning a tenant or repairing an existing policy.
        boolean active=jdbc.query("SELECT tenant_id FROM apr_tenants WHERE tenant_id=:tenant AND lifecycle_state='ACTIVE' FOR UPDATE",p,(org.springframework.jdbc.core.ResultSetExtractor<Boolean>)r->r.next());
        if (!active) throw ApprovalDocumentOwnerRepository.hidden();
        var rules=new Rules(false,false,10485760,5,52428800,1,List.of("text/plain","application/pdf"),300,365);
        if (jdbc.update("""
                INSERT INTO apr_attachment_policy_heads(tenant_id,resource_set_key,policy_id)
                VALUES(:tenant,:scope,:id)
                ON CONFLICT(tenant_id,resource_set_key) DO NOTHING
                """,p)!=1) throw ApprovalDocumentCanonical.conflict();
        jdbc.update("""
                INSERT INTO apr_attachment_policy_versions(tenant_id,policy_id,revision,rules,rules_sha256)
                VALUES(:tenant,:id,0,CAST(:rules AS jsonb),:sha)
                """,p.addValue("rules",canonical.json(rules)).addValue("sha",canonical.fingerprint(rules)));
        return current(actor,scope,null,false);
    }
    public void draft(ApprovalRequestContext.Actor actor,Head head,Rules rules) {
        validate(rules);var p=params(actor).addValue("policy",head.policyId()).addValue("version",head.version()).addValue("pending",head.pendingRevision()).addValue("rules",canonical.json(rules)).addValue("sha",canonical.fingerprint(rules));
        Integer revision=jdbc.queryForObject("SELECT COALESCE(MAX(revision),0)+1 FROM apr_attachment_policy_versions WHERE tenant_id=:tenant AND policy_id=:policy",p,Integer.class);
        jdbc.update("INSERT INTO apr_attachment_policy_versions(tenant_id,policy_id,revision,rules,rules_sha256,maker_user_id) VALUES(:tenant,:policy,:revision,CAST(:rules AS jsonb),:sha,:actor)",p.addValue("revision",revision));
        if(jdbc.update("UPDATE apr_attachment_policy_heads SET pending_revision=:revision,version=version+1 WHERE tenant_id=:tenant AND policy_id=:policy AND version=:version AND pending_revision IS NOT DISTINCT FROM CAST(:pending AS integer)",p)!=1) throw ApprovalDocumentCanonical.conflict();
    }
    public void publish(ApprovalRequestContext.Actor actor,Head head,String comment) {
        if(head.pending()==null || head.maker()==null || actor.userId().equals(head.maker())) throw ApprovalDocumentCanonical.forbidden();validate(head.pending());
        var p=params(actor).addValue("policy",head.policyId()).addValue("version",head.version()).addValue("revision",head.pendingRevision()).addValue("maker",head.maker()).addValue("comment",comment).addValue("id",UUID.randomUUID());
        jdbc.update("INSERT INTO apr_attachment_policy_publications(publication_id,tenant_id,policy_id,revision,maker_user_id,checker_user_id,review_comment) VALUES(:id,:tenant,:policy,:revision,:maker,:actor,:comment)",p);
        if(jdbc.update("UPDATE apr_attachment_policy_heads SET published_revision=:revision,pending_revision=NULL,version=version+1 WHERE tenant_id=:tenant AND policy_id=:policy AND version=:version AND pending_revision=:revision",p)!=1) throw ApprovalDocumentCanonical.conflict();
    }
}
