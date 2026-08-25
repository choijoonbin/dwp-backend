package com.dwp.services.platform.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PublicOpenApiConfigurationTest {

    @Test
    void removesGatewayTrustedIdentityHeadersButKeepsPublicInputs() {
        Operation operation = new Operation()
                .addParametersItem(header("X-DWP-Tenant-ID"))
                .addParametersItem(header("X-DWP-Person-Public-ID"))
                .addParametersItem(header("X-DWP-Group-Refs"))
                .addParametersItem(header("X-DWP-Resource-Roles"))
                .addParametersItem(header("X-DWP-Rollout-Cohort"))
                .addParametersItem(header("Accept-Language"))
                .addParametersItem(new Parameter().in("query").name("from"));
        OpenAPI openApi = new OpenAPI().paths(new Paths().addPathItem(
                "/v1/workplace/explore", new PathItem().get(operation)));

        new PublicOpenApiConfiguration()
                .hideGatewayTrustedIdentityHeaders()
                .customise(openApi);

        assertThat(operation.getParameters())
                .extracting(Parameter::getName)
                .containsExactly("Accept-Language", "from");
    }

    @Test
    void documentsBoundedHomeMutationErrors() {
        Operation operation = new Operation().requestBody(new RequestBody());
        OpenAPI openApi = new OpenAPI().paths(new Paths().addPathItem(
                "/v1/home-views/{viewId}", new PathItem().put(operation)));

        new PublicOpenApiConfiguration()
                .documentHomePersonalizationErrors()
                .customise(openApi);

        assertThat(operation.getResponses().keySet())
                .containsExactlyInAnyOrder("400", "403", "404", "409", "413");
        assertThat(operation.getResponses().get("409").getContent()
                .get("application/json").getSchema().get$ref())
                .isEqualTo("#/components/schemas/ApiResponseVoid");
    }

    @Test
    void doesNotDocumentBodyLimitForBodylessHomeDelete() {
        Operation operation = new Operation();
        OpenAPI openApi = new OpenAPI().paths(new Paths().addPathItem(
                "/v1/home-views/{viewId}", new PathItem().delete(operation)));

        new PublicOpenApiConfiguration()
                .documentHomePersonalizationErrors()
                .customise(openApi);

        assertThat(operation.getResponses().keySet())
                .containsExactlyInAnyOrder("400", "403", "404", "409");
    }

    @Test
    void documentsCommonErrorsForHomeCollectionReads() {
        Operation operation = new Operation();
        OpenAPI openApi = new OpenAPI().paths(new Paths().addPathItem(
                "/v1/home-views", new PathItem().get(operation)));

        new PublicOpenApiConfiguration()
                .documentHomePersonalizationErrors()
                .customise(openApi);

        assertThat(operation.getResponses().keySet())
                .containsExactlyInAnyOrder("400", "403", "404", "409");
    }

    @Test
    void refinesWidgetConfigurationAndDeviceOverlaySchemas() {
        Schema<?> itemLimit = new IntegerSchema().minimum(java.math.BigDecimal.ONE);
        Schema<?> widgetConfiguration = new ObjectSchema()
                .addProperty("itemLimit", itemLimit);
        Schema<?> widgetSizes = new ObjectSchema()
                .additionalProperties(new StringSchema());
        Schema<?> deviceOverlay = new ObjectSchema()
                .addProperty("widgetSizes", widgetSizes);
        OpenAPI openApi = new OpenAPI().components(new Components()
                .addSchemas("WidgetConfigurationPayload", widgetConfiguration)
                .addSchemas("DeviceLayoutOverlay", deviceOverlay));

        new PublicOpenApiConfiguration()
                .refineHomePersonalizationSchemas()
                .customise(openApi);

        assertThat(itemLimit.getMaximum()).isEqualByComparingTo("20");
        assertThat(widgetSizes.getMaxProperties()).isEqualTo(30);
        assertThat(widgetSizes.getPropertyNames().getPattern())
                .isEqualTo("[a-z][a-z0-9-]{0,39}");
        assertThat(widgetSizes.getAdditionalProperties()).isInstanceOf(Schema.class);
        Schema<?> values = (Schema<?>) widgetSizes.getAdditionalProperties();
        assertThat(values.getEnum()).isEqualTo(List.of(
                "fifth", "quarter", "compact", "medium", "large", "full"));
        assertThat(values.getPattern())
                .isEqualTo("fifth|quarter|compact|medium|large|full");
    }

    private Parameter header(String name) {
        return new Parameter().in("header").name(name);
    }
}
