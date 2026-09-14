package com.dwp.services.approval.signatures;

import static com.dwp.services.approval.signatures.ApprovalSignatureCanonical.*;
import com.dwp.services.approval.signatures.ApprovalSignatureAuthority.Verified;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Current native retention metadata only; absence is observed, never provisioned or called LIVE. */
final class ApprovalSignatureRetentionFence {
    private static final Set<String> STATES=Set.of("LIVE","PREPARED","IRREVERSIBLE","IRREVERSIBLE_BLOCKED","OBJECTS_CONFIRMED","LOCAL_DB_PURGED","COMPLETE");
    private final NamedParameterJdbcTemplate jdbc;
    ApprovalSignatureRetentionFence(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}
    record Snapshot(UUID requestId,long requestVersion,String requestStatus,boolean headPresent,String state,Long headVersion,UUID claimId,String inventorySha256){
        boolean unclaimed(){return !headPresent || "LIVE".equals(state);}
    }
    Snapshot require(Verified proof,UUID target,boolean ceremony,boolean lock){
        var current=capture(proof.tenantId(),proof.actorId(),proof.resourceSetKey(),target,ceremony,lock);
        if(!current.unclaimed())throw conflict();return current;
    }
    Snapshot capture(long tenant,long actor,String rs,UUID target,boolean ceremony,boolean lock){
        if(lock && (!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()))throw unavailable();
        Boolean fresh=jdbc.getJdbcTemplate().execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection->connection.getTransactionIsolation()==java.sql.Connection.TRANSACTION_READ_COMMITTED);
        if(!Boolean.TRUE.equals(fresh))throw unavailable();
        var p=new MapSqlParameterSource().addValue("tenant",tenant).addValue("actor",actor).addValue("rs",rs).addValue("target",target);
        String joins=ceremony?" JOIN apr_self_attestations c ON c.tenant_id=r.tenant_id AND c.request_id=r.request_id":"";
        String targetFilter=ceremony?"c.signature_request_id=:target AND c.owner_user_id=:actor AND c.signer_user_id=:actor AND c.signer_kind='SELF_ATTESTATION' AND c.resource_set_key=:rs":"r.request_id=:target";
        // Retention claim also locks the request first; do this before advisory/ceremony locks.
        String sql="SELECT r.request_id,r.version,r.status FROM apr_requests r JOIN apr_tenants t ON t.tenant_id=r.tenant_id AND t.lifecycle_state='ACTIVE'"+joins
                +" WHERE r.tenant_id=:tenant AND r.requester_user_id=:actor AND r.management_resource_set_key=:rs AND r.deleted_at IS NULL AND "+targetFilter
                ;
        var requests=jdbc.query(sql,p,(r,n)->new Request(r.getObject("request_id",UUID.class),r.getLong("version"),r.getString("status")));
        if(requests.size()!=1)throw unavailable();var request=requests.getFirst();
        if(lock){
            new com.dwp.services.approval.documentretention.ApprovalRetentionLiveGuard(jdbc).request(tenant,request.id());
            var current=jdbc.query(sql,p,(r,n)->new Request(r.getObject("request_id",UUID.class),r.getLong("version"),r.getString("status")));
            if(current.size()!=1 || !request.equals(current.getFirst()))throw conflict();
        }
        if(request.version()<0 || request.version()>ApprovalSignatureDtos.MAX_VERSION)throw unavailable();
        p.addValue("request",request.id());
        var heads=jdbc.query("""
            SELECT h.state,h.version,h.claim_id,h.inventory_sha256,c.inventory_sha256 AS claim_sha
              FROM apr_record_retention_heads h LEFT JOIN apr_record_purge_claims c
                ON c.tenant_id=h.tenant_id AND c.request_id=h.request_id AND c.claim_id=h.claim_id
             WHERE h.tenant_id=:tenant AND h.request_id=:request
            """+(lock?" FOR SHARE OF h":""),p,(r,n)->{
                String state=r.getString("state"),inventory=r.getString("inventory_sha256");long version=r.getLong("version");UUID claim=r.getObject("claim_id",UUID.class);
                if(!STATES.contains(state) || version<0 || version>ApprovalSignatureDtos.MAX_VERSION)throw unavailable();
                if("LIVE".equals(state)){
                    if(claim!=null || inventory!=null)throw unavailable();
                }else if(claim==null || inventory==null || !inventory.matches("[a-f0-9]{64}") || !inventory.equals(r.getString("claim_sha")))throw unavailable();
                return new Snapshot(request.id(),request.version(),request.status(),true,state,version,claim,inventory);
            });
        if(heads.size()>1)throw unavailable();
        return heads.isEmpty()?new Snapshot(request.id(),request.version(),request.status(),false,null,null,null,null):heads.getFirst();
    }
    void unchanged(Snapshot original,long tenant,long actor,String rs,boolean lock){
        if(!original.equals(capture(tenant,actor,rs,original.requestId(),false,lock)))throw conflict();
    }
    private record Request(UUID id,long version,String status){ }
}
