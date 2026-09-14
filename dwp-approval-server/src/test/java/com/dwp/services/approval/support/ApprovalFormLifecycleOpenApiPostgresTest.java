package com.dwp.services.approval.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.approval.ApprovalServerApplication;
import com.dwp.services.approval.forms.ApprovalFormLifecycleDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = ApprovalServerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"springdoc.api-docs.enabled=true", "dwp.observability.api-history.enabled=false", "otel.sdk.disabled=true",
                "dwp.approval.policy-impact.source.enabled=false"})
class ApprovalFormLifecycleOpenApiPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withLabel("dwp.approval.owner", "apr12-forms-openapi-collision");
    private static final String PREFIX = "ApprovalFormLifecycle";
    private static final Map<String, Set<String>> FIELDS = Map.ofEntries(
            Map.entry("MetadataInput", fields("categoryId nameKo nameEn descriptionKo descriptionEn ownerGroupRef formKind")),
            Map.entry("Branch", fields("expectedFormRevision expectedWorkspaceRevision")),
            Map.entry("UpdateWorkingDraft", fields("draftFormVersionId expectedFormRevision expectedWorkspaceRevision schema metadata defaultWorkflowId")),
            Map.entry("AvailabilityChange", fields("expectedFormRevision expectedWorkspaceRevision")),
            Map.entry("PublishReviewed", fields("draftFormVersionId basePublishedVersionId expectedFormRevision expectedWorkspaceRevision schemaSha256 reviewContentDigest")),
            Map.entry("Version", fields("formVersionId versionNumber lifecycleState sourceVersionId basePublishedVersionId schema schemaSha256 metadata route materialDigest metadataProvenance capturedAt capturedBy createdAt createdBy publishedAt publishedBy")),
            Map.entry("Workspace", fields("formId formRevision workspaceRevision catalogAvailability published workingDraft lastEditorUserId catalogPolicyEligible observedAt")),
            Map.entry("Review", fields("formId formRevision workspaceRevision draftFormVersionId basePublishedVersionId schemaSha256 reviewContentDigest makerUserId lastEditorUserId independentCheckerEligible authorityValidUntil")),
            Map.entry("Change", fields("path before after")),
            Map.entry("Diff", fields("fromVersionId toVersionId fromSchemaSha256 toSchemaSha256 changes complete fromMetadataProvenance toMetadataProvenance")),
            Map.entry("History", fields("versions mayBeTruncated")));
    private static final Map<String, String> READS = Map.of(
            "/v1/admin/forms/{formId}/working-draft", "Workspace",
            "/v1/admin/forms/{formId}/versions", "History",
            "/v1/admin/forms/{formId}/versions/{formVersionId}", "Version",
            "/v1/admin/forms/{formId}/diff", "Diff",
            "/v1/admin/forms/{formId}/publish-review", "Review");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl); registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
    }
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper json;
    private JsonNode document;
    @BeforeEach void actualDocument() throws Exception {
        var response = rest.getForEntity("/v3/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        document = json.readTree(response.getBody());
    }
    @Test void allElevenFormRecordsHaveUniqueClosedComponentsWithExactFields() {
        assertThat(FIELDS).hasSize(11);
        for (var record : FIELDS.entrySet()) closed(component(PREFIX + record.getKey()), record.getValue());
    }
    @Test void fiveActualGetResponseEnvelopesReferenceTheirOwnDistinctFormModels() {
        var refs = new HashSet<String>();
        for (var read : READS.entrySet()) {
            var payload = payload(read.getKey()); String ref = payload.path("$ref").asText();
            assertThat(ref).isEqualTo("#/components/schemas/" + PREFIX + read.getValue());
            assertThat(refs.add(ref)).isTrue(); closed(resolve(payload), FIELDS.get(read.getValue()));
        }
        assertThat(refs).hasSize(5);
    }
    @Test void policySemanticDiffAndFormVersionDiffCannotCollide() {
        var formDiff = resolve(payload("/v1/admin/forms/{formId}/diff"));
        closed(formDiff, FIELDS.get("Diff"));
        assertThat(formDiff.path("properties").path("changes").path("items").path("$ref").asText())
                .isEqualTo("#/components/schemas/ApprovalFormLifecycleChange");
        var policyPayload = payload("/v1/admin/policies/{policyId}/impact");
        assertThat(policyPayload.path("$ref").asText()).isEqualTo("#/components/schemas/ApprovalPolicyImpactResult");
        var policyDiff = resolve(policyPayload).path("properties").path("semanticDiff").path("items");
        assertThat(policyDiff.path("$ref").asText()).isEqualTo("#/components/schemas/ApprovalPolicyImpactDiff");
        closed(resolve(policyDiff), fields("path kind current proposed"));
        var schemas = document.path("components").path("schemas"); var names = schemas.fieldNames(); int checked = 0;
        while (names.hasNext()) {
            String name = names.next();
            if (name.startsWith("ApprovalPolicyImpact")) { closed(schemas.path(name), keys(schemas.path(name).path("properties"))); checked++; }
        }
        assertThat(checked).isGreaterThanOrEqualTo(8);
    }
    @Test void arbitrarySchemaMetadataRouteMapsAndDiffValuesRemainOpen() {
        var version = component(PREFIX + "Version").path("properties");
        for (String name : List.of("schema", "metadata", "route")) open(version.path(name));
        open(component(PREFIX + "UpdateWorkingDraft").path("properties").path("schema"));
        var changes = component(PREFIX + "Change").path("properties"); open(changes.path("before")); open(changes.path("after"));
        open(component("ApprovalPolicyImpactRules").path("properties").path("rule"));
        var diff = component("ApprovalPolicyImpactDiff").path("properties"); open(diff.path("current")); open(diff.path("proposed"));
    }
    @Test void actualConfiguredMapperKeepsFormDiffEnvelopeAndArbitraryValuesUnchanged() {
        UUID from = UUID.randomUUID(), to = UUID.randomUUID();
        var before = Map.of("custom", List.of(Map.of("amount", "123.456", "arbitrary", true)));
        var after = Map.of("custom", Map.of("another", List.of(1, "value", false)));
        var value = new ApprovalFormLifecycleDtos.Diff(from, to, "a".repeat(64), "b".repeat(64),
                List.of(new ApprovalFormLifecycleDtos.Change("/schema/custom", before, after)), true, "UNRECORDED", "PUBLISH_SNAPSHOT");
        var data = json.valueToTree(ApiResponse.success(value)).path("data");
        assertThat(keys(data)).isEqualTo(FIELDS.get("Diff"));
        assertThat(data.path("fromVersionId").asText()).isEqualTo(from.toString());
        assertThat(data.path("changes").get(0).path("before")).isEqualTo(json.valueToTree(before));
        assertThat(data.path("changes").get(0).path("after")).isEqualTo(json.valueToTree(after));
        closed(resolve(payload("/v1/admin/forms/{formId}/diff")), keys(data));
    }
    private JsonNode payload(String path) {
        var content = document.path("paths").path(path).path("get").path("responses").path("200").path("content");
        assertThat(content.size()).isEqualTo(1); var envelope = resolve(content.elements().next().path("schema"));
        assertThat(envelope.path("type").asText()).isEqualTo("object");
        assertThat(keys(envelope.path("properties"))).contains("data", "success", "status");
        return envelope.path("properties").path("data");
    }
    private JsonNode component(String name) {
        var value = document.path("components").path("schemas").path(name); assertThat(value.isObject()).as(name).isTrue(); return value;
    }
    private JsonNode resolve(JsonNode value) {
        if (!value.has("$ref")) return value;
        String ref = value.path("$ref").asText(); assertThat(ref).startsWith("#/components/schemas/"); return document.at(ref.substring(1));
    }
    private void closed(JsonNode value, Set<String> fields) {
        assertThat(value.path("type").asText()).isEqualTo("object");
        assertThat(value.path("additionalProperties").isBoolean()).isTrue();
        assertThat(value.path("additionalProperties").booleanValue()).isFalse();
        assertThat(keys(value.path("properties"))).isEqualTo(fields);
    }
    private void open(JsonNode value) {
        assertThat(value.isObject()).isTrue();
        assertThat(value.path("additionalProperties").isBoolean() && !value.path("additionalProperties").booleanValue()).isFalse();
    }
    private static Set<String> fields(String names) { return Set.of(names.split(" ")); }
    private static Set<String> keys(JsonNode value) { var keys = new HashSet<String>(); value.fieldNames().forEachRemaining(keys::add); return Set.copyOf(keys); }
}
