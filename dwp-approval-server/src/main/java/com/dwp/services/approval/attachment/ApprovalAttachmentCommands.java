package com.dwp.services.approval.attachment;

import com.dwp.services.approval.document.ApprovalDocumentCanonical;
import com.dwp.services.approval.security.ApprovalRequestContext;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Map;
import static com.dwp.services.approval.document.ApprovalDocumentOwnerRepository.params;

@Repository
public class ApprovalAttachmentCommands {
    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalDocumentCanonical canonical;
    public ApprovalAttachmentCommands(NamedParameterJdbcTemplate jdbc,ApprovalDocumentCanonical canonical) {this.jdbc=jdbc;this.canonical=canonical;}
    public Map<String,Object> replay(ApprovalRequestContext.Actor actor,String route,String key,Object input) {
        if (key==null || !key.matches("[A-Za-z0-9._:-]{1,120}")) throw ApprovalDocumentCanonical.conflict();
        var p=params(actor).addValue("route",route).addValue("key",key);
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey,0))",p.addValue("lockKey",actor.tenantId()+":"+actor.userId()+":"+route+":"+key),r->null);
        return jdbc.query("SELECT fingerprint,metadata::text FROM apr_attachment_command_receipts WHERE tenant_id=:tenant AND actor_user_id=:actor AND route=:route AND idempotency_key=:key",p,r->{
            if (!r.next()) return null;
            if (!canonical.fingerprint(input).equals(r.getString(1))) throw ApprovalDocumentCanonical.conflict();
            @SuppressWarnings("unchecked") Map<String,Object> metadata=canonical.read(r.getString(2),Map.class);return metadata;
        });
    }
    public void complete(ApprovalRequestContext.Actor actor,String route,String key,Object input,Map<String,Object> metadata,int retentionDays) {
        if (retentionDays<1 || retentionDays>3650) throw ApprovalDocumentCanonical.conflict();
        jdbc.update("""
                INSERT INTO apr_attachment_command_receipts(tenant_id,actor_user_id,route,idempotency_key,fingerprint,metadata,retain_until)
                VALUES(:tenant,:actor,:route,:key,:fingerprint,CAST(:metadata AS jsonb),clock_timestamp()+make_interval(days=>:days))
                """,params(actor).addValue("route",route).addValue("key",key).addValue("fingerprint",canonical.fingerprint(input)).addValue("metadata",canonical.json(metadata)).addValue("days",retentionDays));
    }
}
