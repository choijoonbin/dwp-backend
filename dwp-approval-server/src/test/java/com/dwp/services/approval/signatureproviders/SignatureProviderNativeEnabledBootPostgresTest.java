package com.dwp.services.approval.signatureproviders;

import static org.assertj.core.api.Assertions.assertThat;

import com.dwp.services.approval.ApprovalServerApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.aop.support.AopUtils;
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
                "dwp.approval.external-signature-enabled=true",
                "dwp.approval.internal-signatures.enabled=true",
                "dwp.approval.internal-signatures.source.enabled=false",
                "dwp.observability.api-history.enabled=false", "otel.sdk.disabled=true"})
class SignatureProviderNativeEnabledBootPostgresTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine")
            .withLabel("dwp.approval.owner", "signature-provider-native-enabled-boot");

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
    void explicitEnablementInstallsControllersWithOnlyTheFailClosedRuntime() {
        assertThat(context.getBeansOfType(ApprovalSignatureProviderController.class)).hasSize(1);
        assertThat(context.getBeansOfType(ApprovalExternalSignatureController.class)).hasSize(1);
        assertThat(context.getBeansOfType(ApprovalSignatureProviderService.class)).hasSize(1)
                .allSatisfy((name, service) -> assertThat(AopUtils.isAopProxy(service)).isTrue());
        assertThat(context.getBeansOfType(ApprovalExternalSignatureService.class)).hasSize(1)
                .allSatisfy((name, service) -> assertThat(AopUtils.isAopProxy(service)).isTrue());
        assertThat(context.getBeansOfType(SignatureProviderRuntime.class))
                .hasSize(1)
                .allSatisfy((name, runtime) -> assertThat(runtime)
                        .isExactlyInstanceOf(UnavailableSignatureProviderRuntime.class));
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
    void enabledRuntimeOpenApiContainsExactlyTheNineteenNativeOperations() throws Exception {
        var response = rest.getForEntity("/v3/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var document = mapper.readTree(response.getBody());
        var actual = new TreeSet<String>();
        document.path("paths").properties().forEach(entry -> {
            String path = entry.getKey();
            if (path.startsWith("/v1/admin/signatures/")
                    || path.contains("external-signature"))
                entry.getValue().properties().forEach(method -> {
                    if (Set.of("get", "post", "put", "delete", "patch")
                            .contains(method.getKey()))
                        actual.add(method.getKey().toUpperCase(Locale.ROOT) + " " + path);
                });
        });
        var expected = new TreeSet<String>();
        for (SignatureProviderOperation operation : SignatureProviderOperation.values())
            expected.add(operation.method() + " " + operation.pathTemplate());
        assertThat(actual).isEqualTo(expected).hasSize(19);
    }

    @Test
    void contractExportBootPublishesAllTwentySevenRoutesWithoutAProviderRuntime() throws Exception {
        var response = rest.getForEntity("/v3/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var document = mapper.readTree(response.getBody());
        var actual = new TreeSet<String>();
        document.path("paths").properties().forEach(entry -> {
            String path = entry.getKey();
            if (path.contains("signature") && !path.equals("/v1/admin/signatures"))
                entry.getValue().properties().forEach(method -> {
                    if (Set.of("get", "post", "put", "delete", "patch")
                            .contains(method.getKey()))
                        actual.add(method.getKey().toUpperCase(Locale.ROOT) + " " + path);
                });
        });
        var expected = new TreeSet<String>();
        for (SignatureProviderOperation operation : SignatureProviderOperation.values())
            expected.add(operation.method() + " " + operation.pathTemplate());
        expected.addAll(Set.of(
                "GET /v1/requests/{requestId}/signature-context",
                "POST /v1/requests/{requestId}/signature-requests",
                "GET /v1/signature-requests/{signatureRequestId}",
                "POST /v1/signature-requests/{signatureRequestId}/consents",
                "POST /v1/signature-requests/{signatureRequestId}/sign",
                "POST /v1/signature-requests/{signatureRequestId}/cancel",
                "GET /v1/signature-requests/{signatureRequestId}/audit",
                "GET /v1/signature-command-receipts/{idempotencyKey}"));
        assertThat(actual).isEqualTo(expected).hasSize(27);
        assertThat(context.getBean(SignatureProviderRuntime.class))
                .isExactlyInstanceOf(UnavailableSignatureProviderRuntime.class);
    }
}
