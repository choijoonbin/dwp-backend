package com.dwp.services.approval.informationreplay;

import static org.assertj.core.api.Assertions.*;

import com.dwp.core.common.ErrorCode;
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
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes=ApprovalServerApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"springdoc.api-docs.enabled=true","dwp.observability.api-history.enabled=false","otel.sdk.disabled=true",
                "dwp.approval.information-replay.enabled=false"})
class InformationReceiptDefaultBootPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine")
            .withLabel("dwp.approval.owner","workflow-information-receipt-default-boot");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",PG::getJdbcUrl);properties.add("spring.datasource.username",PG::getUsername);
        properties.add("spring.datasource.password",PG::getPassword);
    }
    @Autowired ConfigurableApplicationContext context;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Test void realWebBootPublishesUniqueClosedRequiredBodyAndSevenFieldReceiptWithoutInternalAuthorityPath() throws Exception {
        assertThat(context.getBean(InformationReceiptController.class)).isNotNull();
        var response=rest.getForEntity("/v3/api-docs",String.class);assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var document=json.readTree(response.getBody());var operation=document.path("paths")
                .path("/v1/requests/{requestId}/information-commands/{originalKey}/receipt").path("post");
        assertThat(operation.isObject()).isTrue();assertThat(operation.path("requestBody").path("required").asBoolean()).isTrue();
        assertThat(operation.path("requestBody").path("content").path("application/json").path("schema").path("$ref").asText())
                .isEqualTo("#/components/schemas/ApprovalInformationReceiptBody");
        var schemas=document.path("components").path("schemas");var body=schemas.path("ApprovalInformationReceiptBody");
        var receipt=schemas.path("ApprovalInformationCommandReceipt");
        assertThat(body.path("additionalProperties").isBoolean()).isTrue();assertThat(body.path("additionalProperties").asBoolean()).isFalse();
        assertThat(body.path("properties").size()).isEqualTo(2);assertThat(body.path("required").size()).isEqualTo(2);
        assertThat(receipt.path("additionalProperties").isBoolean()).isTrue();assertThat(receipt.path("additionalProperties").asBoolean()).isFalse();
        assertThat(receipt.path("properties").size()).isEqualTo(7);assertThat(receipt.path("required").size()).isEqualTo(7);
        assertThat(document.path("paths").has(InformationReplayProtocol.PATH)).isFalse();
        assertThat(context.getBeanFactory().containsSingleton("informationReplayRuntime")).isFalse();
    }
    @Test void disabledSourceRejectsBeforeAnyCredentialsOrDatabaseReadsAndKeepsHealthUp() {
        long before=jdbc.queryForObject("SELECT count(*) FROM sys_audit_outbox",Long.class);
        var failure=catchThrowableOfType(()->context.getBean(InformationReceiptFacade.class).read(null,null,null,null),BaseException.class);
        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_audit_outbox",Long.class)).isEqualTo(before);
        assertThat(rest.getForEntity("/actuator/health",String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(context.getBeanFactory().containsSingleton("informationReplayRuntime")).isFalse();
    }
}
