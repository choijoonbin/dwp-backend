package com.dwp.services.approval.templates;

import static com.dwp.services.approval.templates.ApprovalTemplateModels.*;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.formsv3.ApprovalFormSchemaV3;
import com.dwp.services.approval.formsv3.ApprovalFormSchemaV3Compiler;
import com.dwp.services.approval.templates.ApprovalTemplateAuthority.Access;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ApprovalTemplateRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ApprovalFormSchemaV3Compiler compiler = new ApprovalFormSchemaV3Compiler();

    public ApprovalTemplateRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper.copy();
    }

    public CatalogPage catalog(Access access, CatalogFilter filter) {
        validate(filter);
        StringBuilder sql = new StringBuilder("""
                WITH visible AS (
                    SELECT template.*,version.template_version_id,version.version_number,
                           version.name_ko,version.name_en,version.description_ko,version.description_en,
                           version.schema_payload,version.schema_sha256,
                           version.locales,version.tags,
                           version.filter_metadata,
                           version.change_summary_ko,version.change_summary_en,
                           version.lifecycle_state AS version_state,version.released_at,version.released_by,
                           CASE WHEN template.scope_kind='TENANT' THEN 0 ELSE 1 END AS scope_rank
                      FROM apr_form_templates template
                      JOIN apr_form_template_versions version
                        ON version.template_id=template.template_id
                       AND version.version_number=template.current_version
                     WHERE (template.scope_kind='GLOBAL' AND template.lifecycle_state='ACTIVE'
                            AND version.lifecycle_state='RELEASED')
                        OR (template.scope_kind='TENANT' AND template.tenant_id=?
                            AND template.management_resource_set_key=?
                            AND template.lifecycle_state IN ('ACTIVE','DRAFT')
                            AND version.lifecycle_state IN ('RELEASED','DRAFT'))
                ), effective AS (
                    SELECT DISTINCT ON(template_key) * FROM visible
                    ORDER BY template_key,scope_rank,template_id
                ) SELECT * FROM effective WHERE 1=1
                """);
        List<Object> args = new ArrayList<>();
        args.add(access.actor().tenantId()); args.add(access.resourceSetKey());
        if (filter.query() != null) {
            sql.append(" AND (template_key ILIKE ? ESCAPE '!' OR name_ko ILIKE ? ESCAPE '!' OR name_en ILIKE ? ESCAPE '!')");
            String query = '%' + escapeLike(filter.query()) + '%';
            args.add(query); args.add(query); args.add(query);
        }
        if (filter.categoryKey() != null) { sql.append(" AND category_key=?"); args.add(filter.categoryKey()); }
        if (filter.ownerGroupRef() != null) { sql.append(" AND owner_group_ref=?"); args.add(filter.ownerGroupRef()); }
        if (filter.locale() != null) { sql.append(" AND locales @> CAST(? AS jsonb)"); args.add(json(List.of(filter.locale()))); }
        if (!filter.tags().isEmpty()) { sql.append(" AND tags @> CAST(? AS jsonb)"); args.add(json(filter.tags().stream().sorted().toList())); }
        if (filter.scopeKind() != null) { sql.append(" AND scope_kind=?"); args.add(filter.scopeKind()); }
        if (filter.afterTemplateKey() != null) { sql.append(" AND template_key>?"); args.add(filter.afterTemplateKey()); }
        sql.append(" ORDER BY template_key,template_id LIMIT ?"); args.add(filter.limit() + 1);
        List<TemplateRow> rows = jdbc.query(sql.toString(), (row, number) -> mapRow(row), args.toArray());
        boolean more = rows.size() > filter.limit();
        List<TemplateRow> selected = rows.stream().limit(filter.limit()).toList();
        Map<UUID, List<Dependency>> dependencies = dependencies(selected.stream().map(row -> row.version.templateVersionId()).toList());
        List<Template> items = selected.stream().map(row -> row.template(dependencies.getOrDefault(
                row.version.templateVersionId(), List.of()))).toList();
        return new CatalogPage(items, more, items.isEmpty() ? null : items.getLast().templateKey());
    }

    public Template template(Access access, UUID templateId) {
        List<TemplateRow> rows = jdbc.query("""
                SELECT template.*,version.template_version_id,version.version_number,
                       version.name_ko,version.name_en,version.description_ko,version.description_en,
                       version.schema_payload::text AS schema_payload,version.schema_sha256,
                       version.locales::text AS locales,version.tags::text AS tags,
                       version.filter_metadata::text AS filter_metadata,
                       version.change_summary_ko,version.change_summary_en,
                       version.lifecycle_state AS version_state,version.released_at,version.released_by
                  FROM apr_form_templates template
                  JOIN apr_form_template_versions version
                    ON version.template_id=template.template_id AND version.version_number=template.current_version
                 WHERE template.template_id=?
                   AND (template.scope_kind='GLOBAL' OR (template.scope_kind='TENANT'
                        AND template.tenant_id=? AND template.management_resource_set_key=?))
                """, (row, number) -> mapRow(row), templateId, access.actor().tenantId(), access.resourceSetKey());
        if (rows.size() != 1) throw notFound();
        TemplateRow row = rows.getFirst();
        return row.template(dependencies(List.of(row.version.templateVersionId())).getOrDefault(
                row.version.templateVersionId(), List.of()));
    }

    public TemplateVersion version(Access access, UUID templateId, int versionNumber) {
        Template identity = template(access, templateId);
        List<TemplateVersion> values = jdbc.query("""
                SELECT version.*,version.lifecycle_state AS version_state FROM apr_form_template_versions version
                 WHERE version.template_id=? AND version.version_number=?
                """, (row, number) -> mapVersion(row, dependencies(List.of(
                        row.getObject("template_version_id", UUID.class))).getOrDefault(
                        row.getObject("template_version_id", UUID.class), List.of())), templateId, versionNumber);
        if (values.size() != 1 || !identity.templateId().equals(templateId)) throw notFound();
        return values.getFirst();
    }

    public TemplateVersion releasedVersion(Access access, UUID templateVersionId) {
        List<TemplateVersion> values = jdbc.query("""
                SELECT version.*,version.lifecycle_state AS version_state FROM apr_form_template_versions version
                  JOIN apr_form_templates template ON template.template_id=version.template_id
                 WHERE version.template_version_id=? AND version.lifecycle_state='RELEASED'
                   AND template.lifecycle_state='ACTIVE'
                   AND (template.scope_kind='GLOBAL' OR (template.scope_kind='TENANT'
                        AND template.tenant_id=? AND template.management_resource_set_key=?))
                """, (row, number) -> mapVersion(row, dependencies(List.of(templateVersionId)).getOrDefault(
                        templateVersionId, List.of())), templateVersionId, access.actor().tenantId(), access.resourceSetKey());
        if (values.size() != 1) throw notFound();
        return values.getFirst();
    }

    public TemplateVersion visibleVersion(Access access, UUID templateVersionId) {
        List<TemplateVersion> values = jdbc.query("""
                SELECT version.*,version.lifecycle_state AS version_state FROM apr_form_template_versions version
                  JOIN apr_form_templates template ON template.template_id=version.template_id
                 WHERE version.template_version_id=?
                   AND (template.scope_kind='GLOBAL' AND template.lifecycle_state='ACTIVE'
                        AND version.lifecycle_state='RELEASED'
                     OR template.scope_kind='TENANT' AND template.tenant_id=?
                        AND template.management_resource_set_key=?
                        AND template.lifecycle_state IN ('ACTIVE','DRAFT')
                        AND version.lifecycle_state IN ('RELEASED','DRAFT'))
                """, (row, number) -> mapVersion(row, dependencies(List.of(templateVersionId)).getOrDefault(
                        templateVersionId, List.of())), templateVersionId,
                access.actor().tenantId(), access.resourceSetKey());
        if (values.size() != 1) throw notFound();
        return values.getFirst();
    }

    public Template identityForVersion(Access access, UUID templateVersionId) {
        UUID templateId = jdbc.query("""
                SELECT template.template_id FROM apr_form_templates template
                  JOIN apr_form_template_versions version ON version.template_id=template.template_id
                 WHERE version.template_version_id=?
                   AND (template.scope_kind='GLOBAL' OR (template.scope_kind='TENANT'
                        AND template.tenant_id=? AND template.management_resource_set_key=?))
                """, row -> row.next() ? row.getObject(1, UUID.class) : null,
                templateVersionId, access.actor().tenantId(), access.resourceSetKey());
        if (templateId == null) throw notFound();
        return template(access, templateId);
    }

    public Mutation cloneTemplate(Access access, Template source, CloneTemplate input,
            String key, String digest) {
        UUID prior = prior(access, "CLONE_TEMPLATE", key, digest);
        if (prior != null) return new Mutation(template(access, prior), false);
        lockActiveReleased(access, source.templateId(), input.expectedTemplateVersion());
        if (source.version() != input.expectedTemplateVersion()) throw conflict();
        UUID templateId = UUID.randomUUID(), versionId = UUID.randomUUID();
        if (jdbc.update("""
                INSERT INTO apr_form_templates(template_id,scope_kind,tenant_id,management_resource_set_key,
                    template_key,owner_group_ref,category_key,default_workflow_key,lifecycle_state,current_version,
                    source_template_id,created_by,updated_by)
                VALUES(?,'TENANT',?,?,?,?,?,?, 'DRAFT',1,?,?,?)
                """, templateId, access.actor().tenantId(), access.resourceSetKey(), input.templateKey(),
                input.ownerGroupRef(), input.categoryKey(), input.defaultWorkflowKey(), source.templateId(),
                access.actor().userId(), access.actor().userId()) != 1) throw conflict();
        TemplateVersion version = source.current();
        if (jdbc.update("""
                INSERT INTO apr_form_template_versions(template_version_id,template_id,version_number,name_ko,name_en,
                    description_ko,description_en,schema_contract,schema_payload,schema_sha256,locales,tags,
                    filter_metadata,change_summary_ko,change_summary_en,lifecycle_state,created_by)
                VALUES(?,?,1,?,?,?,?,?,CAST(? AS jsonb),?,CAST(? AS jsonb),CAST(? AS jsonb),CAST(? AS jsonb),?,?,'DRAFT',?)
                """, versionId, templateId, input.nameKo(), input.nameEn(), input.descriptionKo(), input.descriptionEn(),
                ApprovalFormSchemaV3.CONTRACT, json(version.schema()), version.schemaSha256(), json(version.locales()),
                json(version.tags()), json(version.filterMetadata()), "원본 템플릿에서 복제", "Cloned from source template",
                access.actor().userId()) != 1) throw conflict();
        for (Dependency dependency : version.dependencies()) {
            jdbc.update("""
                    INSERT INTO apr_form_template_dependencies(template_id,template_version_id,dependency_kind,
                        dependency_key,version_constraint,required) VALUES(?,?,?,?,?,?)
                    """, templateId, versionId, dependency.kind(), dependency.key(),
                    dependency.versionConstraint(), dependency.required());
        }
        if (jdbc.update("""
                INSERT INTO apr_form_template_command_receipts(tenant_id,management_resource_set_key,actor_user_id,
                    command_kind,idempotency_key,request_sha256,result_template_id)
                VALUES(?,?,?,'CLONE_TEMPLATE',?,?,?)
                """, access.actor().tenantId(), access.resourceSetKey(), access.actor().userId(), key, digest,
                templateId) != 1) throw conflict();
        return new Mutation(template(access, templateId), true);
    }

    public Mutation replayClone(Access access, String key, String digest) {
        UUID prior = prior(access, "CLONE_TEMPLATE", key, digest);
        return prior == null ? null : new Mutation(template(access, prior), false);
    }

    public Mutation replay(Access access, String command, String key, String digest) {
        UUID prior = prior(access, command, key, digest);
        return prior == null ? null : new Mutation(template(access, prior), false);
    }

    public Mutation importPackage(Access access, ImportPackage input, ApprovalFormSchemaV3 schema,
            Map<String, Object> manifest, String key, String digest) {
        UUID prior = prior(access, "IMPORT_PACKAGE", key, digest);
        if (prior != null) return new Mutation(template(access, prior), false);
        UUID templateId = UUID.randomUUID(), versionId = UUID.randomUUID();
        if (jdbc.update("""
                INSERT INTO apr_form_templates(template_id,scope_kind,tenant_id,management_resource_set_key,
                    template_key,owner_group_ref,category_key,default_workflow_key,lifecycle_state,current_version,
                    source_template_id,version,created_by,updated_by)
                VALUES(?,'TENANT',?,?,?,?,?,?,'DRAFT',1,NULL,0,?,?)
                """, templateId, access.actor().tenantId(), access.resourceSetKey(), input.templateKey(),
                input.ownerGroupRef(), input.categoryKey(), input.defaultWorkflowKey(),
                access.actor().userId(), access.actor().userId()) != 1) throw conflict();
        if (jdbc.update("""
                INSERT INTO apr_form_template_versions(template_version_id,template_id,version_number,name_ko,name_en,
                    description_ko,description_en,schema_contract,schema_payload,schema_sha256,locales,tags,
                    filter_metadata,change_summary_ko,change_summary_en,lifecycle_state,created_by)
                VALUES(?,?,1,?,?,?,?,?,CAST(? AS jsonb),?,CAST(? AS jsonb),CAST(? AS jsonb),CAST(? AS jsonb),
                    ?,?,'DRAFT',?)
                """, versionId, templateId, input.nameKo(), input.nameEn(), input.descriptionKo(), input.descriptionEn(),
                ApprovalFormSchemaV3.CONTRACT, schema.canonicalJson(), schema.sha256(), json(input.locales()),
                json(input.tags()), json(input.filterMetadata()), input.changeSummaryKo(), input.changeSummaryEn(),
                access.actor().userId()) != 1) throw conflict();
        for (Dependency dependency : input.dependencies()) {
            if (jdbc.update("""
                    INSERT INTO apr_form_template_dependencies(template_id,template_version_id,dependency_kind,
                        dependency_key,version_constraint,required) VALUES(?,?,?,?,?,?)
                    """, templateId, versionId, dependency.kind(), dependency.key(),
                    dependency.versionConstraint(), dependency.required()) != 1) throw conflict();
        }
        if (jdbc.update("""
                INSERT INTO apr_form_template_package_imports(tenant_id,management_resource_set_key,package_id,
                    package_version,package_sha256,package_metadata,template_id,template_version_id,imported_by)
                VALUES(?,?,?,?,?,CAST(? AS jsonb),?,?,?)
                """, access.actor().tenantId(), access.resourceSetKey(), input.packageId(), input.packageVersion(),
                input.packageSha256(), json(manifest), templateId, versionId, access.actor().userId()) != 1) {
            throw conflict();
        }
        if (jdbc.update("""
                INSERT INTO apr_form_template_command_receipts(tenant_id,management_resource_set_key,actor_user_id,
                    command_kind,idempotency_key,request_sha256,result_template_id)
                VALUES(?,?,?,'IMPORT_PACKAGE',?,?,?)
                """, access.actor().tenantId(), access.resourceSetKey(), access.actor().userId(), key, digest,
                templateId) != 1) throw conflict();
        return new Mutation(template(access, templateId), true);
    }

    public void lockActiveReleased(Access access, UUID templateId, long expectedVersion) {
        List<Long> values = jdbc.query("""
                SELECT template.version FROM apr_form_templates template
                  JOIN apr_form_template_versions version
                    ON version.template_id=template.template_id
                   AND version.version_number=template.current_version
                 WHERE template.template_id=? AND template.lifecycle_state='ACTIVE'
                   AND version.lifecycle_state='RELEASED'
                   AND (template.scope_kind='GLOBAL' OR (template.scope_kind='TENANT'
                        AND template.tenant_id=? AND template.management_resource_set_key=?))
                 FOR UPDATE OF template
                """, (row, number) -> row.getLong(1), templateId,
                access.actor().tenantId(), access.resourceSetKey());
        if (values.size() != 1 || values.getFirst() != expectedVersion) throw conflict();
    }

    private UUID prior(Access access, String command, String key, String digest) {
        validateKey(key);
        String lock = access.actor().tenantId() + ":" + access.resourceSetKey() + ":"
                + access.actor().userId() + ":" + command + ":" + key;
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?,0))) locked",
                Integer.class, lock);
        List<UUID> values = jdbc.query("""
                SELECT request_sha256,result_template_id FROM apr_form_template_command_receipts
                 WHERE tenant_id=? AND management_resource_set_key=? AND actor_user_id=?
                   AND command_kind=? AND idempotency_key=?
                """, (row, number) -> {
                    if (!digest.equals(row.getString(1))) throw conflict();
                    return row.getObject(2, UUID.class);
                }, access.actor().tenantId(), access.resourceSetKey(), access.actor().userId(), command, key);
        return values.isEmpty() ? null : values.getFirst();
    }

    private Map<UUID, List<Dependency>> dependencies(List<UUID> versionIds) {
        if (versionIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(versionIds.size(), "?"));
        Map<UUID, List<Dependency>> result = new LinkedHashMap<>();
        jdbc.query("SELECT template_version_id,dependency_kind,dependency_key,version_constraint,required "
                        + "FROM apr_form_template_dependencies WHERE template_version_id IN (" + placeholders + ") "
                        + "ORDER BY dependency_kind,dependency_key",
                (org.springframework.jdbc.core.RowCallbackHandler) row -> result
                        .computeIfAbsent(row.getObject(1, UUID.class), ignored -> new ArrayList<>())
                        .add(new Dependency(row.getString(2), row.getString(3), row.getString(4), row.getBoolean(5))),
                versionIds.toArray());
        return result;
    }

    private TemplateRow mapRow(ResultSet row) throws SQLException {
        TemplateVersion version = mapVersion(row, List.of());
        return new TemplateRow(row.getObject("template_id", UUID.class), row.getString("scope_kind"),
                row.getObject("tenant_id", Long.class), row.getString("management_resource_set_key"),
                row.getString("template_key"), row.getString("owner_group_ref"), row.getString("category_key"),
                row.getString("default_workflow_key"), row.getString("lifecycle_state"), row.getInt("current_version"),
                row.getObject("source_template_id", UUID.class), row.getLong("version"), version);
    }

    private TemplateVersion mapVersion(ResultSet row, List<Dependency> dependencies) throws SQLException {
        Map<String, Object> schema = object(row.getString("schema_payload"));
        ApprovalFormSchemaV3 compiled = compiler.compile(schema);
        if (!compiled.sha256().equals(row.getString("schema_sha256"))) throw unavailable();
        return new TemplateVersion(row.getObject("template_version_id", UUID.class), row.getInt("version_number"),
                row.getString("name_ko"), row.getString("name_en"), row.getString("description_ko"),
                row.getString("description_en"), compiled.definition(), compiled.sha256(),
                strings(row.getString("locales")),
                strings(row.getString("tags")), object(row.getString("filter_metadata")),
                row.getString("change_summary_ko"), row.getString("change_summary_en"),
                row.getString("version_state") == null ? row.getString("lifecycle_state") : row.getString("version_state"),
                row.getObject("released_at", OffsetDateTime.class), row.getObject("released_by", Long.class), dependencies);
    }

    private void validate(CatalogFilter filter) {
        if (filter == null || filter.limit() < 1 || filter.limit() > 100
                || filter.query() != null && !text(filter.query(), 200, false)
                || filter.categoryKey() != null && !code(filter.categoryKey(), 100)
                || filter.ownerGroupRef() != null && !reference(filter.ownerGroupRef(), 160)
                || filter.locale() != null && !Set.of("ko", "en").contains(filter.locale())
                || filter.scopeKind() != null && !Set.of("GLOBAL", "TENANT").contains(filter.scopeKind())
                || filter.afterTemplateKey() != null && !code(filter.afterTemplateKey(), 100)
                || filter.tags().size() > 20 || filter.tags().stream().anyMatch(tag -> !tag.matches("[a-z0-9][a-z0-9-]{0,39}"))) {
            throw invalid("Template catalog filter is invalid.");
        }
    }

    private String escapeLike(String value) { return value.replace("!", "!!").replace("%", "!%").replace("_", "!_"); }
    private boolean code(String value, int max) { return value != null && value.length() <= max && value.matches("[A-Z][A-Z0-9_]{1," + (max - 1) + "}"); }
    private boolean reference(String value, int max) { return value != null && value.length() <= max && value.matches("[A-Z][A-Z0-9_.:-]{1," + (max - 1) + "}"); }
    private boolean text(String value, int max, boolean blank) { return value != null && value.length() <= max && value.equals(value.strip())
            && (blank || !value.isBlank()) && value.codePoints().noneMatch(Character::isISOControl); }
    private void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,200}")) {
            throw invalid("An exact idempotency key is required.");
        }
    }

    private Map<String, Object> object(String value) {
        try { return mapper.readValue(value, new TypeReference<>() { }); }
        catch (Exception exception) { throw unavailable(); }
    }
    private Set<String> strings(String value) {
        try { return Set.copyOf(mapper.readValue(value, new TypeReference<List<String>>() { })); }
        catch (Exception exception) { throw unavailable(); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { throw invalid("Template material is not valid JSON."); }
    }

    private BaseException invalid(String message) { return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message); }
    private BaseException notFound() { return new BaseException(ErrorCode.NOT_FOUND); }
    private BaseException conflict() { return new BaseException(ErrorCode.RESOURCE_CONFLICT, "Template command conflicts with its immutable source."); }
    private BaseException unavailable() { return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
            "Immutable template material cannot be verified."); }

    private record TemplateRow(UUID templateId, String scopeKind, Long tenantId, String scope,
            String key, String owner, String category, String workflow, String state, int current,
            UUID source, long revision, TemplateVersion version) {
        Template template(List<Dependency> dependencies) {
            TemplateVersion material = new TemplateVersion(version.templateVersionId(), version.versionNumber(),
                    version.nameKo(), version.nameEn(), version.descriptionKo(), version.descriptionEn(),
                    version.schema(), version.schemaSha256(), version.locales(), version.tags(),
                    version.filterMetadata(), version.changeSummaryKo(), version.changeSummaryEn(),
                    version.lifecycleState(), version.releasedAt(), version.releasedBy(), dependencies);
            return new Template(templateId, scopeKind, tenantId, scope, key, owner, category, workflow,
                    state, current, source, revision, material);
        }
    }
    public record Mutation(Template template, boolean committed) { }
}
