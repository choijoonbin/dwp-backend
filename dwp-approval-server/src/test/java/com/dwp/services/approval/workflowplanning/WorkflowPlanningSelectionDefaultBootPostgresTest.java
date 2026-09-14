package com.dwp.services.approval.workflowplanning;

import static org.junit.jupiter.api.Assertions.*;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.ApprovalServerApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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

/** Actual web/schema and default-closed proof, not installed Source11/Auth planning authority. */
@Testcontainers
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes=ApprovalServerApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"springdoc.api-docs.enabled=true","dwp.observability.api-history.enabled=false","otel.sdk.disabled=true",
                "dwp.approval.workflow-planning.enabled=false"})
class WorkflowPlanningSelectionDefaultBootPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine")
            .withLabel("dwp.approval.owner","workflow-planning-selection-default-boot");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",PG::getJdbcUrl);properties.add("spring.datasource.username",PG::getUsername);
        properties.add("spring.datasource.password",PG::getPassword);
    }
    @Autowired ConfigurableApplicationContext context;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Test void actualPublicGetUsesUniqueClosedRequiredSelectionPolicyAndFormSchemas() throws Exception {
        var response=rest.getForEntity("/v3/api-docs",String.class);assertEquals(HttpStatus.OK,response.getStatusCode());
        var raw=mapper.readTree(response.getBody());var path=raw.path("paths").path("/v1/admin/workflows/{workflowId}/planning-selection");
        assertTrue(path.path("get").isObject());assertFalse(path.has("post"));
        var schemas=raw.path("components").path("schemas");
        var names=List.of("ApprovalWorkflowPlanningSelection","ApprovalWorkflowPlanningPolicyPin","ApprovalWorkflowPlanningFormPin");
        var sizes=List.of(9,2,5);
        for(int index=0;index<names.size();index++) {
            var schema=schemas.path(names.get(index));assertTrue(schema.isObject(),names.get(index));
            assertTrue(schema.path("additionalProperties").isBoolean());assertFalse(schema.path("additionalProperties").asBoolean());
            assertEquals(sizes.get(index),schema.path("properties").size());assertEquals(sizes.get(index),schema.path("required").size());
        }
        assertEquals(100,schemas.path(names.getFirst()).path("properties").path("forms").path("maxItems").asInt());
        assertFalse(raw.path("paths").has(WorkflowPlanningProtocol.PATH));
        assertNotNull(context.getBean(WorkflowPlanningSelectionController.class));
    }
    @Test void defaultDisabledSelectionDoesNotResolveRuntimeKeysOrMakeAnyCommandAuditWrites() {
        long before=jdbc.queryForObject("SELECT count(*) FROM sys_audit_outbox",Long.class);
        var failure=assertThrows(BaseException.class,()->context.getBean(WorkflowPlanningSelectionFacade.class).select(null,null,null));
        assertEquals(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,failure.getErrorCode());
        assertEquals(before,jdbc.queryForObject("SELECT count(*) FROM sys_audit_outbox",Long.class));
        assertFalse(context.getBeanFactory().containsSingleton("workflowPlanningRuntime"));
        assertEquals(HttpStatus.OK,rest.getForEntity("/actuator/health",String.class).getStatusCode());
    }
}
