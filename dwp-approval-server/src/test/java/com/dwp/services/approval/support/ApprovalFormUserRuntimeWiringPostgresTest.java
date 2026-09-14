package com.dwp.services.approval.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.dwp.services.approval.ApprovalServerApplication;
import com.dwp.services.approval.domain.ApprovalCommandRepository;
import com.dwp.services.approval.domain.ApprovalFormReferenceNormalizer;
import com.dwp.services.approval.integration.ApprovalFormReferenceDirectory;
import com.dwp.services.approval.integration.ApprovalFormUserDirectory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = ApprovalServerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"dwp.observability.api-history.enabled=false", "otel.sdk.disabled=true"})
class ApprovalFormUserRuntimeWiringPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("approval_form_runtime_wiring");

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired ApplicationContext context;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;

    @Test void actualWebBootInjectsInheritedFormNormalizerWithoutConfusingCandidateAndMutationPorts() {
        var command = AopTestUtils.getUltimateTargetObject(context.getBean(ApprovalCommandRepository.class));
        Object formNormalization = ReflectionTestUtils.getField(command, "formNormalization");
        assertThat(formNormalization).isNotNull();
        assertThat(ReflectionTestUtils.getField(formNormalization, "normalizer"))
                .isSameAs(context.getBean(ApprovalFormReferenceNormalizer.class));
        assertThat(context.getBeansOfType(ApprovalFormUserDirectory.class)).hasSize(1);
        assertThat(context.getBeansOfType(ApprovalFormReferenceDirectory.class)).hasSize(1);
        var health = rest.getForEntity("/actuator/health/readiness", String.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).contains("\"status\":\"UP\"");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('17','19') AND success", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE NOT success", Integer.class)).isZero();
    }
}
