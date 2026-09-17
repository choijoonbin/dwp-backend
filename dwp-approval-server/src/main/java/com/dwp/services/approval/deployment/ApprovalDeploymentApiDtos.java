package com.dwp.services.approval.deployment;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.approval.deployment.ApprovalDeploymentModels.*;

final class ApprovalDeploymentApiDtos {
    private ApprovalDeploymentApiDtos() {
    }

    interface StrictInput {
        @JsonAnySetter
        default void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown deployment input field: " + key);
        }
    }

    @Schema(name = "ApprovalDeploymentAssetInput", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record AssetInput(
            @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_.:-]{1,199}") String assetKey,
            @NotNull AssetType assetType,
            @NotNull UUID assetId,
            @NotBlank @Size(max = 80) String assetVersion,
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String contentSha256,
            @NotNull RollbackDisposition rollbackDisposition,
            boolean externalSideEffects) implements StrictInput {
        Asset asset() {
            return new Asset(
                    assetKey, assetType, assetId, assetVersion, contentSha256,
                    rollbackDisposition, externalSideEffects);
        }
    }

    @Schema(name = "ApprovalDeploymentDependencyInput", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record DependencyInput(
            @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_.:-]{1,199}") String assetKey,
            @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_.:-]{1,199}") String dependsOnAssetKey,
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String requiredSha256,
            boolean optional) implements StrictInput {
        Dependency dependency() {
            return new Dependency(
                    assetKey, dependsOnAssetKey, requiredSha256, optional);
        }
    }

    @Schema(name = "ApprovalDeploymentPackageCreate", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record PackageCreate(
            @NotNull UUID packageId,
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_.-]{2,119}") String packageKey,
            @Min(1) int packageVersion,
            @NotBlank @Size(max = 200) String displayName,
            @NotEmpty @Size(max = 500) List<@NotNull @Valid AssetInput> assets,
            @NotNull @Size(max = 2000) List<@NotNull @Valid DependencyInput> dependencies)
            implements StrictInput {
        PackageCommand command() {
            return new PackageCommand(
                    packageId, packageKey, packageVersion, displayName,
                    assets.stream().map(AssetInput::asset).toList(),
                    dependencies.stream().map(DependencyInput::dependency).toList());
        }
    }

    @Schema(name = "ApprovalDeploymentPromotionCreate", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record PromotionCreate(
            @NotNull UUID promotionId,
            @NotNull UUID packageId,
            @NotNull Environment sourceEnvironment,
            @NotNull Environment targetEnvironment) implements StrictInput {
        PromotionCommand command() {
            return new PromotionCommand(
                    promotionId, packageId, sourceEnvironment, targetEnvironment);
        }
    }

    @Schema(name = "ApprovalDeploymentReview", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record Review(
            @NotBlank @Size(min = 10, max = 1000) String reviewComment)
            implements StrictInput {
    }

    @Schema(name = "ApprovalDeploymentSchedule", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record Schedule(@NotNull Instant scheduledFor) implements StrictInput {
    }

    @Schema(name = "ApprovalDeploymentRollback", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record Rollback(
            @NotBlank @Size(min = 10, max = 1000) String reason)
            implements StrictInput {
    }

    @Schema(name = "ApprovalDeploymentExternalEvidence", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    record ExternalEvidence(
            @NotNull UUID evidenceId,
            @NotBlank @Size(max = 24) String evidenceType,
            @NotNull HealthOutcome outcome,
            @NotBlank @Size(max = 500) String externalReference,
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String payloadSha256,
            @NotNull Instant sourceGeneratedAt,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,12000}")
            String evidencePayloadBase64Url,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{86}")
            String evidenceSignatureBase64Url) implements StrictInput {
        ExternalHealthEvidenceSubmission submission() {
            return new ExternalHealthEvidenceSubmission(
                    evidenceId, evidenceType, outcome, externalReference,
                    payloadSha256, sourceGeneratedAt,
                    evidencePayloadBase64Url, evidenceSignatureBase64Url);
        }
    }

    record ListQuery(@Min(1) @Max(100) int limit) {
    }
}
