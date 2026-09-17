package com.dwp.services.approval.templates;

import static com.dwp.services.approval.templates.ApprovalTemplateModels.*;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ApprovalTemplateHttpModels {
    private static final String CODE = "[A-Z][A-Z0-9_]{1,99}";
    private static final String REFERENCE = "[A-Z][A-Z0-9_.:-]{1,159}";
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    private ApprovalTemplateHttpModels() { }

    public record CloneDraftRequest(
            @NotBlank @Pattern(regexp = CODE) String templateKey,
            @NotBlank @Pattern(regexp = REFERENCE) String ownerGroupRef,
            @NotBlank @Pattern(regexp = CODE) String categoryKey,
            @NotBlank @Pattern(regexp = CODE) String defaultWorkflowKey,
            @NotBlank @Size(max = 200) String nameKo,
            @NotBlank @Size(max = 200) String nameEn,
            @NotNull @Size(max = 1000) String descriptionKo,
            @NotNull @Size(max = 1000) String descriptionEn,
            @NotNull @Min(0) @Max(MAX_SAFE_INTEGER)
            @Schema(description = "Exact source template aggregate version") Long expectedTemplateVersion) {
        CloneTemplate toDomain() {
            return new CloneTemplate(templateKey, ownerGroupRef, categoryKey, defaultWorkflowKey,
                    nameKo, nameEn, descriptionKo, descriptionEn, expectedTemplateVersion);
        }
    }

    public record InstallDraftRequest(
            @NotBlank @Pattern(regexp = CODE) String formKey,
            @NotBlank @Size(max = 200) String nameKo,
            @NotBlank @Size(max = 200) String nameEn,
            @NotNull @Size(max = 1000) String descriptionKo,
            @NotNull @Size(max = 1000) String descriptionEn,
            @NotBlank @Pattern(regexp = REFERENCE) String ownerGroupRef,
            @NotBlank @Pattern(regexp = CODE) String categoryKey,
            @NotBlank @Pattern(regexp = CODE) String defaultWorkflowKey,
            @NotNull @Min(0) @Max(MAX_SAFE_INTEGER) Long expectedTemplateVersion) {
        InstallTemplate toDomain() {
            return new InstallTemplate(formKey, nameKo, nameEn, descriptionKo, descriptionEn,
                    ownerGroupRef, categoryKey, defaultWorkflowKey, expectedTemplateVersion);
        }
    }

    public record DependencyRequest(
            @NotBlank @Pattern(regexp = "CATEGORY_KEY|WORKFLOW_KEY|DATA_SOURCE_PROVIDER|ROLE_CODE") String kind,
            @NotBlank @Pattern(regexp = REFERENCE) String key,
            @NotBlank @Pattern(regexp = "CURRENT|[1-9][0-9]{0,8}|>=[1-9][0-9]{0,8}") String versionConstraint,
            boolean required) {
        Dependency toDomain() { return new Dependency(kind, key, versionConstraint, required); }
    }

    public record ImportPackageRequest(
            @NotNull UUID packageId,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._+-]{0,79}") String packageVersion,
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String packageSha256,
            @NotBlank @Pattern(regexp = CODE) String templateKey,
            @NotBlank @Pattern(regexp = REFERENCE) String ownerGroupRef,
            @NotBlank @Pattern(regexp = CODE) String categoryKey,
            @NotBlank @Pattern(regexp = CODE) String defaultWorkflowKey,
            @NotBlank @Size(max = 200) String nameKo,
            @NotBlank @Size(max = 200) String nameEn,
            @NotNull @Size(max = 1000) String descriptionKo,
            @NotNull @Size(max = 1000) String descriptionEn,
            @NotNull Map<String, Object> schema,
            @NotNull @Size(min = 2, max = 8) Set<@Pattern(regexp = "ko|en") String> locales,
            @NotNull @Size(max = 20) Set<@Pattern(regexp = "[a-z0-9][a-z0-9-]{0,39}") String> tags,
            @NotNull Map<String, Object> filterMetadata,
            @NotNull @Size(max = 50) List<@Valid DependencyRequest> dependencies,
            @NotNull @Size(max = 1000) String changeSummaryKo,
            @NotNull @Size(max = 1000) String changeSummaryEn,
            @NotNull @Min(0) @Max(MAX_SAFE_INTEGER) Long expectedTemplateVersion) {
        ImportPackage toDomain() {
            return new ImportPackage(packageId, packageVersion, packageSha256, templateKey,
                    ownerGroupRef, categoryKey, defaultWorkflowKey, nameKo, nameEn,
                    descriptionKo, descriptionEn, schema, locales, tags, filterMetadata,
                    dependencies.stream().map(DependencyRequest::toDomain).toList(),
                    changeSummaryKo, changeSummaryEn, expectedTemplateVersion);
        }
    }
}
