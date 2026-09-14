package com.dwp.services.approval.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.services.approval.ApprovalServerApplication;
import com.dwp.services.approval.attachment.ApprovalAttachmentDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(classes=ApprovalServerApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"springdoc.api-docs.enabled=true","dwp.observability.api-history.enabled=false","otel.sdk.disabled=true"})
class ApprovalAttachmentOpenApiPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine")
            .withLabel("dwp.approval.owner","attachment-actual-openapi");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",PG::getJdbcUrl);
        registry.add("spring.datasource.username",PG::getUsername);
        registry.add("spring.datasource.password",PG::getPassword);
    }
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;
    @Autowired RequestMappingHandlerAdapter handlers;

    static Stream<Arguments> reads() {
        return Stream.of(
                Arguments.of("/v1/attachment-uploads/{uploadId}","ApprovalAttachmentUpload",Set.of("uploadId","attachmentId","state","version","reason","avState","passiveContentState","sizeBytes","sha256","expiresAt")),
                Arguments.of("/v1/admin/attachments/policy","ApprovalAttachmentPolicy",Set.of("policyId","resourceSetKey","version","published","pending","providerReadiness","publishedRevision","pendingRevision","pendingMakerUserId","publishedRulesSha256","pendingRulesSha256","downloadReadiness","publishEligible","publishReason")),
                Arguments.of("/v1/requests/{requestId}/attachments","ApprovalAttachmentAttachments",Set.of("manifest","policyId","policyVersion","upload","download","maxFileBytes","maxFiles","maxRequestBytes","allowedMediaTypes","evaluatedAt")));
    }
    @ParameterizedTest @MethodSource("reads")
    void actualGetDataHasItsUniqueClosedExactSchema(String path,String name,Set<String> fields) throws Exception {
        JsonNode doc=document();JsonNode data=data(doc,path);
        assertThat(data.path("$ref").asText()).isEqualTo("#/components/schemas/"+name);
        assertClosed(resolve(doc,data),fields);
    }
    @Test void allNestedTypedRecordsStayClosedWithoutChangingTheirFields() throws Exception {
        JsonNode doc=document();
        for(Class<?> record:ApprovalAttachmentDtos.class.getDeclaredClasses()) {
            if(!record.isRecord() || record==ApprovalAttachmentDtos.Prepared.class) continue;
            String name="ApprovalAttachment"+record.getSimpleName();
            Set<String> fields=new HashSet<>();Arrays.stream(record.getRecordComponents()).forEach(field->fields.add(field.getName()));
            assertClosed(doc.path("components").path("schemas").path(name),fields);
        }
        JsonNode schemas=doc.path("components").path("schemas");
        assertThat(schemas.path("ApprovalAttachmentPolicy").path("properties").path("published").path("$ref").asText()).endsWith("/ApprovalAttachmentRules");
        assertThat(schemas.path("ApprovalAttachmentPolicy").path("properties").path("pending").path("$ref").asText()).endsWith("/ApprovalAttachmentRules");
        JsonNode manifest=schemas.path("ApprovalAttachmentAttachments").path("properties").path("manifest");
        assertThat(manifest.path("$ref").asText()).endsWith("/ApprovalAttachmentManifest");
        assertThat(schemas.path("ApprovalAttachmentManifest").path("properties").path("items").path("items").path("$ref").asText()).endsWith("/ApprovalAttachmentItem");
        for(String tool:Set.of("upload","download"))
            assertThat(schemas.path("ApprovalAttachmentAttachments").path("properties").path(tool).path("$ref").asText()).endsWith("/ApprovalAttachmentTool");
    }
    @Test void everyRecordIncludingInternalPreparedUsesAUniqueExplicitName() {
        Set<String> names=new HashSet<>();int count=0;
        for(Class<?> record:ApprovalAttachmentDtos.class.getDeclaredClasses()) {
            if(!record.isRecord()) continue;count++;
            Schema annotation=record.getAnnotation(Schema.class);assertThat(annotation).isNotNull();
            assertThat(annotation.name()).isEqualTo("ApprovalAttachment"+record.getSimpleName());
            assertThat(annotation.additionalProperties()).isEqualTo(Schema.AdditionalPropertiesValue.FALSE);
            assertThat(names.add(annotation.name())).isTrue();
        }
        assertThat(count).isEqualTo(16);
    }
    @Test void actualInitializationBodyIsClosedRequiredTwoFieldsAndReturnsUnchangedPolicyFourteen() throws Exception {
        JsonNode doc=document();JsonNode operation=doc.path("paths").path("/v1/admin/attachments/policies").path("post");
        JsonNode body=operation.path("requestBody");assertThat(body.path("required").asBoolean()).isTrue();
        JsonNode input=resolve(doc,body.path("content").path("application/json").path("schema"));
        assertClosed(input,Set.of("expectedAbsent","idempotencyKey"));
        Set<String> required=new HashSet<>();input.path("required").forEach(field->required.add(field.asText()));
        assertThat(required).containsExactlyInAnyOrder("expectedAbsent","idempotencyKey");
        JsonNode content=operation.path("responses").path("200").path("content");
        assertThat(content.size()).isEqualTo(1);
        JsonNode data=resolve(doc,content.elements().next().path("schema")).path("properties").path("data");
        assertThat(data.path("$ref").asText()).isEqualTo("#/components/schemas/ApprovalAttachmentPolicy");
        assertThat(resolve(doc,data).path("properties").size()).isEqualTo(14);
    }
    @Test void actualDownloadIsByteContentNotAnObjectProjection() throws Exception {
        JsonNode response=document().path("paths").path("/v1/attachment-downloads/{grantId}/content").path("get").path("responses").path("200");
        assertThat(fields(response.path("content"))).containsExactly("application/octet-stream");
        JsonNode schema=response.path("content").path("application/octet-stream").path("schema");
        assertThat(schema.path("type").asText()).isEqualTo("string");
        assertThat(schema.path("format").asText()).isEqualTo("binary");
        assertThat(schema.has("properties")).isFalse();assertThat(schema.has("$ref")).isFalse();
    }
    static Stream<Class<?>> strictInputs() {
        return Arrays.stream(ApprovalAttachmentDtos.class.getDeclaredClasses())
                .filter(Class::isRecord).filter(ApprovalAttachmentDtos.Strict.class::isAssignableFrom);
    }
    @ParameterizedTest @MethodSource("strictInputs")
    void actualMvcConfiguredMapperRejectsUnknownInputFields(Class<?> input) {
        assertThat(handlers.getMessageConverters().stream().filter(MappingJackson2HttpMessageConverter.class::isInstance)
                .map(MappingJackson2HttpMessageConverter.class::cast).map(MappingJackson2HttpMessageConverter::getObjectMapper))
                .contains(mapper);
        assertThatThrownBy(()->mapper.readValue("{\"unexpectedAttachmentField\":true}",input))
                .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown attachment field: unexpectedAttachmentField");
    }
    @Test void actualMapperAlsoRejectsUnknownNestedPolicyRules() {
        assertThatThrownBy(()->mapper.readValue("{\"expectedVersion\":0,\"idempotencyKey\":\"policy\",\"rules\":{\"unexpectedAttachmentField\":true}}",ApprovalAttachmentDtos.SavePolicy.class))
                .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown attachment field: unexpectedAttachmentField");
    }
    private JsonNode document() throws Exception {
        var response=rest.getForEntity("/v3/api-docs",String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);return mapper.readTree(response.getBody());
    }
    private JsonNode data(JsonNode doc,String path) {
        JsonNode content=doc.path("paths").path(path).path("get").path("responses").path("200").path("content");
        assertThat(content.size()).isEqualTo(1);
        JsonNode envelope=resolve(doc,content.elements().next().path("schema"));
        return envelope.path("properties").path("data");
    }
    private JsonNode resolve(JsonNode doc,JsonNode value) {
        return value.has("$ref")?doc.path("components").path("schemas").path(value.path("$ref").asText().substring("#/components/schemas/".length())):value;
    }
    private void assertClosed(JsonNode schema,Set<String> expected) {
        assertThat(schema.isObject()).isTrue();
        assertThat(schema.path("additionalProperties").isBoolean()).isTrue();
        assertThat(schema.path("additionalProperties").booleanValue()).isFalse();
        assertThat(fields(schema.path("properties"))).isEqualTo(expected);
    }
    private Set<String> fields(JsonNode node) {
        Set<String> fields=new HashSet<>();node.fieldNames().forEachRemaining(fields::add);return fields;
    }
}
