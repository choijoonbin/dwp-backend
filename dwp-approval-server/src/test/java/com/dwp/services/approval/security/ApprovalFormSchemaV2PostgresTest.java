package com.dwp.services.approval.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalDtos;
import com.dwp.services.approval.domain.ApprovalFormSchemaV2;
import com.dwp.services.approval.domain.ApprovalFormSchemaV2Compiler;
import com.dwp.services.approval.domain.ApprovalWorkDtos;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalFormSchemaV2PostgresTest extends ApprovalDraftPostgresFixture {

    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private Map<String, Object> schema;
    private UUID categoryId;

    @BeforeEach void setUp() throws Exception {
        initialize(POSTGRES);
        try (InputStream input = getClass().getResourceAsStream("/approval/form-schema-v2-parity.json")) {
            Map<String, Object> fixture = new ObjectMapper().readValue(input, new TypeReference<>() { });
            schema = object(fixture.get("schema"));
        }
        List<Object> fields = new ArrayList<>((List<?>) schema.get("fields"));
        fields.add(Map.of("key", "summary", "labelKo", "요청 내용", "labelEn", "Request summary",
                "type", "TEXTAREA", "required", true, "maxLength", 2000));
        schema.put("fields", fields);
        categoryId = jdbc.queryForObject("SELECT category_id FROM apr_forms WHERE form_id=?", UUID.class, formId);
        managementScope();
    }

    @AfterEach void tearDown() { clear(); }

    @Test void persistsCanonicalTypedSchemaAndRejectsAmbiguousOrMissingSummaryDefinitions() {
        UUID id = createTypedForm();
        ApprovalFormSchemaV2 compiled = new ApprovalFormSchemaV2Compiler().compile(schema);
        assertThat(jdbc.queryForObject("SELECT schema_sha256 FROM apr_form_versions WHERE form_id=?", String.class, id))
                .isEqualTo(compiled.sha256());
        assertThat(jdbc.queryForObject("SELECT approval_typed_form_canonical_json(schema_payload) FROM apr_form_versions WHERE form_id=?", String.class, id))
                .isEqualTo(compiled.canonicalJson());
        assertThatThrownBy(() -> tx(() -> commands.createFormDraft(ApprovalRequestContext.require(),
                new ApprovalDtos.CreateFormDraftRequest("AMBIGUOUS_FORM", categoryId, "양식", "Form", "설명", "Description",
                        "APPROVAL_OPERATOR", workflowId,
                        List.of(new ApprovalDtos.FormFieldInput("summary", "요약", "Summary", null, null, "TEXT", true, List.of())), schema))))
                .isInstanceOf(BaseException.class);
        Map<String, Object> missing = new LinkedHashMap<>(schema);
        missing.put("fields", ((List<?>) schema.get("fields")).stream().filter(raw -> !"summary".equals(object(raw).get("key"))).toList());
        assertThatThrownBy(() -> tx(() -> commands.createFormDraft(ApprovalRequestContext.require(), formBody("MISSING_SUMMARY", missing))))
                .isInstanceOf(BaseException.class);
    }

    @Test void publishesOnlyWithIndependentActorAndFreezesSchemaHashIdentityAndDeletion() {
        UUID id = createTypedForm();
        assertThatThrownBy(() -> tx(() -> { commands.publishForm(ApprovalRequestContext.require(), id, version(id)); return null; }))
                .isInstanceOf(BaseException.class);
        publish(id, version(id));
        String hash = jdbc.queryForObject("SELECT schema_sha256 FROM apr_form_versions WHERE form_id=?", String.class, id);
        for (String assignment : List.of("schema_payload=jsonb_set(schema_payload,'{fields,0,labelEn}','\"Rewritten\"')",
                "schema_sha256=repeat('a',64)", "form_version_id=gen_random_uuid()", "version_number=version_number+1",
                "tenant_id=84", "form_id=gen_random_uuid()", "lifecycle_state='DRAFT'")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE apr_form_versions SET " + assignment + " WHERE form_id=?", id))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
        }
        assertThatThrownBy(() -> jdbc.update("DELETE FROM apr_form_versions WHERE form_id=?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
        assertThat(jdbc.queryForObject("SELECT schema_sha256 FROM apr_form_versions WHERE form_id=?", String.class, id)).isEqualTo(hash);
        context(99, true);
        managementScope();
        assertThatThrownBy(() -> tx(() -> { commands.updateFormDraft(ApprovalRequestContext.require(), id,
                new ApprovalDtos.UpdateFormDraftRequest(categoryId, "양식", "Form", "설명", "Description", "APPROVAL_OPERATOR",
                        workflowId, null, version(id), schema)); return null; })).isInstanceOf(BaseException.class);
    }

    @Test void draftSchemaUpdateRepinsHashWhileLegacyUnmarkedSchemasRemainUnchanged() {
        UUID id = createTypedForm();
        Map<String, Object> changed = new LinkedHashMap<>(schema);
        List<Object> fields = new ArrayList<>((List<?>) schema.get("fields"));
        Map<String, Object> first = new LinkedHashMap<>(object(fields.getFirst()));
        first.put("labelEn", "Classification");
        fields.set(0, first);
        changed.put("fields", fields);
        tx(() -> { commands.updateFormDraft(ApprovalRequestContext.require(), id,
                new ApprovalDtos.UpdateFormDraftRequest(categoryId, "양식", "Form", "설명", "Description", "APPROVAL_OPERATOR",
                        workflowId, null, version(id), changed)); return null; });
        assertThat(jdbc.queryForObject("SELECT schema_sha256 FROM apr_form_versions WHERE form_id=?", String.class, id))
                .isEqualTo(new ApprovalFormSchemaV2Compiler().compile(changed).sha256());
        assertThatThrownBy(() -> jdbc.update("UPDATE apr_form_versions SET schema_sha256=repeat('b',64) WHERE form_id=?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("canonical hash");
        assertThat(jdbc.update("UPDATE apr_form_versions SET schema_payload=jsonb_set(schema_payload,'{legacyNote}','\"Legacy unchanged\"') " +
                "WHERE form_id=? AND version_number=(SELECT current_version FROM apr_forms WHERE form_id=?)", formId, formId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT schema_payload->>'schemaVersion' FROM apr_form_versions " +
                "WHERE form_id=? AND version_number=(SELECT current_version FROM apr_forms WHERE form_id=?)", String.class, formId, formId)).isEqualTo("2");
    }

    @Test void canonicalDatabaseHashMatchesFiniteDecimalAndUnicodeEscaping() {
        List<Object> fields = new ArrayList<>((List<?>) schema.get("fields"));
        Map<String, Object> summary = new LinkedHashMap<>(object(fields.getLast()));
        summary.put("labelEn", "Request \"summary\"\tand 😀");
        fields.set(fields.size() - 1, summary);
        fields.add(Map.of("key", "fraction", "labelKo", "소수", "labelEn", "Fraction",
                "type", "NUMBER", "required", false, "min", "0.00000001"));
        schema.put("fields", fields);
        UUID id = createTypedForm();
        assertThat(jdbc.queryForObject("SELECT approval_typed_form_canonical_json(schema_payload) FROM apr_form_versions WHERE form_id=?", String.class, id))
                .isEqualTo(new ApprovalFormSchemaV2Compiler().compile(schema).canonicalJson());
        publish(id, version(id));
    }

    @Test void sqlCanonicalizationPreservesArrayCardinalityAndExactDecimalStringBoundaries() {
        String raw = "{\"rows\":[1,2,3],\"tiny\":\"0.00000001\",\"large\":\"9999999999999999999999999999\",\"number\":1.23000000}";
        assertThat(jdbc.queryForObject("SELECT approval_typed_form_canonical_json(?::jsonb)", String.class, raw))
                .isEqualTo("{\"large\":\"9999999999999999999999999999\",\"number\":1.23,\"rows\":[1,2,3],\"tiny\":\"0.00000001\"}");
    }

    @Test void invalidTypedDecimalBoundsFailBeforeAnyFormWrite() {
        long before = count("apr_forms");
        for (Object invalid : List.of("0.000000001", "10000000000000000000000000000", 1.0d, 1.0f, 9007199254740992L)) {
            Map<String, Object> changed = new LinkedHashMap<>(schema);
            List<Object> fields = new ArrayList<>((List<?>) schema.get("fields"));
            fields.add(Map.of("key", "amount", "labelKo", "금액", "labelEn", "Amount", "type", "NUMBER", "min", invalid));
            changed.put("fields", fields);
            assertThatThrownBy(() -> tx(() -> commands.createFormDraft(ApprovalRequestContext.require(), formBody("INVALID_BOUND", changed))))
                    .isInstanceOf(BaseException.class);
            assertThat(count("apr_forms")).isEqualTo(before);
        }
    }

    @Test void partialDraftNormalizesHiddenInputsAndServerComputationsBeforeRevisionHashAndRecovery() {
        formId = createTypedForm();
        publish(formId, version(formId));
        context(99, true);
        var first = tx(() -> drafts.create(request(Map.of("category", "STANDARD", "details", "must be stripped",
                "items", List.of(Map.of("quantity", 2, "price", "10.25")))), "v2-create", "corr"));
        Map<String, Object> saved = payload(first.requestId());
        assertThat(saved).doesNotContainKey("details");
        assertThat(saved.get("total").toString()).isEqualTo("20.5");
        assertThat(saved.get("total")).isInstanceOf(String.class);
        assertThat(jdbc.queryForObject("SELECT jsonb_typeof(payload->'total') || ':' || " +
                "jsonb_typeof(payload#>'{items,0,quantity}') || ':' || jsonb_typeof(payload#>'{items,0,lineTotal}') " +
                "FROM apr_request_payloads WHERE request_id=?", String.class, first.requestId())).isEqualTo("string:string:string");
        assertThat(jdbc.queryForObject("SELECT payload_sha256 FROM apr_request_payloads WHERE request_id=?", String.class, first.requestId()))
                .isEqualTo(jdbc.queryForObject("SELECT payload_sha256 FROM apr_request_payload_versions WHERE request_id=? AND revision_number=1", String.class, first.requestId()));
        var updated = tx(() -> drafts.update(first.requestId(), update(first.version(), Map.of("category", "STANDARD")), "v2-update", "corr2"));
        assertThat(payload(first.requestId())).doesNotContainKey("total");
        assertThatThrownBy(() -> tx(() -> approvals.submit(first.requestId(), updated.request().version(), "corr3"))).isInstanceOf(BaseException.class);
        var recovered = tx(() -> drafts.recover(first.requestId(), new ApprovalWorkDtos.RecoverDraft(1, updated.request().version(), "v2-recover", "Recover prior inputs"), "corr4"));
        assertThat(recovered.payloadRevision()).isEqualTo(3);
        assertThat(payload(first.requestId())).doesNotContainKey("details").containsKey("total");
        assertThat(jdbc.queryForObject("SELECT payload->>'total' FROM apr_request_payload_versions WHERE request_id=? AND revision_number=1", String.class, first.requestId()))
                .isEqualTo("20.5");
    }

    @Test void completeSubmitRejectsTamperedComputedInputsAndOtherOwnersWithoutWrites() {
        formId = createTypedForm();
        publish(formId, version(formId));
        context(99, true);
        assertThatThrownBy(() -> tx(() -> drafts.create(request(Map.of("category", "STANDARD", "items",
                List.of(Map.of("quantity", 2, "price", 10, "lineTotal", 999)))), "tamper", "corr"))).isInstanceOf(BaseException.class);
        assertThat(count("apr_requests")).isZero();
        var first = tx(() -> drafts.create(request(Map.of("category", "STANDARD", "items",
                List.of(Map.of("quantity", 2, "price", 10)))), "correct", "corr"));
        context(100, true);
        assertThatThrownBy(() -> tx(() -> approvals.submit(first.requestId(), first.version(), "other"))).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> tx(() -> drafts.update(first.requestId(), update(first.version(), Map.of("category", "OTHER")), "other-update", "other")))
                .isInstanceOf(BaseException.class);
        context(99, true);
        var submitted = tx(() -> approvals.submit(first.requestId(), first.version(), "owner"));
        assertThat(submitted.status()).isEqualTo("IN_REVIEW");
        assertThat(count("apr_request_payload_versions")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT form_version_id FROM apr_requests WHERE request_id=?", UUID.class, first.requestId()))
                .isEqualTo(jdbc.queryForObject("SELECT form_version_id FROM apr_form_versions WHERE form_id=?", UUID.class, formId));
    }

    @Test void materialInformationPatchRecomputesDerivedFieldsAndRestartsImmutableDecisionEvidence() {
        formId = createTypedForm();
        publish(formId, version(formId));
        context(99, true);
        var first = tx(() -> drafts.create(request(Map.of("category", "STANDARD", "items", List.of(Map.of("quantity", 2, "price", 10)))), "info-draft", "corr"));
        var submitted = tx(() -> approvals.submit(first.requestId(), first.version(), "submit"));
        UUID taskId = jdbc.queryForObject("SELECT task_id FROM apr_tasks WHERE request_id=?", UUID.class, first.requestId());
        jdbc.update("UPDATE apr_tasks SET status='INFO_REQUESTED',assignee_user_id=100,completed_at=CURRENT_TIMESTAMP,decision_payload_revision=1," +
                "decision_payload_sha256=(SELECT payload_sha256 FROM apr_request_payloads WHERE request_id=?) WHERE task_id=?", first.requestId(), taskId);
        jdbc.update("UPDATE apr_requests SET status='NEEDS_INFO' WHERE request_id=?", first.requestId());
        var amended = tx(() -> approvals.respondToInformationRequest(first.requestId(), new ApprovalDtos.InformationResponseRequest(
                "Updated quantities", Map.of("items", List.of(Map.of("quantity", 3, "price", 10))), submitted.version()), "amend"));
        assertThat(payload(first.requestId()).get("total").toString()).isEqualTo("30");
        assertThat(count("apr_request_payload_versions")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM apr_tasks WHERE task_id=?", String.class, taskId)).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject("SELECT payload->>'total' FROM apr_request_payload_versions WHERE request_id=? AND revision_number=1", String.class, first.requestId()))
                .isEqualTo("20");
        assertThat(amended.status()).isEqualTo("IN_REVIEW");
    }

    @ParameterizedTest
    @ValueSource(strings = {"GTE", "EQ", "IN"})
    void typedConditionalRoutesCompareExactCanonicalDecimalStrings(String operator) {
        formId = createTypedForm();
        publish(formId, version(formId));
        context(99, true);
        Object expected = switch (operator) {
            case "GTE" -> 20;
            case "EQ" -> "20.00";
            default -> List.of("19", "20.000");
        };
        conditionalRoute("total", operator, expected);
        var first = tx(() -> drafts.create(request(Map.of("category", "STANDARD", "items",
                List.of(Map.of("quantity", 2, "price", 10)))), "typed-route", "corr"));
        assertThat(payload(first.requestId()).get("total")).isEqualTo("20");
        assertThat(tx(() -> approvals.submit(first.requestId(), first.version(), "submit-route")).status())
                .isEqualTo("IN_REVIEW");
    }

    @Test void legacyConditionalRoutesDoNotCoerceStringFieldsToNumbers() {
        conditionalRoute("systemName", "GTE", 10);
        var first = tx(() -> drafts.create(new ApprovalDtos.CreateRequest(workflowId, formId, "Legacy request", "Summary",
                "NORMAL", payload("20")), "legacy-route", "corr"));
        assertThatThrownBy(() -> tx(() -> approvals.submit(first.requestId(), first.version(), "legacy-numeric")))
                .isInstanceOf(BaseException.class);
        assertThat(count("apr_tasks")).isZero();
        conditionalRoute("systemName", "EQ", "20");
        assertThat(tx(() -> approvals.submit(first.requestId(), first.version(), "legacy-eq")).status())
                .isEqualTo("IN_REVIEW");
    }

    private void conditionalRoute(String field, String operator, Object expected) {
        try {
            String condition = new ObjectMapper().writeValueAsString(Map.of("all", List.of(
                    Map.of("field", field, "operator", operator, "value", expected))));
            assertThat(jdbc.update("UPDATE apr_form_workflow_bindings SET binding_type='CONDITIONAL',condition_payload=?::jsonb " +
                    "WHERE tenant_id=42 AND form_id=? AND workflow_id=?", condition, formId, workflowId)).isEqualTo(1);
        } catch (Exception exception) { throw new AssertionError(exception); }
    }

    private UUID createTypedForm() {
        return tx(() -> commands.createFormDraft(ApprovalRequestContext.require(), formBody("TYPED_FORM", schema)));
    }
    private ApprovalDtos.CreateFormDraftRequest formBody(String key, Map<String, Object> value) {
        return new ApprovalDtos.CreateFormDraftRequest(key, categoryId, "고급 양식", "Advanced form", "설명", "Description",
                "APPROVAL_OPERATOR", workflowId, null, value);
    }
    private void publish(UUID id, long version) {
        context(100, true);
        managementScope();
        tx(() -> { commands.publishForm(ApprovalRequestContext.require(), id, version); return null; });
    }
    private void managementScope() {
        String scope = jdbc.queryForObject("SELECT management_resource_set_key FROM apr_workflow_definitions WHERE workflow_id=?", String.class, workflowId);
        ApprovalManagementScopeContext.set("test-opaque-scope", scope);
    }
    private long version(UUID id) {
        return jdbc.queryForObject("SELECT version FROM apr_forms WHERE form_id=?", Long.class, id);
    }
    private ApprovalDtos.CreateRequest request(Map<String, Object> input) {
        return new ApprovalDtos.CreateRequest(workflowId, formId, "Typed request", "Summary", "NORMAL", input);
    }
    private ApprovalDtos.UpdateDraftRequest update(long version, Map<String, Object> input) {
        return new ApprovalDtos.UpdateDraftRequest(workflowId, formId, "Typed request", "Summary", "NORMAL", input, version);
    }
    private Map<String, Object> payload(UUID id) {
        try { return new ObjectMapper().readValue(jdbc.queryForObject("SELECT payload::text FROM apr_request_payloads WHERE request_id=?", String.class, id), new TypeReference<>() { }); }
        catch (Exception exception) { throw new AssertionError(exception); }
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> object(Object value) { return (Map<String, Object>) value; }
}
