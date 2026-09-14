package com.dwp.services.approval.documentretention.management;

import com.dwp.services.approval.document.ApprovalDocumentCanonical;
import com.dwp.services.approval.security.ApprovalRequestContext;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.dwp.services.approval.documentretention.management.ApprovalRetentionForeignDtos.*;

@Service
public class ApprovalRetentionForeignJournal {
    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalRetentionInventory inventory;
    private final ApprovalDocumentCanonical canonical;
    private final ApprovalRetentionForeignAckVerifier verifier;
    public ApprovalRetentionForeignJournal(NamedParameterJdbcTemplate jdbc,ApprovalRetentionInventory inventory,
            ApprovalDocumentCanonical canonical,ApprovalRetentionForeignAckVerifier verifier) {
        this.jdbc=jdbc;this.inventory=inventory;this.canonical=canonical;this.verifier=verifier;
    }
    public void prepare(ApprovalRequestContext.Actor actor,UUID intent,UUID request,String inventorySha) {
        for(String consumer:List.of("AUDIT","NOTIFICATION")) {
            var ids=inventory.deliveredEvents(actor.tenantId(),request,consumer);
            int chunks=Math.max(1,(ids.size()+99)/100);if(chunks>1000) throw ApprovalRetentionErrors.conflict();
            for(int index=0;index<chunks;index++) {
                var events=ids.subList(Math.min(index*100,ids.size()),Math.min((index+1)*100,ids.size()));
                UUID id=UUID.randomUUID();var payload=new DeletionRequest(id,actor.tenantId(),intent,request,consumer,index,chunks,events,inventorySha,ApprovalRetentionForeignAckVerifier.PURPOSE);
                jdbc.update("INSERT INTO apr_retention_foreign_requests(deletion_request_id,tenant_id,intent_id,consumer_service,chunk_index,chunk_count,producer_event_ids,inventory_sha256,request_sha256) VALUES(:id,:tenant,:intent,:consumer,:index,:count,CAST(:events AS jsonb),:inventory,:sha)",
                        Map.of("id",id,"tenant",actor.tenantId(),"intent",intent,"consumer",consumer,"index",index,"count",chunks,"events",canonical.json(events),"inventory",inventorySha,"sha",canonical.fingerprint(payload)));
            }
        }
    }
    @Transactional public List<DeletionRequest> dispatchable(UUID intent,String consumer) {
        if(!Set.of("AUDIT","NOTIFICATION").contains(consumer)) throw ApprovalRetentionErrors.forbidden();
        return jdbc.query("""
            SELECT f.*,i.request_id FROM apr_retention_foreign_requests f JOIN apr_retention_dispatch_intents i USING(tenant_id,intent_id)
                JOIN apr_record_retention_heads h ON h.tenant_id=i.tenant_id AND h.request_id=i.request_id AND h.claim_id=i.execution_claim_id
            WHERE i.intent_id=:intent AND f.consumer_service=:consumer AND h.state IN('IRREVERSIBLE','OBJECTS_CONFIRMED','LOCAL_DB_PURGED')
                AND NOT EXISTS(SELECT 1 FROM apr_retention_foreign_acknowledgements a WHERE a.deletion_request_id=f.deletion_request_id)
            ORDER BY chunk_index
            """,Map.of("intent",intent,"consumer",consumer),(r,n)->{
                var events=canonical.read(r.getString("producer_event_ids"),UUID[].class);
                var request=new DeletionRequest(r.getObject("deletion_request_id",UUID.class),r.getLong("tenant_id"),intent,r.getObject("request_id",UUID.class),
                        consumer,r.getInt("chunk_index"),r.getInt("chunk_count"),List.of(events),r.getString("inventory_sha256"),ApprovalRetentionForeignAckVerifier.PURPOSE);
                if(!canonical.fingerprint(request).equals(r.getString("request_sha256"))) throw ApprovalRetentionErrors.unavailable();return request;
            });
    }
    @Transactional public void acknowledge(String consumer,SignedAck signed) {
        var proof=verifier.verify(consumer,signed);var c=proof.claims();
        var rows=jdbc.queryForList("""
            SELECT f.*,h.state FROM apr_retention_foreign_requests f JOIN apr_retention_dispatch_intents i USING(tenant_id,intent_id)
                JOIN apr_record_retention_heads h ON h.tenant_id=i.tenant_id AND h.request_id=i.request_id AND h.claim_id=i.execution_claim_id
            WHERE f.deletion_request_id=:id AND f.tenant_id=:tenant AND f.intent_id=:intent
                AND h.state IN('IRREVERSIBLE','OBJECTS_CONFIRMED','LOCAL_DB_PURGED') FOR UPDATE OF h FOR SHARE OF f
            """,Map.of("id",c.deletionRequestId(),"tenant",c.tenantId(),"intent",c.intentId()));
        if(rows.size()!=1 || !consumer.equals(rows.getFirst().get("consumer_service")) || !c.requestSha256().equals(rows.getFirst().get("request_sha256"))) throw ApprovalRetentionErrors.forbidden();
        var prior=jdbc.queryForList("SELECT signed_proof_sha256 FROM apr_retention_foreign_acknowledgements WHERE deletion_request_id=:id",Map.of("id",c.deletionRequestId()));
        if(!prior.isEmpty()) {if(!proof.proofSha().equals(prior.getFirst().get("signed_proof_sha256"))) throw ApprovalRetentionErrors.conflict();return;}
        try {
            jdbc.update("INSERT INTO apr_retention_foreign_acknowledgements(deletion_request_id,tenant_id,consumer_service,request_sha256,consumer_inventory_sha256,outcome,issuer,key_id,nonce,signed_proof_sha256) VALUES(:id,:tenant,:consumer,:sha,:inventory,:outcome,:issuer,:keyId,:nonce,:proof)",
                    Map.of("id",c.deletionRequestId(),"tenant",c.tenantId(),"consumer",consumer,"sha",c.requestSha256(),"inventory",c.consumerInventorySha256(),"outcome",c.outcome(),"issuer",c.issuer(),"keyId",c.keyId(),"nonce",c.nonce(),"proof",proof.proofSha()));
        } catch(org.springframework.dao.DuplicateKeyException collision) {throw ApprovalRetentionErrors.conflict();}
    }
    @Transactional public void deliver(UUID intent,ApprovalRetentionForeignPort port) {
        for(var request:dispatchable(intent,port.consumerService())) acknowledge(port.consumerService(),port.deleteDeclaredCopies(request));
    }
}
