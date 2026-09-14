package com.dwp.services.approval.signatures;

import static com.dwp.services.approval.signatures.ApprovalSignatureCanonical.*;
import com.dwp.services.approval.signatures.ApprovalSignatureDtos.SourcePin;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Metadata/hash-only current source capture. No renderer, artifact bytes, terms or payload projection. */
final class ApprovalSignatureReceiptCurrentRepository {
    private final NamedParameterJdbcTemplate jdbc;private final ApprovalSignatureCanonical json;private final Supplier<String> signingKeySha;
    ApprovalSignatureReceiptCurrentRepository(NamedParameterJdbcTemplate jdbc,ApprovalSignatureCanonical json,Supplier<String> signingKeySha){this.jdbc=jdbc;this.json=json;this.signingKeySha=signingKeySha;}
    record Current(boolean matches,String digest){ }
    Current capture(long tenant,long actor,String rs,UUID request,UUID ceremony,SourcePin original) {
        var retention=new ApprovalSignatureRetentionFence(jdbc);var retentionSnapshot=retention.capture(tenant,actor,rs,request,false,false);
        if(!retentionSnapshot.unclaimed()){
            retention.unchanged(retentionSnapshot,tenant,actor,rs,false);
            return new Current(false,json.digest(Map.of("retention",retentionSnapshot)));
        }
        var p=new MapSqlParameterSource().addValue("tenant",tenant).addValue("actor",actor).addValue("rs",rs).addValue("request",request).addValue("ceremony",ceremony);
        var rows=jdbc.query("""
            SELECT r.version,r.status,r.form_version_id,r.workflow_version_id,p.schema_version,p.payload_sha256,
                   f.schema_sha256,w.definition_sha256,m.manifest_sha256,
                   d.policy_id AS document_id,d.version AS document_version,d.published_revision AS document_revision,dv.rules_sha256 AS document_sha,
                   a.policy_id AS attachment_id,a.version AS attachment_version,a.published_revision AS attachment_revision,av.rules_sha256 AS attachment_sha,
                   s.provider_id,s.version AS provider_version,
                   encode(sha256(convert_to(approval_typed_form_canonical_json(jsonb_build_object('providerType','INTERNAL_ATTESTATION',
                      'lifecycleState',s.lifecycle_state,'metadata',s.capability_metadata)),'UTF8')),'hex') AS provider_sha,
                   artifact.renderer_version,artifact.artifact_sha256,
                   jsonb_typeof(m.items)='array' AND jsonb_array_length(m.items)<=10
                   AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(m.items) i WHERE NOT EXISTS(
                       SELECT 1 FROM apr_attachment_uploads u WHERE u.tenant_id=r.tenant_id AND u.request_id=r.request_id
                       AND u.attachment_id::text=i->>'attachmentId' AND u.content_sha256::text=i->>'sha256'
                       AND u.object_version=i->>'objectVersion' AND u.object_key=i->>'objectKey' AND u.size_bytes::text=i->>'sizeBytes'
                       AND u.file_name=i->>'fileName' AND u.media_type=i->>'mediaType' AND u.state='AVAILABLE'
                       AND u.av_state='AV_CLEAR' AND u.passive_content_state='PASSIVE_ALLOWED' AND u.retain_until>clock_timestamp())) AS retention_valid,
                   p.payload_sha256::text=encode(sha256(convert_to(approval_typed_form_canonical_json(p.payload),'UTF8')),'hex')
                   AND f.schema_sha256::text=encode(sha256(convert_to(approval_typed_form_canonical_json(f.schema_payload),'UTF8')),'hex')
                   AND w.definition_sha256::text=encode(sha256(convert_to(approval_typed_form_canonical_json(w.definition),'UTF8')),'hex')
                   AND m.manifest_sha256::text=encode(sha256(convert_to(approval_typed_form_canonical_json(m.items),'UTF8')),'hex')
                   AND dv.rules_sha256::text=encode(sha256(convert_to(approval_typed_form_canonical_json(dv.rules),'UTF8')),'hex')
                   AND av.rules_sha256::text=encode(sha256(convert_to(approval_typed_form_canonical_json(av.rules),'UTF8')),'hex') AS hashes_valid
              FROM apr_requests r JOIN apr_tenants t ON t.tenant_id=r.tenant_id AND t.lifecycle_state='ACTIVE'
              JOIN apr_request_payloads p ON p.tenant_id=r.tenant_id AND p.request_id=r.request_id
              JOIN apr_request_payload_versions pv ON pv.tenant_id=p.tenant_id AND pv.request_id=p.request_id AND pv.revision_number=p.schema_version
                   AND pv.payload_sha256=p.payload_sha256 AND pv.payload=p.payload
              JOIN apr_form_versions f ON f.tenant_id=r.tenant_id AND f.form_version_id=r.form_version_id AND f.lifecycle_state='PUBLISHED'
              JOIN apr_workflow_versions w ON w.tenant_id=r.tenant_id AND w.workflow_version_id=r.workflow_version_id AND w.lifecycle_state='PUBLISHED'
              JOIN apr_attachment_manifests m ON m.tenant_id=p.tenant_id AND m.request_id=p.request_id AND m.payload_revision=p.schema_version AND m.payload_sha256=p.payload_sha256
              JOIN apr_document_policy_heads d ON d.tenant_id=r.tenant_id AND d.resource_set_key=r.management_resource_set_key
              JOIN apr_document_policy_versions dv ON dv.tenant_id=d.tenant_id AND dv.policy_id=d.policy_id AND dv.revision=d.published_revision
              JOIN apr_attachment_policy_heads a ON a.tenant_id=r.tenant_id AND a.resource_set_key=r.management_resource_set_key
              JOIN apr_attachment_policy_versions av ON av.tenant_id=a.tenant_id AND av.policy_id=a.policy_id AND av.revision=a.published_revision
              JOIN apr_signature_providers s ON s.tenant_id=r.tenant_id AND s.management_resource_set_key=r.management_resource_set_key AND s.provider_type='INTERNAL_ATTESTATION'
              JOIN apr_self_attestations c ON c.tenant_id=r.tenant_id AND c.request_id=r.request_id AND c.signature_request_id=:ceremony
              LEFT JOIN apr_self_attestation_artifacts artifact ON artifact.tenant_id=c.tenant_id AND artifact.request_id=c.request_id AND artifact.artifact_id=c.artifact_id
             WHERE r.tenant_id=:tenant AND r.request_id=:request AND r.requester_user_id=:actor AND r.management_resource_set_key=:rs AND r.deleted_at IS NULL
            """,p,(r,n)->{
                if(!r.getBoolean("hashes_valid"))throw unavailable();
                String keySha=signingKeySha.get();if(keySha==null || !keySha.matches("[a-f0-9]{64}"))throw unavailable();
                var pin=new SourcePin(request,r.getLong("version"),r.getInt("schema_version"),r.getString("payload_sha256"),
                    r.getObject("form_version_id",UUID.class),r.getString("schema_sha256"),r.getObject("workflow_version_id",UUID.class),r.getString("definition_sha256"),rs,r.getString("manifest_sha256"),
                    r.getObject("document_id",UUID.class),r.getLong("document_version"),r.getInt("document_revision"),r.getString("document_sha"),
                    r.getObject("attachment_id",UUID.class),r.getLong("attachment_version"),r.getInt("attachment_revision"),r.getString("attachment_sha"),
                    r.getObject("provider_id",UUID.class),r.getLong("provider_version"),r.getString("provider_sha"),actor,r.getString("renderer_version"),r.getString("artifact_sha256"),keySha);
                for(long v:new long[]{pin.requestVersion(),pin.documentPolicyVersion(),pin.attachmentPolicyVersion(),pin.providerVersion()})if(v<0 || v>ApprovalSignatureDtos.MAX_VERSION)throw unavailable();
                boolean retained=r.getBoolean("retention_valid"),approved="APPROVED".equals(r.getString("status"));
                return new Current(retained && approved && original.equals(pin),json.digest(Map.of("source",pin,"retentionValid",retained,"approved",approved,"recordRetention",retentionSnapshot)));
            });
        // Missing policy/payload/manifest evidence is UNKNOWN, never an invented mismatching proof or read-time repair.
        if(rows.size()!=1)throw unavailable();retention.unchanged(retentionSnapshot,tenant,actor,rs,false);return rows.getFirst();
    }
}
