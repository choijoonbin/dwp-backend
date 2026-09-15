package com.dwp.services.approval.security;

import com.dwp.services.approval.ApprovalServerApplication;
import com.dwp.services.approval.documentretention.management.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes=ApprovalServerApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"springdoc.api-docs.enabled=true","dwp.observability.api-history.enabled=false","otel.sdk.disabled=true"})
class ApprovalRetentionDefaultBootPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine")
            .withLabel("dwp.approval.owner","v29-retention-default-boot");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",PG::getJdbcUrl);properties.add("spring.datasource.username",PG::getUsername);
        properties.add("spring.datasource.password",PG::getPassword);
    }
    @Autowired ConfigurableApplicationContext context;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Test void actualDefaultWebBootInstantiatesBothRepositoryProxiesWithoutRuntimeExecutionOrReadProvisioning() {
        assertThat(context.getBean(ApprovalRetentionInventory.class)).isNotNull();
        assertThat(context.getBean(ApprovalRetentionManagementRepository.class)).isNotNull();
        assertThat(context.getBean(ApprovalRetentionManagementService.class)).isNotNull();
        assertThat(context.getBeansOfType(ApprovalRetentionIntentExecutor.class)).isEmpty();
        assertThat(context.getBeansOfType(ApprovalRetentionManagedWorker.class)).isEmpty();
        assertThat(context.getBeansOfType(ApprovalRetentionForeignPort.class)).isEmpty();
        assertThat(context.getBeansOfType(ApprovalRetentionExecutionAuthorityPort.class)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_retention_policy_heads",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM apr_retention_dispatch_intents",Long.class)).isZero();
        assertThat(rest.getForEntity("/actuator/health",String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    @Test void actualOpenApiHasExactElevenOperationsAndUniqueClosedBoundedNestedRules() throws Exception {
        var response=rest.getForEntity("/v3/api-docs",String.class);assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var document=mapper.readTree(response.getBody());var schemas=document.path("components").path("schemas");
        var operations=new TreeSet<String>();document.path("paths").fields().forEachRemaining(e->{
            if(e.getKey().startsWith("/v1/admin/retention/")) e.getValue().fields().forEachRemaining(method->{
                if(Set.of("get","put","post","delete","patch").contains(method.getKey())) operations.add(method.getKey()+" "+e.getKey());
            });
        });
        assertThat(operations).containsExactlyInAnyOrder("get /v1/admin/retention/policy","post /v1/admin/retention/policies",
                "put /v1/admin/retention/policies/{policyId}/draft","post /v1/admin/retention/policies/{policyId}/publish",
                "get /v1/admin/retention/records/{requestId}","post /v1/admin/retention/records/{requestId}/claims","get /v1/admin/retention/claims/{claimId}",
                "get /v1/admin/retention/policy-initialization-commands/{idempotencyKey}",
                "get /v1/admin/retention/policies/{policyId}/draft-commands/{idempotencyKey}",
                "get /v1/admin/retention/policies/{policyId}/publication-commands/{idempotencyKey}",
                "get /v1/admin/retention/records/{requestId}/claim-commands/{idempotencyKey}");
        for(String name:List.of("ApprovalRetentionPublicRules","ApprovalRetentionPolicy","ApprovalRetentionRecord","ApprovalRetentionClaim",
                "ApprovalRetentionInitializePolicy","ApprovalRetentionSavePolicy","ApprovalRetentionPublishPolicy","ApprovalRetentionCreateClaim",
                "ApprovalRetentionCommandReceipt")) {
            JsonNode schema=schemas.path(name);assertThat(schema.isObject()).as(name).isTrue();
            assertThat(schema.path("additionalProperties").isBoolean()).as(name).isTrue();assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        }
        var rules=schemas.path("ApprovalRetentionPublicRules");assertThat(rules.path("properties").size()).isEqualTo(9);assertThat(rules.path("required").size()).isEqualTo(9);
        var max=rules.path("properties").path("maxInventoryRows");assertThat(max.path("minimum").asInt()).isEqualTo(1);assertThat(max.path("maximum").asInt()).isEqualTo(50000);
        assertThat(schemas.path("ApprovalRetentionPolicy").path("properties").path("published").path("$ref").asText()).isEqualTo("#/components/schemas/ApprovalRetentionPublicRules");
        assertThat(schemas.path("ApprovalRetentionPolicy").path("properties").has("objectKey")).isFalse();
        var receipt=schemas.path("ApprovalRetentionCommandReceipt").path("properties");
        assertThat(receipt.size()).isEqualTo(14);
        assertThat(receipt.path("originAuthorityProfile").path("type").asText()).isEqualTo("string");
        assertThat(receipt.path("status").path("enum").toString()).isEqualTo("[\"COMMITTED\"]");
    }
}
