package com.dwp.services.platform.config;

import com.dwp.services.platform.PlatformServerApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
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
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

/** Explicit local export job: never touches shared databases or normally running servers. */
@EnabledIfEnvironmentVariable(named = "DWP_EXPORT_PLATFORM_OPENAPI", matches = "true")
class PlatformOpenApiSnapshotExportTest {
    @TempDir Path temporary;

    @Test
    void exportLatestPlatformControllersUsingAnIsolatedDatabaseAndRandomPort() throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        Map<Path, byte[]> otherSnapshots = new HashMap<>();
        try (var paths = Files.list(root.resolve("contracts/openapi"))) {
            paths.filter(path -> path.toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().equals("platform.json")
                            && !path.getFileName().toString().equals("gateway-public.json"))
                    .forEach(path -> { try { otherSnapshots.put(path, Files.readAllBytes(path)); }
                        catch (Exception failure) { throw new IllegalStateException(failure); } });
        }
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            try (var context = new SpringApplicationBuilder(PlatformServerApplication.class)
                    .initializers(application -> application.addBeanFactoryPostProcessor(factory -> {
                        BeanDefinitionRegistry registry = (BeanDefinitionRegistry) factory;
                        String scheduler = "org.springframework.context.annotation.internalScheduledAnnotationProcessor";
                        if (registry.containsBeanDefinition(scheduler)) registry.removeBeanDefinition(scheduler);
                    }))
                    .run("--server.port=0", "--server.address=127.0.0.1", "--springdoc.api-docs.enabled=true",
                            "--spring.datasource.url=" + postgres.getJdbcUrl(),
                            "--spring.datasource.username=" + postgres.getUsername(),
                            "--spring.datasource.password=" + postgres.getPassword(),
                            "--spring.kafka.bootstrap-servers=127.0.0.1:1",
                            "--dwp.platform.assets.root=" + temporary.resolve("assets"),
                            "--dwp.platform.assets.storage=local", "--DWP_ENVIRONMENT=test",
                            "--dwp.workspace.identity-governance-consumer-enabled=false",
                            "--dwp.platform.mail.delivery.enabled=false", "--dwp.platform.productivity.sync.enabled=false",
                            "--dwp.observability.api-history.enabled=false", "--dwp.audit.collector-url=",
                            "--dwp.identity-sync.auth-url=http://127.0.0.1:1", "--otel.sdk.disabled=true")) {
                int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
                String url = "http://127.0.0.1:" + port + "/v3/api-docs";
                var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                var paths = new ObjectMapper().readTree(response.body()).path("paths");
                assertThat(paths.has("/v1/workspace/activity/events/{id}")).isTrue();
                assertThat(paths.has("/v1/workspace/activity/executions/summary")).isTrue();
                assertThat(paths.has("/v1/workspace/activity/events/{id}/evidence")).isTrue();
                assertThat(paths.has("/v1/workspace/activity/audit/evidence/{auditRecordId}")).isTrue();
                assertThat(paths.has("/v1/workspace/activity/sources/status")).isTrue();
                for (String evidencePath : java.util.List.of("/v1/workspace/activity/events/{id}/evidence",
                        "/v1/workspace/activity/audit/evidence/{auditRecordId}", "/v1/workspace/activity/sources/status")) {
                    assertThat(paths.path(evidencePath).path("get").path("responses").has("200")).isTrue();
                    assertThat(paths.path(evidencePath).path("get").path("responses").has("403")).isTrue();
                }
                assertThat(paths.has("/v1/workspace/work-items")).isTrue();
                ProcessBuilder export = new ProcessBuilder("python3", "scripts/export-openapi-contracts.py",
                        "--write", "--service", "platform").directory(root.toFile()).inheritIO();
                export.environment().put("DWP_OPENAPI_PLATFORM_URL", url);
                Process process = export.start();
                assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
                assertThat(process.exitValue()).isZero();
            }
        }
        for (var snapshot : otherSnapshots.entrySet()) {
            assertThat(Files.readAllBytes(snapshot.getKey())).as(snapshot.getKey().toString()).isEqualTo(snapshot.getValue());
        }
    }
}
