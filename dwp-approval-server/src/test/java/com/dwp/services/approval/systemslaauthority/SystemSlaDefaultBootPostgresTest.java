package com.dwp.services.approval.systemslaauthority;

import static org.assertj.core.api.Assertions.*;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.ApprovalServerApplication;
import com.dwp.services.approval.domain.ApprovalSystemSlaNativeSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(classes = ApprovalServerApplication.class,webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"springdoc.api-docs.enabled=true","otel.sdk.disabled=true","dwp.observability.api-history.enabled=false"})
class SystemSlaDefaultBootPostgresTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine").withLabel("dwp-owner","cicero-system-defaultboot");
    @DynamicPropertySource static void db(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",PG::getJdbcUrl); properties.add("spring.datasource.username",PG::getUsername); properties.add("spring.datasource.password",PG::getPassword);
    }
    @Autowired TestRestTemplate rest;
    @Autowired SystemSlaCurrentSource source;
    @Autowired ApprovalSystemSlaNativeSource nativeSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    @Autowired com.dwp.services.approval.domain.ApprovalWorkflowQuorumFacade facade;
    @Test void actualDefaultWebBootUsesRealSourceButAbsentKeysNeverBootstrapOrWrite() {
        assertThat(nativeSource).isNotNull();
        assertThat(rest.getForEntity("/actuator/health/readiness",String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThatThrownBy(() -> source.produce(null)).isInstanceOfSatisfying(BaseException.class,error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
        assertThatThrownBy(() -> facade.pollSla("disabled-source-test",30,1)).isInstanceOfSatisfying(BaseException.class,error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
        var before = writes(); var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); headers.set("X-DWP-Service-Identity","dwp-notification-server");
        headers.set(SystemSlaNotificationProtocol.HEADER,"not-a-proof");
        var response = rest.postForEntity(SystemSlaNotificationProtocol.PATH,new HttpEntity<>("{}",headers),String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE); assertThat(writes()).isEqualTo(before);
    }
    @Test void nearPathAliasAndBorrowedGatewayCredentialsDoNotReachAuthorityOrBusinessWrites() {
        var before = writes(); var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); headers.set("X-DWP-Service-Identity","dwp-notification-server");
        headers.set(SystemSlaNotificationProtocol.HEADER,"not-a-proof"); headers.setBearerAuth("borrowed");
        assertThat(rest.postForEntity(SystemSlaNotificationProtocol.PATH,new HttpEntity<>("{}",headers),String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        headers.remove("Authorization");
        assertThat(rest.postForEntity(SystemSlaNotificationProtocol.PATH+"/alias",new HttpEntity<>("{}",headers),String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(writes()).isEqualTo(before);
    }
    @Test void actualSpringdocPublicProjectionCannotLeakInternalRecipientRouteOrProofSchemas() throws Exception {
        var response = rest.getForEntity("/v3/api-docs",String.class); assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var raw = mapper.readTree(response.getBody()); var names = new java.util.ArrayList<String>(); raw.get("paths").fieldNames().forEachRemaining(names::add);
        assertThat(names).noneMatch(path -> path.startsWith("/internal/") || path.contains("recipient-authority"));
        var schemas = new java.util.ArrayList<String>(); raw.at("/components/schemas").fieldNames().forEachRemaining(schemas::add);
        assertThat(schemas).noneMatch(name -> name.contains("SystemSla") || name.contains("NotificationVerifier") || name.contains("SourceProofIssuer"));
    }
    private long writes() { return jdbc.queryForObject("SELECT (SELECT count(*) FROM apr_request_events)+(SELECT count(*) FROM apr_integration_outbox)+(SELECT count(*) FROM sys_audit_outbox)",Long.class); }
}
