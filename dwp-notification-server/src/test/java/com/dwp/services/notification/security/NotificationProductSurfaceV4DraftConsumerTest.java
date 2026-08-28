package com.dwp.services.notification.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationProductSurfaceV4DraftConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void consumesNotificationsProductSurfacePolicyAndOwnerBindingsFromV4Draft()
            throws IOException {
        JsonNode document = objectMapper.readTree(Files.readString(v4Draft()));

        assertThat(document.path("version").asInt()).isEqualTo(4);
        assertThat(document.path("bundleStatus").asText()).isEqualTo("DRAFT");
        assertThat(NotificationProductSurfaceContract.OWNER_SERVICE)
                .isEqualTo("dwp-notification-server");

        JsonNode policy = find(
                document.path("accessPolicies"),
                "accessPolicyKey",
                NotificationProductSurfaceContract.ACCESS_POLICY_KEY);
        assertThat(policy.path("productKey").asText())
                .isEqualTo(NotificationProductSurfaceContract.PRODUCT_KEY);
        assertThat(policy.path("surfaceKey").asText())
                .isEqualTo(NotificationProductSurfaceContract.SURFACE_KEY);
        assertThat(policy.path("scopeResolver").asText()).isEqualTo("SELF");
        assertThat(policy.path("entitlementExpressionKey").asText())
                .isEqualTo("NOTIFICATIONS_WORK_ACCESS_V1");

        JsonNode expression = find(
                document.path("entitlementExpressions"),
                "expressionKey",
                "NOTIFICATIONS_WORK_ACCESS_V1");
        assertThat(expression.at("/expression/entitlement").asText())
                .isEqualTo("APP.NOTIFICATIONS:VIEW");

        JsonNode capability = find(
                document.path("capabilities"),
                "contractKey",
                NotificationProductSurfaceContract.ACTION_CAPABILITY_KEY);
        assertThat(capability.path("resolvedCapabilityCode").asText())
                .isEqualTo("APP.NOTIFICATIONS:VIEW");
        assertThat(capability.path("scopeResolver").asText()).isEqualTo("SELF");

        NotificationProductSurfaceContract contract =
                new NotificationProductSurfaceContract();
        for (NotificationProductSurfaceContract.BindingDescriptor descriptor
                : contract.descriptors()) {
            JsonNode route = find(
                    document.path("routes"), "routeContractKey", descriptor.routeContractKey());
            assertThat(route.path("routeKind").asText()).isEqualTo(descriptor.routeKind());
            assertThat(route.at("/subject/productKey").asText())
                    .isEqualTo(NotificationProductSurfaceContract.PRODUCT_KEY);
            assertThat(route.at("/subject/surfaceKey").asText())
                    .isEqualTo(NotificationProductSurfaceContract.SURFACE_KEY);

            JsonNode gateway = route.path("gatewayApiBindings").get(0);
            assertThat(gateway.path("method").asText()).isEqualTo(descriptor.method());
            assertThat(gateway.path("path").asText()).isEqualTo(descriptor.publicPath());

            JsonNode owner = route.path("servicePepBindings").get(0);
            assertThat(owner.path("method").asText()).isEqualTo(descriptor.method());
            assertThat(owner.path("path").asText()).isEqualTo(descriptor.ownerPath());
            assertThat(owner.path("serviceKey").asText()).isEqualTo(descriptor.serviceKey());

            JsonNode accessProfile = route.path("accessProfiles").get(0);
            assertThat(textSet(accessProfile.path("activeAccessModes")))
                    .isEqualTo(Set.of("NORMAL", "ELEVATED"));
        }

        JsonNode page = find(
                document.path("routes"),
                "routeContractKey",
                NotificationProductSurfaceContract.CENTER_PAGE_ROUTE);
        assertThat(page.path("uiRouteId").asText()).isEqualTo("notifications.work.center");
        assertThat(page.path("uiRoutePattern").asText()).isEqualTo("/notifications/center");

        JsonNode data = find(
                document.path("routes"),
                "routeContractKey",
                NotificationProductSurfaceContract.SUMMARY_DATA_ROUTE);
        assertThat(data.path("sideEffectFree").asBoolean()).isTrue();

        JsonNode action = find(
                document.path("routes"),
                "routeContractKey",
                NotificationProductSurfaceContract.READ_ACTION_ROUTE);
        assertThat(action.at("/accessProfiles/0/requiredAccess/type").asText())
                .isEqualTo("CAPABILITY");
        assertThat(action.at("/accessProfiles/0/requiredAccess/capabilityContractKey").asText())
                .isEqualTo(NotificationProductSurfaceContract.ACTION_CAPABILITY_KEY);
    }

    private JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode item : array) {
            if (value.equals(item.path(field).asText())) return item;
        }
        throw new AssertionError("Missing " + field + '=' + value);
    }

    private Set<String> textSet(JsonNode array) {
        java.util.Set<String> values = new java.util.HashSet<>();
        array.forEach(value -> values.add(value.asText()));
        return Set.copyOf(values);
    }

    private Path v4Draft() {
        Path direct = Path.of(
                "contracts/product-authorization/product-surfaces-v1.bundle-v4.json");
        if (Files.isRegularFile(direct)) return direct;
        Path moduleRelative = Path.of(
                "../contracts/product-authorization/product-surfaces-v1.bundle-v4.json");
        if (Files.isRegularFile(moduleRelative)) return moduleRelative;
        throw new AssertionError("The Product Surface v4 DRAFT artifact is unavailable.");
    }
}
