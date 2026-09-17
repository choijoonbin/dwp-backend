package com.dwp.services.platform.config;

import com.dwp.services.platform.PlatformServerApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Read-only ApplicationContext and Springdoc gate for Workplace visits and DEVICE routes. */
@Testcontainers(disabledWithoutDocker = true)
class PlatformWorkplaceDeviceOpenApiContextTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @TempDir Path temporary;

    @Test
    void applicationContextPublishesVisitsAndActorlessDeviceRoutes() throws Exception {
        try (var context = new SpringApplicationBuilder(PlatformServerApplication.class)
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
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.kafka.bootstrap-servers=127.0.0.1:1",
                        "--dwp.platform.assets.root=" + temporary.resolve("assets"),
                        "--dwp.platform.assets.storage=local", "--DWP_ENVIRONMENT=test",
                        "--dwp.workspace.identity-governance-consumer-enabled=false",
                        "--dwp.platform.mail.delivery.enabled=false",
                        "--dwp.platform.productivity.sync.enabled=false",
                        "--dwp.observability.api-history.enabled=false",
                        "--dwp.audit.collector-url=",
                        "--dwp.identity-sync.auth-url=http://127.0.0.1:1",
                        "--otel.sdk.disabled=true")) {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(
                                    "http://127.0.0.1:" + port + "/v3/api-docs"))
                            .timeout(Duration.ofSeconds(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode paths = new ObjectMapper().readTree(response.body()).path("paths");
            List.of(
                    "/v1/workplace/visits:preview",
                    "/v1/workplace/visits/{visitId}:send-invitation",
                    "/v1/admin/workplace/visits/exceptions",
                    "/v1/admin/workplace/visit-policies",
                    "/v1/admin/workplace/access-zones",
                    "/v1/admin/workplace/provider-bindings",
                    "/v1/admin/workplace/kiosk-devices",
                    "/v1/workplace/kiosk/session",
                    "/v1/workplace/kiosk/visits/{visitId}:arrive",
                    "/v1/workplace/kiosk/devices/{deviceId}:heartbeat",
                    "/v1/device/workplace/devices:register",
                    "/v1/device/workplace/devices/{deviceId}/heartbeat",
                    "/v1/device/workplace/devices/{deviceId}/projection")
                    .forEach(path -> assertThat(paths.has(path)).as(path).isTrue());
        }
    }
}
