package com.dwp.services.approval.formsv3;

import static com.dwp.services.approval.formsv3.ApprovalFormV3Models.*;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.formsv3.ApprovalFormV3Authority.Access;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ApprovalFormV3Repository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ApprovalFormSchemaV3Compiler compiler;

    public ApprovalFormV3Repository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper.copy();
        this.compiler = new ApprovalFormSchemaV3Compiler();
    }

    public WorkspacePage workspaces(Access access, WorkspaceFilter filter) {
        validate(filter);
        StringBuilder sql = new StringBuilder("""
                SELECT workspace.*, category.category_key, workflow.workflow_key,
                       version.form_version_id, version.schema_sha256
                  FROM apr_form_v3_workspaces workspace
                  JOIN apr_form_categories category
                    ON category.tenant_id=workspace.tenant_id AND category.category_id=workspace.category_id
                  JOIN apr_workflow_definitions workflow
                    ON workflow.tenant_id=workspace.tenant_id AND workflow.workflow_id=workspace.default_workflow_id
                  JOIN apr_form_v3_versions version
                    ON version.tenant_id=workspace.tenant_id AND version.form_id=workspace.form_id
                   AND version.version_number=workspace.current_version_number
                 WHERE workspace.tenant_id=? AND workspace.management_resource_set_key=?
                """);
        List<Object> arguments = new ArrayList<>();
        arguments.add(access.actor().tenantId());
        arguments.add(access.resourceSetKey());
        if (filter.query() != null) {
            sql.append(" AND (workspace.form_key ILIKE ? ESCAPE '!' OR workspace.name_ko ILIKE ? ESCAPE '!'"
                    + " OR workspace.name_en ILIKE ? ESCAPE '!')");
            String query = '%' + escapeLike(filter.query()) + '%';
            arguments.add(query); arguments.add(query); arguments.add(query);
        }
        if (filter.lifecycleState() != null) {
            sql.append(" AND workspace.lifecycle_state=?");
            arguments.add(filter.lifecycleState());
        }
        if (filter.afterFormKey() != null) {
            sql.append(" AND workspace.form_key>?");
            arguments.add(filter.afterFormKey());
        }
        sql.append(" ORDER BY workspace.form_key,workspace.form_id LIMIT ?");
        arguments.add(filter.limit() + 1);
        List<WorkspaceSummary> rows = jdbc.query(sql.toString(), (row, number) -> new WorkspaceSummary(
                row.getObject("form_id", UUID.class), row.getString("form_key"), row.getString("name_ko"),
                row.getString("name_en"), row.getString("owner_group_ref"), row.getString("category_key"),
                row.getString("workflow_key"), row.getString("lifecycle_state"), row.getLong("workspace_version"),
                row.getInt("current_version_number"), row.getObject("form_version_id", UUID.class),
                row.getString("schema_sha256"), row.getObject("source_template_version_id", UUID.class),
                row.getObject("source_form_id", UUID.class), row.getObject("updated_at", OffsetDateTime.class),
                row.getLong("updated_by")), arguments.toArray());
        boolean more = rows.size() > filter.limit();
        List<WorkspaceSummary> items = rows.stream().limit(filter.limit()).toList();
        return new WorkspacePage(items, more, items.isEmpty() ? null : items.getLast().formKey());
    }

    public Workspace workspace(Access access, UUID formId, boolean lock) {
        List<Workspace> values = jdbc.query("""
                SELECT workspace.*, category.category_key, workflow.workflow_key
                  FROM apr_form_v3_workspaces workspace
                  JOIN apr_form_categories category
                    ON category.tenant_id=workspace.tenant_id AND category.category_id=workspace.category_id
                  JOIN apr_workflow_definitions workflow
                    ON workflow.tenant_id=workspace.tenant_id AND workflow.workflow_id=workspace.default_workflow_id
                 WHERE workspace.tenant_id=? AND workspace.management_resource_set_key=? AND workspace.form_id=?
                """ + (lock ? " FOR UPDATE OF workspace" : ""),
                (row, number) -> mapWorkspace(access, row), access.actor().tenantId(), access.resourceSetKey(), formId);
        if (values.size() != 1) throw notFound();
        return values.getFirst();
    }

    public History history(Access access, UUID formId, int size) {
        if (size < 1 || size > 100) throw invalid("History size must be between 1 and 100.");
        workspace(access, formId, false);
        List<Version> versions = jdbc.query("""
                SELECT version.* FROM apr_form_v3_versions version
                 WHERE version.tenant_id=? AND version.form_id=?
                 ORDER BY version.version_number DESC LIMIT ?
                """, (row, number) -> mapVersion(row), access.actor().tenantId(), formId, size + 1);
        return new History(versions.stream().limit(size).toList(), versions.size() > size);
    }

    public Version version(Access access, UUID formId, int versionNumber) {
        if (versionNumber < 1) throw invalid("Form version must be positive.");
        workspace(access, formId, false);
        return version(access.actor().tenantId(), formId, versionNumber);
    }

    public Mutation replay(Access access, String command, String key, String digest) {
        Prior prior = prior(access, command, key, digest);
        return prior == null ? null : new Mutation(workspace(access, prior.formId(), false), false);
    }

    public Mutation install(Access access, InstallDraft input, ApprovalFormSchemaV3 schema,
            String key, String digest) {
        Prior prior = prior(access, "INSTALL_TEMPLATE", key, digest);
        if (prior != null) return new Mutation(workspace(access, prior.formId(), false), false);
        References references = references(access, input.categoryKey(), input.defaultWorkflowKey());
        UUID formId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO apr_form_v3_workspaces(form_id,tenant_id,management_resource_set_key,form_key,
                    name_ko,name_en,description_ko,description_en,owner_group_ref,category_id,default_workflow_id,
                    source_template_version_id,lifecycle_state,current_version_number,workspace_version,created_by,updated_by)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',0,0,?,?)
                """, formId, access.actor().tenantId(), access.resourceSetKey(), input.formKey(), input.nameKo(),
                input.nameEn(), input.descriptionKo(), input.descriptionEn(), input.ownerGroupRef(),
                references.categoryId(), references.workflowId(), input.sourceTemplateVersionId(),
                access.actor().userId(), access.actor().userId());
        if (inserted != 1) throw conflict();
        insertVersion(access, formId, versionId, 1, schema, input.sourceTemplateVersionId(), null);
        advance(access, formId, 0, 1, "DRAFT");
        event(access, formId, versionId, "INSTALL_TEMPLATE", 1,
                Map.of("sourceTemplateVersionId", input.sourceTemplateVersionId().toString(),
                        "schemaSha256", schema.sha256()));
        receipt(access, "INSTALL_TEMPLATE", key, digest, formId, versionId);
        return new Mutation(workspace(access, formId, false), true);
    }

    public Mutation cloneDraft(Access access, Workspace source, CloneDraft input,
            ApprovalFormSchemaV3 schema, String key, String digest) {
        Prior prior = prior(access, "CLONE_DRAFT", key, digest);
        if (prior != null) return new Mutation(workspace(access, prior.formId(), false), false);
        if (source.workspaceVersion() != input.expectedSourceWorkspaceVersion()
                || !"DRAFT".equals(source.lifecycleState())) throw conflict();
        References references = references(access, input.categoryKey(), input.defaultWorkflowKey());
        UUID formId = UUID.randomUUID(), versionId = UUID.randomUUID();
        if (jdbc.update("""
                INSERT INTO apr_form_v3_workspaces(form_id,tenant_id,management_resource_set_key,form_key,
                    name_ko,name_en,description_ko,description_en,owner_group_ref,category_id,default_workflow_id,
                    source_template_version_id,source_form_id,lifecycle_state,current_version_number,workspace_version,
                    created_by,updated_by)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',0,0,?,?)
                """, formId, access.actor().tenantId(), access.resourceSetKey(), input.formKey(), input.nameKo(),
                input.nameEn(), input.descriptionKo(), input.descriptionEn(), input.ownerGroupRef(),
                references.categoryId(), references.workflowId(), source.sourceTemplateVersionId(), source.formId(),
                access.actor().userId(), access.actor().userId()) != 1) throw conflict();
        insertVersion(access, formId, versionId, 1, schema, source.sourceTemplateVersionId(),
                source.current().formVersionId());
        advance(access, formId, 0, 1, "DRAFT");
        event(access, formId, versionId, "CLONE_DRAFT", 1,
                Map.of("sourceFormId", source.formId().toString(), "sourceFormVersionId",
                        source.current().formVersionId().toString(), "schemaSha256", schema.sha256()));
        receipt(access, "CLONE_DRAFT", key, digest, formId, versionId);
        return new Mutation(workspace(access, formId, false), true);
    }

    public Mutation update(Access access, UUID formId, UpdateDraft input,
            ApprovalFormSchemaV3 schema, String key, String digest) {
        return update(access, formId, input, schema, "UPDATE_DRAFT", key, digest,
                Map.of());
    }

    public Mutation update(Access access, UUID formId, UpdateDraft input,
            ApprovalFormSchemaV3 schema, String command, String key, String digest,
            Map<String, Object> commandMaterial) {
        Prior prior = prior(access, command, key, digest);
        if (prior != null) return new Mutation(workspace(access, prior.formId(), false), false);
        Workspace current = workspace(access, formId, true);
        if (!"DRAFT".equals(current.lifecycleState()) || current.workspaceVersion() != input.expectedWorkspaceVersion()
                || current.current().schemaSha256().equals(schema.sha256())) throw conflict();
        UUID versionId = UUID.randomUUID();
        int next = Math.addExact(current.current().versionNumber(), 1);
        insertVersion(access, formId, versionId, next, schema, current.sourceTemplateVersionId(),
                current.current().formVersionId());
        advance(access, formId, current.workspaceVersion(), next, "DRAFT");
        Map<String, Object> material = new java.util.LinkedHashMap<>(commandMaterial);
        material.put("baseSchemaSha256", current.current().schemaSha256());
        material.put("schemaSha256", schema.sha256());
        event(access, formId, versionId, command, current.workspaceVersion() + 1, material);
        receipt(access, command, key, digest, formId, versionId);
        return new Mutation(workspace(access, formId, false), true);
    }

    public Mutation archive(Access access, UUID formId, ArchiveDraft input, String key, String digest) {
        Prior prior = prior(access, "ARCHIVE_DRAFT", key, digest);
        if (prior != null) return new Mutation(workspace(access, prior.formId(), false), false);
        Workspace current = workspace(access, formId, true);
        if (!"DRAFT".equals(current.lifecycleState()) || current.workspaceVersion() != input.expectedWorkspaceVersion()) {
            throw conflict();
        }
        advance(access, formId, current.workspaceVersion(), current.current().versionNumber(), "ARCHIVED");
        event(access, formId, current.current().formVersionId(), "ARCHIVE_DRAFT", current.workspaceVersion() + 1,
                Map.of("schemaSha256", current.current().schemaSha256()));
        receipt(access, "ARCHIVE_DRAFT", key, digest, formId, current.current().formVersionId());
        return new Mutation(workspace(access, formId, false), true);
    }

    private Workspace mapWorkspace(Access access, ResultSet row) throws SQLException {
        UUID formId = row.getObject("form_id", UUID.class);
        int current = row.getInt("current_version_number");
        if (current < 1) throw unavailable();
        Version version = version(access.actor().tenantId(), formId, current);
        return new Workspace(formId, row.getString("form_key"), row.getString("name_ko"), row.getString("name_en"),
                row.getString("description_ko"), row.getString("description_en"), row.getString("owner_group_ref"),
                row.getObject("category_id", UUID.class), row.getString("category_key"),
                row.getObject("default_workflow_id", UUID.class), row.getString("workflow_key"),
                row.getObject("source_template_version_id", UUID.class), row.getObject("source_form_id", UUID.class),
                row.getString("lifecycle_state"), row.getLong("workspace_version"), version,
                row.getObject("created_at", OffsetDateTime.class), row.getLong("created_by"),
                row.getObject("updated_at", OffsetDateTime.class), row.getLong("updated_by"));
    }

    private Version version(long tenantId, UUID formId, int number) {
        List<Version> values = jdbc.query("""
                SELECT * FROM apr_form_v3_versions
                 WHERE tenant_id=? AND form_id=? AND version_number=?
                """, (row, index) -> mapVersion(row), tenantId, formId, number);
        if (values.size() != 1) throw unavailable();
        return values.getFirst();
    }

    private Version mapVersion(ResultSet row) throws SQLException {
        Map<String, Object> schema = object(row.getString("schema_payload"));
        ApprovalFormSchemaV3 compiled = compiler.compile(schema);
        if (!compiled.sha256().equals(row.getString("schema_sha256"))
                || !compiled.compatibilityMode().equals(row.getString("compatibility_mode"))
                || !Objects.equals(compiled.baseSchemaSha256(), row.getString("base_schema_sha256"))) {
            throw unavailable();
        }
        return new Version(row.getObject("form_version_id", UUID.class), row.getInt("version_number"),
                compiled.definition(),
                compiled.sha256(), compiled.compatibilityMode(), compiled.baseSchemaSha256(),
                row.getObject("source_template_version_id", UUID.class),
                row.getObject("source_form_version_id", UUID.class), row.getObject("created_at", OffsetDateTime.class),
                row.getLong("created_by"));
    }

    private References references(Access access, String categoryKey, String workflowKey) {
        List<References> values = jdbc.query("""
                SELECT category.category_id, workflow.workflow_id
                  FROM apr_form_categories category
                  JOIN apr_workflow_definitions workflow
                    ON workflow.tenant_id=category.tenant_id
                   AND workflow.management_resource_set_key=category.management_resource_set_key
                 WHERE category.tenant_id=? AND category.management_resource_set_key=?
                   AND category.category_key=? AND category.lifecycle_state='ACTIVE'
                   AND workflow.workflow_key=? AND workflow.lifecycle_state='PUBLISHED'
                """, (row, number) -> new References(row.getObject(1, UUID.class), row.getObject(2, UUID.class)),
                access.actor().tenantId(), access.resourceSetKey(), categoryKey, workflowKey);
        if (values.size() != 1) throw conflict();
        return values.getFirst();
    }

    private void insertVersion(Access access, UUID formId, UUID versionId, int number,
            ApprovalFormSchemaV3 schema, UUID sourceTemplate, UUID sourceVersion) {
        if (jdbc.update("""
                INSERT INTO apr_form_v3_versions(form_version_id,tenant_id,form_id,version_number,schema_contract,
                    schema_payload,schema_sha256,compatibility_mode,base_schema_sha256,source_template_version_id,
                    source_form_version_id,created_by)
                VALUES(?,?,?,?,?,CAST(? AS jsonb),?,?,?,?,?,?)
                """, versionId, access.actor().tenantId(), formId, number, ApprovalFormSchemaV3.CONTRACT,
                schema.canonicalJson(), schema.sha256(), schema.compatibilityMode(), schema.baseSchemaSha256(),
                sourceTemplate, sourceVersion, access.actor().userId()) != 1) throw conflict();
    }

    private void advance(Access access, UUID formId, long expectedWorkspace, int version, String state) {
        int updated = jdbc.update("""
                UPDATE apr_form_v3_workspaces
                   SET current_version_number=?,workspace_version=workspace_version+1,lifecycle_state=?,
                       updated_at=clock_timestamp(),updated_by=?
                 WHERE tenant_id=? AND management_resource_set_key=? AND form_id=? AND workspace_version=?
                """, version, state, access.actor().userId(), access.actor().tenantId(), access.resourceSetKey(),
                formId, expectedWorkspace);
        if (updated != 1) throw conflict();
    }

    private void event(Access access, UUID formId, UUID versionId, String action,
            long workspaceVersion, Map<String, Object> material) {
        if (jdbc.update("""
                INSERT INTO apr_form_v3_lifecycle_events(event_id,tenant_id,form_id,form_version_id,
                    management_resource_set_key,actor_user_id,action,workspace_version,material)
                VALUES(?,?,?,?,?,?,?,?,CAST(? AS jsonb))
                """, UUID.randomUUID(), access.actor().tenantId(), formId, versionId, access.resourceSetKey(),
                access.actor().userId(), action, workspaceVersion, json(material)) != 1) throw conflict();
    }

    private Prior prior(Access access, String command, String key, String digest) {
        validateKey(key);
        String lock = access.actor().tenantId() + ":" + access.resourceSetKey() + ":"
                + access.actor().userId() + ":" + command + ":" + key;
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?,0))) locked",
                Integer.class, lock);
        List<Prior> values = jdbc.query("""
                SELECT request_sha256,result_form_id,result_form_version_id
                  FROM apr_form_v3_command_receipts
                 WHERE tenant_id=? AND management_resource_set_key=? AND actor_user_id=?
                   AND command_kind=? AND idempotency_key=?
                """, (row, number) -> {
                    if (!digest.equals(row.getString(1))) throw conflict();
                    return new Prior(row.getObject(2, UUID.class), row.getObject(3, UUID.class));
                }, access.actor().tenantId(), access.resourceSetKey(), access.actor().userId(), command, key);
        return values.isEmpty() ? null : values.getFirst();
    }

    private void receipt(Access access, String command, String key, String digest, UUID formId, UUID versionId) {
        if (jdbc.update("""
                INSERT INTO apr_form_v3_command_receipts(tenant_id,management_resource_set_key,actor_user_id,
                    command_kind,idempotency_key,request_sha256,result_form_id,result_form_version_id)
                VALUES(?,?,?,?,?,?,?,?)
                """, access.actor().tenantId(), access.resourceSetKey(), access.actor().userId(), command,
                key, digest, formId, versionId) != 1) throw conflict();
    }

    private void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,200}")) {
            throw invalid("An exact idempotency key is required.");
        }
    }

    private Map<String, Object> object(String json) {
        try { return mapper.readValue(json, new TypeReference<>() { }); }
        catch (Exception exception) { throw unavailable(); }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { throw invalid("Command material is not valid JSON."); }
    }

    private void validate(WorkspaceFilter filter) {
        if (filter == null || filter.limit() < 1 || filter.limit() > 100
                || filter.query() != null && (!text(filter.query(), 200) || filter.query().isBlank())
                || filter.lifecycleState() != null && !java.util.Set.of("DRAFT", "ARCHIVED").contains(filter.lifecycleState())
                || filter.afterFormKey() != null && !filter.afterFormKey().matches("[A-Z][A-Z0-9_]{1,99}")) {
            throw invalid("Form V3 workspace filter is invalid.");
        }
    }

    private boolean text(String value, int max) {
        return value.length() <= max && value.equals(value.strip())
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private BaseException invalid(String message) { return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message); }
    private BaseException notFound() { return new BaseException(ErrorCode.NOT_FOUND); }
    private BaseException conflict() { return new BaseException(ErrorCode.RESOURCE_CONFLICT, "Form V3 workspace changed or conflicts with its scope."); }
    private BaseException unavailable() { return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
            "Immutable Form V3 material cannot be verified."); }
    private record References(UUID categoryId, UUID workflowId) { }
    private record Prior(UUID formId, UUID formVersionId) { }
    public record Mutation(Workspace workspace, boolean committed) { }
}
