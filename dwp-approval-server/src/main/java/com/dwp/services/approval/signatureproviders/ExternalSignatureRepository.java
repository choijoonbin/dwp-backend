package com.dwp.services.approval.signatureproviders;

import static com.dwp.services.approval.signatureproviders.ApprovalSignatureProviderDtos.*;
import static com.dwp.services.approval.signatureproviders.SignatureProviderModel.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

final class ExternalSignatureRepository {
    private final SignatureProviderPersistence persistence;

    ExternalSignatureRepository(SignatureProviderPersistence persistence) {
        this.persistence = persistence;
    }

    ExternalSource source(SignatureProviderCurrentAuthority.Current current, UUID requestId, boolean lock) {
        persistence.bind(current);
        var rows = persistence.jdbc().query("""
                SELECT r.request_id,r.version,r.management_resource_set_key,r.data_classification,
                       r.workflow_version_id,r.form_version_id,p.schema_version,p.payload_sha256
                  FROM apr_requests r
                  JOIN apr_request_payloads p ON p.tenant_id=r.tenant_id AND p.request_id=r.request_id
                 WHERE r.tenant_id=:tenant AND r.request_id=:request AND r.requester_user_id=:actor
                   AND r.management_resource_set_key=:scope AND r.status='APPROVED' AND r.deleted_at IS NULL
                """ + (lock ? " FOR SHARE OF r,p" : ""), persistence.base(current)
                .addValue("request", requestId).addValue("actor", current.actor().userId()), (row, number) -> {
            long requestVersion = row.getLong("version");
            String resourceSet = row.getString("management_resource_set_key");
            String classification = row.getString("data_classification");
            UUID workflowVersion = row.getObject("workflow_version_id", UUID.class);
            UUID formVersion = row.getObject("form_version_id", UUID.class);
            int payloadRevision = row.getInt("schema_version");
            String payloadSha = row.getString("payload_sha256");
            return new ExternalSource(requestId, row.getLong("version"), row.getString("management_resource_set_key"),
                    classification, workflowVersion, formVersion, payloadRevision, payloadSha,
                    sourceSha(requestId, requestVersion, resourceSet, classification,
                            workflowVersion, formVersion, payloadRevision, payloadSha));
        });
        if (rows.size() != 1) throw SignatureProviderErrors.hidden();
        return rows.getFirst();
    }

    ExternalRequest create(SignatureProviderCurrentAuthority.Current current, ExternalSource source,
            ProviderTarget provider, SignatureProviderPolicyRepository.State policy) {
        if (policy.published() == null || provider.expectedConfiguration() == null)
            throw SignatureProviderErrors.conflict();
        UUID id = UUID.randomUUID(); Instant now = persistence.clock().instant();
        int inserted = persistence.jdbc().update("""
                INSERT INTO apr_external_signature_requests(
                    tenant_id,resource_set_key,signature_request_id,request_id,owner_user_id,
                    provider_id,provider_version,provider_sha256,configuration_id,configuration_version,
                    configuration_sha256,policy_id,policy_version_id,policy_sha256,source,source_sha256,
                    state,version,reason_codes,created_at,updated_at)
                VALUES(:tenant,:scope,:id,:request,:actor,:provider,:providerVersion,:providerSha,
                    :configuration,:configurationVersion,:configurationSha,:policy,:policyVersion,:policySha,
                    CAST(:source AS jsonb),:sourceSha,'PREPARED',0,'[]'::jsonb,:now,:now)
                """, persistence.base(current).addValue("id", id).addValue("request", source.requestId())
                .addValue("actor", current.actor().userId()).addValue("provider", provider.providerId())
                .addValue("providerVersion", provider.expectedProviderVersion())
                .addValue("providerSha", provider.expectedProviderSha256())
                .addValue("configuration", provider.expectedConfiguration().sourceId())
                .addValue("configurationVersion", provider.expectedConfiguration().version())
                .addValue("configurationSha", provider.expectedConfiguration().sha256())
                .addValue("policy", policy.policyId()).addValue("policyVersion", policy.published().id())
                .addValue("policySha", policy.published().sha256())
                .addValue("source", persistence.canonical().json(source)).addValue("sourceSha", source.sourceSha256())
                .addValue("now", Timestamp.from(now)));
        if (inserted != 1) throw SignatureProviderErrors.conflict();
        event(current, id, 0, "CREATE", ExternalState.PREPARED, List.of(), null, null, now);
        return require(current, id, true);
    }

