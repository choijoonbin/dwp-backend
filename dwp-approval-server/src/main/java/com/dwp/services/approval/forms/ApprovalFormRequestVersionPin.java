package com.dwp.services.approval.forms;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Request-owned DB observation. Capture-time form metadata never retargets its workflow. */
public final class ApprovalFormRequestVersionPin {
    private final Observation value;
    private ApprovalFormRequestVersionPin(Observation value) { this.value=value; }
    static ApprovalFormRequestVersionPin verified(Observation value) { return new ApprovalFormRequestVersionPin(value); }
    boolean sameObservation(ApprovalFormRequestVersionPin other) { return other!=null&&value.equals(other.value); }
    public long tenantId() { return value.tenantId(); }
    public long actorId() { return value.actorId(); }
    public UUID personPublicId() { return value.personPublicId(); }
    public UUID requestId() { return value.requestId(); }
    public long requestVersion() { return value.requestVersion(); }
    public String requestStatus() { return value.requestStatus(); }
    public UUID formId() { return value.formId(); }
    public UUID formVersionId() { return value.formVersionId(); }
    public String schemaSha256() { return value.schemaSha256(); }
    public String schemaJson() { return value.schemaJson(); }
    public long formRevision() { return value.formRevision(); }
    public Long workspaceRevision() { return value.workspaceRevision(); }
    public String resourceSetKey() { return value.resourceSetKey(); }
    public UUID categoryId() { return value.categoryId(); }
    public long categoryRevision() { return value.categoryRevision(); }
    public String materialDigest() { return value.materialDigest(); }
    public String metadataProvenance() { return value.metadataProvenance(); }
    public Map<String,Object> metadata() { return value.metadata(); }
    public Instant capturedAt() { return value.capturedAt(); }
    public WorkflowVersion workflow() { return value.workflow(); }
    public String storedStepsJson() { return value.storedStepsJson(); }
    public String legacyBindingJson() { return value.legacyBindingJson(); }
    public String actionRoute() { return value.actionRoute(); }
    /** No historical head revision or parent SLA is implied by the stored version. */
    public record WorkflowVersion(UUID workflowId,UUID workflowVersionId,int versionNumber,String definitionSha256,
            String definitionJson,String requestDataClassification,Instant effectiveFrom,Instant effectiveTo) { }
    record Observation(long tenantId,long actorId,UUID personPublicId,UUID requestId,long requestVersion,String requestStatus,
            UUID formId,UUID formVersionId,String schemaSha256,String schemaJson,long formRevision,Long workspaceRevision,
            String resourceSetKey,UUID categoryId,long categoryRevision,String materialDigest,String metadataProvenance,
            Map<String,Object> metadata,Instant capturedAt,WorkflowVersion workflow,String storedStepsJson,String legacyBindingJson,String actionRoute) {
        Observation {
            if(metadata.size()>20||metadata.values().stream().anyMatch(item->item!=null&&!(item instanceof String||item instanceof Integer||item instanceof Long)))
                throw new IllegalArgumentException("Version metadata must be a bounded scalar projection.");
            metadata=Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        }
    }
}
