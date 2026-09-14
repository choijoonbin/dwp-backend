package com.dwp.services.approval.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalDtos;
import com.dwp.services.approval.domain.ApprovalFormReferenceBindingRepository;
import com.dwp.services.approval.domain.ApprovalFormReferenceNormalizer;
import com.dwp.services.approval.domain.ApprovalFormSchemaV2Compiler;
import com.dwp.services.approval.domain.ApprovalFormUserBindingRepository;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.integration.ApprovalFormReferenceDirectory;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;

class ApprovalFormUserCommandPostgresFixture extends ApprovalDraftPostgresFixture {
    final UUID person = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    MockHttpServletRequest http;
    Map<String, Object> schema;
    UUID formVersionId;
    String schemaSha;
    final AtomicInteger sourceCalls = new AtomicInteger();
    SourceResult sourceResult = SourceResult.ACTIVE;
    ApprovalFormReferenceDirectory.MutationPins lastPins;
    ApprovalFormUserDirectory.Authority lastAuthority;
    Runnable afterSourceRead;

    enum SourceResult { ACTIVE, FORBIDDEN, NOT_FOUND, UNAVAILABLE, INACTIVE, REVISION_CHANGED, SOURCE_REVOKED }

    void initializeUserCommands(PostgreSQLContainer<?> postgres) {
        initialize(postgres);
        schema = Map.of("schemaContract", "DWP_APPROVAL_FORM_TYPED_V2", "schemaVersion", 2, "fields",
                List.of(field("summary", "TEXTAREA"), field("mode", "TEXT"), field("units", "NUMBER"),
                        Map.of("key", "total", "labelKo", "합계", "labelEn", "Total", "type", "CALCULATED_NUMBER",
                                "calculation", Map.of("op", "MULTIPLY", "args", List.of(Map.of("op", "FIELD", "field", "units"), Map.of("op", "CONST", "value", "2")))),
                        Map.of("key", "reviewer", "labelKo", "검토자", "labelEn", "Reviewer", "type", "USER",
                                "visibleWhen", Map.of("op", "EQ", "field", "mode", "value", "REVIEW"))));
        schemaSha = new ApprovalFormSchemaV2Compiler().compile(schema).sha256();
        UUID category = jdbc.queryForObject("SELECT category_id FROM apr_forms WHERE form_id=?", UUID.class, formId);
        ApprovalManagementScopeContext.set("management", "RS_APPROVALS");
        formId = tx(() -> commands.createFormDraft(ApprovalRequestContext.require(), new ApprovalDtos.CreateFormDraftRequest(
                "COMMAND_USER_FORM", category, "사용자", "User", "설명", "Description", "APPROVAL_OPERATOR", workflowId, null, schema)));
        context(100, true); ApprovalManagementScopeContext.set("management", "RS_APPROVALS");
        tx(() -> { commands.publishForm(ApprovalRequestContext.require(), formId, 0); return null; });
        formVersionId = jdbc.queryForObject("SELECT form_version_id FROM apr_form_versions WHERE form_id=?", UUID.class, formId);
        sourcePermissions(true);
        ApprovalManagementScopeContext.set("scope", "RS_APPROVALS");
        http = new MockHttpServletRequest(); http.addHeader("X-DWP-Active-Access-Mode", "NORMAL");
        http.addHeader("Idempotency-Key", "real-command-key");
        @SuppressWarnings("unchecked") ObjectProvider<HttpServletRequest> requests = mock(ObjectProvider.class);
        when(requests.getIfAvailable()).thenReturn(http);
        var named = new NamedParameterJdbcTemplate(jdbc.getDataSource());
        var mapper = new ObjectMapper().findAndRegisterModules();
        ApprovalFormReferenceDirectory directory = (authority, pins, people) -> {
            sourceCalls.incrementAndGet(); lastPins = pins; lastAuthority = authority;
            if (sourceResult == SourceResult.FORBIDDEN || sourceResult == SourceResult.NOT_FOUND) throw new BaseException(ErrorCode.FORBIDDEN);
            if (sourceResult == SourceResult.UNAVAILABLE) throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
            if (sourceResult == SourceResult.REVISION_CHANGED) {
                var evidence = ApprovalDecisionRevisionContext.current().orElseThrow();
                ApprovalDecisionRevisionContext.set("psr-" + "c".repeat(64), evidence.validUntil(), evidence.contextKey(),
                        evidence.contextScopeKey(), evidence.routeContractKey(), evidence.rolloutState());
            }
            if (sourceResult == SourceResult.SOURCE_REVOKED) sourcePermissions(false);
            if (afterSourceRead != null) afterSourceRead.run();
            return new ApprovalFormUserDirectory.Result(authority, people.stream().map(id -> new ApprovalFormUserDirectory.Person(
                    42L, 123L, id, "Kim", "TENANT", sourceResult == SourceResult.INACTIVE ? "INACTIVE" : "ACTIVE")).toList());
        };
        var normalizer = new ApprovalFormReferenceNormalizer(new ApprovalFormReferenceBindingRepository(named, mapper),
                new ApprovalFormUserBindingRepository(named, mapper), new ApprovalFormReferenceAuthority(new ApprovalWorkAuthority(identities), requests),
                new ApprovalFormReferenceMutationContext(requests), directory, mapper);
        ReflectionTestUtils.invokeMethod(commands, "bindFormReferenceNormalizer", normalizer);
    }

