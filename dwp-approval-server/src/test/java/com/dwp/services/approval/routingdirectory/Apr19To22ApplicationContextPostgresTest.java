package com.dwp.services.approval.routingdirectory;

import com.dwp.services.approval.ApprovalServerApplication;
import com.dwp.services.approval.connectors.ConnectorController;
import com.dwp.services.approval.incidents.IncidentController;
import com.dwp.services.approval.policyautomation.PolicyAutomationController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        classes = ApprovalServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "springdoc.api-docs.enabled=true",
                "dwp.observability.api-history.enabled=false",
                "otel.sdk.disabled=true"
        })
class Apr19To22ApplicationContextPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("approval_apr_19_22")
                    .withUsername("approval")
                    .withPassword("approval");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private TestRestTemplate rest;

    @LocalServerPort
    private int port;

    @Test
    void bootsControllersAndPublishesOnlyTheirImplementedHttpSurface() {
        assertThat(context.getBean(RoutingDirectoryController.class)).isNotNull();
        assertThat(context.getBean(PolicyAutomationController.class)).isNotNull();
        assertThat(context.getBean(ConnectorController.class)).isNotNull();
        assertThat(context.getBean(IncidentController.class)).isNotNull();

        var response = rest.getForEntity(
                "http://127.0.0.1:" + port + "/v3/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains(
                "/v1/admin/workflows/routing-directory/groups",
                "/v1/admin/policies/automation/rules/{policyId}/publish",
                "/v1/admin/policies/automation/delegations/{delegationId}/reviews",
                "/v1/admin/operations/connectors/{connectorId}/probes/{probeId}/complete",
                "/v1/admin/operations/incidents/{incidentId}/recovery-plans/{planId}/reconcile");
    }
}
