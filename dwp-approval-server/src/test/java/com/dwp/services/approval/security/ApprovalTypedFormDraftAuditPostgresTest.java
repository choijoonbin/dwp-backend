package com.dwp.services.approval.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.dwp.services.approval.domain.ApprovalDtos;
import com.dwp.services.approval.domain.ApprovalFormSchemaV2;
import com.dwp.services.approval.domain.ApprovalFormSchemaV2Compiler;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalTypedFormDraftAuditPostgresTest extends ApprovalDraftPostgresFixture {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeEach void setUp() {
        initialize(POSTGRES);
        String resourceSet = jdbc.queryForObject("SELECT management_resource_set_key "
                + "FROM apr_workflow_definitions WHERE workflow_id=?", String.class, workflowId);
        ApprovalManagementScopeContext.set("test-opaque-scope", resourceSet);
    }

    @AfterEach void tearDown() { clear(); }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void updatesTypedOnlyDraftAndAuditsPersistedFieldCount(boolean emptyLegacyFields) {
        UUID categoryId = jdbc.queryForObject("SELECT category_id FROM apr_forms WHERE form_id=?", UUID.class, formId);
        Map<String, Object> schema = Map.of("schemaVersion", 2, "schemaContract", ApprovalFormSchemaV2.CONTRACT,
                "fields", List.of(Map.of("key", "summary", "labelKo", "요청 내용", "labelEn", "Summary",
                        "type", "TEXTAREA", "required", true, "maxLength", 2000)));
        UUID id = tx(() -> commands.createFormDraft(ApprovalRequestContext.require(),
                new ApprovalDtos.CreateFormDraftRequest("TYPED_AUDIT_FORM", categoryId, "고급 양식", "Typed form",
                        "설명", "Description", "APPROVAL_OPERATOR", workflowId, null, schema)));
        long version = jdbc.queryForObject("SELECT version FROM apr_forms WHERE form_id=?", Long.class, id);
        var body = new ApprovalDtos.UpdateFormDraftRequest(categoryId, "수정 양식", "Updated form",
                "설명", "Description", "APPROVAL_OPERATOR", workflowId,
                emptyLegacyFields ? List.of() : null, version, schema);

        var updated = tx(() -> approvals.updateFormDraft(id, body, "typed-audit"));

        assertThat(updated.form().version()).isEqualTo(version + 1);
        assertThat(updated.form().fieldCount()).isEqualTo(1);
        assertThat(updated.schemaHash()).isEqualTo(new ApprovalFormSchemaV2Compiler().compile(schema).sha256());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_audit_outbox "
                + "WHERE payload->>'action'='approval.form.draft.updated' "
                + "AND payload->>'targetId'=? "
                + "AND payload#>>'{afterState,fieldCount}'='1'", Long.class, id.toString())).isEqualTo(1);
    }
}
