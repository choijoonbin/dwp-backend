package com.dwp.services.approval.formsv3;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ApprovalFormV3Models {
    private ApprovalFormV3Models() { }

    public record InstallDraft(String formKey, String nameKo, String nameEn,
            String descriptionKo, String descriptionEn, String ownerGroupRef,
            String categoryKey, String defaultWorkflowKey,
            UUID sourceTemplateVersionId, long expectedSourceTemplateVersion,
            Map<String, Object> schema) { }

    public record CloneDraft(String formKey, String nameKo, String nameEn,
            String descriptionKo, String descriptionEn, String ownerGroupRef,
            String categoryKey, String defaultWorkflowKey, long expectedSourceWorkspaceVersion) { }

    public record UpdateDraft(long expectedWorkspaceVersion, Map<String, Object> schema) { }

    public record AddField(long expectedWorkspaceVersion, String pageKey, String sectionKey,
            int index, Map<String, Object> field, String compatibilityMode) { }

    public record DeleteField(long expectedWorkspaceVersion, String fieldKey, String compatibilityMode) { }

    public record CloneField(long expectedWorkspaceVersion, String sourceFieldKey,
            String targetFieldKey, int index, String compatibilityMode) { }

    public record ReorderFields(long expectedWorkspaceVersion, String pageKey,
            String sectionKey, List<String> fieldKeys) {
        public ReorderFields { fieldKeys = fieldKeys == null ? List.of() : List.copyOf(fieldKeys); }
    }

    public record UpdateFieldProperties(long expectedWorkspaceVersion, String fieldKey,
            Map<String, Object> properties, String compatibilityMode) { }

    public record ArchiveDraft(long expectedWorkspaceVersion) { }

    public record WorkspaceFilter(String query, String lifecycleState,
            String afterFormKey, int limit) { }

    public record Version(UUID formVersionId, int versionNumber, Map<String, Object> schema,
            String schemaSha256, String compatibilityMode, String baseSchemaSha256,
            UUID sourceTemplateVersionId, UUID sourceFormVersionId,
            OffsetDateTime createdAt, long createdBy) { }

    public record Workspace(UUID formId, String formKey, String nameKo, String nameEn,
            String descriptionKo, String descriptionEn, String ownerGroupRef,
            UUID categoryId, String categoryKey, UUID defaultWorkflowId,
            String defaultWorkflowKey, UUID sourceTemplateVersionId, UUID sourceFormId,
            String lifecycleState, long workspaceVersion, Version current,
            OffsetDateTime createdAt, long createdBy, OffsetDateTime updatedAt, long updatedBy) { }

    public record WorkspaceSummary(UUID formId, String formKey, String nameKo, String nameEn,
            String ownerGroupRef, String categoryKey, String defaultWorkflowKey,
            String lifecycleState, long workspaceVersion, int currentVersionNumber,
            UUID currentFormVersionId, String schemaSha256, UUID sourceTemplateVersionId,
            UUID sourceFormId, OffsetDateTime updatedAt, long updatedBy) { }

    public record WorkspacePage(List<WorkspaceSummary> items, boolean mayHaveMore, String nextFormKey) {
        public WorkspacePage { items = List.copyOf(items); }
    }

    public record History(List<Version> versions, boolean mayHaveMore) {
        public History { versions = List.copyOf(versions); }
    }

    public record SchemaValidation(String schemaSha256, String compatibilityMode,
            List<String> fieldKeys, boolean scenariosPassed,
            List<ApprovalFormSchemaV3.ScenarioResult> scenarios) {
        public SchemaValidation {
            fieldKeys = List.copyOf(fieldKeys);
            scenarios = List.copyOf(scenarios);
        }
    }

    public record DraftReview(UUID formId, long workspaceVersion, String lifecycleState,
            UUID sourceTemplateVersionId, SchemaValidation validation, List<String> blockers) {
        public DraftReview { blockers = List.copyOf(blockers); }
    }

    public record VersionDiff(UUID formId, int fromVersion, int toVersion,
            String fromSchemaSha256, String toSchemaSha256, boolean compatible,
            List<String> addedFields, List<String> removedFields,
            List<ApprovalFormSchemaV3.CompatibilityChange> changes) {
        public VersionDiff {
            addedFields = List.copyOf(addedFields);
            removedFields = List.copyOf(removedFields);
            changes = List.copyOf(changes);
        }
    }
}
