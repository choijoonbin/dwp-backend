package com.dwp.services.platform.auditcontrol;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class HcmAuditEvidenceDtos {

    private HcmAuditEvidenceDtos() {
    }

    @Schema(
            name = "HcmAuditEvidencePageV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Page(
            List<Evidence> items,
            int size,
            Instant snapshotAt,
            String nextCursor) {

        public Page {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    @Schema(
            name = "HcmAuditEvidenceV1",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Evidence(
            UUID eventId,
            Instant occurredAt,
            String category,
            String action,
            String outcome,
            String severity,
            String actorType,
            String sourceService,
            String sourceModule,
            String aggregateType,
            long aggregateRevision,
            String effectiveCapability,
            String purposeCode,
            String authorizationDecisionRef,
            long scopeRevision,
            long fieldPolicyRevision,
            UUID approvalReceiptId,
            String reasonCode,
            String beforeDigest,
            String afterDigest,
            String evidenceDigest,
            String classification,
            String retentionClass,
            UUID causationId,
            UUID correlationId,
            String recordHash) {
    }
}
