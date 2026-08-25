package com.dwp.services.platform.config;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
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

@Configuration
class PublicOpenApiConfiguration {
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
