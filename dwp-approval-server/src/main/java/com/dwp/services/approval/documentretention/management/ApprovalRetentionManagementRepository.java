package com.dwp.services.approval.documentretention.management;

import com.dwp.services.approval.document.ApprovalDocumentCanonical;
import com.dwp.services.approval.security.ApprovalRequestContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import static com.dwp.services.approval.documentretention.management.ApprovalRetentionDtos.*;
import com.dwp.services.approval.documentretention.management.ApprovalRetentionDtos.Record;

@Repository
public class ApprovalRetentionManagementRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalDocumentCanonical canonical;
    private final ApprovalRetentionPolicyValidator validator;
    public ApprovalRetentionManagementRepository(NamedParameterJdbcTemplate jdbc,ApprovalDocumentCanonical canonical,
            ApprovalRetentionPolicyValidator validator) {this.jdbc=jdbc;this.canonical=canonical;this.validator=validator;}
    public void tenant(ApprovalRequestContext.Actor actor) {
        var rows=jdbc.query("SELECT tenant_id FROM apr_tenants WHERE tenant_id=:tenant AND lifecycle_state='ACTIVE' FOR SHARE",
                Map.of("tenant",actor.tenantId()),(r,n)->r.getLong(1));
        if(rows.size()!=1) throw ApprovalRetentionErrors.forbidden();
    }
    public Policy policy(ApprovalRequestContext.Actor actor,String scope,UUID expectedId,boolean lock) {
        tenant(actor);
        var args=new HashMap<String,Object>();args.put("tenant",actor.tenantId());args.put("scope",scope);
        var heads=jdbc.query("SELECT policy_id,version,published_revision,pending_revision FROM apr_retention_policy_heads WHERE tenant_id=:tenant AND resource_set_key=:scope"+(lock?" FOR UPDATE":" FOR SHARE"),args,
                (r,n)->new Head(r.getObject("policy_id",UUID.class),r.getLong("version"),r.getInt("published_revision"),r.getObject("pending_revision",Integer.class)));
        if(heads.isEmpty()) {if(expectedId!=null) throw ApprovalRetentionErrors.hidden();throw ApprovalRetentionErrors.notConfigured();}
        var h=heads.getFirst();if(expectedId!=null && !expectedId.equals(h.id())) throw ApprovalRetentionErrors.hidden();
        var published=version(actor,h.id(),h.published());
        var pending=h.pending()==null?null:version(actor,h.id(),h.pending());
        if(h.published()>0) {
            var p=jdbc.query("SELECT maker_user_id,checker_user_id FROM apr_retention_policy_publications WHERE tenant_id=:tenant AND policy_id=:policy AND revision=:revision",
                    Map.of("tenant",actor.tenantId(),"policy",h.id(),"revision",h.published()),(r,n)->List.of(r.getLong(1),r.getLong(2)));
            if(p.size()!=1 || !Objects.equals(p.getFirst().getFirst(),published.maker()) || p.getFirst().getFirst().equals(p.getFirst().get(1))) throw ApprovalRetentionErrors.unavailable();
        }
        if(pending!=null && (pending.maker()==null || h.pending()<=h.published())) throw ApprovalRetentionErrors.unavailable();
        String reason=pending==null?"NO_PENDING_REVISION":pending.maker().equals(actor.userId())?"INDEPENDENT_CHECKER_REQUIRED":"ELIGIBLE_REQUIRES_SIGNED_HIGH";
        return new Policy(h.id(),scope,h.version(),h.published(),h.pending(),pending==null?null:pending.maker(),
                published.sha(),pending==null?null:pending.sha(),published.rules(),pending==null?null:pending.rules(),
                "ELIGIBLE_REQUIRES_SIGNED_HIGH".equals(reason),reason,"MANAGED_EXECUTION_REQUIRES_CONFIGURED_AUTH_AND_OWNER_PORTS");
    }
    private record Head(UUID id,long version,int published,Integer pending) {}
    private record Version(PublicRules rules,String sha,Long maker) {}
    private Version version(ApprovalRequestContext.Actor actor,UUID id,int revision) {
        var versions=jdbc.query("SELECT rules::text,rules_sha256,maker_user_id,encode(sha256(convert_to(rules::text,'UTF8')),'hex') actual FROM apr_retention_policy_versions WHERE tenant_id=:tenant AND policy_id=:policy AND revision=:revision",
                Map.of("tenant",actor.tenantId(),"policy",id,"revision",revision),(r,n)->{
                    if(!r.getString("rules_sha256").equals(r.getString("actual"))) throw ApprovalRetentionErrors.unavailable();
                    PublicRules rules=canonical.read(r.getString("rules"),PublicRules.class);
                    try {validator.validate(rules);} catch(RuntimeException invalid) {throw ApprovalRetentionErrors.unavailable();}
                    Long maker=r.getObject("maker_user_id",Long.class);
                    if((revision==0 && (maker!=null || !Boolean.FALSE.equals(rules.allowPurge()))) || (revision>0 && (maker==null || maker<=0))) throw ApprovalRetentionErrors.unavailable();
                    return new Version(rules,r.getString("rules_sha256"),maker);
                });
        if(versions.size()!=1) throw ApprovalRetentionErrors.unavailable();return versions.getFirst();
    }
    public UUID initialize(ApprovalRequestContext.Actor actor,String scope,PublicRules rules) {
        var id=UUID.randomUUID();var args=new HashMap<String,Object>();args.put("tenant",actor.tenantId());args.put("scope",scope);args.put("id",id);
        if(jdbc.update("INSERT INTO apr_retention_policy_heads(tenant_id,resource_set_key,policy_id) VALUES(:tenant,:scope,:id) ON CONFLICT(tenant_id,resource_set_key) DO NOTHING",args)!=1) throw ApprovalRetentionErrors.conflict();
        insertVersion(actor,id,0,rules,null);return id;
    }
    private void insertVersion(ApprovalRequestContext.Actor actor,UUID id,int revision,PublicRules rules,Long maker) {
        var args=new HashMap<String,Object>();args.put("tenant",actor.tenantId());args.put("policy",id);args.put("revision",revision);args.put("rules",canonical.json(rules));args.put("maker",maker);
        jdbc.update("INSERT INTO apr_retention_policy_versions(tenant_id,policy_id,revision,rules,rules_sha256,maker_user_id) SELECT :tenant,:policy,:revision,r,encode(sha256(convert_to(r::text,'UTF8')),'hex'),:maker FROM (SELECT CAST(:rules AS jsonb) r) input",args);
    }
    public void save(ApprovalRequestContext.Actor actor,Policy policy,PublicRules rules) {
        int revision=jdbc.queryForObject("SELECT COALESCE(max(revision),0)+1 FROM apr_retention_policy_versions WHERE tenant_id=:tenant AND policy_id=:policy",
                Map.of("tenant",actor.tenantId(),"policy",policy.policyId()),Integer.class);
        insertVersion(actor,policy.policyId(),revision,rules,actor.userId());
        if(jdbc.update("UPDATE apr_retention_policy_heads SET pending_revision=:revision,version=version+1 WHERE tenant_id=:tenant AND policy_id=:policy AND version=:version",
                Map.of("tenant",actor.tenantId(),"policy",policy.policyId(),"version",policy.version(),"revision",revision))!=1) throw ApprovalRetentionErrors.conflict();
    }
    public void publish(ApprovalRequestContext.Actor actor,Policy p,String review) {
        if(p.pendingRevision()==null || p.pendingMakerUserId()==null || p.pendingMakerUserId().equals(actor.userId())) throw ApprovalRetentionErrors.forbidden();
        if(review==null || review.trim().length()<10 || review.length()>1000) throw ApprovalRetentionErrors.invalid();
        jdbc.update("INSERT INTO apr_retention_policy_publications(publication_id,tenant_id,policy_id,revision,maker_user_id,checker_user_id,review_comment) VALUES(:id,:tenant,:policy,:revision,:maker,:checker,:review)",
                Map.of("id",UUID.randomUUID(),"tenant",actor.tenantId(),"policy",p.policyId(),"revision",p.pendingRevision(),"maker",p.pendingMakerUserId(),"checker",actor.userId(),"review",review));
        if(jdbc.update("UPDATE apr_retention_policy_heads SET published_revision=:revision,pending_revision=NULL,version=version+1 WHERE tenant_id=:tenant AND policy_id=:policy AND version=:version AND pending_revision=:revision",
                Map.of("tenant",actor.tenantId(),"policy",p.policyId(),"version",p.version(),"revision",p.pendingRevision()))!=1) throw ApprovalRetentionErrors.conflict();
    }
    public UUID receipt(ApprovalRequestContext.Actor actor,String scope,String route,String key,Object input) {
        if(key==null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw ApprovalRetentionErrors.invalid();
        var args=Map.<String,Object>of("tenant",actor.tenantId(),"actor",actor.userId(),"route",route,"key",key);
        String material=actor.tenantId()+":"+actor.userId()+":"+route+":"+key;
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(:material,0)) IS NULL",Map.of("material",material),Boolean.class);
        var result=jdbc.query("SELECT target_id,fingerprint,resource_set_key FROM apr_retention_management_commands WHERE tenant_id=:tenant AND actor_user_id=:actor AND route=:route AND idempotency_key=:key",args,(r,n)->{
            if(!scope.equals(r.getString("resource_set_key")) || !canonical.fingerprint(input).equals(r.getString("fingerprint"))) throw ApprovalRetentionErrors.conflict();
            return r.getObject("target_id",UUID.class);
        });return result.isEmpty()?null:result.getFirst();
    }
    public void complete(ApprovalRequestContext.Actor actor,String scope,String route,String key,Object input,UUID target,long version) {
        jdbc.update("INSERT INTO apr_retention_management_commands(tenant_id,actor_user_id,route,idempotency_key,fingerprint,resource_set_key,target_id,result_version) VALUES(:tenant,:actor,:route,:key,:fingerprint,:scope,:target,:version)",
                Map.of("tenant",actor.tenantId(),"actor",actor.userId(),"route",route,"key",key,"fingerprint",canonical.fingerprint(input),"scope",scope,"target",target,"version",version));
    }
    public Map<String,Object> request(ApprovalRequestContext.Actor actor,String scope,UUID request,boolean lock) {
        tenant(actor);
        var rows=jdbc.queryForList("SELECT r.*,d.hold_version,d.hold_active,d.pending_hold_id,d.retain_until FROM apr_requests r JOIN apr_document_heads d USING(tenant_id,request_id) WHERE r.tenant_id=:tenant AND r.request_id=:request AND r.management_resource_set_key=:scope"+(lock?" FOR UPDATE OF r,d":" FOR SHARE OF r,d"),
                Map.of("tenant",actor.tenantId(),"request",request,"scope",scope));
        if(rows.size()!=1) throw ApprovalRetentionErrors.hidden();return rows.getFirst();
    }
    public UUID createIntent(ApprovalRequestContext.Actor actor,Record record,CreateClaim input,String authoritySha) {
        UUID id=UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO apr_retention_dispatch_intents(intent_id,tenant_id,request_id,resource_set_key,actor_user_id,request_version,policy_id,policy_version,hold_version,inventory_sha256,original_inventory_sha256,command_fingerprint,authority_sha256) VALUES(:id,:tenant,:request,:scope,:actor,:version,:policy,:policyVersion,:hold,:inventory,:inventory,:fingerprint,:authority)",
                    new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("id",id).addValue("tenant",actor.tenantId()).addValue("request",record.requestId()).addValue("scope",record.resourceSetKey()).addValue("actor",actor.userId()).addValue("version",record.version()).addValue("policy",record.policyId()).addValue("policyVersion",record.policyVersion()).addValue("hold",record.holdVersion()).addValue("inventory",record.inventorySha256()).addValue("fingerprint",canonical.fingerprint(input)).addValue("authority",authoritySha));
        } catch(org.springframework.dao.DuplicateKeyException conflict) {throw ApprovalRetentionErrors.conflict();}
        return id;
    }
    public Claim claim(ApprovalRequestContext.Actor actor,String scope,UUID id) {
        tenant(actor);
        var rows=jdbc.query("""
            SELECT i.*,h.state actual_state,h.version head_version,
                (SELECT count(*) FROM apr_retention_foreign_requests f WHERE f.intent_id=i.intent_id) foreign_requests,
                (SELECT count(*) FROM apr_retention_foreign_requests f JOIN apr_retention_foreign_acknowledgements a USING(deletion_request_id)
                    WHERE f.intent_id=i.intent_id AND a.tenant_id=f.tenant_id AND a.consumer_service=f.consumer_service AND a.request_sha256=f.request_sha256) verified_acks
                ,(SELECT count(*)=2 AND bool_and(n=chunks AND chunks=smallest) FROM
                    (SELECT consumer_service,count(*) n,max(chunk_count) chunks,min(chunk_count) smallest
                    FROM apr_retention_foreign_requests WHERE intent_id=i.intent_id GROUP BY consumer_service) complete) complete_chunks
            FROM apr_retention_dispatch_intents i LEFT JOIN apr_record_retention_heads h ON h.tenant_id=i.tenant_id AND h.request_id=i.request_id AND h.claim_id=i.execution_claim_id
            WHERE i.tenant_id=:tenant AND i.resource_set_key=:scope AND i.intent_id=:id
            """,Map.of("tenant",actor.tenantId(),"scope",scope,"id",id),(r,n)->mapClaim(r));
        if(rows.size()!=1) throw ApprovalRetentionErrors.hidden();return rows.getFirst();
    }
    private Claim mapClaim(ResultSet r) throws SQLException {
        int requested=r.getInt("foreign_requests"),verified=r.getInt("verified_acks");
        String foreign=r.getBoolean("complete_chunks") && requested==verified?"ALL_DECLARED_COPIES_CONFIRMED":"VERIFIED_FOREIGN_COPY_ACKS_PENDING";
        return new Claim(r.getObject("intent_id",UUID.class),r.getObject("request_id",UUID.class),r.getString("resource_set_key"),
                r.getLong("version"),r.getString("actual_state")==null?r.getString("state"):r.getString("actual_state"),r.getString("reason_code"),
                r.getString("inventory_sha256"),r.getObject("execution_claim_id",UUID.class),requested,verified,foreign,"MANAGED_EXECUTION_REQUIRES_CONFIGURED_AUTH_AND_OWNER_PORTS");
    }
}
