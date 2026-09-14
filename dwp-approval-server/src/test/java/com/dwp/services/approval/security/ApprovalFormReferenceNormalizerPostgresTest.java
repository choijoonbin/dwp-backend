package com.dwp.services.approval.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalDtos;
import com.dwp.services.approval.domain.ApprovalFormReferenceBindingRepository;
import com.dwp.services.approval.domain.ApprovalFormReferenceNormalizer;
import com.dwp.services.approval.domain.ApprovalFormSchemaV2Compiler;
import com.dwp.services.approval.domain.ApprovalFormUserBindingRepository;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.integration.ApprovalFormReferenceProofIssuer;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.integration.AuthApprovalFormReferenceDirectory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ApprovalFormReferenceNormalizerPostgresTest extends ApprovalDraftPostgresFixture {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    private static RSAKey key;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final UUID person = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    private ApprovalFormReferenceNormalizer normalizer;
    private MockRestServiceServer server;
    private MockHttpServletRequest http;
    private UUID versionId;
    private Map<String, Object> schema;

    @BeforeAll static void keys() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("pg-reference").keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();
    }
    @BeforeEach void setUp() {
        initialize(POSTGRES);
        schema = Map.of("schemaContract", "DWP_APPROVAL_FORM_TYPED_V2", "schemaVersion", 2, "fields",
                List.of(field("summary", "TEXTAREA"), field("mode", "TEXT"),
                        Map.of("key", "reviewer", "labelKo", "검토자", "labelEn", "Reviewer", "type", "USER",
                                "visibleWhen", Map.of("op", "EQ", "field", "mode", "value", "REVIEW"))));
        UUID category = jdbc.queryForObject("SELECT category_id FROM apr_forms WHERE form_id=?", UUID.class, formId);
        ApprovalManagementScopeContext.set("management", "RS_APPROVALS");
        formId = tx(() -> commands.createFormDraft(ApprovalRequestContext.require(), new ApprovalDtos.CreateFormDraftRequest(
                "REFERENCE_USER_FORM", category, "사용자", "User", "설명", "Description", "APPROVAL_OPERATOR", workflowId, null, schema)));
        context(100, true); ApprovalManagementScopeContext.set("management", "RS_APPROVALS");
        tx(() -> { commands.publishForm(ApprovalRequestContext.require(), formId, 0); return null; });
        versionId = jdbc.queryForObject("SELECT form_version_id FROM apr_form_versions WHERE form_id=?", UUID.class, formId);
        context(99, true);
        http = new MockHttpServletRequest();
        http.addHeader("X-DWP-Active-Access-Mode", "NORMAL"); http.addHeader("Idempotency-Key", "real-original-command-key");
        @SuppressWarnings("unchecked") ObjectProvider<HttpServletRequest> requests = mock(ObjectProvider.class);
        when(requests.getIfAvailable()).thenReturn(http);
        var named = new NamedParameterJdbcTemplate(jdbc.getDataSource());
        var builder = RestClient.builder().defaultHeader("Authorization", "not-allowed");
        server = MockRestServiceServer.bindTo(builder).build();
        var directory = new AuthApprovalFormReferenceDirectory(builder, mapper,
                new ApprovalFormReferenceProofIssuer(mapper, key.toJSONString()), "http://auth.test", "only-reference-token");
        normalizer = new ApprovalFormReferenceNormalizer(new ApprovalFormReferenceBindingRepository(named, mapper),
                new ApprovalFormUserBindingRepository(named, mapper),
                new ApprovalFormReferenceAuthority(new ApprovalWorkAuthority(identities), requests),
                new ApprovalFormReferenceMutationContext(requests), directory, mapper);
        ReflectionTestUtils.invokeMethod(commands, "bindFormReferenceNormalizer", normalizer);
        sourcePermissions(true);
    }
    @AfterEach void tearDown() { clear(); }

    @Test void createSealsServerUuidVersionZeroActualActionAndNormalizedPayloadBeforeAnyWrite() {
        assertThat(queries.form(42, formId).formVersionId()).isEqualTo(versionId);
        UUID target = UUID.randomUUID(); action("request-create.action", target);
        response(false);
        long before = count("apr_requests");
        var normalized = tx(() -> normalizer.normalize(ApprovalRequestContext.require(), target, versionId, json(schema), values(), false, 0, true, false));
        assertThat(normalized).containsEntry("reviewer", person.toString());
        assertThat(count("apr_requests")).isEqualTo(before);
        server.verify();
    }

    @Test void existingPinnedPublishedVersionSurvivesCurrentVersionAdvanceWithoutMigration() {
        var request = createPinnedRequest("create-old");
        advanceVersion();
        var detail = queries.requestDetail(ApprovalRequestContext.require(), request.requestId());
        assertThat(detail.formVersionId()).isEqualTo(versionId);
        assertThat(detail.formSchemaSha256()).isEqualTo(new ApprovalFormSchemaV2Compiler().compile(schema).sha256());
        action("request-submit.action", request.requestId()); response(false);
        var normalized = tx(() -> normalizer.normalize(ApprovalRequestContext.require(), request.requestId(), versionId,
                json(schema), values(), true, request.version(), false, false));
        assertThat(normalized).containsEntry("reviewer", person.toString());
        assertThat(jdbc.queryForObject("SELECT form_version_id FROM apr_requests WHERE request_id=?", UUID.class, request.requestId())).isEqualTo(versionId);
        server.verify();
    }

    @Test void alternateClientSchemaVersionAndRetiredOrForeignOwnerFailBeforeSourceAndWrite() {
        var request = createPinnedRequest("cross-old");
        action("request-submit.action", request.requestId());
        assertThatThrownBy(() -> tx(() -> normalizer.normalize(ApprovalRequestContext.require(), request.requestId(), UUID.randomUUID(), json(schema), values(), true,
                request.version(), false, false))).isInstanceOf(BaseException.class);
        jdbc.update("UPDATE apr_forms SET lifecycle_state='RETIRED' WHERE form_id=?", formId);
        assertThatThrownBy(() -> tx(() -> normalizer.normalize(ApprovalRequestContext.require(), request.requestId(), versionId, json(schema), values(), true,
                request.version(), false, false))).isInstanceOf(BaseException.class);
        assertThat(jdbc.queryForObject("SELECT version FROM apr_requests WHERE request_id=?", Long.class, request.requestId())).isEqualTo(request.version());
        server.verify();
    }

    @Test void hiddenStaleUserIsStrippedWithoutDirectoryReadOrInventedMetadata() {
        http.removeHeader("Idempotency-Key");
        UUID target = UUID.randomUUID(); action("request-create.action", target);
        var result = tx(() -> normalizer.normalize(ApprovalRequestContext.require(), target, versionId, json(schema),
                Map.of("summary", "Summary", "mode", "OTHER", "reviewer", "stale-invalid-person"), false, 0, true, false));
        assertThat(result).doesNotContainKey("reviewer");
        server.verify();
    }

    @Test void sourceRevocationDuringLookupDiscardsResultWithZeroWrites() {
        UUID target = UUID.randomUUID(); action("request-create.action", target);
        response(true);
        assertThatThrownBy(() -> tx(() -> normalizer.normalize(ApprovalRequestContext.require(), target, versionId, json(schema), values(), false, 0, true, false)))
                .isInstanceOfSatisfying(BaseException.class, exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(count("apr_requests")).isZero(); server.verify();
    }

    @Test void unavailableSourceAndMissingOriginalKeyCannotWrite() {
        UUID target = UUID.randomUUID(); action("request-create.action", target);
        http.removeHeader("Idempotency-Key");
        assertThatThrownBy(() -> tx(() -> normalizer.normalize(ApprovalRequestContext.require(), target, versionId, json(schema), values(), false, 0, true, false))).isInstanceOf(BaseException.class);
        http.addHeader("Idempotency-Key", "real-key");
        server.expect(requestTo("http://auth.test/internal/auth/v1/approval-form-user-directory/resolve"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> tx(() -> normalizer.normalize(ApprovalRequestContext.require(), target, versionId, json(schema), values(), false, 0, true, false))).isInstanceOf(BaseException.class);
        assertThat(count("apr_requests")).isZero(); server.verify();
    }

    private void action(String action, UUID target) {
        String suffix = switch (action) { case "request-create.action" -> ""; case "request-draft-update.action" -> "/draft";
            case "request-submit.action" -> "/submit"; default -> "/information-response"; };
        http.setMethod(action.equals("request-draft-update.action") ? "PUT" : "POST");
        http.setRequestURI(action.equals("request-create.action") ? "/v1/requests" : "/v1/requests/" + target + suffix);
        String route = "route.approvals.work." + action;
        ApprovalDecisionRevisionContext.set("psr-" + "b".repeat(64), OffsetDateTime.now().plusSeconds(55), "ctx", "scope", route, "110");
        ApprovalPilotAuthorizationContext.set(List.of(new ApprovalPilotPepRegistry.RouteAuthority(route, "ACTION", "full-work", false,
                action.equals("request-create.action") ? Set.of() : Set.of("predicate.approval.own-request.v1"), null, null, null, false, null, null)));
    }
    private void sourcePermissions(boolean allowed) {
        var permissions = new java.util.HashSet<>(PERMISSIONS);
        if (allowed) permissions.add(ApprovalFormUserCurrentAuthority.SOURCE_VIEW);
        ApprovalRequestContext.set(99L, 42L, null, "Owner", Set.of("APPROVAL_OPERATOR"), permissions);
        when(identities.require(42, 99)).thenReturn(new ApprovalIdentityDirectory.Subject(42L, 99L, null, null, "Owner", null, null,
                "ACTIVE", List.of("APPROVAL_OPERATOR"), List.copyOf(permissions)));
    }
    private void response(boolean revoke) {
        server.expect(requestTo("http://auth.test/internal/auth/v1/approval-form-user-directory/resolve")).andRespond(request -> {
            try {
                assertThat(request.getHeaders().containsKey("Authorization")).isFalse();
                JsonNode body = mapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
                var jwt = SignedJWT.parse(body.get("sourceProof").textValue());
                assertThat(mapper.readTree(jwt.getPayload().toString()).size()).isEqualTo(26);
                assertThat(jwt.getJWTClaimsSet().getStringClaim("purpose")).isEqualTo("APPROVAL_FORM_REFERENCE_RESOLVE_V1");
                assertThat(jwt.getJWTClaimsSet().getStringClaim("mutationPath")).isEqualTo(http.getRequestURI());
                assertThat(jwt.getJWTClaimsSet().getStringClaim("routeContractKey")).isEqualTo(ApprovalDecisionRevisionContext.current().orElseThrow().routeContractKey());
                if (revoke) sourcePermissions(false);
                return withSuccess(json(Map.of("proofId", jwt.getJWTClaimsSet().getJWTID(), "requestDigest", jwt.getJWTClaimsSet().getStringClaim("requestDigest"),
                        "authRevision", "auth-current", "policyRevision", "policy-current", "people", List.of(Map.of("tenantId", 42, "subjectId", 123,
                                "personPublicId", person.toString(), "displayName", "Kim", "identityPlane", "TENANT", "status", "ACTIVE")))), MediaType.APPLICATION_JSON).createResponse(request);
            } catch (Exception exception) { throw new AssertionError(exception); }
        });
    }
    private void advanceVersion() {
        Map<String, Object> changed = new LinkedHashMap<>(schema);
        changed.put("fields", List.of(field("summary", "TEXTAREA"), field("reviewer", "USER")));
        var compiled = new ApprovalFormSchemaV2Compiler().compile(changed);
        jdbc.update("INSERT INTO apr_form_versions(form_version_id,tenant_id,form_id,version_number,schema_payload,schema_sha256,lifecycle_state,published_by) "
                + "VALUES (?,42,?,2,?::jsonb,?,'PUBLISHED',100)", UUID.randomUUID(), formId, compiled.canonicalJson(), compiled.sha256());
        jdbc.update("UPDATE apr_forms SET current_version=2 WHERE form_id=?", formId);
    }
    private ApprovalDtos.RequestSummary createPinnedRequest(String key) {
        action("request-create.action", null); response(false);
        var result = tx(() -> drafts.create(new ApprovalDtos.CreateRequest(workflowId, formId, "User request", "Summary", "NORMAL", values()), key, "corr"));
        server.verify(); server.reset();
        return result;
    }
    private Map<String, Object> values() { return Map.of("summary", "Summary", "mode", "REVIEW", "reviewer", person.toString()); }
    private Map<String, Object> field(String key, String type) { return Map.of("key", key, "labelKo", "항목", "labelEn", "Field", "type", type); }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception exception) { throw new AssertionError(exception); } }
}
