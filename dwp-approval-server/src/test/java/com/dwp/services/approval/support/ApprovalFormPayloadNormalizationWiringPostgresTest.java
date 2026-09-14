package com.dwp.services.approval.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.ApprovalServerApplication;
import com.dwp.services.approval.domain.ApprovalFormPayloadNormalization;
import com.dwp.services.approval.domain.ApprovalFormPayloadNormalizer;
import com.dwp.services.approval.domain.ApprovalFormReferenceNormalizer;
import com.dwp.services.approval.security.ApprovalRequestContext.Actor;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = ApprovalServerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"dwp.observability.api-history.enabled=false", "otel.sdk.disabled=true"})
class ApprovalFormPayloadNormalizationWiringPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("approval_form_payload_composition").withLabel("dwp.approval.owner", "apr12-form-normalization");

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired ApplicationContext context;
    @Autowired TestRestTemplate rest;

    @Test void actualWebBootSuppliesExactlyOneSourceBoundPortAndOriginalLegacyCallback() {
        assertThat(context.getBeansOfType(ApprovalFormPayloadNormalization.class)).hasSize(1);
        var normalizer = context.getBean(ApprovalFormPayloadNormalization.class);
        assertThat(normalizer).isInstanceOf(ApprovalFormPayloadNormalizer.class);
        assertThat(ReflectionTestUtils.getField(normalizer, "references"))
                .isSameAs(context.getBean(ApprovalFormReferenceNormalizer.class));
        var actor = new Actor(99L, 42L, null, "Owner", Set.of(), Set.of());
        String schema = "{\"schemaVersion\":2,\"fields\":[{\"key\":\"summary\",\"type\":\"TEXTAREA\",\"required\":true}]}";
        Map<String, Object> partial = Map.of();
        assertThat(normalizer.normalize(actor, UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), schema, partial, false, 7))
                .isSameAs(partial);
        assertThatThrownBy(() -> normalizer.normalize(actor, UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), schema, partial, true, 7))
                .isInstanceOf(BaseException.class);
        var health = rest.getForEntity("/actuator/health/readiness", String.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).contains("\"status\":\"UP\"");
    }
}
