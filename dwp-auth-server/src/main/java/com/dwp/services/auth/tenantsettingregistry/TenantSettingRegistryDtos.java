package com.dwp.services.auth.tenantsettingregistry;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TenantSettingRegistryDtos {

    private TenantSettingRegistryDtos() {
    }

    public record OwnerDescriptor(
            String ownerKey,
            long ownerVersion,
            String settingKey,
            String ownerService,
            String valueType,
            String editorKind,
            String resolutionStrategy,
            String overridePolicy,
            String activationMode,
            JsonNode defaultValue,
            String localizedLabelKey,
            String lifecycleState,
            String adapterState,
            Instant observedAt,
            Instant sourceUpdatedAt,
            String freshnessState,
            List<String> allowedActions) {

        public OwnerDescriptor {
            defaultValue = defaultValue.deepCopy();
            allowedActions = List.copyOf(allowedActions);
        }
    }

    public record CreateChangeRequest(
            @NotBlank String settingKey,
            @NotBlank @Pattern(regexp = "VALUE|INHERIT") String desiredState,
            JsonNode proposedValue,
            @NotBlank @Size(min = 10, max = 1000) String justification) {
    }

    public record VersionedCommand(@NotNull @PositiveOrZero Long version) {
    }

    public record DecisionCommand(
            @NotNull @PositiveOrZero Long version,
            @NotBlank @Pattern(regexp = "APPROVE|REJECT") String decision,
            @NotBlank @Size(min = 10, max = 1000) String reason) {
    }

    public record Preview(
            String settingKey,
            JsonNode beforeValue,
            JsonNode effectiveAfter,
            String sourceAfter,
            long impactedPrincipalCount,
            String coverage,
            Instant observedAt,
            List<String> warnings) {

        public Preview {
            beforeValue = beforeValue.deepCopy();
            effectiveAfter = effectiveAfter.deepCopy();
            warnings = List.copyOf(warnings);
        }
    }

    public record Change(
            UUID changeId,
            String settingKey,
            String ownerKey,
            long ownerVersion,
            String desiredState,
            JsonNode beforeValue,
            JsonNode proposedValue,
            String lifecycleState,
            long impactCount,
            String impactCoverage,
            Instant impactObservedAt,
            String justification,
            Long requestedBy,
            Instant submittedAt,
            Long approvedBy,
            Instant approvedAt,
            String decisionReason,
            Long publishedBy,
            Instant publishedAt,
            UUID publishReceiptId,
            long version,
            Instant createdAt,
            Instant updatedAt,
            Preview preview,
            List<String> allowedActions) {

        public Change {
            beforeValue = beforeValue.deepCopy();
            proposedValue = proposedValue == null ? null : proposedValue.deepCopy();
            allowedActions = List.copyOf(allowedActions);
        }
    }

    public record ChangePage(
            List<Change> items,
            int limit,
            boolean hasMore) {

        public ChangePage {
            items = List.copyOf(items);
        }
    }

    public record Provenance(
            String level,
            String localizedOwnerLabelKey,
            long ownerVersion,
            String evaluation,
            String reason) {
    }

    /** User-reader projection deliberately excludes drafts, actors, and approval details. */
    public record EffectiveSetting(
            String settingKey,
            String localizedLabelKey,
            JsonNode effectiveValue,
            String effectiveSource,
            String overrideState,
            List<Provenance> provenance,
            String freshnessState,
            Instant sourceUpdatedAt,
            Instant evaluatedAt) {

        public EffectiveSetting {
            effectiveValue = effectiveValue.deepCopy();
            provenance = List.copyOf(provenance);
        }
    }
}