    ExternalRequest require(SignatureProviderCurrentAuthority.Current current, UUID id, boolean lock) {
        persistence.bind(current);
        var providerSource = persistence.source(current, lock);
        var rows = persistence.jdbc().query("""
                SELECT x.* FROM apr_external_signature_requests x
                  JOIN apr_requests r ON r.tenant_id=x.tenant_id AND r.request_id=x.request_id
                 WHERE x.tenant_id=:tenant AND x.resource_set_key=:scope AND x.signature_request_id=:id
                   AND x.owner_user_id=:actor AND r.requester_user_id=:actor AND r.deleted_at IS NULL
                """ + (lock ? " FOR UPDATE OF x" : " FOR SHARE OF x"), persistence.base(current)
                .addValue("id", id).addValue("actor", current.actor().userId()), (row, number) -> {
            ExternalSource source = persistence.canonical().read(row.getString("source"), ExternalSource.class);
            if (!source.sourceSha256().equals(row.getString("source_sha256"))
                    || !source.sourceSha256().equals(sourceSha(source.requestId(), source.requestVersion(),
                    source.resourceSetKey(), source.dataClassification(), source.workflowVersionId(),
                    source.formVersionId(), source.payloadRevision(), source.payloadSha256())))
                throw SignatureProviderErrors.unavailable();
            SourcePin configuration = new SourcePin(row.getObject("configuration_id", UUID.class),
                    row.getLong("configuration_version"), row.getString("configuration_sha256"));
            ProviderTarget target = new ProviderTarget(row.getObject("provider_id", UUID.class),
                    row.getLong("provider_version"), row.getString("provider_sha256"), configuration);
            return new ExternalRequest(providerSource.scope(current, persistence.clock().instant()),
                    id, source, target, row.getObject("policy_id", UUID.class),
                    row.getObject("policy_version_id", UUID.class), row.getString("policy_sha256"),
                    ExternalState.valueOf(row.getString("state")), row.getLong("version"),
                    row.getString("remote_reference_sha256"), reasons(row.getString("reason_codes")),
                    row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
        });
        if (rows.size() != 1) throw SignatureProviderErrors.hidden();
        ExternalRequest result = rows.getFirst();
        if (!source(current, result.source().requestId(), lock).equals(result.source()))
            throw SignatureProviderErrors.conflict();
        return result;
    }

    ExternalRequest transition(SignatureProviderCurrentAuthority.Current current, ExternalRequest original,
            String action, SignatureProviderRuntime.ExternalTransition transition) {
        requireTransition(original.state(), action, transition.state());
        Instant now = persistence.clock().instant();
        int changed = persistence.jdbc().update("""
                UPDATE apr_external_signature_requests
                   SET state=:state,version=version+1,remote_reference_sha256=:remote,
                       reason_codes=CAST(:reasons AS jsonb),updated_at=:updated
                 WHERE tenant_id=:tenant AND resource_set_key=:scope AND signature_request_id=:id
                   AND owner_user_id=:actor AND version=:version AND state=:originalState
                """, persistence.base(current).addValue("id", original.signatureRequestId())
                .addValue("actor", current.actor().userId()).addValue("version", original.version())
                .addValue("originalState", original.state().name()).addValue("state", transition.state().name())
                .addValue("remote", transition.remoteReferenceSha256())
                .addValue("reasons", persistence.canonical().json(transition.reasonCodes()))
                .addValue("updated", Timestamp.from(now)));
        if (changed != 1) throw SignatureProviderErrors.conflict();
        event(current, original.signatureRequestId(), original.version() + 1, action, transition.state(),
                transition.reasonCodes(), transition.evidenceId(), transition.evidenceSha256(), now);
        for (ExternalArtifact artifact : transition.artifacts()) artifact(current, original.signatureRequestId(), artifact);
        return require(current, original.signatureRequestId(), true);
    }

    ExternalAudit audit(SignatureProviderCurrentAuthority.Current current, UUID id) {
        require(current, id, false);
        var rows = persistence.jdbc().query("""
                SELECT event_id,sequence,action,state,reason_codes::text,evidence_id,evidence_sha256,occurred_at
                  FROM apr_external_signature_events
                 WHERE tenant_id=:tenant AND resource_set_key=:scope AND signature_request_id=:id
                   AND owner_user_id=:actor ORDER BY sequence,event_id LIMIT 1001
                """, persistence.base(current).addValue("id", id).addValue("actor", current.actor().userId()),
                (row, number) -> new ExternalEvent(row.getObject("event_id", UUID.class), row.getLong("sequence"),
                        row.getString("action"), ExternalState.valueOf(row.getString("state")),
                        reasons(row.getString("reason_codes")), row.getObject("evidence_id", UUID.class),
                        row.getString("evidence_sha256"), row.getTimestamp("occurred_at").toInstant()));
        return new ExternalAudit(List.copyOf(rows.subList(0, Math.min(1_000, rows.size()))), rows.size() > 1_000);
    }

    ExternalArtifact artifact(SignatureProviderCurrentAuthority.Current current, UUID id, UUID artifactId) {
        require(current, id, false);
        var rows = persistence.jdbc().query("""
                SELECT artifact_id,artifact_kind,media_type,content_sha256,size_bytes,storage_locator_sha256,
                       object_version_sha256,retain_until,evidence_id,evidence_sha256,recorded_at
                  FROM apr_external_signature_artifacts
                 WHERE tenant_id=:tenant AND resource_set_key=:scope AND signature_request_id=:id
                   AND owner_user_id=:actor AND artifact_id=:artifact
                """, persistence.base(current).addValue("id", id).addValue("artifact", artifactId)
                .addValue("actor", current.actor().userId()), (row, number) -> mapArtifact(row));
        if (rows.size() != 1) throw SignatureProviderErrors.hidden();
        return rows.getFirst();
    }

    private void event(SignatureProviderCurrentAuthority.Current current, UUID id, long sequence,
            String action, ExternalState state, List<String> reasons, UUID evidence, String evidenceSha, Instant at) {
        persistence.jdbc().update("""
                INSERT INTO apr_external_signature_events(
                    tenant_id,resource_set_key,signature_request_id,owner_user_id,event_id,sequence,
                    action,state,reason_codes,evidence_id,evidence_sha256,occurred_at)
                VALUES(:tenant,:scope,:id,:actor,:event,:sequence,:action,:state,CAST(:reasons AS jsonb),
                    :evidence,:evidenceSha,:occurred)
                """, persistence.base(current).addValue("id", id).addValue("actor", current.actor().userId())
                .addValue("event", UUID.randomUUID()).addValue("sequence", sequence).addValue("action", action)
                .addValue("state", state.name()).addValue("reasons", persistence.canonical().json(reasons))
                .addValue("evidence", evidence).addValue("evidenceSha", evidenceSha)
                .addValue("occurred", Timestamp.from(at)));
    }

    private void artifact(SignatureProviderCurrentAuthority.Current current, UUID id, ExternalArtifact artifact) {
        persistence.jdbc().update("""
                INSERT INTO apr_external_signature_artifacts(
                    tenant_id,resource_set_key,signature_request_id,owner_user_id,artifact_id,artifact_kind,
                    media_type,content_sha256,size_bytes,storage_locator_sha256,object_version_sha256,
                    retain_until,evidence_id,evidence_sha256,recorded_at)
                VALUES(:tenant,:scope,:id,:actor,:artifact,:kind,:media,:sha,:size,:storage,:objectVersion,
                    :retainUntil,:evidence,:evidenceSha,:recorded)
                """, persistence.base(current).addValue("id", id).addValue("actor", current.actor().userId())
                .addValue("artifact", artifact.artifactId()).addValue("kind", artifact.kind().name())
                .addValue("media", artifact.mediaType()).addValue("sha", artifact.sha256())
                .addValue("size", artifact.sizeBytes()).addValue("storage", artifact.storageLocatorSha256())
                .addValue("objectVersion", artifact.objectVersionSha256())
                .addValue("retainUntil", Timestamp.from(artifact.retainUntil()))
                .addValue("evidence", artifact.evidenceId()).addValue("evidenceSha", artifact.evidenceSha256())
                .addValue("recorded", Timestamp.from(artifact.recordedAt())));
    }

    private ExternalArtifact mapArtifact(java.sql.ResultSet row) throws java.sql.SQLException {
        return new ExternalArtifact(row.getObject("artifact_id", UUID.class),
                ArtifactKind.valueOf(row.getString("artifact_kind")), row.getString("media_type"),
                row.getString("content_sha256"), row.getLong("size_bytes"),
                row.getString("storage_locator_sha256"), row.getString("object_version_sha256"),
                row.getTimestamp("retain_until").toInstant(), row.getObject("evidence_id", UUID.class),
                row.getString("evidence_sha256"), row.getTimestamp("recorded_at").toInstant());
    }

    private String sourceSha(UUID requestId, long requestVersion, String resourceSetKey,
            String dataClassification, UUID workflowVersionId, UUID formVersionId,
            int payloadRevision, String payloadSha256) {
        var material = new LinkedHashMap<String, Object>();
        material.put("contract", "DWP_EXTERNAL_SIGNATURE_SOURCE_V1");
        material.put("requestId", requestId.toString());
        material.put("requestVersion", requestVersion);
        material.put("resourceSetKey", resourceSetKey);
        material.put("dataClassification", dataClassification);
        material.put("workflowVersionId", workflowVersionId.toString());
        material.put("formVersionId", formVersionId.toString());
        material.put("payloadRevision", payloadRevision);
        material.put("payloadSha256", payloadSha256);
        return persistence.canonical().digest(material);
    }

    @SuppressWarnings("unchecked")
    private List<String> reasons(String value) {
        return List.copyOf(persistence.canonical().read(value, List.class).stream()
                .map(item -> text((String) item, 80)).toList());
    }

    private void requireTransition(ExternalState original, String action, ExternalState target) {
        boolean allowed = switch (action) {
            case "HANDOVER" -> original == ExternalState.PREPARED
                    && Set.of(ExternalState.HANDOVER_PENDING, ExternalState.OUT_FOR_SIGNATURE,
                    ExternalState.UNKNOWN_REMOTE_OUTCOME, ExternalState.FAILED).contains(target);
            case "REFRESH" -> Set.of(ExternalState.HANDOVER_PENDING, ExternalState.OUT_FOR_SIGNATURE,
                    ExternalState.COMPLETION_PENDING, ExternalState.UNKNOWN_REMOTE_OUTCOME).contains(original)
                    && target != ExternalState.PREPARED;
            case "CANCEL" -> !Set.of(ExternalState.COMPLETED_VERIFIED, ExternalState.CANCELLED).contains(original)
                    && Set.of(ExternalState.CANCEL_PENDING, ExternalState.CANCELLED,
                    ExternalState.UNKNOWN_REMOTE_OUTCOME, ExternalState.FAILED).contains(target);
            default -> false;
        };
        if (!allowed) throw SignatureProviderErrors.conflict();
    }
}
