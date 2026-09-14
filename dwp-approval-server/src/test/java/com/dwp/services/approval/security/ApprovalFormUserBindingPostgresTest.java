package com.dwp.services.approval.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalDtos;
import com.dwp.services.approval.domain.ApprovalFormSchemaV2Compiler;
import com.dwp.services.approval.domain.ApprovalFormUserBindingRepository;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory.FormBinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalFormUserBindingPostgresTest extends ApprovalDraftPostgresFixture {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private ApprovalFormUserBindingRepository forms;
    private FormBinding binding;
    private Map<String, Object> schema;

    @BeforeEach void setUp() {
        initialize(POSTGRES);
        forms = new ApprovalFormUserBindingRepository(new NamedParameterJdbcTemplate(jdbc.getDataSource()), new ObjectMapper());
        schema = Map.of("schemaContract", "DWP_APPROVAL_FORM_TYPED_V2", "schemaVersion", 2, "fields",
                List.of(field("summary", "TEXTAREA"), field("reviewer", "USER")));
        scope("RS_APPROVALS");
        UUID category = jdbc.queryForObject("SELECT category_id FROM apr_forms WHERE form_id=?", UUID.class, formId);
        formId = tx(() -> commands.createFormDraft(ApprovalRequestContext.require(),
                new ApprovalDtos.CreateFormDraftRequest("SOURCE_USER_FORM", category, "사용자 양식", "User form", "설명", "Description",
                        "APPROVAL_OPERATOR", workflowId, null, schema)));
        UUID version = jdbc.queryForObject("SELECT form_version_id FROM apr_form_versions WHERE form_id=?", UUID.class, formId);
        binding = new FormBinding(42, 99, formId, version, new ApprovalFormSchemaV2Compiler().compile(schema).sha256());
    }
    @AfterEach void tearDown() { clear(); }

    @Test void currentPublishedTypedVersionIsReadWithImmutableExactHash() {
        publish();
        assertThat(forms.requirePublished(binding, false).sha256()).isEqualTo(binding.schemaSha256());
        scope("RS_APPROVALS");
        assertThat(forms.requirePublished(binding, true).canonicalJson()).isEqualTo(new ApprovalFormSchemaV2Compiler().compile(schema).canonicalJson());
    }

    @Test void unpublishedOrWrongTenantVersionAndHashNeverReturnSchema() {
        assertConflict(() -> forms.requirePublished(binding, false));
        publish();
        assertConflict(() -> forms.requirePublished(new FormBinding(900, 99, formId, binding.formVersionId(), binding.schemaSha256()), false));
        assertConflict(() -> forms.requirePublished(new FormBinding(42, 99, formId, UUID.randomUUID(), binding.schemaSha256()), false));
        assertConflict(() -> forms.requirePublished(new FormBinding(42, 99, formId, binding.formVersionId(), "b".repeat(64)), false));
    }

    @Test void revokedCategoryOrExpiredBindingFailsCurrentPublishedPrerequisites() {
        publish();
        UUID category = jdbc.queryForObject("SELECT category_id FROM apr_forms WHERE form_id=?", UUID.class, formId);
        jdbc.update("UPDATE apr_form_categories SET lifecycle_state='INACTIVE' WHERE category_id=?", category);
        assertConflict(() -> forms.requirePublished(binding, false));
        jdbc.update("UPDATE apr_form_categories SET lifecycle_state='ACTIVE' WHERE category_id=?", category);
        jdbc.update("UPDATE apr_form_workflow_bindings SET effective_to=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE form_id=?", formId);
        assertConflict(() -> forms.requirePublished(binding, false));
    }

    @Test void adminCrossManagementScopeAndRetiredFormAreUnavailable() {
        publish();
        scope("RS_OTHER");
        assertConflict(() -> forms.requirePublished(binding, true));
        jdbc.update("UPDATE apr_forms SET lifecycle_state='RETIRED' WHERE form_id=?", formId);
        assertConflict(() -> forms.requirePublished(binding, false));
    }

    @Test void legacyUnmarkedVersionRemainsUnmodifiedAndIsNotTypedDirectorySource() {
        UUID legacy = jdbc.queryForObject("SELECT form_id FROM apr_forms WHERE tenant_id=42 AND form_key='ACCESS_EXCEPTION_FORM'", UUID.class);
        UUID version = jdbc.queryForObject("SELECT form_version_id FROM apr_form_versions WHERE form_id=? AND version_number=(SELECT current_version FROM apr_forms WHERE form_id=?)",
                UUID.class, legacy, legacy);
        String hash = jdbc.queryForObject("SELECT schema_sha256 FROM apr_form_versions WHERE form_version_id=?", String.class, version);
        assertThatThrownBy(() -> forms.requirePublished(new FormBinding(42, 99, legacy, version, hash), false))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
        assertThat(jdbc.queryForObject("SELECT schema_sha256 FROM apr_form_versions WHERE form_version_id=?", String.class, version)).isEqualTo(hash);
        assertThat(jdbc.queryForObject("SELECT schema_payload->>'schemaVersion' FROM apr_form_versions WHERE form_version_id=?", String.class, version)).isEqualTo("2");
    }

    private void publish() {
        context(100, true);
        scope("RS_APPROVALS");
        long version = jdbc.queryForObject("SELECT version FROM apr_forms WHERE form_id=?", Long.class, formId);
        tx(() -> { commands.publishForm(ApprovalRequestContext.require(), formId, version); return null; });
        context(99, true);
    }
    private void scope(String key) { ApprovalManagementScopeContext.set("exact-admin-scope", key); }
    private Map<String, Object> field(String key, String type) {
        return Map.of("key", key, "labelKo", "항목", "labelEn", "Field", "type", type);
    }
    private void assertConflict(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BaseException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }
}
