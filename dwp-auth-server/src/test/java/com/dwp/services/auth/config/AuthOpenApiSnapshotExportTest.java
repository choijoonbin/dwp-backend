package com.dwp.services.auth.config;

import com.dwp.services.auth.AuthServerApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
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
@EnabledIfEnvironmentVariable(named = "DWP_EXPORT_AUTH_OPENAPI", matches = "true")
class AuthOpenApiSnapshotExportTest {

    @Test
    void exportLatestAuthControllersUsingAnIsolatedDatabaseAndRandomPort() throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        Map<Path, byte[]> otherSnapshots = new HashMap<>();
        try (var paths = Files.list(root.resolve("contracts/openapi"))) {
            paths.filter(path -> path.toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().equals("auth.json")
                            && !path.getFileName().toString().equals("gateway-public.json"))
                    .forEach(path -> {
                        try {
                            otherSnapshots.put(path, Files.readAllBytes(path));
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure);
                        }
                    });
        }
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            try (var context = new SpringApplicationBuilder(ExportApplication.class)
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
                                    + root.resolve("dwp-auth-server/src/main/resources/db/migration"),
                            "--spring.kafka.bootstrap-servers=127.0.0.1:1",
                            "--spring.data.redis.host=127.0.0.1",
                            "--spring.data.redis.port=1",
                            "--dwp.observability.api-history.enabled=false",
                            "--dwp.audit.collector-url=", "--otel.sdk.disabled=true")) {
                int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
                HttpResponse<String> response = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(URI.create(
                                        "http://127.0.0.1:" + port + "/v3/api-docs"))
                                .timeout(Duration.ofSeconds(60)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                JsonNode document = new ObjectMapper().readTree(response.body());
                JsonNode paths = document.path("paths");
                List.of(
                        "/auth/admin/tenant-settings/auth-policy/changes",
                        "/auth/admin/tenant-settings/auth-policy/changes/{changeSetId}/submit",
                        "/auth/admin/tenant-settings/auth-policy/changes/{changeSetId}/decision",
                        "/auth/admin/tenant-settings/auth-policy/changes/{changeSetId}/publish",
                        "/auth/admin/tenant-settings/access-projection",
                        "/auth/admin/tenant-settings/governance-snapshot",
                        "/auth/admin/tenant-settings/sso-test-login-jobs",
                        "/auth/admin/tenant-settings/sso-test-login-jobs/{jobId}",
                        "/auth/admin/tenant-setting-registry/changes",
                        "/auth/tenant-settings/effective-settings/me",
                        "/auth/tenant-settings/effective-settings/me/preferred-locale",
                        "/auth/tenant-settings/effective-settings/me/preferred-locale/restore",
                        "/auth/admin/tenant-app-adoption",
                        "/auth/admin/tenant-app-adoption/installations",
                        "/auth/admin/tenant-app-adoption/assignments",
                        "/auth/admin/tenant-app-adoption/capability-overrides",
                        "/auth/admin/tenant-app-adoption/capability-overrides/{changeId}/submit",
                        "/auth/admin/tenant-app-adoption/capability-overrides/{changeId}/decision",
                        "/auth/admin/tenant-app-adoption/capability-overrides/{changeId}/activate",
                        "/auth/admin/tenant-app-adoption/capability-overrides/{changeId}/revoke")
                        .forEach(path -> assertThat(paths.has(path)).as(path).isTrue());
                assertThat(paths.path("/auth/admin/tenant-settings/sso-test-login-jobs")
                        .has("post")).isTrue();
                assertThat(paths.path("/auth/admin/tenant-settings/sso-test-login-jobs")
                        .has("get")).isTrue();
                assertThat(paths.path("/auth/admin/tenant-settings/sso-test-login-jobs/{jobId}")
                        .has("get")).isTrue();
                JsonNode schemas = document.path("components").path("schemas");
                assertThat(schemas
                        .path("AuthPolicyResponse").path("properties")
                        .has("tokenTtlSec")).isTrue();
                assertThat(schemas.has("SsoTestLoginCommand")).isTrue();
                assertThat(schemas.has("SsoTestLoginReceipt")).isTrue();
                JsonNode ssoReceipt = schemas.path("SsoTestLoginReceipt").path("properties");
                assertThat(ssoReceipt.has("tenantId")).isTrue();
                assertThat(ssoReceipt.has("idempotencyKey")).isTrue();
                assertThat(ssoReceipt.has("receiptPayloadCanonical")).isTrue();
                assertPage(schemas, "SsoTestLoginReceiptPage");
                assertPage(schemas, "AuthPolicyChangePage");
                assertPage(schemas, "ChangePage");
                assertPage(schemas, "AssignmentPage");
                assertThat(schemas.path("AdoptionProjection").path("properties").has(
                        "installationsLimit")).isTrue();
                assertThat(schemas.path("AdoptionProjection").path("properties").has(
                        "installationsHasMore")).isTrue();

                for (String mode : List.of("--write", "--check")) {
                    ProcessBuilder export = new ProcessBuilder(
                            "python3", "scripts/export-openapi-contracts.py", mode, "--service", "auth")
                            .directory(root.toFile()).inheritIO();
                    export.environment().put(
                            "DWP_OPENAPI_AUTH_URL", "http://127.0.0.1:" + port + "/v3/api-docs");
                    Process process = export.start();
                    assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
                    assertThat(process.exitValue()).isZero();
                }
            }
        }
        for (var snapshot : otherSnapshots.entrySet()) {
            assertThat(Files.readAllBytes(snapshot.getKey()))
                    .as(snapshot.getKey().toString()).isEqualTo(snapshot.getValue());
        }
    }

    private void assertPage(JsonNode schemas, String name) {
        JsonNode properties = schemas.path(name).path("properties");
        assertThat(properties.has("items")).as(name + " items").isTrue();
        assertThat(properties.has("limit")).as(name + " limit").isTrue();
        assertThat(properties.has("hasMore")).as(name + " hasMore").isTrue();
    }

    // This is an explicit exporter bootstrap, not the module's test-wide boot configuration.
    // Keeping it as a plain configuration prevents @WebMvcTest from discovering it instead of
    // AuthServerApplication and then trying to create JPA repositories without JPA auto-config.
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackages = "com.dwp.services.auth.entity")
    @EnableJpaRepositories(basePackages = "com.dwp.services.auth.repository")
    @ComponentScan(
            basePackages = "com.dwp.services.auth",
            excludeFilters = {
                    @ComponentScan.Filter(type = FilterType.CUSTOM,
                            classes = TypeExcludeFilter.class),
                    @ComponentScan.Filter(type = FilterType.CUSTOM,
                            classes = AutoConfigurationExcludeFilter.class),
                    @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                            classes = AuthServerApplication.class),
                    @ComponentScan.Filter(type = FilterType.REGEX,
                            pattern = "com\\.dwp\\.services\\.auth\\..*"
                                    + "(Test|EmbeddedServer|SecurityProbe).*")
            })
    static class ExportApplication {
    }
}
