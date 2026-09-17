package com.dwp.services.approval.templates;

import com.dwp.services.approval.formsv3.ApprovalFormV3Models.Workspace;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ApprovalTemplateModels {
    private ApprovalTemplateModels() { }

    public record CatalogFilter(String query, String categoryKey, String ownerGroupRef,
            String locale, Set<String> tags, String scopeKind, String afterTemplateKey, int limit) {
        public CatalogFilter { tags = tags == null ? Set.of() : Set.copyOf(tags); }
    }

    public record Dependency(String kind, String key, String versionConstraint, boolean required) { }

    public record TemplateVersion(UUID templateVersionId, int versionNumber,
            String nameKo, String nameEn, String descriptionKo, String descriptionEn,
            Map<String, Object> schema, String schemaSha256, Set<String> locales,
            Set<String> tags, Map<String, Object> filterMetadata,
            String changeSummaryKo, String changeSummaryEn, String lifecycleState,
            OffsetDateTime releasedAt, Long releasedBy, List<Dependency> dependencies) {
        public TemplateVersion {
            locales = Set.copyOf(locales); tags = Set.copyOf(tags);
            filterMetadata = Map.copyOf(filterMetadata); dependencies = List.copyOf(dependencies);
        }
    }

    public record Template(UUID templateId, String scopeKind, Long tenantId,
            String managementResourceSetKey, String templateKey, String ownerGroupRef,
            String categoryKey, String defaultWorkflowKey, String lifecycleState,
            int currentVersion, UUID sourceTemplateId, long version, TemplateVersion current) { }

    public record CatalogPage(List<Template> items, boolean mayHaveMore, String nextTemplateKey) {
        public CatalogPage { items = List.copyOf(items); }
    }

    public record CloneTemplate(String templateKey, String ownerGroupRef, String categoryKey,
            String defaultWorkflowKey, String nameKo, String nameEn,
            String descriptionKo, String descriptionEn, long expectedTemplateVersion) { }

    public record InstallTemplate(String formKey, String nameKo, String nameEn,
            String descriptionKo, String descriptionEn, String ownerGroupRef,
            String categoryKey, String defaultWorkflowKey, long expectedTemplateVersion) { }

    public record ImportPackage(UUID packageId, String packageVersion, String packageSha256,
            String templateKey, String ownerGroupRef, String categoryKey, String defaultWorkflowKey,
            String nameKo, String nameEn, String descriptionKo, String descriptionEn,
            Map<String, Object> schema, Set<String> locales, Set<String> tags,
            Map<String, Object> filterMetadata, List<Dependency> dependencies,
            String changeSummaryKo, String changeSummaryEn, long expectedTemplateVersion) {
        public ImportPackage {
            schema = schema == null ? null : Map.copyOf(schema);
            locales = locales == null ? Set.of() : Set.copyOf(locales);
            tags = tags == null ? Set.of() : Set.copyOf(tags);
            filterMetadata = filterMetadata == null ? Map.of() : Map.copyOf(filterMetadata);
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        }
    }

    public record PackageImport(UUID packageId, String packageVersion, String packageSha256,
            Template template, boolean imported) { }

    public record TemplatePreview(UUID templateId, UUID templateVersionId, int versionNumber,
            String viewport, String schemaSha256, int pageCount, int sectionCount,
            int fieldCount, Map<String, Object> schema) {
        public TemplatePreview { schema = Map.copyOf(schema); }
    }

    public record UpdateComparison(UUID templateId, int installedVersion, int availableVersion,
            boolean updateAvailable, boolean compatible, List<String> addedFields,
            List<String> removedFields, List<String> changedDependencies,
            List<com.dwp.services.approval.formsv3.ApprovalFormSchemaV3.CompatibilityChange> schemaChanges) {
        public UpdateComparison {
            addedFields = List.copyOf(addedFields); removedFields = List.copyOf(removedFields);
            changedDependencies = List.copyOf(changedDependencies); schemaChanges = List.copyOf(schemaChanges);
        }
    }

    public record Installation(Workspace draft, UUID sourceTemplateId,
            UUID sourceTemplateVersionId, int sourceVersionNumber) { }
}
