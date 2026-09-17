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
    private static final Map<String, Set<String>> FIELDS = Map.ofEntries(
            Map.entry("ApprovalFormLifecycleMetadataInput", fields("categoryId nameKo nameEn descriptionKo descriptionEn ownerGroupRef formKind")),
            Map.entry("ApprovalFormLifecycleBranch", fields("expectedFormRevision expectedWorkspaceRevision")),
            Map.entry("ApprovalFormLifecycleUpdateWorkingDraft", fields("draftFormVersionId expectedFormRevision expectedWorkspaceRevision schema metadata defaultWorkflowId")),
            Map.entry("ApprovalFormLifecycleAvailabilityChange", fields("expectedFormRevision expectedWorkspaceRevision")),
            Map.entry("ApprovalFormLifecyclePublishReviewed", fields("draftFormVersionId basePublishedVersionId expectedFormRevision expectedWorkspaceRevision schemaSha256 reviewContentDigest reviewRequestId expectedReviewRequestVersion reviewComment")),
            Map.entry("ApprovalFormLifecycleVersion", fields("formVersionId versionNumber lifecycleState sourceVersionId basePublishedVersionId schema schemaSha256 metadata route materialDigest metadataProvenance capturedAt capturedBy createdAt createdBy publishedAt publishedBy")),
            Map.entry("ApprovalFormLifecycleWorkspace", fields("formId formRevision workspaceRevision catalogAvailability published workingDraft lastEditorUserId catalogPolicyEligible observedAt")),
            Map.entry("ApprovalFormLifecycleReview", fields("formId formRevision workspaceRevision draftFormVersionId basePublishedVersionId schemaSha256 reviewContentDigest makerUserId lastEditorUserId independentCheckerEligible authorityValidUntil")),
            Map.entry("ApprovalFormLifecycleChange", fields("path before after")),
            Map.entry("ApprovalFormLifecycleDiff", fields("fromVersionId toVersionId fromSchemaSha256 toSchemaSha256 changes complete fromMetadataProvenance toMetadataProvenance")),
            Map.entry("ApprovalFormLifecycleHistory", fields("versions mayBeTruncated")),
            Map.entry("ApprovalFormPublishReviewRequestInput", fields("draftFormVersionId basePublishedVersionId expectedFormRevision expectedWorkspaceRevision schemaSha256 reviewerUserId reviewerPersonPublicId expectedReviewRequestId expectedReviewRequestVersion reason")),
            Map.entry("ApprovalFormPublishReviewRejectInput", fields("expectedFormRevision expectedWorkspaceRevision expectedReviewRequestVersion reason")),
            Map.entry("ApprovalFormPublishReviewRequest", fields("reviewRequestId formId draftFormVersionId basePublishedFormVersionId status version makerUserId lastEditorUserId reviewerUserId reviewerPersonPublicId formRevision workspaceRevision schemaSha256 reviewContentDigest requestReason requestedAt decidedAt decidedBy decisionReason")),
            Map.entry("ApprovalFormPublishReviewRequestState", fields("request")),
            Map.entry("ApprovalFormPublishReviewCandidate", fields("userId personPublicId displayName email jobTitle")),
            Map.entry("ApprovalFormPublishReviewCandidates", fields("candidates mayBeTruncated decisionRevision authorityValidUntil")),
            Map.entry("ApprovalFormPublishReviewQueueItem", fields("request formKey formNameKo formNameEn")),
            Map.entry("ApprovalFormPublishReviewQueue", fields("items mayBeTruncated generatedAt")));
    private static final Map<String, String> READS = Map.of(
            "/v1/admin/forms/{formId}/working-draft", "ApprovalFormLifecycleWorkspace",
            "/v1/admin/forms/{formId}/versions", "ApprovalFormLifecycleHistory",
            "/v1/admin/forms/{formId}/versions/{formVersionId}", "ApprovalFormLifecycleVersion",
            "/v1/admin/forms/{formId}/diff", "ApprovalFormLifecycleDiff",
            "/v1/admin/forms/{formId}/publish-review", "ApprovalFormLifecycleReview",
            "/v1/admin/forms/publish-review-candidates", "ApprovalFormPublishReviewCandidates",
            "/v1/admin/forms/publish-review-requests", "ApprovalFormPublishReviewQueue",
            "/v1/admin/forms/{formId}/publish-review-request", "ApprovalFormPublishReviewRequestState");
    private static final Map<String, WriteContract> WRITES = Map.of(
            "/v1/admin/forms/{formId}/publish-review-request",
            new WriteContract("ApprovalFormPublishReviewRequestInput", "ApprovalFormPublishReviewRequest"),
            "/v1/admin/forms/{formId}/publish-review-requests/{requestId}/reject",
            new WriteContract("ApprovalFormPublishReviewRejectInput", "ApprovalFormPublishReviewRequest"));
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
    @Test void allNineteenFormRecordsHaveUniqueClosedComponentsWithExactFields() {
        assertThat(FIELDS).hasSize(19);
        for (var record : FIELDS.entrySet()) closed(component(record.getKey()), record.getValue());
    }
    @Test void eightActualGetResponseEnvelopesReferenceTheirOwnDistinctFormModels() {
        var refs = new HashSet<String>();
        for (var read : READS.entrySet()) {
            var payload = payload(read.getKey()); String ref = payload.path("$ref").asText();
            assertThat(ref).isEqualTo("#/components/schemas/" + read.getValue());
            assertThat(refs.add(ref)).isTrue(); closed(resolve(payload), FIELDS.get(read.getValue()));
        }
        assertThat(refs).hasSize(8);
    }
    @Test void publishReviewWritesUseTheirOwnExactClosedRequestAndResponseModels() {
        for (var write : WRITES.entrySet()) {
            var operation = document.path("paths").path(write.getKey()).path("post");
            var request = operation.path("requestBody").path("content").elements().next().path("schema");
            assertThat(request.path("$ref").asText())
                    .isEqualTo("#/components/schemas/" + write.getValue().request());
            closed(resolve(request), FIELDS.get(write.getValue().request()));
            var response = payload(write.getKey(), "post");
            assertThat(response.path("$ref").asText())
                    .isEqualTo("#/components/schemas/" + write.getValue().response());
            closed(resolve(response), FIELDS.get(write.getValue().response()));
        }
    }
    @Test void policySemanticDiffAndFormVersionDiffCannotCollide() {
        var formDiff = resolve(payload("/v1/admin/forms/{formId}/diff"));
        closed(formDiff, FIELDS.get("ApprovalFormLifecycleDiff"));
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
        var version = component("ApprovalFormLifecycleVersion").path("properties");
        for (String name : List.of("schema", "metadata", "route")) open(version.path(name));
        open(component("ApprovalFormLifecycleUpdateWorkingDraft").path("properties").path("schema"));
        var changes = component("ApprovalFormLifecycleChange").path("properties"); open(changes.path("before")); open(changes.path("after"));
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
        assertThat(keys(data)).isEqualTo(FIELDS.get("ApprovalFormLifecycleDiff"));
        assertThat(data.path("fromVersionId").asText()).isEqualTo(from.toString());
        assertThat(data.path("changes").get(0).path("before")).isEqualTo(json.valueToTree(before));
        assertThat(data.path("changes").get(0).path("after")).isEqualTo(json.valueToTree(after));
        closed(resolve(payload("/v1/admin/forms/{formId}/diff")), keys(data));
    }
    private JsonNode payload(String path) {
        return payload(path, "get");
    }
    private JsonNode payload(String path, String method) {
        var content = document.path("paths").path(path).path(method).path("responses").path("200").path("content");
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
    private record WriteContract(String request, String response) { }
}
