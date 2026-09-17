package com.dwp.services.approval.formsv3;

import static com.dwp.services.approval.formsv3.ApprovalFormV3Models.*;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public final class ApprovalFormV3HttpModels {
    private static final String CODE = "[A-Z][A-Z0-9_]{1,99}";
    private static final String REFERENCE = "[A-Z][A-Z0-9_.:-]{1,159}";
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    private ApprovalFormV3HttpModels() { }

    public record ValidateSchemaRequest(@NotNull Map<String, Object> schema) { }

    public record EvaluateRequest(@NotNull Map<String, Object> payload) { }

    public record CloneDraftRequest(
            @NotBlank @Pattern(regexp = CODE) String formKey,
            @NotBlank @Size(max = 200) String nameKo,
            @NotBlank @Size(max = 200) String nameEn,
            @NotNull @Size(max = 1000) String descriptionKo,
            @NotNull @Size(max = 1000) String descriptionEn,
            @NotBlank @Pattern(regexp = REFERENCE) String ownerGroupRef,
            @NotBlank @Pattern(regexp = CODE) String categoryKey,
            @NotBlank @Pattern(regexp = CODE) String defaultWorkflowKey,
            @NotNull @Min(0) @Max(MAX_SAFE_INTEGER) Long expectedSourceWorkspaceVersion) {
        CloneDraft toDomain() {
            return new CloneDraft(formKey, nameKo, nameEn, descriptionKo, descriptionEn,
                    ownerGroupRef, categoryKey, defaultWorkflowKey, expectedSourceWorkspaceVersion);
        }
    }

    public record UpdateDraftRequest(
            @NotNull @Min(0) @Max(MAX_SAFE_INTEGER) Long expectedWorkspaceVersion,
            @NotNull Map<String, Object> schema) {
        UpdateDraft toDomain() { return new UpdateDraft(expectedWorkspaceVersion, schema); }
    }

    public record ArchiveDraftRequest(
            @NotNull @Min(0) @Max(MAX_SAFE_INTEGER) Long expectedWorkspaceVersion) {
        ArchiveDraft toDomain() { return new ArchiveDraft(expectedWorkspaceVersion); }
    }

    public record AddFieldRequest(
            @NotNull @Min(0) @Max(MAX_SAFE_INTEGER) Long expectedWorkspaceVersion,
            @NotBlank @Pattern(regexp = "[a-z][A-Za-z0-9_]{1,79}") String pageKey,
            @NotBlank @Pattern(regexp = "[a-z][A-Za-z0-9_]{1,79}") String sectionKey,
            @NotNull @Min(0) @Max(300) Integer index,
            @NotNull Map<String, Object> field,
            @NotBlank @Pattern(regexp = "STRICT|BACKWARD_COMPATIBLE|BREAKING") String compatibilityMode) {
        AddField toDomain() { return new AddField(expectedWorkspaceVersion, pageKey, sectionKey,
                index, field, compatibilityMode); }
    }

    public record DeleteFieldRequest(
            @NotNull @Min(0) @Max(MAX_SAFE_INTEGER) Long expectedWorkspaceVersion,
            @NotBlank @Pattern(regexp = "STRICT|BACKWARD_COMPATIBLE|BREAKING") String compatibilityMode) {
        DeleteField toDomain(String fieldKey) {
            return new DeleteField(expectedWorkspaceVersion, fieldKey, compatibilityMode);
        }
    }

    public record CloneFieldRequest(
            @NotNull @Min(0) @Max(MAX_SAFE_INTEGER) Long expectedWorkspaceVersion,
            @NotBlank @Pattern(regexp = "[a-z][A-Za-z0-9_]{1,79}") String targetFieldKey,
            @NotNull @Min(0) @Max(300) Integer index,
            @NotBlank @Pattern(regexp = "STRICT|BACKWARD_COMPATIBLE|BREAKING") String compatibilityMode) {
        CloneField toDomain(String sourceFieldKey) { return new CloneField(expectedWorkspaceVersion,
                sourceFieldKey, targetFieldKey, index, compatibilityMode); }
    }

    public record ReorderFieldsRequest(
            @NotNull @Min(0) @Max(MAX_SAFE_INTEGER) Long expectedWorkspaceVersion,
            @NotBlank @Pattern(regexp = "[a-z][A-Za-z0-9_]{1,79}") String pageKey,
            @NotNull @Size(min = 1, max = 80)
            List<@Pattern(regexp = "[a-z][A-Za-z0-9_]{1,79}") String> fieldKeys) {
        ReorderFields toDomain(String sectionKey) {
            return new ReorderFields(expectedWorkspaceVersion, pageKey, sectionKey, fieldKeys);
        }
    }

    public record UpdateFieldPropertiesRequest(
            @NotNull @Min(0) @Max(MAX_SAFE_INTEGER) Long expectedWorkspaceVersion,
            @NotNull @Size(min = 1, max = 20) Map<String, Object> properties,
            @NotBlank @Pattern(regexp = "STRICT|BACKWARD_COMPATIBLE|BREAKING") String compatibilityMode) {
        UpdateFieldProperties toDomain(String fieldKey) {
            return new UpdateFieldProperties(expectedWorkspaceVersion, fieldKey, properties, compatibilityMode);
        }
    }
}