    void sourcePermissions(boolean sourceView) {
        var permissions = new java.util.HashSet<>(PERMISSIONS);
        if (sourceView) permissions.add(ApprovalFormUserCurrentAuthority.SOURCE_VIEW);
        ApprovalRequestContext.set(99L, 42L, null, "Owner", Set.of("APPROVAL_OPERATOR"), permissions);
        when(identities.require(42, 99)).thenReturn(new ApprovalIdentityDirectory.Subject(42L, 99L, null, null, "Owner", null, null,
                "ACTIVE", List.of("APPROVAL_OPERATOR"), List.copyOf(permissions)));
    }

    void action(String leaf, UUID id) {
        String suffix = switch (leaf) { case "request-create.action" -> ""; case "request-draft-update.action" -> "/draft";
            case "request-submit.action" -> "/submit"; case "request-draft-recover.action" -> "/draft/recover"; default -> "/information-response"; };
        http.setMethod(leaf.equals("request-draft-update.action") ? "PUT" : "POST");
        http.setRequestURI(leaf.equals("request-create.action") ? "/v1/requests" : "/v1/requests/" + id + suffix);
        String route = "route.approvals.work." + leaf;
        ApprovalDecisionRevisionContext.set("psr-" + "b".repeat(64), OffsetDateTime.now().plusSeconds(55), "ctx", "scope", route, "110");
        ApprovalPilotAuthorizationContext.set(List.of(new ApprovalPilotPepRegistry.RouteAuthority(route, "ACTION", "full-work", false,
                leaf.equals("request-create.action") ? Set.of() : Set.of("predicate.approval.own-request.v1"), null, null, null, false, null, null)));
    }

    ApprovalDtos.RequestSummary createUserRequest(String key) {
        http.removeHeader("Idempotency-Key"); http.addHeader("Idempotency-Key", key);
        action("request-create.action", null);
        return tx(() -> drafts.create(new ApprovalDtos.CreateRequest(workflowId, formId, "User command", "Summary", "NORMAL", values()), key, "corr"));
    }

    Map<String, Object> values() { return Map.of("mode", "REVIEW", "units", "3", "reviewer", person.toString()); }

    ApprovalDtos.UpdateDraftRequest updateUser(ApprovalDtos.RequestSummary request, Map<String, Object> payload) {
        return new ApprovalDtos.UpdateDraftRequest(workflowId, formId, "Updated", "Summary", "NORMAL", payload, request.version());
    }

    Map<String, Object> databaseState() {
        var state = new LinkedHashMap<String, Object>();
        for (String table : List.of("apr_requests", "apr_request_payloads", "apr_request_payload_versions", "apr_steps", "apr_tasks",
                "apr_request_events", "sys_audit_outbox", "apr_integration_outbox", "apr_draft_commands")) {
            state.put(table, jdbc.queryForObject("SELECT COALESCE(jsonb_agg(row ORDER BY row::text),'[]'::jsonb)::text "
                    + "FROM (SELECT to_jsonb(t) AS row FROM " + table + " t) records", String.class));
        }
        return state;
    }

    void assertSealedPayload(UUID id, long version) throws Exception {
        assertThat(lastPins.targetRequestId()).isEqualTo(id); assertThat(lastPins.targetRequestVersion()).isEqualTo(version);
        String payload = jdbc.queryForObject("SELECT payload::text FROM apr_request_payloads WHERE request_id=?", String.class, id);
        Map<String, Object> actual = new ObjectMapper().readValue(payload, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        byte[] canonical = new ObjectMapper().configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true).writeValueAsBytes(actual);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        assertThat(lastPins.mutationPayloadSha256()).isEqualTo(hash);
        assertThat(lastAuthority.form().formVersionId()).isEqualTo(formVersionId);
        assertThat(lastAuthority.form().schemaSha256()).isEqualTo(schemaSha);
    }

    void advanceForm() {
        var compiled = new ApprovalFormSchemaV2Compiler().compile(schema);
        jdbc.update("INSERT INTO apr_form_versions(form_version_id,tenant_id,form_id,version_number,schema_payload,schema_sha256,lifecycle_state) "
                + "VALUES (?,42,?,2,?::jsonb,?,'DRAFT')", UUID.randomUUID(), formId, compiled.canonicalJson(), compiled.sha256());
        jdbc.update("UPDATE apr_forms SET current_version=2,lifecycle_state='DRAFT' WHERE form_id=?", formId);
    }
    Map<String, Object> field(String key, String type) { return Map.of("key", key, "labelKo", "항목", "labelEn", "Field", "type", type); }
}
