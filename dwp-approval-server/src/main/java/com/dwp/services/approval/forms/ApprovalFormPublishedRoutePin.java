package com.dwp.services.approval.forms;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Internal DB observation, not an authoring DTO or an independent authorization decision. */
public final class ApprovalFormPublishedRoutePin {
    public enum Operation { NEW_INITIATION, EXISTING_REQUEST }
    private final Observation value;
    private ApprovalFormPublishedRoutePin(Observation value) { this.value=value; }
    static ApprovalFormPublishedRoutePin verified(Observation value) { return new ApprovalFormPublishedRoutePin(value); }
    boolean sameObservation(ApprovalFormPublishedRoutePin other) { return other!=null&&value.equals(other.value); }
    public long tenantId() { return value.tenantId(); }
    public UUID formId() { return value.formId(); }
    public UUID formVersionId() { return value.formVersionId(); }
    public String schemaSha256() { return value.schemaSha256(); }
    public String schemaJson() { return value.schemaJson(); }
    public String materialDigest() { return value.materialDigest(); }
    public long formRevision() { return value.formRevision(); }
    public long workspaceRevision() { return value.workspaceRevision(); }
    public String resourceSetKey() { return value.resourceSetKey(); }
    public UUID categoryId() { return value.categoryId(); }
    public long categoryRevision() { return value.categoryRevision(); }
    public UUID requestId() { return value.requestId(); }
    public Long requestVersion() { return value.requestVersion(); }
    public Operation operation() { return value.operation(); }
    public WorkflowRoute workflow() { return value.workflow(); }
    public Map<String,Object> metadata() { return value.metadata(); }
    public String metadataProvenance() { return value.metadataProvenance(); }
    public Instant capturedAt() { return value.capturedAt(); }
    public record WorkflowRoute(UUID workflowId,UUID workflowVersionId,long workflowRevision,int workflowVersionNumber,
            String definitionSha256,String dataClassification,int slaMinutes,Instant effectiveFrom,Instant effectiveTo) {
        /** Published route revisions are capture-time provenance, never a new-initiation head CAS. */
        public long capturedWorkflowRevision() { return workflowRevision; }
    }
    record Observation(long tenantId,UUID formId,UUID formVersionId,String schemaSha256,String schemaJson,String materialDigest,
            long formRevision,long workspaceRevision,String resourceSetKey,UUID categoryId,long categoryRevision,
            UUID requestId,Long requestVersion,Operation operation,WorkflowRoute workflow,Map<String,Object> metadata,
            String metadataProvenance,Instant capturedAt) {
        Observation {
            if(metadata.size()>20||metadata.values().stream().anyMatch(item->item!=null&&!(item instanceof String||item instanceof Integer||item instanceof Long)))
                throw new IllegalArgumentException("Version metadata must be a bounded scalar projection.");
            metadata=Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        }
    }
}
