package com.dwp.services.auth.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Closed internal contract for the R6-B v46 SELF POLICY decision carrier. */
public final class HrisNavigationPolicyDecisionDtos {

    private HrisNavigationPolicyDecisionDtos() {
    }

    public enum Decision {
        ALLOWED,
        ROUTE_DENIED
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    @Schema(name = "HrisNavigationPolicyDecisionBatchRequestV1")
    public record BatchRequest(
            @NotNull @Positive Long tenantId,
            @NotNull @Positive Long actorId,
            @NotNull ProductSurfaceAuthorityDtos.AccessMode activeAccessMode,
            @NotNull UUID expectedPersonPublicId,
            @NotBlank @Pattern(regexp = "^scope-[0-9a-f]{32}$") String expectedScopeKey,
            @NotNull @Positive Long expectedRegistryVersion,
            @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String expectedRegistryChecksum,
            @Pattern(regexp = "^decision-[0-9a-f]{64}$") String expectedDecisionRevision) {

        @JsonAnySetter
        public void rejectUnknownField(String field, Object ignored) {
            throw new IllegalArgumentException(
                    "Unknown HRIS navigation POLICY decision request field: " + field);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    @Schema(name = "HrisNavigationPolicyDecisionBatchV1")
    public record BatchResult(
            long registryVersion,
            @NotBlank String registryChecksum,
            @NotBlank String snapshotRevision,
            @NotBlank String decisionRevision,
            @NotBlank String authRevision,
            @NotBlank String policyRevision,
            @NotBlank String relationshipRevision,
            @NotNull OffsetDateTime freshUntil,
            @NotNull List<RouteDecision> decisions) {

        public BatchResult {
            decisions = List.copyOf(decisions);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    @Schema(name = "HrisNavigationPolicyRouteDecisionV1")
    public record RouteDecision(
            @NotBlank String nodeId,
            @NotBlank String path,
            @NotBlank String routeContractKey,
            @NotBlank String profileKey,
            @NotBlank String targetBinding,
            ScopeBinding scopeBinding,
            @NotNull Decision decision,
            @NotBlank String reasonCode,
            String routeGrantRef,
            @NotBlank String decisionRevision,
            @NotBlank String authRevision,
            @NotBlank String policyRevision,
            @NotBlank String relationshipRevision,
            @NotNull OffsetDateTime freshUntil) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    @Schema(name = "HrisNavigationPolicyScopeBindingV1")
    public record ScopeBinding(
            @NotBlank String kind,
            @NotBlank String key,
            @NotNull UUID personPublicId) {
    }
}
