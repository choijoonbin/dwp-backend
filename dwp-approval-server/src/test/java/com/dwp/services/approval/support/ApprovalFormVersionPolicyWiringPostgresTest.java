package com.dwp.services.approval.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.dwp.services.approval.ApprovalServerApplication;
import com.dwp.services.approval.forms.ApprovalFormRequestVersionRepository;
import com.dwp.services.approval.forms.ApprovalFormVersionPolicyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(classes=ApprovalServerApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"springdoc.api-docs.enabled=true","dwp.observability.api-history.enabled=false","otel.sdk.disabled=true"})
class ApprovalFormVersionPolicyWiringPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES=new PostgreSQLContainer<>("postgres:16-alpine")
        .withLabel("dwp.approval.owner","apr12-v23-version-policy-wiring");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username",POSTGRES::getUsername);
        registry.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired ApplicationContext context;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    @Test void actualWebBootCreatesSeparateRequestAndPublishedPolicyPorts() {
        assertThat(context.getBean(ApprovalFormRequestVersionRepository.class)).isNotNull();
        assertThat(context.getBean(ApprovalFormVersionPolicyRepository.class)).isNotNull();
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success=true",String.class)).contains("17","19","23");
        var openapi=rest.getForEntity("http://127.0.0.1:"+port+"/v3/api-docs",String.class);
        assertThat(openapi.getStatusCode().value()).isEqualTo(200);
        assertThat(openapi.getBody()).contains("/v1/admin/forms/{formId}/working-draft","/v1/admin/forms/{formId}/versions/{formVersionId}");
    }
}
