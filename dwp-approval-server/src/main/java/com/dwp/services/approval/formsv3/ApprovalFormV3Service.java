package com.dwp.services.approval.formsv3;

import static com.dwp.services.approval.formsv3.ApprovalFormV3Models.*;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.formsv3.ApprovalFormV3Authority.Access;
import com.dwp.services.approval.formsv3.ApprovalFormV3Repository.Mutation;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApprovalFormV3Service {
    private final ApprovalFormV3Authority authority;
    private final ApprovalFormV3Repository repository;
    private final ApprovalFormSchemaV3Compiler compiler;
    private final ApprovalFormSchemaV3Evaluator evaluator;
    private final AuditOutboxRecorder audit;
    private final ApprovalFormV3Editor editor;

    public ApprovalFormV3Service(ApprovalFormV3Authority authority, ApprovalFormV3Repository repository,
            AuditOutboxRecorder audit) {
        this.authority = authority;
        this.repository = repository;
        this.audit = audit;
        this.compiler = new ApprovalFormSchemaV3Compiler();
        this.evaluator = new ApprovalFormSchemaV3Evaluator();
        this.editor = new ApprovalFormV3Editor();
    }

    @Transactional(readOnly = true)
    public Workspace workspace(UUID formId) {
        Access access = authority.require("VIEW");
        Workspace result = repository.workspace(access, required(formId), false);
        authority.unchanged(access, "VIEW");
        return result;
    }

    @Transactional(readOnly = true)
    public WorkspacePage workspaces(WorkspaceFilter filter) {
        Access access = authority.require("VIEW");
        WorkspacePage result = repository.workspaces(access, filter);
        authority.unchanged(access, "VIEW");
        return result;
    }

    @Transactional(readOnly = true)
    public History history(UUID formId, int size) {
        Access access = authority.require("VIEW");
        History result = repository.history(access, required(formId), size);
        authority.unchanged(access, "VIEW");
        return result;
    }

    @Transactional(readOnly = true)
    public VersionDiff diff(UUID formId, int fromVersion, int toVersion) {
        if (fromVersion < 1 || toVersion < 1 || fromVersion == toVersion) {
            throw invalid("Two distinct positive Form V3 versions are required.");
        }
        Access access = authority.require("VIEW");
        UUID exactFormId = required(formId);
        Version from = repository.version(access, exactFormId, fromVersion);
        Version to = repository.version(access, exactFormId, toVersion);
        ApprovalFormSchemaV3 before = compiler.compile(from.schema());
        ApprovalFormSchemaV3 after = compiler.compile(to.schema());
        ApprovalFormSchemaV3.CompatibilityReport report = compiler.compare(before, after);
        Set<String> added = new LinkedHashSet<>(after.fieldKeys());
        added.removeAll(before.fieldKeys());
        Set<String> removed = new LinkedHashSet<>(before.fieldKeys());
        removed.removeAll(after.fieldKeys());
        VersionDiff result = new VersionDiff(exactFormId, fromVersion, toVersion,
                before.sha256(), after.sha256(), report.compatible(),
                added.stream().sorted().toList(), removed.stream().sorted().toList(), report.changes());
        authority.unchanged(access, "VIEW");
        return result;
    }

    @Transactional(readOnly = true)
    public ApprovalFormSchemaV3.Evaluation evaluate(UUID formId, Map<String, Object> payload) {
        Access access = authority.require("VIEW");
        Workspace workspace = repository.workspace(access, required(formId), false);
        ApprovalFormSchemaV3 schema = compiler.compile(workspace.current().schema());
        ApprovalFormSchemaV3.Evaluation result = evaluator.evaluate(schema, payload, access.actor().roles());
        authority.unchanged(access, "VIEW");
        return result;
    }

    @Transactional(readOnly = true)
    public SchemaValidation validateSchema(Map<String, Object> material) {
        Access access = authority.require("VIEW");
        ApprovalFormSchemaV3 schema = compiler.compile(material);
        SchemaValidation result = validation(schema);
        authority.unchanged(access, "VIEW");
        return result;
    }

    @Transactional(readOnly = true)
    public DraftReview review(UUID formId) {
        Access access = authority.require("VIEW");
        Workspace workspace = repository.workspace(access, required(formId), false);
        ApprovalFormSchemaV3 schema = compiler.compile(workspace.current().schema());
        SchemaValidation validation = validation(schema);
        List<String> blockers = new java.util.ArrayList<>();
        if (!"DRAFT".equals(workspace.lifecycleState())) blockers.add("WORKSPACE_NOT_DRAFT");
        if (!validation.scenariosPassed()) blockers.add("SCENARIO_MISMATCH");
        DraftReview result = new DraftReview(workspace.formId(), workspace.workspaceVersion(),
                workspace.lifecycleState(), workspace.sourceTemplateVersionId(), validation, blockers);
        authority.unchanged(access, "VIEW");
        return result;
    }

    @Transactional(readOnly = true)
    public List<ApprovalFormSchemaV3.ScenarioResult> scenarios(UUID formId) {
        Access access = authority.require("VIEW");
        Workspace workspace = repository.workspace(access, required(formId), false);
        List<ApprovalFormSchemaV3.ScenarioResult> result = evaluator.evaluateScenarios(
                compiler.compile(workspace.current().schema()));
        authority.unchanged(access, "VIEW");
        return result;
    }

    @Transactional
    public Workspace install(InstallDraft input, String idempotencyKey, String correlationId) {
        validate(input);
        Access access = authority.require("CREATE");
        ApprovalFormSchemaV3 schema = compileAndVerify(input.schema());
        if (!"INITIAL".equals(schema.compatibilityMode()) || input.sourceTemplateVersionId() == null) {
            throw invalid("Template installation requires an initial V3 schema and an immutable source version.");
        }
        String digest = digest(Map.ofEntries(
                Map.entry("command", "INSTALL_TEMPLATE"), Map.entry("formKey", input.formKey()),
                Map.entry("nameKo", input.nameKo()), Map.entry("nameEn", input.nameEn()),
                Map.entry("descriptionKo", input.descriptionKo()), Map.entry("descriptionEn", input.descriptionEn()),
                Map.entry("ownerGroupRef", input.ownerGroupRef()), Map.entry("categoryKey", input.categoryKey()),
                Map.entry("defaultWorkflowKey", input.defaultWorkflowKey()),
                Map.entry("sourceTemplateVersionId", input.sourceTemplateVersionId().toString()),
                Map.entry("expectedSourceTemplateVersion", input.expectedSourceTemplateVersion()),
                Map.entry("schemaSha256", schema.sha256())));
        Mutation mutation = repository.install(access, input, schema, idempotencyKey, digest);
        authority.unchanged(access, "CREATE");
        if (mutation.committed()) record(access, mutation.workspace(), "installed", correlationId,
                Map.of("sourceTemplateVersionId", input.sourceTemplateVersionId().toString(), "schemaSha256", schema.sha256()));
        return mutation.workspace();
    }

    @Transactional
    public Workspace cloneDraft(UUID sourceFormId, CloneDraft input, String idempotencyKey, String correlationId) {
        validate(input);
        Access access = authority.require("CREATE", "VIEW");
        UUID exactSourceFormId = required(sourceFormId);
        String digest = digest(Map.ofEntries(
                Map.entry("command", "CLONE_DRAFT"), Map.entry("sourceFormId", exactSourceFormId.toString()),
                Map.entry("sourceWorkspaceVersion", input.expectedSourceWorkspaceVersion()),
                Map.entry("formKey", input.formKey()), Map.entry("nameKo", input.nameKo()),
                Map.entry("nameEn", input.nameEn()), Map.entry("descriptionKo", input.descriptionKo()),
                Map.entry("descriptionEn", input.descriptionEn()), Map.entry("ownerGroupRef", input.ownerGroupRef()),
                Map.entry("categoryKey", input.categoryKey()), Map.entry("defaultWorkflowKey", input.defaultWorkflowKey())));
        Mutation replay = repository.replay(access, "CLONE_DRAFT", idempotencyKey, digest);
        if (replay != null) {
            authority.unchanged(access, "CREATE", "VIEW");
            return replay.workspace();
        }
        Workspace source = repository.workspace(access, exactSourceFormId, true);
        ApprovalFormSchemaV3 schema = compileAndVerify(source.current().schema());
        Mutation mutation = repository.cloneDraft(access, source, input, schema, idempotencyKey, digest);
        authority.unchanged(access, "CREATE", "VIEW");
        if (mutation.committed()) record(access, mutation.workspace(), "cloned", correlationId,
                Map.of("sourceFormId", sourceFormId.toString(), "sourceSchemaSha256", schema.sha256()));
        return mutation.workspace();
    }

    @Transactional
    public Workspace update(UUID formId, UpdateDraft input, String idempotencyKey, String correlationId) {
        if (input == null || input.expectedWorkspaceVersion() < 0 || input.schema() == null) {
            throw invalid("A version-fenced Form V3 update is required.");
        }
        Access access = authority.require("UPDATE");
        UUID exactFormId = required(formId);
        ApprovalFormSchemaV3 candidate = compileAndVerify(input.schema());
        String digest = digest(Map.of("command", "UPDATE_DRAFT", "formId", exactFormId.toString(),
                "expectedWorkspaceVersion", input.expectedWorkspaceVersion(), "schemaSha256", candidate.sha256()));
        Mutation replay = repository.replay(access, "UPDATE_DRAFT", idempotencyKey, digest);
        if (replay != null) {
            authority.unchanged(access, "UPDATE");
            return replay.workspace();
        }
        Workspace before = repository.workspace(access, exactFormId, false);
        ApprovalFormSchemaV3 base = compiler.compile(before.current().schema());
        ApprovalFormSchemaV3.CompatibilityReport compatibility = compiler.compare(base, candidate);
        if (!compatibility.compatible()) throw conflict("The Form V3 compatibility contract rejects this update.");
        Mutation mutation = repository.update(access, exactFormId, input, candidate, idempotencyKey, digest);
        authority.unchanged(access, "UPDATE");
        if (mutation.committed()) record(access, mutation.workspace(), "updated", correlationId,
                Map.of("baseSchemaSha256", base.sha256(), "schemaSha256", candidate.sha256(),
                        "compatibilityMode", candidate.compatibilityMode()));
        return mutation.workspace();
    }

    @Transactional
    public Workspace addField(UUID formId, AddField input, String idempotencyKey, String correlationId) {
        if (input == null || input.expectedWorkspaceVersion() < 0 || input.field() == null) {
            throw invalid("A version-fenced add-field command is required.");
        }
        Map<String, Object> material = Map.of("pageKey", input.pageKey(), "sectionKey", input.sectionKey(),
                "index", input.index(), "field", input.field(), "compatibilityMode", input.compatibilityMode());
        return edit(formId, input.expectedWorkspaceVersion(), "ADD_FIELD", material,
                workspace -> editor.add(workspace.current().schema(), input.pageKey(), input.sectionKey(),
                        input.index(), input.field(), input.compatibilityMode(), workspace.current().schemaSha256()),
                idempotencyKey, correlationId);
    }

    @Transactional
    public Workspace deleteField(UUID formId, DeleteField input, String idempotencyKey, String correlationId) {
        if (input == null || input.expectedWorkspaceVersion() < 0) {
            throw invalid("A version-fenced delete-field command is required.");
        }
        Map<String, Object> material = Map.of("fieldKey", input.fieldKey(),
                "compatibilityMode", input.compatibilityMode());
        return edit(formId, input.expectedWorkspaceVersion(), "DELETE_FIELD", material,
                workspace -> editor.delete(workspace.current().schema(), input.fieldKey(),
                        input.compatibilityMode(), workspace.current().schemaSha256()),
                idempotencyKey, correlationId);
    }

    @Transactional
    public Workspace cloneField(UUID formId, CloneField input, String idempotencyKey, String correlationId) {
        if (input == null || input.expectedWorkspaceVersion() < 0) {
            throw invalid("A version-fenced clone-field command is required.");
        }
        Map<String, Object> material = Map.of("sourceFieldKey", input.sourceFieldKey(),
                "targetFieldKey", input.targetFieldKey(), "index", input.index(),
                "compatibilityMode", input.compatibilityMode());
        return edit(formId, input.expectedWorkspaceVersion(), "CLONE_FIELD", material,
                workspace -> editor.cloneField(workspace.current().schema(), input.sourceFieldKey(),
                        input.targetFieldKey(), input.index(), input.compatibilityMode(),
                        workspace.current().schemaSha256()), idempotencyKey, correlationId);
    }

    @Transactional
    public Workspace reorderFields(UUID formId, ReorderFields input,
            String idempotencyKey, String correlationId) {
        if (input == null || input.expectedWorkspaceVersion() < 0 || input.fieldKeys().isEmpty()) {
            throw invalid("A version-fenced reorder command is required.");
        }
        Map<String, Object> material = Map.of("pageKey", input.pageKey(), "sectionKey", input.sectionKey(),
                "fieldKeys", input.fieldKeys());
        return edit(formId, input.expectedWorkspaceVersion(), "REORDER_FIELDS", material,
                workspace -> editor.reorder(workspace.current().schema(), input.pageKey(), input.sectionKey(),
                        input.fieldKeys(), workspace.current().schemaSha256()), idempotencyKey, correlationId);
    }

    @Transactional
    public Workspace updateFieldProperties(UUID formId, UpdateFieldProperties input,
            String idempotencyKey, String correlationId) {
        if (input == null || input.expectedWorkspaceVersion() < 0 || input.properties() == null
                || input.properties().isEmpty()) {
            throw invalid("A version-fenced field-property command is required.");
        }
        Map<String, Object> material = Map.of("fieldKey", input.fieldKey(), "properties", input.properties(),
                "compatibilityMode", input.compatibilityMode());
        return edit(formId, input.expectedWorkspaceVersion(), "UPDATE_FIELD_PROPERTIES", material,
                workspace -> editor.properties(workspace.current().schema(), input.fieldKey(), input.properties(),
                        input.compatibilityMode(), workspace.current().schemaSha256()),
                idempotencyKey, correlationId);
    }

    private Workspace edit(UUID formId, long expectedWorkspaceVersion, String command,
            Map<String, Object> commandMaterial, Function<Workspace, Map<String, Object>> transformation,
            String idempotencyKey, String correlationId) {
        Access access = authority.require("UPDATE");
        UUID exactFormId = required(formId);
        Map<String, Object> digestMaterial = new java.util.LinkedHashMap<>(commandMaterial);
        digestMaterial.put("command", command);
        digestMaterial.put("formId", exactFormId.toString());
        digestMaterial.put("expectedWorkspaceVersion", expectedWorkspaceVersion);
        String commandDigest = digest(digestMaterial);
        Mutation replay = repository.replay(access, command, idempotencyKey, commandDigest);
        if (replay != null) {
            authority.unchanged(access, "UPDATE");
            return replay.workspace();
        }
        Workspace before = repository.workspace(access, exactFormId, false);
        if (!"DRAFT".equals(before.lifecycleState())
                || before.workspaceVersion() != expectedWorkspaceVersion) {
            throw conflict("The Form V3 workspace changed before the editor command.");
        }
        ApprovalFormSchemaV3 base = compiler.compile(before.current().schema());
        ApprovalFormSchemaV3 candidate = compileAndVerify(transformation.apply(before));
        ApprovalFormSchemaV3.CompatibilityReport compatibility = compiler.compare(base, candidate);
        if (!compatibility.compatible()) {
            throw conflict("The Form V3 compatibility contract rejects this editor command.");
        }
        Mutation mutation = repository.update(access, exactFormId,
                new UpdateDraft(expectedWorkspaceVersion, candidate.definition()), candidate,
                command, idempotencyKey, commandDigest, commandMaterial);
        authority.unchanged(access, "UPDATE");
        if (mutation.committed()) {
            record(access, mutation.workspace(), command.toLowerCase(java.util.Locale.ROOT), correlationId,
                    Map.of("baseSchemaSha256", base.sha256(), "schemaSha256", candidate.sha256(),
                            "compatibilityMode", candidate.compatibilityMode(), "command", command));
        }
        return mutation.workspace();
    }

    @Transactional
    public Workspace archive(UUID formId, ArchiveDraft input, String idempotencyKey, String correlationId) {
        if (input == null || input.expectedWorkspaceVersion() < 0) throw invalid("A version-fenced archive command is required.");
        Access access = authority.require("UPDATE");
        String digest = digest(Map.of("command", "ARCHIVE_DRAFT", "formId", required(formId).toString(),
                "expectedWorkspaceVersion", input.expectedWorkspaceVersion()));
        Mutation mutation = repository.archive(access, formId, input, idempotencyKey, digest);
        authority.unchanged(access, "UPDATE");
        if (mutation.committed()) record(access, mutation.workspace(), "archived", correlationId,
                Map.of("schemaSha256", mutation.workspace().current().schemaSha256()));
        return mutation.workspace();
    }

    private ApprovalFormSchemaV3 compileAndVerify(Map<String, Object> schema) {
        ApprovalFormSchemaV3 compiled = compiler.compile(schema);
        evaluator.requirePassingScenarios(compiled);
        return compiled;
    }

    private SchemaValidation validation(ApprovalFormSchemaV3 schema) {
        List<ApprovalFormSchemaV3.ScenarioResult> scenarios = evaluator.evaluateScenarios(schema);
        return new SchemaValidation(schema.sha256(), schema.compatibilityMode(),
                schema.fieldKeys().stream().sorted().toList(),
                scenarios.stream().allMatch(ApprovalFormSchemaV3.ScenarioResult::passed), scenarios);
    }

    private void validate(InstallDraft input) {
        if (input == null || input.expectedSourceTemplateVersion() < 0) {
            throw invalid("Version-fenced template installation input is required.");
        }
        metadata(input.formKey(), input.nameKo(), input.nameEn(), input.descriptionKo(), input.descriptionEn(),
                input.ownerGroupRef(), input.categoryKey(), input.defaultWorkflowKey());
        if (input.schema() == null) throw invalid("Template schema material is required.");
    }

    private void validate(CloneDraft input) {
        if (input == null || input.expectedSourceWorkspaceVersion() < 0) throw invalid("Version-fenced clone input is required.");
        metadata(input.formKey(), input.nameKo(), input.nameEn(), input.descriptionKo(), input.descriptionEn(),
                input.ownerGroupRef(), input.categoryKey(), input.defaultWorkflowKey());
    }

    private void metadata(String formKey, String nameKo, String nameEn, String descriptionKo,
            String descriptionEn, String owner, String category, String workflow) {
        if (!code(formKey, 100) || !text(nameKo, 200, false) || !text(nameEn, 200, false)
                || !text(descriptionKo, 1000, true) || !text(descriptionEn, 1000, true)
                || !reference(owner, 160) || !code(category, 100) || !code(workflow, 100)) {
            throw invalid("Form V3 metadata is invalid.");
        }
    }

    private boolean code(String value, int max) {
        return value != null && value.length() <= max && value.matches("[A-Z][A-Z0-9_]{1," + (max - 1) + "}");
    }

    private boolean reference(String value, int max) {
        return value != null && value.length() <= max && value.matches("[A-Z][A-Z0-9_.:-]{1," + (max - 1) + "}");
    }

    private boolean text(String value, int max, boolean blankAllowed) {
        return value != null && value.length() <= max && value.equals(value.strip())
                && (blankAllowed || !value.isBlank()) && value.codePoints().noneMatch(Character::isISOControl);
    }

    private String digest(Map<String, Object> material) {
        return ApprovalFormSchemaV3Canonical.freeze(material).sha256();
    }

    private void record(Access access, Workspace workspace, String action, String correlationId,
            Map<String, Object> after) {
        audit.record(AuditEvent.builder().tenantId(access.actor().tenantId()).category("ADMIN_CHANGE")
                .action("approval.form.v3.draft." + action).outcome("SUCCESS").severity("INFO")
                .actorType("USER").actorId(access.actor().userId().toString())
                .actorRoles(List.copyOf(access.actor().roles())).sourceService("dwp-approval-server")
                .sourceModule("approval-formsv3").targetType("APPROVAL_FORM_V3_DRAFT")
                .targetId(workspace.formId().toString()).correlationId(correlationId)
                .afterState(after).retentionClass("EXTENDED").build());
    }

    private UUID required(UUID value) { if (value == null) throw invalid("Form identifier is required."); return value; }
    private BaseException invalid(String message) { return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message); }
    private BaseException conflict(String message) { return new BaseException(ErrorCode.RESOURCE_CONFLICT, message); }
}
