package com.dwp.services.platform.preference;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ManagedPreferenceDtos {

    private ManagedPreferenceDtos() {
    }

    public record ManagedPreferenceRule(
            UUID ruleId,
            String preferencePath,
            String displayKey,
            JsonNode managedValue,
            boolean exceptionAllowed,
            long version) {
    }

    public record ManagedPreferencePolicy(
            UUID policyId,
            String scope,
            String source,
            String ownerType,
            String ownerRef,
            String ownerDisplayName,
            String contactUri,
            List<String> managedPaths,
            List<ManagedPreferenceRule> rules,
            long version) {
    }

    public record PreferenceExceptionRequest(
            UUID requestId,
            long userId,
            String preferencePath,
            JsonNode requestedValue,
            String businessJustification,
            String businessImpact,
            String requestState,
            String assignedOwnerRef,
            OffsetDateTime requestedUntil,
            String decisionReason,
            String decisionEvidenceRef,
            Long decidedBy,
            OffsetDateTime decidedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            long version) {
    }

    public record CreateExceptionRequest(
            @NotBlank @Size(max = 180)
            @Pattern(regexp = "^[a-z][A-Za-z0-9]*(\\.[a-z][A-Za-z0-9]*)+$")
            String preferencePath,
            @NotNull JsonNode requestedValue,
            @NotBlank @Size(min = 10, max = 1000) String businessJustification,
            @NotBlank @Size(min = 10, max = 1000) String businessImpact,
            @Future OffsetDateTime requestedUntil) {
    }

    public record DecideExceptionRequest(
            @NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision,
            @NotBlank @Size(min = 10, max = 1000) String reason,
            @Size(max = 500) String evidenceRef,
            @NotNull @Min(0) @Max(Long.MAX_VALUE) Long version) {
    }

    public record VersionRequest(@NotNull @Min(0) Long version) {
    }
}
