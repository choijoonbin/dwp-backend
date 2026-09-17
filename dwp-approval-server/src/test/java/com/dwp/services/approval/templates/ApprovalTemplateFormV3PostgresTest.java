package com.dwp.services.approval.formsv3;

import static com.dwp.services.approval.formsv3.ApprovalFormV3TestFixtures.*;
import static com.dwp.services.approval.templates.ApprovalTemplateModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalQueryRepository;
import com.dwp.services.approval.formsv3.*;
import com.dwp.services.approval.formsv3.ApprovalFormV3Models.UpdateDraft;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.ApprovalFormManagementScopeTestSupport;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.templates.ApprovalTemplateAuthority;
import com.dwp.services.approval.templates.ApprovalTemplateRepository;
import com.dwp.services.approval.templates.ApprovalTemplateService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ApprovalTemplateFormV3PostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withLabel("dwp.approval.owner", "apr17-18-v42-v43");
    private static final Set<String> PERMISSIONS = Set.of("ADMIN.APPROVAL_DESIGN:VIEW",
            "ADMIN.APPROVAL_DESIGN:CREATE", "ADMIN.APPROVAL_DESIGN:UPDATE");

    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private ObjectMapper mapper;
    private ApprovalTemplateService templates;
    private ApprovalFormV3Service forms;

    @BeforeEach
    void setUp() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl()); source.setUser(POSTGRES.getUsername()); source.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP SCHEMA IF EXISTS apr_retention_internal CASCADE");
        jdbc.execute("DROP SCHEMA IF EXISTS apr_signature_native CASCADE");
        Flyway flyway = Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .cleanDisabled(false).load();
        flyway.clean(); flyway.migrate();
        mapper = new ObjectMapper().findAndRegisterModules();
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(source);
        ApprovalQueryRepository tenantSetup = new ApprovalQueryRepository(named, mapper);
        tenantSetup.ensureTenant(42);
        tenantSetup.ensureTenant(43);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        ApprovalIdentityDirectory identities = mock(ApprovalIdentityDirectory.class);
        when(identities.require(anyLong(), anyLong())).thenAnswer(call -> subject(call.getArgument(0), call.getArgument(1)));
        AuditOutboxRecorder audit = new AuditOutboxRecorder(named, mapper, "dwp-approval-server", "test", "test");
        ApprovalFormV3Repository formRepository = new ApprovalFormV3Repository(jdbc, mapper);
        forms = new ApprovalFormV3Service(new ApprovalFormV3Authority(identities), formRepository, audit);
        ApprovalTemplateRepository templateRepository = new ApprovalTemplateRepository(jdbc, mapper);
        templates = new ApprovalTemplateService(new ApprovalTemplateAuthority(identities), templateRepository,
                forms, audit, mapper);
        context(42, 99, "RS_APPROVALS");
    }

    @AfterEach
    void clear() {
        ApprovalRequestContext.clear();
        ApprovalFormManagementScopeTestSupport.clear();
    }

    @Test
    void migrationsSeedSeventeenBilingualImmutableStarterTemplatesWithExactDependencies() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_form_templates WHERE scope_kind='GLOBAL'", Integer.class))
                .isEqualTo(17);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_form_template_versions WHERE lifecycle_state='RELEASED'", Integer.class))
                .isEqualTo(17);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_form_template_dependencies", Integer.class)).isEqualTo(34);
        ApprovalFormSchemaV3Compiler compiler = new ApprovalFormSchemaV3Compiler();
        List<String> schemas = jdbc.queryForList("SELECT schema_payload::text FROM apr_form_template_versions ORDER BY template_version_id", String.class);
        assertThat(schemas).hasSize(17).allSatisfy(raw -> {
            ApprovalFormSchemaV3 schema = compiler.compile(object(raw));
            assertThat(schema.definition().get("locales")).isEqualTo(List.of("ko", "en"));
        });
        UUID released = jdbc.queryForObject("SELECT template_version_id FROM apr_form_template_versions LIMIT 1", UUID.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE apr_form_template_versions SET name_en='Tampered' WHERE template_version_id=?", released))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM apr_form_template_dependencies WHERE template_version_id=?", released))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    @Test
    void catalogFiltersGlobalStartersAndTenantCloneIsDraftIdempotentAndTenantIsolated() {
        CatalogPage finance = templates.catalog(new CatalogFilter(null, "FINANCE", null, "ko",
                Set.of("finance"), "GLOBAL", null, 20));
        assertThat(finance.items()).extracting(Template::templateKey)
                .containsExactly("CAPEX_PURCHASE", "EXPENSE_REIMBURSEMENT");
        Template source = finance.items().getFirst();
        CloneTemplate clone = new CloneTemplate("TENANT_CAPEX", "FINANCE_CONTROLLERS", "FINANCE",
                "CAPEX_PURCHASE", "테넌트 설비 투자", "Tenant capital purchase", "테넌트 전용", "Tenant-specific",
                source.version());
        Template first = transaction.execute(ignored -> templates.cloneTemplate(source.templateId(), clone, "clone-1", "corr"));
        Template replay = transaction.execute(ignored -> templates.cloneTemplate(source.templateId(), clone, "clone-1", "corr"));
        assertThat(first).isEqualTo(replay);
        UUID alternateGlobalSource = finance.items().get(1).templateId();
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE apr_form_templates SET source_template_id=? WHERE template_id=?",
                alternateGlobalSource, first.templateId()))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        jdbc.update("UPDATE apr_form_templates SET lifecycle_state='RETIRED' WHERE template_id=?", source.templateId());
        Template replayAfterSourceChange = transaction.execute(
                ignored -> templates.cloneTemplate(source.templateId(), clone, "clone-1", "corr"));
        assertThat(replayAfterSourceChange).isEqualTo(first);
        jdbc.update("UPDATE apr_form_templates SET lifecycle_state='ACTIVE' WHERE template_id=?", source.templateId());
        assertThat(first.scopeKind()).isEqualTo("TENANT");
        assertThat(first.lifecycleState()).isEqualTo("DRAFT");
        assertThat(first.current().lifecycleState()).isEqualTo("DRAFT");
        assertThat(templates.catalog(new CatalogFilter("TENANT_CAPEX", null, null, null,
                Set.of(), "TENANT", null, 10)).items()).extracting(Template::lifecycleState).containsExactly("DRAFT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_form_template_command_receipts", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO apr_form_template_command_receipts(tenant_id,management_resource_set_key,
                    actor_user_id,command_kind,idempotency_key,request_sha256,result_template_id)
                VALUES(42,'RS_OTHER',99,'CLONE_TEMPLATE','wrong-scope',?,?)
                """, "a".repeat(64), first.templateId())).hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> transaction.execute(ignored -> templates.cloneTemplate(source.templateId(),
                new CloneTemplate("STALE_CAPEX", "FINANCE_CONTROLLERS", "FINANCE", "CAPEX_PURCHASE",
                        "만료된 복제", "Stale clone", "", "", source.version() + 1), "clone-stale", "corr")))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> transaction.execute(ignored -> templates.cloneTemplate(source.templateId(),
                new CloneTemplate("TENANT_CAPEX_CHANGED", "FINANCE_CONTROLLERS", "FINANCE", "CAPEX_PURCHASE",
                        "변경", "Changed", "", "", source.version()), "clone-1", "corr")))
                .isInstanceOf(BaseException.class);

        context(43, 99, "RS_APPROVALS");
        CatalogPage otherTenant = templates.catalog(new CatalogFilter(null, null, null, null,
                Set.of(), null, null, 100));
        assertThat(otherTenant.items()).extracting(Template::templateKey).doesNotContain("TENANT_CAPEX");
        Template tenant43 = transaction.execute(ignored -> templates.cloneTemplate(source.templateId(),
                new CloneTemplate("TENANT_43_CAPEX", "FINANCE_CONTROLLERS", "FINANCE", "CAPEX_PURCHASE",
                        "43번 설비 투자", "Tenant 43 capital purchase", "", "", source.version()),
                "clone-43", "corr"));
        context(42, 99, "RS_APPROVALS");
        assertThatThrownBy(() -> jdbc.update("UPDATE apr_form_templates SET source_template_id=? WHERE template_id=?",
                tenant43.templateId(), first.templateId())).hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    @Test
    void installCreatesOnlyDraftV3WorkspaceAndUpdateIsAppendOnlyVersionFencedAndIdempotent() {
        Template source = templates.catalog(new CatalogFilter("CAPEX_PURCHASE", null, null, null,
                Set.of(), "GLOBAL", null, 10)).items().getFirst();
        InstallTemplate install = new InstallTemplate("TEAM_CAPEX_FORM", "팀 설비 투자", "Team capital purchase",
                "팀 설비 투자 초안", "Team capital purchase draft", "FINANCE_CONTROLLERS", "FINANCE", "CAPEX_PURCHASE",
                source.version());
        Installation first = transaction.execute(ignored -> templates.install(source.current().templateVersionId(),
                install, "install-1", "corr"));
        Installation replay = transaction.execute(ignored -> templates.install(source.current().templateVersionId(),
                install, "install-1", "corr"));
        assertThat(first.draft().formId()).isEqualTo(replay.draft().formId());
        assertThat(first.draft().lifecycleState()).isEqualTo("DRAFT");
        assertThat(first.draft().workspaceVersion()).isEqualTo(1);
        assertThat(forms.validateSchema(first.draft().current().schema()).scenariosPassed()).isTrue();
        assertThat(forms.review(first.draft().formId()).blockers()).isEmpty();
        assertThat(forms.workspaces(new ApprovalFormV3Models.WorkspaceFilter("TEAM_CAPEX", "DRAFT", null, 10)).items())
                .singleElement().satisfies(item -> {
                    assertThat(item.formId()).isEqualTo(first.draft().formId());
                    assertThat(item.schemaSha256()).isEqualTo(first.draft().current().schemaSha256());
                });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_forms WHERE tenant_id=42 AND form_key='TEAM_CAPEX_FORM'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_form_v3_lifecycle_events WHERE action='INSTALL_TEMPLATE'", Integer.class))
                .isEqualTo(1);
        UUID alternateTemplateVersion = jdbc.queryForObject("""
                SELECT template_version_id FROM apr_form_template_versions
                 WHERE template_version_id <> ? AND lifecycle_state = 'RELEASED'
                 ORDER BY template_version_id LIMIT 1
                """, UUID.class, first.draft().sourceTemplateVersionId());
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE apr_form_v3_workspaces SET source_template_version_id = ?
                 WHERE tenant_id = 42 AND form_id = ?
                """, alternateTemplateVersion, first.draft().formId()))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        Installation alternate = transaction.execute(ignored -> templates.install(
                source.current().templateVersionId(),
                new InstallTemplate("TEAM_CAPEX_FORM_ALT", "대체 팀 설비 투자", "Alternate team capital purchase",
                        "대체 초안", "Alternate draft", "FINANCE_CONTROLLERS", "FINANCE", "CAPEX_PURCHASE",
                        source.version()), "install-provenance-alt", "corr"));
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE apr_form_v3_workspaces SET source_form_id = ?
                 WHERE tenant_id = 42 AND form_id = ?
                """, alternate.draft().formId(), first.draft().formId()))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO apr_form_v3_lifecycle_events(event_id,tenant_id,form_id,form_version_id,
                    management_resource_set_key,actor_user_id,action,workspace_version,material)
                VALUES(?,42,?,?,'RS_OTHER',99,'UPDATE_DRAFT',2,'{}')
                """, UUID.randomUUID(), first.draft().formId(), first.draft().current().formVersionId()))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE apr_form_v3_workspaces SET current_version_number=99
                 WHERE tenant_id=42 AND form_id=?
                """, first.draft().formId())).hasRootCauseInstanceOf(java.sql.SQLException.class);

        Map<String, Object> candidate = copy(first.draft().current().schema());
        candidate.put("compatibility", map("mode", "BACKWARD_COMPATIBLE", "minimumRuntime", "3.0",
                "baseSchemaSha256", first.draft().current().schemaSha256()));
        fields(candidate).add(ApprovalFormV3TestFixtures.field("businessJustification", "TEXTAREA", "TEXTAREA", false));
        UpdateDraft update = new UpdateDraft(first.draft().workspaceVersion(), candidate);
        var updated = transaction.execute(ignored -> forms.update(first.draft().formId(), update, "update-1", "corr"));
        var updateReplay = transaction.execute(ignored -> forms.update(first.draft().formId(), update, "update-1", "corr"));
        assertThat(updated).isEqualTo(updateReplay);
        assertThat(updated.workspaceVersion()).isEqualTo(2);
        assertThat(updated.current().versionNumber()).isEqualTo(2);
        assertThat(forms.history(updated.formId(), 10).versions()).hasSize(2);
        assertThatThrownBy(() -> transaction.execute(ignored -> forms.update(updated.formId(),
                new UpdateDraft(1, candidate), "stale-update", "corr"))).isInstanceOf(BaseException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_form_v3_versions WHERE form_id=?", Integer.class, updated.formId()))
                .isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update("UPDATE apr_form_v3_versions SET schema_sha256=? WHERE form_version_id=?",
                "f".repeat(64), updated.current().formVersionId())).hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_audit_outbox WHERE payload->>'targetId'=?", Integer.class,
                updated.formId().toString())).isEqualTo(2);
    }

    @Test
    void packageImportAndStructuredEditorPersistImmutableReplayableEvidence() {
        Template source = templates.catalog(new CatalogFilter("CAPEX_PURCHASE", null, null, null,
                Set.of(), "GLOBAL", null, 10)).items().getFirst();
        UUID packageId = UUID.randomUUID();
        ImportPackage unsigned = new ImportPackage(packageId, "2026.09.1", "0".repeat(64),
                "IMPORTED_CAPEX", "FINANCE_CONTROLLERS", source.categoryKey(),
                source.defaultWorkflowKey(), "가져온 설비 투자", "Imported capital purchase",
                "검증된 패키지", "Verified package", source.current().schema(), Set.of("ko", "en"),
                Set.of("capex", "finance"), source.current().filterMetadata(),
                source.current().dependencies(), "초기 가져오기", "Initial import", 0);
        ImportPackage input = packageWithHash(unsigned, packageHash(unsigned));

        PackageImport imported = transaction.execute(
                ignored -> templates.importPackage(input, "package-import", "corr"));
        PackageImport replay = transaction.execute(
                ignored -> templates.importPackage(input, "package-import", "corr"));
        assertThat(imported.imported()).isTrue();
        assertThat(replay.imported()).isFalse();
        assertThat(replay.template().templateId()).isEqualTo(imported.template().templateId());
        assertThat(imported.template().lifecycleState()).isEqualTo("DRAFT");
        assertThat(templates.preview(imported.template().current().templateVersionId(), "MOBILE"))
                .satisfies(preview -> {
                    assertThat(preview.templateId()).isEqualTo(imported.template().templateId());
                    assertThat(preview.fieldCount()).isPositive();
                    assertThat(preview.viewport()).isEqualTo("MOBILE");
                });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_form_template_package_imports", Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE apr_form_template_package_imports SET package_version='tampered'
                 WHERE package_id=?
                """, packageId)).hasRootCauseInstanceOf(java.sql.SQLException.class);

        Installation installation = transaction.execute(ignored -> templates.install(
                source.current().templateVersionId(),
                new InstallTemplate("EDITOR_CAPEX", "편집 설비 투자", "Editable capital purchase",
                        "구조화 편집 회귀", "Structured editor regression", "FINANCE_CONTROLLERS",
                        source.categoryKey(), source.defaultWorkflowKey(), source.version()),
                "editor-install", "corr"));
        var workspace = installation.draft();
        String pageKey = (String) ((Map<?, ?>) ((List<?>) workspace.current().schema()
                .get("pages")).getFirst()).get("key");
        Map<?, ?> firstSection = (Map<?, ?>) ((List<?>) ((Map<?, ?>) ((List<?>) workspace.current()
                .schema().get("pages")).getFirst()).get("sections")).getFirst();
        String sectionKey = (String) firstSection.get("key");
        int initialFieldCount = ((List<?>) firstSection.get("fields")).size();

        var added = transaction.execute(ignored -> forms.addField(workspace.formId(),
                new ApprovalFormV3Models.AddField(workspace.workspaceVersion(), pageKey, sectionKey,
                        initialFieldCount, ApprovalFormV3TestFixtures.field(
                                "businessJustification", "TEXTAREA", "TEXTAREA", false),
                        "BACKWARD_COMPATIBLE"), "field-add", "corr"));
        var addReplay = transaction.execute(ignored -> forms.addField(workspace.formId(),
                new ApprovalFormV3Models.AddField(workspace.workspaceVersion(), pageKey, sectionKey,
                        initialFieldCount, ApprovalFormV3TestFixtures.field(
                                "businessJustification", "TEXTAREA", "TEXTAREA", false),
                        "BACKWARD_COMPATIBLE"), "field-add", "corr"));
        assertThat(addReplay).isEqualTo(added);

        var cloned = transaction.execute(ignored -> forms.cloneField(workspace.formId(),
                new ApprovalFormV3Models.CloneField(added.workspaceVersion(), "businessJustification",
                        "businessJustificationCopy", initialFieldCount + 1, "BACKWARD_COMPATIBLE"),
                "field-clone", "corr"));
        var properties = transaction.execute(ignored -> forms.updateFieldProperties(workspace.formId(),
                new ApprovalFormV3Models.UpdateFieldProperties(cloned.workspaceVersion(),
                        "businessJustificationCopy", Map.of("help",
                                ApprovalFormV3TestFixtures.localized("추가 설명", "Additional guidance")),
                        "BACKWARD_COMPATIBLE"), "field-properties", "corr"));
        List<String> fieldOrder = fields(properties.current().schema()).stream()
                .map(field -> (String) field.get("key")).toList().reversed();
        var reordered = transaction.execute(ignored -> forms.reorderFields(workspace.formId(),
                new ApprovalFormV3Models.ReorderFields(properties.workspaceVersion(), pageKey, sectionKey,
                        fieldOrder), "field-reorder", "corr"));
        var deleted = transaction.execute(ignored -> forms.deleteField(workspace.formId(),
                new ApprovalFormV3Models.DeleteField(reordered.workspaceVersion(),
                        "businessJustificationCopy", "BREAKING"), "field-delete", "corr"));

        assertThat(deleted.workspaceVersion()).isEqualTo(workspace.workspaceVersion() + 5);
        assertThat(forms.diff(workspace.formId(), 1, deleted.current().versionNumber()))
                .satisfies(diff -> {
                    assertThat(diff.addedFields()).containsExactly("businessJustification");
                    assertThat(diff.removedFields()).isEmpty();
                });
        assertThat(jdbc.queryForList("""
                SELECT action FROM apr_form_v3_lifecycle_events
                 WHERE form_id=? ORDER BY workspace_version
                """, String.class, workspace.formId())).containsSubsequence(
                        "ADD_FIELD", "CLONE_FIELD", "UPDATE_FIELD_PROPERTIES",
                        "REORDER_FIELDS", "DELETE_FIELD");
        assertThatThrownBy(() -> transaction.execute(ignored -> forms.deleteField(workspace.formId(),
                new ApprovalFormV3Models.DeleteField(added.workspaceVersion(),
                        "businessJustification", "BREAKING"), "field-delete-stale", "corr")))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void managementScopeAndDatabaseSecretFencesFailClosed() {
        Template source = templates.catalog(new CatalogFilter("ACCESS_EXCEPTION", null, null, null,
                Set.of(), "GLOBAL", null, 10)).items().getFirst();
        context(42, 99, "RS_UNPROVISIONED");
        InstallTemplate install = new InstallTemplate("SCOPE_ESCAPE", "범위 이탈", "Scope escape", "", "",
                "SECURITY_GOVERNANCE", "ACCESS", "ACCESS_EXCEPTION", source.version());
        assertThatThrownBy(() -> transaction.execute(ignored -> templates.install(source.current().templateVersionId(),
                install, "scope-escape", "corr"))).isInstanceOf(BaseException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM apr_form_v3_workspaces WHERE form_key='SCOPE_ESCAPE'", Integer.class)).isZero();

        context(42, 99, "RS_APPROVALS");
        Installation valid = transaction.execute(ignored -> templates.install(source.current().templateVersionId(),
                new InstallTemplate("SECRET_FENCE", "비밀 차단", "Secret fence", "", "", "SECURITY_GOVERNANCE",
                        "ACCESS", "ACCESS_EXCEPTION", source.version()), "secret-fence", "corr"));
        Map<String, Object> payload = copy(valid.draft().current().schema());
        field(payload, "summary").put("password", "plain-text");
        String raw = json(payload);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO apr_form_v3_versions(form_version_id,tenant_id,form_id,version_number,schema_contract,
                    schema_payload,schema_sha256,compatibility_mode,base_schema_sha256,source_form_version_id,created_by)
                VALUES(?,42,?,2,?,CAST(? AS jsonb),encode(sha256(convert_to(
                    approval_typed_form_canonical_json(CAST(? AS jsonb)),'UTF8')),'hex'),
                    'STRICT',?,?,99)
                """, UUID.randomUUID(), valid.draft().formId(), ApprovalFormSchemaV3.CONTRACT, raw, raw,
                valid.draft().current().schemaSha256(), valid.draft().current().formVersionId()))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    @Test
    void workspaceProvenanceRejectsCrossTenantTemplateAndSourceFormTampering() {
        Template global = templates.catalog(new CatalogFilter("CAPEX_PURCHASE", null, null, null,
                Set.of(), "GLOBAL", null, 10)).items().getFirst();
        Installation local = transaction.execute(ignored -> templates.install(
                global.current().templateVersionId(),
                new InstallTemplate("LOCAL_PROVENANCE", "로컬 출처", "Local provenance",
                        "정확 범위 검증", "Exact scope validation", "FINANCE_CONTROLLERS",
                        "FINANCE", "CAPEX_PURCHASE", global.version()),
                "local-provenance", "corr"));

        context(43, 99, "RS_APPROVALS");
        Template tenantOnly = transaction.execute(ignored -> templates.cloneTemplate(
                global.templateId(),
                new CloneTemplate("TENANT_43_ONLY", "FINANCE_CONTROLLERS", "FINANCE",
                        "CAPEX_PURCHASE", "43 전용", "Tenant 43 only", "", "",
                        global.version()),
                "tenant-43-only", "corr"));
        jdbc.update("""
                UPDATE apr_form_template_versions
                   SET lifecycle_state='RELEASED',released_at=clock_timestamp(),released_by=99
                 WHERE template_version_id=?
                """, tenantOnly.current().templateVersionId());
        jdbc.update("""
                UPDATE apr_form_templates SET lifecycle_state='ACTIVE'
                 WHERE template_id=?
                """, tenantOnly.templateId());
        Installation foreign = transaction.execute(ignored -> templates.install(
                global.current().templateVersionId(),
                new InstallTemplate("FOREIGN_PROVENANCE", "외부 출처", "Foreign provenance",
                        "교차 테넌트 검증", "Cross-tenant validation", "FINANCE_CONTROLLERS",
                        "FINANCE", "CAPEX_PURCHASE", global.version()),
                "foreign-provenance", "corr"));

        context(42, 99, "RS_APPROVALS");
        assertThatThrownBy(() -> insertWorkspaceWithSources(
                local.draft().formId(), "CROSS_TENANT_TEMPLATE",
                tenantOnly.current().templateVersionId(), null))
                .hasRootCauseInstanceOf(java.sql.SQLException.class)
                .hasStackTraceContaining("template source must be released and visible in the exact scope");
        assertThatThrownBy(() -> insertWorkspaceWithSources(
                local.draft().formId(), "CROSS_TENANT_FORM",
                local.draft().sourceTemplateVersionId(), foreign.draft().formId()))
                .hasRootCauseInstanceOf(java.sql.SQLException.class)
                .hasStackTraceContaining("fk_apr_form_v3_workspace_source_scope");
    }

    private ApprovalIdentityDirectory.Subject subject(long tenant, long user) {
        return new ApprovalIdentityDirectory.Subject(tenant, user, UUID.randomUUID(), UUID.randomUUID(),
                "Approvals Admin", "admin@example.com", "Administrator", "ACTIVE", List.of("TENANT_ADMIN"),
                new ArrayList<>(PERMISSIONS));
    }

    private void context(long tenant, long user, String scope) {
        ApprovalRequestContext.clear(); ApprovalFormManagementScopeTestSupport.clear();
        ApprovalRequestContext.set(user, tenant, UUID.randomUUID(), "Approvals Admin", Set.of("TENANT_ADMIN"), PERMISSIONS);
        ApprovalFormManagementScopeTestSupport.set("opaque-" + tenant + '-' + scope, scope);
    }

    private void insertWorkspaceWithSources(
            UUID baseFormId,
            String formKey,
            UUID sourceTemplateVersionId,
            UUID sourceFormId) {
        jdbc.update("""
                INSERT INTO apr_form_v3_workspaces(
                    form_id,tenant_id,management_resource_set_key,form_key,
                    name_ko,name_en,description_ko,description_en,owner_group_ref,
                    category_id,default_workflow_id,source_template_version_id,
                    source_form_id,lifecycle_state,current_version_number,
                    workspace_version,created_by,updated_by)
                SELECT ?,tenant_id,management_resource_set_key,?,name_ko,name_en,
                       description_ko,description_en,owner_group_ref,category_id,
                       default_workflow_id,?,?,'DRAFT',0,0,99,99
                  FROM apr_form_v3_workspaces
                 WHERE tenant_id=42 AND form_id=?
                """, UUID.randomUUID(), formKey, sourceTemplateVersionId,
                sourceFormId, baseFormId);
    }

    private Map<String, Object> object(String raw) {
        try { return mapper.readValue(raw, new TypeReference<>() { }); }
        catch (Exception exception) { throw new AssertionError(exception); }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { throw new AssertionError(exception); }
    }

    private ImportPackage packageWithHash(ImportPackage value, String hash) {
        return new ImportPackage(value.packageId(), value.packageVersion(), hash, value.templateKey(),
                value.ownerGroupRef(), value.categoryKey(), value.defaultWorkflowKey(), value.nameKo(),
                value.nameEn(), value.descriptionKo(), value.descriptionEn(), value.schema(), value.locales(),
                value.tags(), value.filterMetadata(), value.dependencies(), value.changeSummaryKo(),
                value.changeSummaryEn(), value.expectedTemplateVersion());
    }

    private String packageHash(ImportPackage value) {
        Map<String, Object> manifest = Map.ofEntries(
                Map.entry("packageId", value.packageId().toString()),
                Map.entry("packageVersion", value.packageVersion()),
                Map.entry("templateKey", value.templateKey()),
                Map.entry("ownerGroupRef", value.ownerGroupRef()),
                Map.entry("categoryKey", value.categoryKey()),
                Map.entry("defaultWorkflowKey", value.defaultWorkflowKey()),
                Map.entry("nameKo", value.nameKo()), Map.entry("nameEn", value.nameEn()),
                Map.entry("descriptionKo", value.descriptionKo()),
                Map.entry("descriptionEn", value.descriptionEn()),
                Map.entry("schema", value.schema()),
                Map.entry("locales", value.locales().stream().sorted().toList()),
                Map.entry("tags", value.tags().stream().sorted().toList()),
                Map.entry("filterMetadata", value.filterMetadata()),
                Map.entry("dependencies", value.dependencies()),
                Map.entry("changeSummaryKo", value.changeSummaryKo()),
                Map.entry("changeSummaryEn", value.changeSummaryEn()));
        try {
            byte[] canonical = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(manifest).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fields(Map<String, Object> schema) {
        Map<String, Object> page = (Map<String, Object>) ((List<?>) schema.get("pages")).getFirst();
        Map<String, Object> section = (Map<String, Object>) ((List<?>) page.get("sections")).getFirst();
        return (List<Map<String, Object>>) section.get("fields");
    }

    private Map<String, Object> field(Map<String, Object> schema, String key) {
        return fields(schema).stream().filter(field -> key.equals(field.get("key"))).findFirst().orElseThrow();
    }
}
