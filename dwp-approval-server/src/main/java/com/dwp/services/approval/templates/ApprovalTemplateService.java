package com.dwp.services.approval.templates;

import static com.dwp.services.approval.templates.ApprovalTemplateModels.*;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.formsv3.ApprovalFormSchemaV3;
import com.dwp.services.approval.formsv3.ApprovalFormSchemaV3Compiler;
import com.dwp.services.approval.formsv3.ApprovalFormV3Models.InstallDraft;
import com.dwp.services.approval.formsv3.ApprovalFormV3Service;
import com.dwp.services.approval.templates.ApprovalTemplateAuthority.Access;
import com.dwp.services.approval.templates.ApprovalTemplateRepository.Mutation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApprovalTemplateService {
    private final ApprovalTemplateAuthority authority;
    private final ApprovalTemplateRepository repository;
    private final ApprovalFormV3Service forms;
    private final AuditOutboxRecorder audit;
    private final ApprovalFormSchemaV3Compiler compiler = new ApprovalFormSchemaV3Compiler();
    private final ObjectMapper mapper;

    public ApprovalTemplateService(ApprovalTemplateAuthority authority, ApprovalTemplateRepository repository,
            ApprovalFormV3Service forms, AuditOutboxRecorder audit, ObjectMapper mapper) {
        this.authority = authority;
        this.repository = repository;
        this.forms = forms;
        this.audit = audit;
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    @Transactional(readOnly = true)
    public CatalogPage catalog(CatalogFilter filter) {
        Access access = authority.require("VIEW");
        CatalogPage result = repository.catalog(access, filter);
        authority.unchanged(access, "VIEW");
        return result;
    }

    @Transactional(readOnly = true)
    public Template template(UUID templateId) {
        Access access = authority.require("VIEW");
        Template result = repository.template(access, required(templateId));
        authority.unchanged(access, "VIEW");
        return result;
    }

    @Transactional(readOnly = true)
    public UpdateComparison compare(UUID templateId, int installedVersion) {
        if (installedVersion < 1) throw invalid("Installed template version must be positive.");
        Access access = authority.require("VIEW");
        Template template = repository.template(access, required(templateId));
        TemplateVersion installed = repository.version(access, templateId, installedVersion);
        TemplateVersion available = template.current();
        ApprovalFormSchemaV3 base = compiler.compile(installed.schema());
        ApprovalFormSchemaV3 candidate = compiler.compile(available.schema());
        ApprovalFormSchemaV3.CompatibilityReport compatibility = installedVersion == available.versionNumber()
                ? new ApprovalFormSchemaV3.CompatibilityReport(base.sha256(), candidate.sha256(), true, List.of())
                : compiler.compare(base, candidate);
        Set<String> added = new LinkedHashSet<>(candidate.fieldKeys()); added.removeAll(base.fieldKeys());
        Set<String> removed = new LinkedHashSet<>(base.fieldKeys()); removed.removeAll(candidate.fieldKeys());
        Set<String> beforeDependencies = dependencyKeys(installed.dependencies());
        Set<String> afterDependencies = dependencyKeys(available.dependencies());
        List<String> changedDependencies = new ArrayList<>();
        beforeDependencies.stream().filter(value -> !afterDependencies.contains(value))
                .forEach(value -> changedDependencies.add("REMOVED:" + value));
        afterDependencies.stream().filter(value -> !beforeDependencies.contains(value))
                .forEach(value -> changedDependencies.add("ADDED:" + value));
        authority.unchanged(access, "VIEW");
        return new UpdateComparison(templateId, installedVersion, available.versionNumber(),
                installedVersion < available.versionNumber(), compatibility.compatible(),
                added.stream().sorted().toList(), removed.stream().sorted().toList(),
                changedDependencies.stream().sorted().toList(), compatibility.changes());
    }

    @Transactional(readOnly = true)
    public TemplatePreview preview(UUID templateVersionId, String viewport) {
        if (!Set.of("DESKTOP", "MOBILE").contains(viewport)) {
            throw invalid("Template preview viewport must be DESKTOP or MOBILE.");
        }
        Access access = authority.require("VIEW");
        Template template = repository.identityForVersion(access, required(templateVersionId));
        TemplateVersion version = repository.visibleVersion(access, templateVersionId);
        ApprovalFormSchemaV3 compiled = compiler.compile(version.schema());
        int pages = list(version.schema().get("pages")).size();
        int sections = 0;
        for (Object rawPage : list(version.schema().get("pages"))) {
            sections += list(map(rawPage).get("sections")).size();
        }
        TemplatePreview result = new TemplatePreview(template.templateId(), templateVersionId,
                version.versionNumber(), viewport, compiled.sha256(), pages, sections,
                compiled.fieldKeys().size(), compiled.definition());
        authority.unchanged(access, "VIEW");
        return result;
    }

    @Transactional
    public PackageImport importPackage(ImportPackage input, String idempotencyKey, String correlationId) {
        validate(input);
        Access access = authority.require("CREATE", "VIEW");
        ApprovalFormSchemaV3 schema = compiler.compile(input.schema());
        if (!"INITIAL".equals(schema.compatibilityMode())) {
            throw invalid("Imported template packages require an INITIAL Form V3 schema.");
        }
        Map<String, Object> manifest = packageManifest(input, schema);
        String verifiedSha = digest(manifest);
        if (!verifiedSha.equals(input.packageSha256())) {
            throw invalid("Template package SHA-256 does not match its canonical manifest.");
        }
        String commandSha = digest(Map.of("command", "IMPORT_PACKAGE", "manifest", manifest));
        Mutation replay = repository.replay(access, "IMPORT_PACKAGE", idempotencyKey, commandSha);
        if (replay != null) {
            authority.unchanged(access, "CREATE", "VIEW");
            return new PackageImport(input.packageId(), input.packageVersion(), verifiedSha,
                    replay.template(), false);
        }
        Mutation mutation = repository.importPackage(access, input, schema, manifest,
                idempotencyKey, commandSha);
        authority.unchanged(access, "CREATE", "VIEW");
        if (mutation.committed()) {
            record(access, mutation.template().templateId(), "package.imported", correlationId,
                    Map.of("packageId", input.packageId().toString(),
                            "packageVersion", input.packageVersion(), "packageSha256", verifiedSha,
                            "templateVersionId", mutation.template().current().templateVersionId().toString()));
        }
        return new PackageImport(input.packageId(), input.packageVersion(), verifiedSha,
                mutation.template(), mutation.committed());
    }

    @Transactional
    public Template cloneTemplate(UUID sourceTemplateId, CloneTemplate input,
            String idempotencyKey, String correlationId) {
        validate(input);
        Access access = authority.require("CREATE", "VIEW");
        UUID exactSourceTemplateId = required(sourceTemplateId);
        String digest = digest(Map.of("command", "CLONE_TEMPLATE", "sourceTemplateId", exactSourceTemplateId,
                "input", input));
        Mutation replay = repository.replayClone(access, idempotencyKey, digest);
        if (replay != null) {
            authority.unchanged(access, "CREATE", "VIEW");
            return replay.template();
        }
        Template source = repository.template(access, exactSourceTemplateId);
        if (!"ACTIVE".equals(source.lifecycleState()) || !"RELEASED".equals(source.current().lifecycleState())) {
            throw conflict("Only an active released template may be cloned.");
        }
        Mutation mutation = repository.cloneTemplate(access, source, input, idempotencyKey, digest);
        authority.unchanged(access, "CREATE", "VIEW");
        if (mutation.committed()) record(access, mutation.template().templateId(), "cloned", correlationId,
                Map.of("sourceTemplateId", sourceTemplateId.toString(),
                        "sourceTemplateVersionId", source.current().templateVersionId().toString()));
        return mutation.template();
    }

    @Transactional
    public Installation install(UUID templateVersionId, InstallTemplate input,
            String idempotencyKey, String correlationId) {
        validate(input);
        Access access = authority.require("CREATE", "VIEW");
        TemplateVersion version = repository.releasedVersion(access, required(templateVersionId));
        Template template = repository.identityForVersion(access, templateVersionId);
        if (template.version() != input.expectedTemplateVersion()) {
            throw conflict("The source template aggregate version changed.");
        }
        repository.lockActiveReleased(access, template.templateId(), input.expectedTemplateVersion());
        requireDependencies(version.dependencies(), input);
        InstallDraft draft = new InstallDraft(input.formKey(), input.nameKo(), input.nameEn(),
                input.descriptionKo(), input.descriptionEn(), input.ownerGroupRef(), input.categoryKey(),
                input.defaultWorkflowKey(), templateVersionId, input.expectedTemplateVersion(), version.schema());
        WorkspaceResult result = new WorkspaceResult(forms.install(draft, idempotencyKey, correlationId));
        authority.unchanged(access, "CREATE", "VIEW");
        return new Installation(result.workspace(), template.templateId(), templateVersionId, version.versionNumber());
    }

    private void requireDependencies(List<Dependency> dependencies, InstallTemplate input) {
        for (Dependency dependency : dependencies) {
            if (!dependency.required()) continue;
            switch (dependency.kind()) {
                case "CATEGORY_KEY" -> {
                    if (!dependency.key().equals(input.categoryKey())) throw conflict("Template category dependency changed.");
                }
                case "WORKFLOW_KEY" -> {
                    if (!dependency.key().equals(input.defaultWorkflowKey())) throw conflict("Template workflow dependency changed.");
                }
                default -> throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                        "A required template dependency is not configured in this bounded release.");
            }
        }
    }

    private Set<String> dependencyKeys(List<Dependency> dependencies) {
        Set<String> values = new LinkedHashSet<>();
        for (Dependency dependency : dependencies) {
            values.add(dependency.kind() + ':' + dependency.key() + ':' + dependency.versionConstraint()
                    + ':' + dependency.required());
        }
        return values;
    }

    private void validate(CloneTemplate input) {
        if (input == null || input.expectedTemplateVersion() < 0) {
            throw invalid("Version-fenced template clone input is required.");
        }
        metadata(input.templateKey(), input.nameKo(), input.nameEn(), input.descriptionKo(), input.descriptionEn(),
                input.ownerGroupRef(), input.categoryKey(), input.defaultWorkflowKey());
    }

    private void validate(InstallTemplate input) {
        if (input == null || input.expectedTemplateVersion() < 0) {
            throw invalid("Version-fenced template installation input is required.");
        }
        metadata(input.formKey(), input.nameKo(), input.nameEn(), input.descriptionKo(), input.descriptionEn(),
                input.ownerGroupRef(), input.categoryKey(), input.defaultWorkflowKey());
    }

    private void validate(ImportPackage input) {
        if (input == null || input.packageId() == null || input.expectedTemplateVersion() != 0
                || input.schema() == null || input.packageVersion() == null
                || !input.packageVersion().matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,79}")
                || input.packageSha256() == null || !input.packageSha256().matches("[a-f0-9]{64}")
                || !input.locales().equals(Set.of("ko", "en")) || input.tags().size() > 20
                || input.tags().stream().anyMatch(tag -> !tag.matches("[a-z0-9][a-z0-9-]{0,39}"))
                || input.dependencies().size() > 50 || input.dependencies().stream().distinct().count()
                        != input.dependencies().size()
                || !text(input.changeSummaryKo(), 1000, true)
                || !text(input.changeSummaryEn(), 1000, true)) {
            throw invalid("Template package manifest is invalid.");
        }
        metadata(input.templateKey(), input.nameKo(), input.nameEn(), input.descriptionKo(),
                input.descriptionEn(), input.ownerGroupRef(), input.categoryKey(), input.defaultWorkflowKey());
    }

    private Map<String, Object> packageManifest(ImportPackage input, ApprovalFormSchemaV3 schema) {
        return Map.ofEntries(
                Map.entry("packageId", input.packageId().toString()),
                Map.entry("packageVersion", input.packageVersion()),
                Map.entry("templateKey", input.templateKey()),
                Map.entry("ownerGroupRef", input.ownerGroupRef()),
                Map.entry("categoryKey", input.categoryKey()),
                Map.entry("defaultWorkflowKey", input.defaultWorkflowKey()),
                Map.entry("nameKo", input.nameKo()), Map.entry("nameEn", input.nameEn()),
                Map.entry("descriptionKo", input.descriptionKo()),
                Map.entry("descriptionEn", input.descriptionEn()),
                Map.entry("schema", schema.definition()),
                Map.entry("locales", input.locales().stream().sorted().toList()),
                Map.entry("tags", input.tags().stream().sorted().toList()),
                Map.entry("filterMetadata", input.filterMetadata()),
                Map.entry("dependencies", input.dependencies()),
                Map.entry("changeSummaryKo", input.changeSummaryKo()),
                Map.entry("changeSummaryEn", input.changeSummaryEn()));
    }

    @SuppressWarnings("unchecked")
    private List<Object> list(Object value) {
        if (!(value instanceof List<?> values)) throw invalid("Template preview schema is malformed.");
        return (List<Object>) values;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> values)) throw invalid("Template preview schema is malformed.");
        return (Map<String, Object>) values;
    }

    private void metadata(String key, String nameKo, String nameEn, String descriptionKo,
            String descriptionEn, String owner, String category, String workflow) {
        if (!code(key, 100) || !text(nameKo, 200, false) || !text(nameEn, 200, false)
                || !text(descriptionKo, 1000, true) || !text(descriptionEn, 1000, true)
                || !reference(owner, 160) || !code(category, 100) || !code(workflow, 100)) {
            throw invalid("Template metadata is invalid.");
        }
    }

    private boolean code(String value, int max) { return value != null && value.length() <= max
            && value.matches("[A-Z][A-Z0-9_]{1," + (max - 1) + "}"); }
    private boolean reference(String value, int max) { return value != null && value.length() <= max
            && value.matches("[A-Z][A-Z0-9_.:-]{1," + (max - 1) + "}"); }
    private boolean text(String value, int max, boolean blankAllowed) { return value != null && value.length() <= max
            && value.equals(value.strip()) && (blankAllowed || !value.isBlank())
            && value.codePoints().noneMatch(Character::isISOControl); }

    private String digest(Object value) {
        try {
            byte[] bytes = mapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) { throw invalid("Template command material is invalid."); }
    }

    private void record(Access access, UUID templateId, String action, String correlationId,
            Map<String, Object> after) {
        audit.record(AuditEvent.builder().tenantId(access.actor().tenantId()).category("ADMIN_CHANGE")
                .action("approval.form.template." + action).outcome("SUCCESS").severity("INFO")
                .actorType("USER").actorId(access.actor().userId().toString())
                .actorRoles(List.copyOf(access.actor().roles())).sourceService("dwp-approval-server")
                .sourceModule("approval-templates").targetType("APPROVAL_FORM_TEMPLATE")
                .targetId(templateId.toString()).correlationId(correlationId)
                .afterState(after).retentionClass("EXTENDED").build());
    }

    private UUID required(UUID value) { if (value == null) throw invalid("Template identifier is required."); return value; }
    private BaseException invalid(String message) { return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message); }
    private BaseException conflict(String message) { return new BaseException(ErrorCode.RESOURCE_CONFLICT, message); }

    private record WorkspaceResult(com.dwp.services.approval.formsv3.ApprovalFormV3Models.Workspace workspace) { }
}
