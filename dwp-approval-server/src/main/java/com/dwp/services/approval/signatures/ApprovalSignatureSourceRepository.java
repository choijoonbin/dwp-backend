package com.dwp.services.approval.signatures;

import static com.dwp.services.approval.signatures.ApprovalSignatureCanonical.*;
import com.dwp.services.approval.document.ApprovalDocumentDtos.Rules;
import com.dwp.services.approval.document.ApprovalDocumentRenderer;
import com.dwp.services.approval.signatures.ApprovalSignatureAuthority.Verified;
import com.dwp.services.approval.signatures.ApprovalSignatureDtos.SourcePin;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

public final class ApprovalSignatureSourceRepository {
    public static final String RENDERER="DWP_SELF_ATTESTATION_JSON_V1";
    public static final class Snapshot {
        private final SourcePin pin;
        private final byte[] bytes;
        private final ApprovalSignatureRetentionFence.Snapshot retention;
        Snapshot(SourcePin pin, byte[] bytes) { this(pin,bytes,null); }
        Snapshot(SourcePin pin, byte[] bytes,ApprovalSignatureRetentionFence.Snapshot retention) { this.pin=pin; this.bytes=bytes.clone(); this.retention=retention; }
        public SourcePin pin() { return pin; }
        public byte[] bytes() { return bytes.clone(); }
        boolean sameCurrent(Snapshot other){return pin.equals(other.pin) && java.util.Objects.equals(retention,other.retention);}
    }
    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalSignatureCanonical canonical;
    private final ApprovalDocumentRenderer renderer;
    private final ApprovalSignatureEvidenceSigner signer;
    private final ApprovalSignatureRetentionFence retention;
    public ApprovalSignatureSourceRepository(NamedParameterJdbcTemplate jdbc, ApprovalSignatureCanonical canonical,
            ApprovalDocumentRenderer renderer, ApprovalSignatureEvidenceSigner signer) {
        this.jdbc=jdbc; this.canonical=canonical; this.renderer=renderer; this.signer=signer;this.retention=new ApprovalSignatureRetentionFence(jdbc);
    }
    void lockCurrent(Verified proof,UUID target,boolean ceremony){retention.require(proof,target,ceremony,true);}
    public Snapshot read(Verified authority, UUID requestId, boolean lock) {
        return capture(authority.tenantId(), authority.actorId(), authority.resourceSetKey(), requestId, lock);
    }
    // Native owner source capture is not a Verified authority and cannot authorize a ceremony command.
    Snapshot capture(long tenant, long actor, String resourceSetKey, UUID requestId, boolean lock) {
        boolean fenceLock=lock || org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
        var retained=retention.capture(tenant,actor,resourceSetKey,requestId,false,fenceLock);
        if(!retained.unclaimed())throw conflict();
        var p=new MapSqlParameterSource().addValue("tenant",tenant).addValue("actor",actor)
                .addValue("request",requestId).addValue("scope",resourceSetKey);
        String sql="""
            SELECT r.request_id,r.requester_user_id,r.version,r.title,r.request_number,r.data_classification,
                   r.form_version_id,r.workflow_version_id,r.management_resource_set_key,
                   p.schema_version,p.payload_sha256,p.payload::text AS payload,
                   f.schema_payload::text AS form_schema,f.schema_sha256,w.definition::text AS workflow,w.definition_sha256,
                   m.manifest_sha256,m.items::text AS manifest,
                   d.policy_id AS document_policy,d.version AS document_version,d.published_revision AS document_revision,
                   dv.rules::text AS document_rules,dv.rules_sha256 AS document_sha,
                   a.policy_id AS attachment_policy,a.version AS attachment_version,a.published_revision AS attachment_revision,
                   av.rules::text AS attachment_rules,av.rules_sha256 AS attachment_sha,
                   s.provider_id,s.version AS provider_version,s.lifecycle_state,s.capability_metadata::text AS provider_metadata
              FROM apr_requests r JOIN apr_tenants t ON t.tenant_id=r.tenant_id AND t.lifecycle_state='ACTIVE'
              JOIN apr_request_payloads p ON p.tenant_id=r.tenant_id AND p.request_id=r.request_id
              JOIN apr_request_payload_versions pv ON pv.tenant_id=p.tenant_id AND pv.request_id=p.request_id
                   AND pv.revision_number=p.schema_version AND pv.payload_sha256=p.payload_sha256 AND pv.payload=p.payload
              JOIN apr_form_versions f ON f.tenant_id=r.tenant_id AND f.form_version_id=r.form_version_id AND f.lifecycle_state='PUBLISHED'
              JOIN apr_workflow_versions w ON w.tenant_id=r.tenant_id AND w.workflow_version_id=r.workflow_version_id AND w.lifecycle_state='PUBLISHED'
              JOIN apr_attachment_manifests m ON m.tenant_id=p.tenant_id AND m.request_id=p.request_id
                   AND m.payload_revision=p.schema_version AND m.payload_sha256=p.payload_sha256
              JOIN apr_document_policy_heads d ON d.tenant_id=r.tenant_id AND d.resource_set_key=r.management_resource_set_key
              JOIN apr_document_policy_versions dv ON dv.tenant_id=d.tenant_id AND dv.policy_id=d.policy_id AND dv.revision=d.published_revision
              JOIN apr_attachment_policy_heads a ON a.tenant_id=r.tenant_id AND a.resource_set_key=r.management_resource_set_key
              JOIN apr_attachment_policy_versions av ON av.tenant_id=a.tenant_id AND av.policy_id=a.policy_id AND av.revision=a.published_revision
              JOIN apr_signature_providers s ON s.tenant_id=r.tenant_id AND s.management_resource_set_key=r.management_resource_set_key
                   AND s.provider_type='INTERNAL_ATTESTATION'
             WHERE r.tenant_id=:tenant AND r.request_id=:request AND r.requester_user_id=:actor
                   AND r.management_resource_set_key=:scope AND r.status='APPROVED' AND r.deleted_at IS NULL
            """;
        if (lock) sql+=" FOR SHARE OF r,t,p,pv,f,w,m,d,dv,a,av,s";
        var rows=jdbc.query(sql,p,(r,n) -> {
            String payload=r.getString("payload"), schema=r.getString("form_schema"), workflow=r.getString("workflow"), manifest=r.getString("manifest");
            Rules rules=canonical.read(r.getString("document_rules"),Rules.class);
            try { new com.dwp.services.approval.document.ApprovalDocumentPolicy().validate(rules); }
            catch (RuntimeException malformed) { throw unavailable(); }
            if (!rules.allowJsonExport() || !rules.allowedClassifications().contains(r.getString("data_classification"))
                    || rules.includeComments() || rules.includeEvidence() || !"ACTIVE".equals(r.getString("lifecycle_state"))) throw denied();
            // No incomplete comments/evidence projections and no historical empty-manifest healing.
            checkDigest(canonical.read(payload,JsonNode.class),r.getString("payload_sha256"));
            checkDigest(canonical.read(schema,JsonNode.class),r.getString("schema_sha256"));
            checkDigest(canonical.read(workflow,JsonNode.class),r.getString("definition_sha256"));
            checkDigest(canonical.read(manifest,JsonNode.class),r.getString("manifest_sha256"));
            checkDigest(canonical.read(r.getString("document_rules"),JsonNode.class),r.getString("document_sha"));
            checkDigest(rules,r.getString("document_sha"));
            checkDigest(canonical.read(r.getString("attachment_rules"),JsonNode.class),r.getString("attachment_sha"));
            verifyItems(p,manifest,lock);
            byte[] bytes=canonical.json(Map.of("contract",RENDERER,"requestId",requestId,"requestNumber",r.getString("request_number"),
                    "title",r.getString("title"),"fields",renderer.fields(payload,schema,rules.fields()),
                    "attachments",publicManifest(manifest))).getBytes(StandardCharsets.UTF_8);
            if (bytes.length>rules.maxBytes() || bytes.length>5242880) throw denied();
            String providerSha=canonical.digest(Map.of("providerType","INTERNAL_ATTESTATION","lifecycleState",r.getString("lifecycle_state"),
                    "metadata",canonical.read(r.getString("provider_metadata"),JsonNode.class)));
            for (String column:java.util.List.of("version","document_version","attachment_version","provider_version")) {
                long version=r.getLong(column); if (version<0 || version>ApprovalSignatureDtos.MAX_VERSION) throw unavailable();
            }
            return new Snapshot(new SourcePin(requestId,r.getLong("version"),r.getInt("schema_version"),r.getString("payload_sha256"),
                    r.getObject("form_version_id",UUID.class),r.getString("schema_sha256"),r.getObject("workflow_version_id",UUID.class),r.getString("definition_sha256"),
                    resourceSetKey,r.getString("manifest_sha256"),r.getObject("document_policy",UUID.class),r.getLong("document_version"),r.getInt("document_revision"),r.getString("document_sha"),
                    r.getObject("attachment_policy",UUID.class),r.getLong("attachment_version"),r.getInt("attachment_revision"),r.getString("attachment_sha"),
                    r.getObject("provider_id",UUID.class),r.getLong("provider_version"),providerSha,actor,RENDERER,sha(bytes),signer.keySha256()),bytes,retained);
        });
        if (rows.size()!=1) throw hidden();retention.unchanged(retained,tenant,actor,resourceSetKey,fenceLock);return rows.getFirst();
    }
    private void checkDigest(Object value,String expected) { if (!canonical.digest(value).equals(expected)) throw unavailable(); }
    private void verifyItems(MapSqlParameterSource p,String manifest,boolean lock) {
        JsonNode items=canonical.read(manifest,JsonNode.class); if (!items.isArray() || items.size()>10) throw unavailable();
        var seen=new java.util.HashSet<String>();
        for (JsonNode item:items) {
            if (!item.isObject()) throw unavailable();
            var keys=new java.util.HashSet<String>(); item.fieldNames().forEachRemaining(keys::add);
            if (!keys.equals(java.util.Set.of("attachmentId","sha256","objectVersion","objectKey","sizeBytes","fileName","mediaType"))) throw unavailable();
            for (String key:keys) if (!key.equals("sizeBytes") && (!item.path(key).isTextual() || item.path(key).textValue().isBlank())) throw unavailable();
            if (!item.path("sizeBytes").isIntegralNumber() || !item.path("sizeBytes").canConvertToLong() || item.path("sizeBytes").longValue()<1
                    || !item.path("sha256").textValue().matches("[a-f0-9]{64}")) throw unavailable();
            String id=item.path("attachmentId").textValue(); UUID attachment;
            try { attachment=UUID.fromString(id); if (!attachment.toString().equals(id)) throw unavailable(); }
            catch (IllegalArgumentException invalid) { throw unavailable(); }
            if (!seen.add(id)) throw unavailable(); p.addValue("attachment",attachment);
            var uploads=jdbc.query("""
                    SELECT content_sha256,object_version,object_key,size_bytes,file_name,media_type FROM apr_attachment_uploads
                     WHERE tenant_id=:tenant AND request_id=:request AND attachment_id=:attachment AND state='AVAILABLE'
                       AND av_state='AV_CLEAR' AND passive_content_state='PASSIVE_ALLOWED'
                       AND object_version IS NOT NULL AND object_key IS NOT NULL AND content_sha256 IS NOT NULL
                       AND retain_until>clock_timestamp()
                    """+(lock?" FOR SHARE":""),p,(r,n) -> Map.<String,Object>of("sha256",r.getString(1),"objectVersion",r.getString(2),"objectKey",r.getString(3),
                            "sizeBytes",r.getLong(4),"fileName",r.getString(5),"mediaType",r.getString(6),"attachmentId",id));
            if (uploads.size()!=1 || !canonical.digest(uploads.getFirst()).equals(canonical.digest(item))) throw unavailable();
        }
    }
    private Object publicManifest(String manifest) {
        var result=new java.util.ArrayList<Object>();
        for (JsonNode item:canonical.read(manifest,JsonNode.class)) result.add(Map.of("attachmentId",item.path("attachmentId").asText(),
                "fileName",item.path("fileName").asText(),"sha256",item.path("sha256").asText(),"sizeBytes",item.path("sizeBytes").longValue(),
                "mediaType",item.path("mediaType").asText()));
        return result;
    }
}
