package com.dwp.services.approval.signatures;

import static com.dwp.services.approval.signatures.ApprovalSignatureCanonical.*;
import static com.dwp.services.approval.signatures.ApprovalSignatureDtos.*;
import com.dwp.services.approval.signatures.ApprovalSignatureAuthority.Verified;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public final class ApprovalSignatureRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalSignatureCanonical canonical;
    private final ApprovalSignatureRetentionFence retention;
    public ApprovalSignatureRepository(NamedParameterJdbcTemplate jdbc,ApprovalSignatureCanonical canonical) { this.jdbc=jdbc; this.canonical=canonical;this.retention=new ApprovalSignatureRetentionFence(jdbc); }
    static MapSqlParameterSource params(Verified authority) {
        return new MapSqlParameterSource().addValue("tenant",authority.tenantId()).addValue("actor",authority.actorId()).addValue("scope",authority.resourceSetKey());
    }
    public void serializeCommand(Verified authority,String operation,String key) {
        // Transaction-scoped serialization includes CREATE, where no ceremony head exists yet.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))",params(authority)
                .addValue("key",authority.tenantId()+":"+authority.actorId()+":"+operation+":"+key),Object.class);
    }
    public Receipt prior(Verified authority,String operation,String key,Object body) {
        var p=params(authority).addValue("operation",operation).addValue("key",key);
        var rows=jdbc.query("SELECT body_sha256,private_body::text,context_scope_key,result::text,request_id,signature_request_id FROM apr_self_attestation_commands "
                + "WHERE tenant_id=:tenant AND actor_user_id=:actor AND operation=:operation AND idempotency_key=:key",p,(r,n) -> {
            if (!r.getString(1).equals(canonical.digest(body)) || !canonical.digest(canonical.read(r.getString(2),Object.class)).equals(canonical.digest(body))
                    || !r.getString(3).equals(authority.contextScopeKey()) || !operation.equals(authority.operation().name())
                    || !authority.objectId().equals(r.getObject(authority.operation()==ApprovalSignatureAuthority.Operation.CREATE?5:6,UUID.class))) throw conflict();
            return canonical.read(r.getString(4),Receipt.class);
        });
        return rows.isEmpty()?null:rows.getFirst();
    }
    public Ceremony create(Verified authority,ApprovalSignatureSourceRepository.Snapshot snapshot,Terms terms,String digest,Instant now,Instant expires) {
        SourcePin pin=snapshot.pin(); UUID id=UUID.randomUUID(),artifact=UUID.randomUUID();
        var p=params(authority).addValue("request",pin.requestId()).addValue("id",id).addValue("artifact",artifact)
                .addValue("bytes",snapshot.bytes()).addValue("sha",pin.artifactSha256()).addValue("renderer",pin.rendererVersion())
                .addValue("payload",pin.payloadRevision()).addValue("form",pin.formVersionId()).addValue("workflow",pin.workflowVersionId())
                .addValue("provider",pin.providerId()).addValue("documentPolicy",pin.documentPolicyId()).addValue("documentRevision",pin.documentPolicyRevision())
                .addValue("attachmentPolicy",pin.attachmentPolicyId()).addValue("attachmentRevision",pin.attachmentPolicyRevision())
                .addValue("source",canonical.json(pin)).addValue("terms",canonical.json(terms)).addValue("digest",digest)
                .addValue("now",Timestamp.from(now)).addValue("expires",Timestamp.from(expires));
        jdbc.update("INSERT INTO apr_self_attestation_artifacts(tenant_id,request_id,artifact_id,renderer_version,bytes,artifact_sha256) "
                + "VALUES(:tenant,:request,:artifact,:renderer,:bytes,:sha)",p);
        jdbc.update("""
                INSERT INTO apr_self_attestations(tenant_id,request_id,signature_request_id,owner_user_id,signer_user_id,resource_set_key,
                  artifact_id,payload_revision,form_version_id,workflow_version_id,provider_id,document_policy_id,document_policy_revision,
                  attachment_policy_id,attachment_policy_revision,source_pin,terms,source_digest,created_at,expires_at)
                VALUES(:tenant,:request,:id,:actor,:actor,:scope,:artifact,:payload,:form,:workflow,:provider,:documentPolicy,:documentRevision,
                  :attachmentPolicy,:attachmentRevision,CAST(:source AS jsonb),CAST(:terms AS jsonb),:digest,:now,:expires)
                """,p);
        return read(authority,id,true);
    }
    public Ceremony read(Verified authority,UUID id,boolean lock) {
        boolean fenceLock=lock || org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
        var retained=retention.require(authority,id,true,fenceLock);
        var p=params(authority).addValue("id",id);
        var rows=jdbc.query("""
                SELECT c.*,a.bytes,a.artifact_sha256,e.evidence::text AS proof
                  FROM apr_self_attestations c JOIN apr_self_attestation_artifacts a
                    ON a.tenant_id=c.tenant_id AND a.request_id=c.request_id AND a.artifact_id=c.artifact_id
                  JOIN apr_requests r ON r.tenant_id=c.tenant_id AND r.request_id=c.request_id
                  JOIN apr_tenants t ON t.tenant_id=c.tenant_id AND t.lifecycle_state='ACTIVE'
                  LEFT JOIN apr_self_attestation_evidence e ON e.tenant_id=c.tenant_id AND e.signature_request_id=c.signature_request_id
                 WHERE c.tenant_id=:tenant AND c.signature_request_id=:id AND c.owner_user_id=:actor AND c.signer_user_id=:actor
                   AND c.resource_set_key=:scope AND r.requester_user_id=:actor AND r.management_resource_set_key=:scope AND r.deleted_at IS NULL
                """+(lock?" FOR UPDATE OF c":""),p,(r,n) -> {
            SourcePin pin=canonical.read(r.getString("source_pin"),SourcePin.class); Terms terms=canonical.read(r.getString("terms"),Terms.class);
            byte[] bytes=r.getBytes("bytes"); String sha=sha(bytes),digest=r.getString("source_digest");
            if (!sha.equals(r.getString("artifact_sha256")) || !sha.equals(pin.artifactSha256())
                    || !canonical.digest(java.util.Map.of("source",pin,"terms",terms)).equals(digest)) throw unavailable();
            return new Ceremony(id,r.getObject("request_id",UUID.class),SignerKind.SELF_ATTESTATION,State.valueOf(r.getString("state")),
                    r.getLong("version"),pin,digest,new Artifact(pin.rendererVersion(),"application/json",sha,bytes.length,new String(bytes,StandardCharsets.UTF_8)),
                    terms,r.getTimestamp("expires_at").toInstant(),r.getObject("consent_receipt_id",UUID.class),
                    r.getString("proof")==null?null:canonical.read(r.getString("proof"),Evidence.class),"NOT_WORM_VERIFIED");
        });
        if (rows.size()!=1) throw hidden();retention.unchanged(retained,authority.tenantId(),authority.actorId(),authority.resourceSetKey(),fenceLock);return rows.getFirst();
    }
    public UUID consent(Verified authority,Ceremony original,Instant now) {
        UUID id=UUID.randomUUID(); Terms t=original.terms();
        var p=params(authority).addValue("id",original.signatureRequestId()).addValue("consent",id).addValue("terms",t.termsId())
                .addValue("termsVersion",t.version()).addValue("sha",t.sha256()).addValue("locale",t.locale()).addValue("digest",original.sourceDigest())
                .addValue("now",Timestamp.from(now)).addValue("expires",Timestamp.from(original.expiresAt()));
        jdbc.update("""
                INSERT INTO apr_self_attestation_consents(tenant_id,signature_request_id,consent_receipt_id,signer_user_id,accepted,
                  terms_id,terms_version,terms_sha256,locale,source_digest,consented_at,expires_at)
                VALUES(:tenant,:id,:consent,:actor,true,:terms,:termsVersion,:sha,:locale,:digest,:now,:expires)
                """,p);
        transition(authority,original,State.CONSENTED,id); return id;
    }
    public void validateConsent(Verified authority,Ceremony original,UUID id,Instant now) {
        var p=params(authority).addValue("id",original.signatureRequestId()).addValue("consent",id).addValue("digest",original.sourceDigest())
                .addValue("now",Timestamp.from(now)).addValue("terms",original.terms().termsId()).addValue("termsVersion",original.terms().version())
                .addValue("termsSha",original.terms().sha256()).addValue("locale",original.terms().locale());
        Boolean valid=jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM apr_self_attestation_consents WHERE tenant_id=:tenant AND signature_request_id=:id
                 AND consent_receipt_id=:consent AND signer_user_id=:actor AND accepted AND source_digest=:digest AND expires_at>:now
                 AND terms_id=:terms AND terms_version=:termsVersion AND terms_sha256=:termsSha AND locale=:locale)
                """,p,Boolean.class);
        if (!Boolean.TRUE.equals(valid) || !id.equals(original.consentReceiptId())) throw conflict();
    }
    public void attest(Verified authority,Ceremony original,Evidence evidence) {
        jdbc.update("INSERT INTO apr_self_attestation_evidence(tenant_id,signature_request_id,evidence_id,consent_receipt_id,evidence) "
                + "VALUES(:tenant,:id,:evidence,:consent,CAST(:proof AS jsonb))",params(authority).addValue("id",original.signatureRequestId())
                .addValue("evidence",evidence.evidenceId()).addValue("consent",original.consentReceiptId()).addValue("proof",canonical.json(evidence)));
        transition(authority,original,State.ATTESTED,original.consentReceiptId());
    }
    public void cancel(Verified authority,Ceremony original) { transition(authority,original,State.CANCELLED,original.consentReceiptId()); }
    private void transition(Verified authority,Ceremony original,State target,UUID consent) {
        int changed=jdbc.update("UPDATE apr_self_attestations SET state=:state,consent_receipt_id=:consent,version=version+1 "
                + "WHERE tenant_id=:tenant AND signature_request_id=:id AND version=:version AND source_digest=:digest AND owner_user_id=:actor AND resource_set_key=:scope",
                params(authority).addValue("id",original.signatureRequestId()).addValue("version",original.version()).addValue("digest",original.sourceDigest())
                        .addValue("state",target.name()).addValue("consent",consent));
        if (changed!=1) throw conflict();
    }
    public Receipt complete(Verified authority,String operation,String key,Object body,Ceremony result,Instant now) {
        Receipt receipt=new Receipt(UUID.randomUUID(),"COMMITTED",now,result);
        var p=params(authority).addValue("id",result.signatureRequestId()).addValue("request",result.requestId()).addValue("operation",operation)
                .addValue("key",key).addValue("context",authority.contextScopeKey()).addValue("digest",canonical.digest(body)).addValue("body",canonical.json(body))
                .addValue("receipt",receipt.commandReceiptId()).addValue("result",canonical.json(receipt)).addValue("event",UUID.randomUUID())
                .addValue("sequence",result.version()).addValue("source",result.sourceDigest()).addValue("now",Timestamp.from(now));
        jdbc.update("""
                INSERT INTO apr_self_attestation_commands(tenant_id,actor_user_id,operation,idempotency_key,context_scope_key,request_id,
                  signature_request_id,body_sha256,private_body,command_receipt_id,result)
                VALUES(:tenant,:actor,:operation,:key,:context,:request,:id,:digest,CAST(:body AS jsonb),:receipt,CAST(:result AS jsonb))
                """,p);
        jdbc.update("INSERT INTO apr_self_attestation_events(tenant_id,signature_request_id,event_id,sequence,action,actor_user_id,source_digest,occurred_at) "
                + "VALUES(:tenant,:id,:event,:sequence,:operation,:actor,:source,:now)",p);
        return receipt;
    }
    public Audit audit(Verified authority,UUID id) {
        var rows=jdbc.query("SELECT event_id,sequence,action,source_digest,occurred_at FROM apr_self_attestation_events "
                + "WHERE tenant_id=:tenant AND signature_request_id=:id ORDER BY sequence LIMIT 1001",params(authority).addValue("id",id),
                (r,n) -> new Event(r.getObject(1,UUID.class),r.getLong(2),r.getString(3),r.getString(4),r.getTimestamp(5).toInstant()));
        return new Audit(java.util.List.copyOf(rows.subList(0,Math.min(1000,rows.size()))),rows.size()>1000);
    }
}
