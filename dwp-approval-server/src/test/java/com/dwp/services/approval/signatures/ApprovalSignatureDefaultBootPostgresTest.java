package com.dwp.services.approval.signatures;

import static org.assertj.core.api.Assertions.*;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.ApprovalServerApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes=ApprovalServerApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"springdoc.api-docs.enabled=true","dwp.observability.api-history.enabled=false","otel.sdk.disabled=true",
                "dwp.approval.internal-signatures.enabled=true","dwp.approval.internal-signatures.source.enabled=false"})
class ApprovalSignatureDefaultBootPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine")
            .withLabel("dwp.approval.owner","apr16b-signature-default-boot");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties){
        properties.add("spring.datasource.url",PG::getJdbcUrl);properties.add("spring.datasource.username",PG::getUsername);
        properties.add("spring.datasource.password",PG::getPassword);
    }
    @Autowired ConfigurableApplicationContext context;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Test void actualHttpSpringdocPublishesClosedNineRequiredMetadataFieldsAndOnlyPublicReceiptGet() throws Exception {
        var response=rest.getForEntity("/v3/api-docs",String.class);assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var document=json.readTree(response.getBody());var schema=document.path("components").path("schemas").path("ApprovalSignatureCommandReceiptMetadata");
        assertThat(schema.path("additionalProperties").isBoolean()).isTrue();assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        Set<String> expected=Set.of("receiptId","originalOperation","requestId","signatureRequestId","committedAt","eventSequence","resultState","resultVersion","sourceCurrent");
        var properties=new HashSet<String>();schema.path("properties").fieldNames().forEachRemaining(properties::add);assertThat(properties).isEqualTo(expected);
        var required=new HashSet<String>();schema.path("required").forEach(field->required.add(field.asText()));assertThat(required).isEqualTo(expected);
        schema.path("properties").forEach(field->assertThat(field.path("nullable").asBoolean()).isFalse());
        var path=document.path("paths").path("/v1/signature-command-receipts/{idempotencyKey}");
        assertThat(path.has("get")).isTrue();assertThat(path.has("post")).isFalse();assertThat(path.has("head")).isFalse();
        var parameters=new HashSet<String>();path.path("get").path("parameters").forEach(parameter->{if("query".equals(parameter.path("in").asText())){
            parameters.add(parameter.path("name").asText());assertThat(parameter.path("required").asBoolean()).isTrue();}});
        assertThat(parameters).isEqualTo(Set.of("originalOperation","targetId","bodySha256"));
        assertThat(document.path("paths").has(ApprovalSignatureSourceExchange.PATH)).isFalse();
    }
    @Test void disabledProductionSourceAndMetadataGetDoNotProvisionKeysOrAppendAnythingAndKeepBootHealthy(){
        long before=jdbc.queryForObject("SELECT count(*) FROM sys_audit_outbox",Long.class);
        assertThatThrownBy(()->context.getBean(ApprovalSignatureCommandReceiptService.class).read(null)).isInstanceOf(BaseException.class);
        assertThatThrownBy(()->context.getBean(ApprovalSignatureAuthority.Source.class).currentSignedAssertion(null,null)).isInstanceOf(BaseException.class);
        assertThat(context.getBeanFactory().containsSingleton("approvalSignatureCommandReceiptAuthority")).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_audit_outbox",Long.class)).isEqualTo(before);
        assertThat(rest.getForEntity("/actuator/health",String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    @Test void actualHttpSpringdocClosesAllNativeSigningOutputAndCommandObjectGraphs() throws Exception {
        var response=rest.getForEntity("/v3/api-docs",String.class);assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var schemas=json.readTree(response.getBody()).path("components").path("schemas");
        var expected=java.util.Map.ofEntries(
                java.util.Map.entry("Context",8),java.util.Map.entry("Ceremony",13),java.util.Map.entry("Receipt",4),
                java.util.Map.entry("SourcePin",25),java.util.Map.entry("Artifact",5),java.util.Map.entry("Terms",6),
                java.util.Map.entry("Evidence",8),java.util.Map.entry("Event",5),java.util.Map.entry("Audit",2),
                java.util.Map.entry("Create",5),java.util.Map.entry("Consent",8),java.util.Map.entry("Sign",4),java.util.Map.entry("Cancel",3));
        expected.forEach((name,count)->{
            var schema=schemas.path("ApprovalSignature"+name);assertThat(schema.path("properties").size()).as(name).isEqualTo(count);
            assertThat(schema.path("additionalProperties").isBoolean()).as(name).isTrue();assertThat(schema.path("additionalProperties").asBoolean()).as(name).isFalse();
            int optional=name.equals("Ceremony")?2:name.equals("SourcePin")?1:0;assertThat(schema.path("required").size()).as(name).isEqualTo(count-optional);
        });
        assertThat(schemas.path("ApprovalSignatureContext").path("properties").path("source").path("$ref").asText()).isEqualTo("#/components/schemas/ApprovalSignatureSourcePin");
        assertThat(schemas.path("ApprovalSignatureReceipt").path("properties").path("ceremony").path("$ref").asText()).isEqualTo("#/components/schemas/ApprovalSignatureCeremony");
        assertNullUnion(schemas.path("ApprovalSignatureSourcePin").path("properties").path("signingKeySha256"));
        assertNullUnion(schemas.path("ApprovalSignatureCeremony").path("properties").path("consentReceiptId"));
        var evidence=schemas.path("ApprovalSignatureCeremony").path("properties").path("evidence");
        assertThat(evidence.has("$ref")).as("Union cannot retain a conjunctive root ref: %s",evidence).isFalse();
        assertThat(evidence.has("type")).as("Union cannot constrain the root to object or null: %s",evidence).isFalse();
        assertThat(evidence.path("oneOf").size()).as("Evidence must have two explicit alternatives: %s",evidence).isEqualTo(2);
        assertThat(evidence.path("oneOf").get(0).path("$ref").asText()).isEqualTo("#/components/schemas/ApprovalSignatureEvidence");
        var nullBranch=evidence.path("oneOf").get(1);
        if(nullBranch.has("$ref"))nullBranch=schemas.path(nullBranch.path("$ref").asText().substring("#/components/schemas/".length()));
        assertThat(nullBranch).isEqualTo(json.readTree("{\"type\":\"null\"}"));
    }
    private static void assertNullUnion(com.fasterxml.jackson.databind.JsonNode schema){
        // OpenAPI 3.1 expresses null through JSON Schema unions, not the old nullable keyword.
        assertThat(schema.has("$ref")).as("A ref sibling is not a nullable scalar union: %s",schema).isFalse();
        boolean allowed=hasNullType(schema);
        for(String keyword:java.util.List.of("anyOf","oneOf"))for(var alternative:schema.path(keyword))allowed|=hasNullType(alternative);
        assertThat(allowed).as("Actual legal null union: %s",schema).isTrue();
    }
    private static boolean hasNullType(com.fasterxml.jackson.databind.JsonNode schema){
        var type=schema.path("type");if(type.isTextual())return "null".equals(type.asText());
        if(type.isArray())for(var item:type)if("null".equals(item.asText()))return true;return false;
    }
    @Test void actualHttpEvidenceSchemaAcceptsNullAndNativeCryptoObjectAndRejectsTheOriginalEmptyIntersection() throws Exception {
        var response=rest.getForEntity("/v3/api-docs",String.class);assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var document=json.readTree(response.getBody());assertThat(document.path("openapi").asText()).startsWith("3.1.");
        var schemas=document.path("components").path("schemas");
        var evidenceSchema=schemas.path("ApprovalSignatureCeremony").path("properties").path("evidence");
        var key=new RSAKeyGenerator(2048).keyID("approval-self-attestation:http-schema").keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();
        var canonical=new ApprovalSignatureCanonical(json);
        var signer=new ApprovalSignatureEvidenceSigner(key,Set.of(),true,canonical);
        var nativeEvidence=signer.sign(UUID.randomUUID(),99,"a".repeat(64),"b".repeat(64),UUID.randomUUID(),"c".repeat(64),Instant.now());
        assertThat(JWSObject.parse(nativeEvidence.compactJws()).verify(new RSASSAVerifier(key.toPublicJWK()))).isTrue();
        var evidence=json.readTree(canonical.json(nativeEvidence));
        assertThat(evidence.size()).isEqualTo(8);
        var factory=JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        // Keep the emitted component graph and local refs intact; only select the property as the validation root.
        var validationRoot=json.createObjectNode();validationRoot.setAll((com.fasterxml.jackson.databind.node.ObjectNode)evidenceSchema);
        validationRoot.set("components",document.path("components"));
        var validator=factory.getSchema(validationRoot);
        assertThat(validator.validate(json.nullNode())).as("Null must be a real union alternative: %s",evidenceSchema).isEmpty();
        assertThat(validator.validate(evidence)).as("The full native crypto evidence must satisfy the same emitted union: %s",evidenceSchema).isEmpty();
        assertThat(validator.validate(json.createObjectNode())).isNotEmpty();
        var missing=evidence.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)missing).remove("compactJws");assertThat(validator.validate(missing)).isNotEmpty();
        var extra=evidence.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)extra).put("unexpected",true);assertThat(validator.validate(extra)).isNotEmpty();
        var wrongType=evidence.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)wrongType).put("artifactSha256",false);assertThat(validator.validate(wrongType)).isNotEmpty();
        assertThat(validator.validate(json.getNodeFactory().textNode("not-evidence"))).isNotEmpty();
        var originalIntersection=json.createObjectNode().put("$ref","#/components/schemas/ApprovalSignatureEvidence").put("type","null");
        originalIntersection.set("components",document.path("components"));
        var impossible=factory.getSchema(originalIntersection);
        assertThat(impossible.validate(json.nullNode())).as("The original $ref AND null schema rejects null").isNotEmpty();
        assertThat(impossible.validate(evidence)).as("The original $ref AND null schema rejects a valid native object").isNotEmpty();
        assertNativeCeremonyGraph(factory,document,nativeEvidence,signer.keySha256(),canonical);
    }
    private void assertNativeCeremonyGraph(JsonSchemaFactory factory,com.fasterxml.jackson.databind.JsonNode document,
            ApprovalSignatureDtos.Evidence evidence,String keySha256,ApprovalSignatureCanonical canonical) throws Exception {
        // These are DTO/crypto specimens, not an installed authority or a claimed business transaction.
        var request=UUID.randomUUID();var at=evidence.attestedAt();var expires=at.plusSeconds(600);
        var source=new ApprovalSignatureDtos.SourcePin(request,5,1,"a".repeat(64),UUID.randomUUID(),"b".repeat(64),
                UUID.randomUUID(),"c".repeat(64),"signature-schema-test-rs","d".repeat(64),UUID.randomUUID(),1,1,"e".repeat(64),
                UUID.randomUUID(),1,1,"f".repeat(64),UUID.randomUUID(),1,"a".repeat(64),99,"self-attestation-renderer-v1",evidence.artifactSha256(),keySha256);
        var artifact=new ApprovalSignatureDtos.Artifact(source.rendererVersion(),"application/json",evidence.artifactSha256(),2,"{}");
        var terms=new ApprovalSignatureDtos.Terms("SELF_ATTESTATION_TERMS",1,"b".repeat(64),"en","Internal self-attestation only.",expires);
        var root=json.createObjectNode().put("$ref","#/components/schemas/ApprovalSignatureCeremony");root.set("components",document.path("components"));
        var validator=factory.getSchema(root);
        var pending=new ApprovalSignatureDtos.Ceremony(UUID.randomUUID(),request,ApprovalSignatureDtos.SignerKind.SELF_ATTESTATION,
                ApprovalSignatureDtos.State.AWAITING_CONSENT,0,source,evidence.sourceDigest(),artifact,terms,expires,null,null,"NOT_WORM_VERIFIED");
        assertThat(validator.validate(json.readTree(canonical.json(pending)))).as("Full pending native ceremony with null evidence").isEmpty();
        var attested=new ApprovalSignatureDtos.Ceremony(pending.signatureRequestId(),request,pending.signerKind(),ApprovalSignatureDtos.State.ATTESTED,
                2,source,evidence.sourceDigest(),artifact,terms,expires,UUID.randomUUID(),evidence,"NOT_WORM_VERIFIED");
        assertThat(validator.validate(json.readTree(canonical.json(attested)))).as("Full attested native ceremony with real crypto evidence").isEmpty();
    }
}
