package com.dwp.services.platform.config;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Configuration
class PublicOpenApiConfiguration {
    static final String DEVICE_CREDENTIAL_SCHEME = "DeviceCredential";
    static final String DEVICE_TENANT_SCHEME = "DeviceTenant";
    private static final String DEVICE_IDENTITY_PLANE = "DEVICE";
    private static final Set<String> DEVICE_IDENTITY_PATHS = Set.of(
            "/v1/device/workplace/devices:register",
            "/v1/device/workplace/devices/{deviceId}/heartbeat",
            "/v1/device/workplace/devices/{deviceId}/projection",
            "/v1/device/workplace/devices/{deviceId}/access-pass:pair",
            "/v1/workplace/kiosk/session",
            "/v1/workplace/kiosk/visits/{visitId}",
            "/v1/workplace/kiosk/visits/{visitId}:arrive",
            "/v1/workplace/kiosk/visits/{visitId}:checkout",
            "/v1/workplace/kiosk/devices/{deviceId}:heartbeat",
            "/v1/workplace/kiosk/devices/{deviceId}:help");
    private static final List<String> HOME_PERSONALIZATION_PATHS = List.of(
            "/v1/home-views", "/v1/home-templates", "/v1/home-composer");

    @Bean
    OpenApiCustomizer hideGatewayTrustedIdentityHeaders() {
        return openApi -> {
            if (openApi.getPaths() == null) return;
            openApi.getPaths().values().stream()
                    .map(PathItem::readOperations)
                    .flatMap(List::stream)
                    .forEach(this::removeTrustedHeaders);
        };
    }

    /**
     * Keeps the actorless Workplace device plane explicit in the owner contract.
     *
     * <p>The owner service consumes headers that were verified and rewritten by the Gateway.
     * The exporter projects these two schemes to their public header names when it composes the
     * Gateway contract. Human PRODUCT authorization is deliberately absent from these operations.</p>
     */
    @Bean
    OpenApiCustomizer documentWorkplaceDeviceIdentityPlane() {
        return openApi -> {
            Components components = openApi.getComponents();
            if (components == null) {
                components = new Components();
                openApi.setComponents(components);
            }
            components.addSecuritySchemes(DEVICE_TENANT_SCHEME, apiKey(
                    "X-DWP-Tenant-ID",
                    "Gateway-verified tenant evidence for the actorless DEVICE identity plane."));
            components.addSecuritySchemes(DEVICE_CREDENTIAL_SCHEME, apiKey(
                    "X-DWP-Device-Credential",
                    "Gateway-verified device credential. The owner service hashes it before "
                            + "matching tenant-bound device evidence."));
            if (openApi.getPaths() == null) return;
            openApi.getPaths().forEach((path, item) -> {
                if (!DEVICE_IDENTITY_PATHS.contains(path)) return;
                item.readOperations().forEach(operation -> {
                    operation.setSecurity(List.of(new SecurityRequirement()
                            .addList(DEVICE_TENANT_SCHEME)
                            .addList(DEVICE_CREDENTIAL_SCHEME)));
                    operation.addExtension("x-dwp-identity-plane", DEVICE_IDENTITY_PLANE);
                });
            });
        };
    }

    @Bean
    OpenApiCustomizer documentHomePersonalizationErrors() {
        return openApi -> {
            if (openApi.getPaths() == null) return;
            openApi.getPaths().forEach((path, item) -> {
                if (HOME_PERSONALIZATION_PATHS.stream().noneMatch(path::startsWith)) return;
                item.readOperationsMap().forEach((method, operation) -> {
                    ApiResponses responses = operation.getResponses() == null
                            ? new ApiResponses() : operation.getResponses();
                    operation.setResponses(responses);
                    responses.putIfAbsent("400", error("Invalid request"));
                    responses.putIfAbsent("403", error("Feature, tenant policy, or permission denied"));
                    responses.putIfAbsent("404", error("Owned resource or dependency not found"));
                    responses.putIfAbsent("409", error("State, version, or idempotency conflict"));
                    boolean boundedRequestBody = operation.getRequestBody() != null
                            && (method == PathItem.HttpMethod.POST
                            || method == PathItem.HttpMethod.PUT
                            || method == PathItem.HttpMethod.PATCH);
                    if (boundedRequestBody) {
                        responses.putIfAbsent("413", error("Request body exceeds the bounded limit"));
                    } else {
                        responses.remove("413");
                    }
                });
            });
        };
    }

    @Bean
    OpenApiCustomizer refineHomePersonalizationSchemas() {
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            Schema<?> widgetConfiguration =
                    openApi.getComponents().getSchemas().get("WidgetConfigurationPayload");
            Schema<?> itemLimit = property(widgetConfiguration, "itemLimit");
            if (itemLimit != null) itemLimit.setMaximum(BigDecimal.valueOf(20));

            Schema<?> deviceOverlay =
                    openApi.getComponents().getSchemas().get("DeviceLayoutOverlay");
            Schema<?> widgetSizes = property(deviceOverlay, "widgetSizes");
            if (widgetSizes != null) {
                widgetSizes.setMaxProperties(30);
                widgetSizes.setPropertyNames(
                        new StringSchema().pattern("[a-z][a-z0-9-]{0,39}"));
                widgetSizes.setAdditionalProperties(new StringSchema()
                        ._enum(List.of(
                                "fifth", "quarter", "compact",
                                "medium", "large", "full"))
                        .pattern("fifth|quarter|compact|medium|large|full"));
            }
        };
    }

    private Schema<?> property(Schema<?> owner, String name) {
        if (owner == null || owner.getProperties() == null) return null;
        return owner.getProperties().get(name);
    }

    private SecurityScheme apiKey(String header, String description) {
        return new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .name(header)
                .description(description);
    }

    private void removeTrustedHeaders(Operation operation) {
        if (operation.getParameters() == null) return;
        operation.getParameters().removeIf(parameter ->
                "header".equalsIgnoreCase(parameter.getIn())
                        && parameter.getName() != null
                        && parameter.getName().toLowerCase(Locale.ROOT)
                                .startsWith("x-dwp-"));
    }

    private io.swagger.v3.oas.models.responses.ApiResponse error(String description) {
        Schema<?> schema = new Schema<>().$ref("#/components/schemas/ApiResponseVoid");
        return new io.swagger.v3.oas.models.responses.ApiResponse()
                .description(description)
                .content(new Content().addMediaType(
                        "application/json", new MediaType().schema(schema)));
    }
}
