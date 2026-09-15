package com.dwp.services.approval.signatureproviders;

import static org.assertj.core.api.Assertions.assertThat;

import com.dwp.services.approval.ApprovalServerApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.TreeSet;
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
@SpringBootTest(classes = ApprovalServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"springdoc.api-docs.enabled=true",
                "dwp.observability.api-history.enabled=false", "otel.sdk.disabled=true"})
class SignatureProviderNativeDefaultBootPostgresTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withLabel("dwp.approval.owner", "signature-provider-native-default-boot");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", PG::getJdbcUrl);
        properties.add("spring.datasource.username", PG::getUsername);
        properties.add("spring.datasource.password", PG::getPassword);
    }

    @Autowired ConfigurableApplicationContext context;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @Test
    void defaultBootKeepsNativeProviderApiAndRuntimeDisabledWithoutProvisioningState() {
        assertThat(context.getBeansOfType(ApprovalSignatureProviderController.class)).isEmpty();
        assertThat(context.getBeansOfType(ApprovalExternalSignatureController.class)).isEmpty();
        assertThat(context.getBeansOfType(ApprovalSignatureProviderService.class)).isEmpty();
        assertThat(context.getBeansOfType(ApprovalExternalSignatureService.class)).isEmpty();
        assertThat(context.getBeansOfType(SignatureProviderRuntime.class)).isEmpty();
        for (String table : Set.of(
                "apr_signature_provider_policy_heads",
                "apr_signature_provider_policy_versions",
                "apr_signature_provider_policy_publications",
                "apr_signature_native_commands",
                "apr_signature_provider_probe_runs",
                "apr_signature_provider_probe_observations",
                "apr_signature_provider_inspections",
                "apr_external_signature_requests",
                "apr_external_signature_events",
                "apr_external_signature_artifacts"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class))
                    .as(table).isZero();
        assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void defaultOpenApiKeepsLegacySettingsButDoesNotClaimTheUnactivatedSourceThirteenRoutes()
            throws Exception {
        var response = rest.getForEntity("/v3/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var document = mapper.readTree(response.getBody());
        var operations = new TreeSet<String>();
        document.path("paths").properties().forEach(entry -> {
            String path = entry.getKey();
            if (path.startsWith("/v1/admin/signatures")
                    || path.contains("external-signature"))
                entry.getValue().properties().forEach(method -> {
                    if (Set.of("get", "post", "put", "delete", "patch")
                            .contains(method.getKey()))
                        operations.add(method.getKey() + " " + path);
                });
        });
        assertThat(operations).contains("get /v1/admin/signatures");
        Set<String> sourceThirteen = new TreeSet<>();
        for (SignatureProviderOperation operation : SignatureProviderOperation.values())
            sourceThirteen.add(operation.method().toLowerCase(java.util.Locale.ROOT)
                    + " " + operation.pathTemplate());
        assertThat(operations).doesNotContainAnyElementsOf(sourceThirteen);
    }
}
