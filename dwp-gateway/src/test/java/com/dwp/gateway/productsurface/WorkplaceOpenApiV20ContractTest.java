package com.dwp.gateway.productsurface;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Public projection guard for the Workplace v20 command and actorless DEVICE contracts. */
class WorkplaceOpenApiV20ContractTest {
    private static final List<Map.Entry<String, String>> DEVICE_OPERATIONS = List.of(
            Map.entry("/v1/device/workplace/devices:register", "post"),
            Map.entry("/v1/device/workplace/devices/{deviceId}/heartbeat", "post"),
            Map.entry("/v1/device/workplace/devices/{deviceId}/projection", "get"),
            Map.entry("/v1/device/workplace/devices/{deviceId}/access-pass:pair", "post"),
            Map.entry("/v1/workplace/kiosk/session", "get"),
            Map.entry("/v1/workplace/kiosk/visits/{visitId}", "get"),
            Map.entry("/v1/workplace/kiosk/visits/{visitId}:arrive", "post"),
            Map.entry("/v1/workplace/kiosk/visits/{visitId}:checkout", "post"),
            Map.entry("/v1/workplace/kiosk/devices/{deviceId}:heartbeat", "post"),
            Map.entry("/v1/workplace/kiosk/devices/{deviceId}:help", "post"));
    private static final List<Map.Entry<String, String>> IDEMPOTENT_COMMANDS = List.of(
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
            Map.entry("/v1/admin/workplace/connectors/{kind}/replays", "post"));

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deviceOperationsExposeOnlyTheActorlessPublicIdentityPlane() throws Exception {
        JsonNode platform = read("contracts/openapi/platform.json");
        JsonNode gateway = read("contracts/openapi/gateway-public.json");

        assertThat(DEVICE_OPERATIONS).hasSize(10);
        assertThat(platform.path("paths").has("/v1/device/workplace/devices/:register"))
                .isFalse();
        assertThat(gateway.path("paths")
                .has("/api/platform/v1/device/workplace/devices/:register")).isFalse();
        assertThat(platform.path("components").path("securitySchemes")
                .path("DeviceTenant").path("name").asText()).isEqualTo("X-DWP-Tenant-ID");
        assertThat(platform.path("components").path("securitySchemes")
                .path("DeviceCredential").path("name").asText())
                .isEqualTo("X-DWP-Device-Credential");
        assertThat(gateway.path("components").path("securitySchemes")
                .path("platform_DeviceTenant").path("name").asText())
                .isEqualTo("X-Tenant-ID");
        assertThat(gateway.path("components").path("securitySchemes")
                .path("platform_DeviceCredential").path("name").asText())
                .isEqualTo("X-Device-Credential");

        for (Map.Entry<String, String> expected : DEVICE_OPERATIONS) {
            JsonNode owner = platform.path("paths").path(expected.getKey())
                    .path(expected.getValue());
            JsonNode publicOperation = gateway.path("paths")
                    .path("/api/platform" + expected.getKey()).path(expected.getValue());
            assertDeviceOperation(owner, expected.toString(), false);
            assertDeviceOperation(publicOperation, expected.toString(), true);
        }
    }

    @Test
    void retriableWorkplaceMutationsExposeTheExactPublicCommandHeaders() throws Exception {
        JsonNode platform = read("contracts/openapi/platform.json");
        JsonNode gateway = read("contracts/openapi/gateway-public.json");

        assertThat(IDEMPOTENT_COMMANDS).hasSize(11);
        for (Map.Entry<String, String> expected : IDEMPOTENT_COMMANDS) {
            assertCommandHeaders(platform.path("paths").path(expected.getKey())
                    .path(expected.getValue()), expected.toString());
            assertCommandHeaders(gateway.path("paths")
                    .path("/api/platform" + expected.getKey()).path(expected.getValue()),
                    expected.toString());
        }
    }

    private void assertDeviceOperation(JsonNode operation, String description, boolean gateway) {
        assertThat(operation.isObject()).as(description).isTrue();
        assertThat(operation.path("x-dwp-identity-plane").asText())
                .as(description).isEqualTo("DEVICE");
        JsonNode requirement = operation.path("security").path(0);
        String prefix = gateway ? "platform_" : "";
        assertThat(requirement.has(prefix + "DeviceTenant")).as(description).isTrue();
        assertThat(requirement.has(prefix + "DeviceCredential")).as(description).isTrue();
        assertThat(operation.path("parameters"))
                .noneMatch(parameter -> "contextScopeKey".equals(
                                parameter.path("name").asText())
                        || "X-DWP-Expected-Decision-Revision".equals(
                                parameter.path("name").asText()));
    }

    private void assertCommandHeaders(JsonNode operation, String description) {
        assertThat(operation.isObject()).as(description).isTrue();
        assertThat(operation.path("parameters"))
                .filteredOn(parameter -> "header".equals(parameter.path("in").asText())
                        && "Idempotency-Key".equalsIgnoreCase(
                                parameter.path("name").asText()))
                .as(description).singleElement()
                .satisfies(parameter -> assertThat(
                        parameter.path("required").asBoolean(false)).isTrue());
        assertThat(operation.path("parameters"))
                .filteredOn(parameter -> "header".equals(parameter.path("in").asText())
                        && "X-Correlation-ID".equalsIgnoreCase(
                                parameter.path("name").asText()))
                .as(description).singleElement()
                .satisfies(parameter -> assertThat(
                        parameter.path("required").asBoolean(true)).isFalse());
    }

    private JsonNode read(String relative) throws Exception {
        return objectMapper.readTree(repositoryRoot().resolve(relative).toFile());
    }

    private Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("contracts/openapi/platform.json"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Backend repository root could not be located.");
    }
}
