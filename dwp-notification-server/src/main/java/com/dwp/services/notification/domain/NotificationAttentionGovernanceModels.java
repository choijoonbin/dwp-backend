package com.dwp.services.notification.domain;

import com.dwp.services.notification.api.DecimalVersionStringDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class NotificationAttentionGovernanceModels {

    private NotificationAttentionGovernanceModels() {
    }

    public record Settings(
            @Min(1) @Max(500) int maxActiveUserRules,
            @Min(0) @Max(500) int maxVipRules,
            @Min(0) @Max(500) int maxFollowRules,
            @NotNull @Size(max = 100)
            List<@NotBlank @Pattern(regexp = "#[a-z0-9][a-z0-9._-]{1,79}") String>
                    approvedTopicAllowlist,
            boolean mandatoryPolicyPrecedence,
            @Min(10) @Max(10000) int minimumAnalyticsCohort,
            boolean independentReviewerRequired) {

        public Settings {
            approvedTopicAllowlist = approvedTopicAllowlist == null
                    ? List.of()
                    : List.copyOf(approvedTopicAllowlist);
        }
    }

    public record DraftRequest(
            @NotNull @Valid Settings settings,
            @NotBlank @Size(min = 10, max = 500) String changeReason,
            @NotBlank
            @JsonDeserialize(using = DecimalVersionStringDeserializer.class)
            String expectedVersion) {
    }

    public record DecisionRequest(
            @NotBlank
            @JsonDeserialize(using = DecimalVersionStringDeserializer.class)
            String expectedVersion,
            @NotBlank @Size(min = 10, max = 500) String reason) {
    }

    public record Revision(
            UUID governanceId,
            String state,
            Settings settings,
            long revisionNumber,
            String version,
            String changeReason,
            Long createdBy,
            Instant createdAt,
            Long approvedBy,
            Instant approvedAt,
            Long updatedBy,
            Instant updatedAt,
            String decisionReason,
            UUID supersedesGovernanceId) {
    }

    public record Workspace(
            Revision activeRevision,
            List<Revision> drafts,
            String changeVersion,
            Instant generatedAt) {

        public Workspace {
            drafts = List.copyOf(drafts);
        }
    }
}
