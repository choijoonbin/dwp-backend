package com.dwp.services.provider.config;

import com.dwp.services.provider.ProviderServerApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit local export job: never touches shared databases or normally running servers. */
@EnabledIfEnvironmentVariable(named = "DWP_EXPORT_PROVIDER_OPENAPI", matches = "true")
class ProviderOpenApiSnapshotExportTest {

    @Test
    void exportLatestProviderControllersUsingAnIsolatedDatabaseAndRandomPort() throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        Path gatewaySnapshotPath = root.resolve("contracts/openapi/gateway-public.json");
        byte[] gatewaySnapshot = Files.readAllBytes(gatewaySnapshotPath);
        Map<Path, byte[]> otherSnapshots = new HashMap<>();
        try (var paths = Files.list(root.resolve("contracts/openapi"))) {
            paths.filter(path -> path.toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().equals("provider.json")
                            && !path.getFileName().toString().equals("gateway-public.json"))
                    .forEach(path -> {
                        try {
                            otherSnapshots.put(path, Files.readAllBytes(path));
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure);
                        }
                    });
        }

        try {
            try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
                postgres.start();
                try (var context = new SpringApplicationBuilder(ProviderServerApplication.class)
                    .initializers(application -> application.addBeanFactoryPostProcessor(factory -> {
                        BeanDefinitionRegistry registry = (BeanDefinitionRegistry) factory;
                        String scheduler =
                                "org.springframework.context.annotation.internalScheduledAnnotationProcessor";
                        if (registry.containsBeanDefinition(scheduler)) {
                            registry.removeBeanDefinition(scheduler);
                        }
                    }))
                    .run("--server.port=0", "--server.address=127.0.0.1",
                            "--springdoc.api-docs.enabled=true",
                            "--spring.datasource.url=" + postgres.getJdbcUrl(),
                            "--spring.datasource.username=" + postgres.getUsername(),
                            "--spring.datasource.password=" + postgres.getPassword(),
                            "--spring.flyway.locations=filesystem:"
                                    + root.resolve("dwp-provider-server/src/main/resources/db/migration"),
                            "--spring.kafka.bootstrap-servers=127.0.0.1:1",
                            "--dwp.provider.support-authority-reconciliation.enabled=false",
                            "--dwp.provider.tenant-mutation.recovery-enabled=false",
                            "--dwp.provider.product-surface-rollout.relay-enabled=false",
                            "--dwp.provider.product-surface-rollout.publisher-enabled=false",
                            "--dwp.observability.api-history.enabled=false",
                            "--dwp.audit.collector-url=", "--otel.sdk.disabled=true")) {
                int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
                String url = "http://127.0.0.1:" + port + "/v3/api-docs";
                HttpResponse<String> response = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(url))
                                .timeout(Duration.ofSeconds(60)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                JsonNode document = new ObjectMapper().readTree(response.body());
                JsonNode paths = document.path("paths");
                List.of(
                        "/v1/tenant/settings/provider-domains",
                        "/v1/tenant/settings/data-governance-observation",
                        "/v1/tenant/settings/plan-eligibility")
                        .forEach(path -> assertThat(paths.path(path).path("get").isObject())
                                .as("GET " + path).isTrue());
                String schemas = document.path("components").path("schemas").toString();
                assertThat(schemas)
                        .contains("DomainProjection")
                        .contains("DataGovernanceProjection")
                        .contains("PlanEligibilityProjection")
                        .doesNotContain("verificationTokenHash")
                        .doesNotContain("verificationRecordValue");

                for (String mode : List.of("--write", "--check")) {
                    ProcessBuilder export = new ProcessBuilder(
                            "python3", "scripts/export-openapi-contracts.py", mode,
                            "--service", "provider")
                            .directory(root.toFile()).inheritIO();
                    export.environment().put("DWP_OPENAPI_PROVIDER_URL", url);
                    Process process = export.start();
                    assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
                    assertThat(process.exitValue()).isZero();
                }
                }
            }
        } finally {
            Files.write(gatewaySnapshotPath, gatewaySnapshot);
        }

        for (var snapshot : otherSnapshots.entrySet()) {
            assertThat(Files.readAllBytes(snapshot.getKey()))
                    .as(snapshot.getKey().toString()).isEqualTo(snapshot.getValue());
        }
    }
}
