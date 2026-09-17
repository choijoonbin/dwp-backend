package com.dwp.services.platform.config;

import com.dwp.services.platform.PlatformServerApplication;
import com.fasterxml.jackson.databind.JsonNode;
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
                JsonNode document = new ObjectMapper().readTree(response.body());
                JsonNode paths = document.path("paths");
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
                java.util.List.of(
                        Map.entry("/v1/admin/workplace/connectors/operations", "get"),
                        Map.entry("/v1/admin/workplace/connectors/{kind}/operations", "get"),
                        Map.entry("/v1/admin/workplace/connectors/{kind}/replays:preview", "post"),
                        Map.entry("/v1/admin/workplace/connectors/{kind}/replays", "post"),
                        Map.entry("/v1/admin/workplace/connectors/{kind}/replays/{jobId}", "get"),
                        Map.entry("/v1/workplace/booking-intents/beneficiaries", "get"),
                        Map.entry("/v1/workplace/booking-intents/preview", "post"),
                        Map.entry("/v1/workplace/booking-intents/{intentId}", "get"),
                        Map.entry("/v1/workplace/booking-intents/{intentId}/holds", "post"),
                        Map.entry("/v1/workplace/booking-batches", "post"),
                        Map.entry("/v1/workplace/booking-batches/{batchId}", "get"),
                        Map.entry("/v1/workplace/booking-batches/{batchId}/compensations", "post"),
                        Map.entry("/v1/workplace/booking-batches/{batchId}/replans", "post"),
                        Map.entry("/v1/workplace/waitlist-entries", "get"),
                        Map.entry("/v1/workplace/waitlist-entries", "post"),
                        Map.entry("/v1/workplace/waitlist-entries/{entryId}", "get"),
                        Map.entry("/v1/workplace/waitlist-entries/{entryId}", "patch"),
                        Map.entry("/v1/workplace/waitlist-entries/{entryId}:cancel", "post"),
                        Map.entry("/v1/workplace/alternative-offers/{offerId}:accept", "post"),
                        Map.entry("/v1/workplace/service-orders/{orderId}/events", "get"),
                        Map.entry("/v1/workplace/service-orders/{orderId}/messages", "get"),
                        Map.entry("/v1/workplace/service-orders/{orderId}/attachments", "get"),
                        Map.entry("/v1/workplace/service-orders/{orderId}/lines/{lineId}/cancellation-impact:preview", "post"),
                        Map.entry("/v1/workplace/service-orders/{orderId}/lines/{lineId}:cancel", "post"),
                        Map.entry("/v1/workplace/service-orders/{orderId}/line-adjustments/{adjustmentId}", "get"),
                        Map.entry("/v1/admin/workplace/service-orders/{orderId}/events", "get"),
                        Map.entry("/v1/admin/workplace/service-orders/{orderId}/messages", "get"),
                        Map.entry("/v1/admin/workplace/service-orders/{orderId}/attachments", "get"),
                        Map.entry("/v1/admin/workplace/service-orders/{orderId}/line-adjustments/{adjustmentId}", "get"),
                        Map.entry("/v1/admin/workplace/service-orders/{orderId}/line-adjustments/{adjustmentId}:reconcile", "post"),
                        Map.entry("/v1/admin/workplace/service-orders/{orderId}/attachments/{attachmentId}/scan-result", "post"),
                        Map.entry("/v1/admin/workplace/service-orders/{orderId}/attachments/{attachmentId}/scan-status", "get")
                ).forEach(operation -> {
                    String path = operation.getKey();
                    String method = operation.getValue();
                    assertThat(paths.path(path).has(method))
                            .as(method + " " + path).isTrue();
                    assertThat(paths.path(path).path(method).path("responses").size())
                            .as("success response " + method + " " + path)
                            .isGreaterThan(0);
                });
                java.util.List.of(
                        Map.entry("/v1/device/workplace/devices:register", "post"),
                        Map.entry("/v1/device/workplace/devices/{deviceId}/heartbeat", "post"),
                        Map.entry("/v1/device/workplace/devices/{deviceId}/projection", "get"),
                        Map.entry("/v1/device/workplace/devices/{deviceId}/access-pass:pair", "post"),
                        Map.entry("/v1/workplace/kiosk/session", "get"),
                        Map.entry("/v1/workplace/kiosk/visits/{visitId}", "get"),
                        Map.entry("/v1/workplace/kiosk/visits/{visitId}:arrive", "post"),
                        Map.entry("/v1/workplace/kiosk/visits/{visitId}:checkout", "post"),
                        Map.entry("/v1/workplace/kiosk/devices/{deviceId}:heartbeat", "post"),
                        Map.entry("/v1/workplace/kiosk/devices/{deviceId}:help", "post")
                ).forEach(operation -> {
                    JsonNode contract = paths.path(operation.getKey()).path(operation.getValue());
                    assertThat(contract.path("x-dwp-identity-plane").asText())
                            .as(operation.toString()).isEqualTo("DEVICE");
                    assertThat(contract.path("security").size()).isEqualTo(1);
                    assertThat(contract.path("security").path(0).has("DeviceTenant")).isTrue();
                    assertThat(contract.path("security").path(0).has("DeviceCredential")).isTrue();
                });
                assertThat(document.path("components").path("securitySchemes")
                        .path("DeviceTenant").path("name").asText())
                        .isEqualTo("X-DWP-Tenant-ID");
                assertThat(document.path("components").path("securitySchemes")
                        .path("DeviceCredential").path("name").asText())
                        .isEqualTo("X-DWP-Device-Credential");
                java.util.List.of(
                        Map.entry("/v1/workplace/bookings/{bookingId}/check-in", "post"),
                        Map.entry("/v1/workplace/bookings/{bookingId}/cancel", "post"),
                        Map.entry("/v1/workplace/bookings/{bookingId}/release", "post"),
                        Map.entry("/v1/workplace/bookings/{bookingId}/relocate", "post"),
                        Map.entry("/v1/device/workplace/devices/{deviceId}/access-pass:pair", "post"),
                        Map.entry("/v1/admin/workplace/devices/{deviceId}:approve", "post"),
                        Map.entry("/v1/admin/workplace/devices/{deviceId}:bind", "post"),
                        Map.entry("/v1/admin/workplace/device-providers/{capability}", "put"),
                        Map.entry("/v1/admin/workplace/devices/{deviceId}/commands:preview", "post"),
                        Map.entry("/v1/admin/workplace/connectors/{kind}/replays:preview", "post"),
                        Map.entry("/v1/admin/workplace/connectors/{kind}/replays", "post")
                ).forEach(operation -> {
                    JsonNode parameters = paths.path(operation.getKey())
                            .path(operation.getValue()).path("parameters");
                    assertThat(requiredHeader(parameters, "Idempotency-Key"))
                            .as(operation.toString()).isTrue();
                    assertThat(optionalHeader(parameters, "X-Correlation-ID"))
                            .as(operation.toString()).isTrue();
                });
                for (String mode : java.util.List.of("--write", "--check")) {
                    ProcessBuilder export = new ProcessBuilder(
                            "python3", "scripts/export-openapi-contracts.py",
                            mode, "--service", "platform")
                            .directory(root.toFile()).inheritIO();
                    export.environment().put("DWP_OPENAPI_PLATFORM_URL", url);
                    Process process = export.start();
                    assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
                    assertThat(process.exitValue()).isZero();
                }
            }
        }
        for (var snapshot : otherSnapshots.entrySet()) {
            assertThat(Files.readAllBytes(snapshot.getKey())).as(snapshot.getKey().toString()).isEqualTo(snapshot.getValue());
        }
    }

    private boolean requiredHeader(JsonNode parameters, String name) {
        for (JsonNode parameter : parameters) {
            if (name.equalsIgnoreCase(parameter.path("name").asText())
                    && "header".equalsIgnoreCase(parameter.path("in").asText())) {
                return parameter.path("required").asBoolean(false);
            }
        }
        return false;
    }

    private boolean optionalHeader(JsonNode parameters, String name) {
        for (JsonNode parameter : parameters) {
            if (name.equalsIgnoreCase(parameter.path("name").asText())
                    && "header".equalsIgnoreCase(parameter.path("in").asText())) {
                return !parameter.path("required").asBoolean(true);
            }
        }
        return false;
    }
}
