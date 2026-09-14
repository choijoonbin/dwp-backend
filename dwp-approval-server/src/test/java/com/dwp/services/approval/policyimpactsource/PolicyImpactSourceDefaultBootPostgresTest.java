package com.dwp.services.approval.policyimpactsource;

import static org.assertj.core.api.Assertions.*;

import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.ApprovalServerApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = ApprovalServerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"springdoc.api-docs.enabled=true", "dwp.observability.api-history.enabled=false", "otel.sdk.disabled=true",
                "dwp.approval.policy-impact.source.enabled=false"})
class PolicyImpactSourceDefaultBootPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withLabel("dwp.approval.owner", "apr15-policyimpact-default-boot");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", PG::getJdbcUrl); properties.add("spring.datasource.username", PG::getUsername);
        properties.add("spring.datasource.password", PG::getPassword);
    }
    @Autowired ConfigurableApplicationContext context;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Test void actualWebBootExposesTheRequiredTypedRouteButDoesNotInstantiateDisabledCredentials() throws Exception {
        assertThat(context.getBean(PolicyImpactController.class)).isNotNull();
        var response = rest.getForEntity("/v3/api-docs", String.class); assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var document = json.readTree(response.getBody()); var operation = document.path("paths").path("/v1/admin/policies/{policyId}/impact").path("get");
        assertThat(operation.isObject()).isTrue();
        var params = operation.path("parameters"); var required = java.util.stream.StreamSupport.stream(params.spliterator(), false)
                .filter(parameter -> "expectedVersion".equals(parameter.path("name").asText())).toList();
        assertThat(required).hasSize(1); assertThat(required.getFirst().path("required").asBoolean()).isTrue();
        assertThat(required.getFirst().path("schema").path("type").asText()).isEqualTo("integer");
        assertThat(document.path("paths").has(PolicyImpactSourceProtocol.PATH)).isFalse();
        assertThat(context.getBeanFactory().containsSingleton("policyImpactSourceKeys")).isFalse();
        assertThat(context.getBeanFactory().containsSingleton("authApprovalPolicyImpactAuthorityClient")).isFalse();
    }
    @Test void defaultClosedPreviewMakesNoSourceReadsOrWritesAndKeepsActualDatabaseHealthy() {
        long before = jdbc.queryForObject("SELECT count(*) FROM sys_audit_outbox", Long.class);
        assertThatThrownBy(() -> context.getBean(PolicyImpactSourceService.class).preview(null, null, 0)).isInstanceOf(BaseException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_audit_outbox", Long.class)).isEqualTo(before);
        assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(context.getBeanFactory().containsSingleton("policyImpactSourceProofIssuer")).isFalse();
    }
}
