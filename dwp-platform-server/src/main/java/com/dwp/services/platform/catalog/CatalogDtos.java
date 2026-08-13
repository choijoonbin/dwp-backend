package com.dwp.services.platform.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CatalogDtos {

    private CatalogDtos() {
    }

    public record Entity(
            String ref,
            String kind,
            String key,
            String name,
            String description,
            String ownerRef,
            String lifecycleState,
            String riskTier,
            String scope,
            long revision,
            JsonNode metadata) {
    }

    public record Relation(
            UUID relationId,
            String sourceRef,
            String targetRef,
            String relationType,
            String relationOrigin,
            String criticality,
            String evidenceRef,
            JsonNode metadata,
            String lifecycleState,
            long version) {
    }

    public record Overview(
            long entityCount,
            long relationCount,
            long declaredRelationCount,
            long orphanCount,
            long criticalRelationCount,
            Map<String, Long> entitiesByKind,
            Map<String, Long> entitiesByLifecycle,
            List<Entity> entities,
            OffsetDateTime generatedAt) {
    }

    public record GraphNode(
            Entity entity,
            long incomingCount,
            long outgoingCount,
            boolean orphan) {
    }

    public record Graph(
            String focusRef,
            List<GraphNode> nodes,
            List<Relation> relations,
            boolean truncated,
            OffsetDateTime generatedAt) {
    }

    public record ImpactItem(
            Entity entity,
            int distance,
            List<String> relationTypes,
            String highestCriticality) {
    }

    public record ImpactAnalysis(
            Entity target,
            String operation,
            String compatibilityState,
            String ruleKey,
            long ruleVersion,
            int riskScore,
            boolean blocked,
            long directDependentCount,
            long transitiveDependentCount,
            List<ImpactItem> impactedEntities,
            List<String> findings,
            OffsetDateTime generatedAt) {
    }

    public record CompatibilityRule(
            String ruleKey,
            long ruleVersion,
            JsonNode definition,
            String contentSha256) {
    }

    public record AssuranceFinding(
            UUID findingId,
            String entityRef,
            String findingCode,
            String severity,
            String lifecycleState,
            String ruleKey,
            long ruleVersion,
            JsonNode evidence,
            String evidenceSha256,
            OffsetDateTime firstDetectedAt,
            OffsetDateTime lastDetectedAt,
            String dispositionReason,
            String dispositionEvidenceRef,
            Long disposedBy,
            OffsetDateTime disposedAt,
            long version) {
    }

    public record AssuranceSummary(
            long openCount,
            long criticalCount,
            long ownerMissingCount,
            long deprecationImpactCount,
            CompatibilityRule activeRule,
            List<AssuranceFinding> findings,
            OffsetDateTime generatedAt) {
    }

    public record DispositionFindingRequest(
            @NotBlank
            @Pattern(regexp = "ACKNOWLEDGED|FALSE_POSITIVE|ACCEPTED_RISK|RESOLVED")
            String decision,
            @NotBlank @Size(min = 10, max = 1000) String reason,
            @Size(max = 500) String evidenceRef,
            @NotNull @Min(0) Long version) {
    }

    public record DeclareRelationRequest(
            @NotBlank
            @Size(max = 260)
            @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]*:[A-Za-z0-9_.:/-]+")
            String sourceRef,
            @NotBlank
            @Size(max = 260)
            @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]*:[A-Za-z0-9_.:/-]+")
            String targetRef,
            @NotBlank
            @Pattern(regexp = "DEPENDS_ON|CONSUMES|PRODUCES|EXPOSES|GOVERNS|NAVIGATES_TO|REQUIRES_PERMISSION|SYNCHRONIZES")
            String relationType,
            @NotBlank
            @Pattern(regexp = "INFORMATIONAL|OPERATIONAL|CRITICAL")
            String criticality,
            @Size(max = 500) String evidenceRef,
            JsonNode metadata,
            @Min(0) Long version) {
    }

    public record RelationVersionRequest(@NotNull @Min(0) Long version) {
    }

    public record GraphRequest(
            String focusRef,
            @Min(1) @Max(4) Integer depth) {
    }
}
