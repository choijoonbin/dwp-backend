package com.dwp.services.platform.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PublicOpenApiConfigurationTest {

    @Test
    void removesGatewayTrustedIdentityHeadersButKeepsPublicInputs() {
        Operation operation = new Operation()
                .addParametersItem(header("X-DWP-Tenant-ID"))
                .addParametersItem(header("X-DWP-Person-Public-ID"))
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

    private Parameter header(String name) {
        return new Parameter().in("header").name(name);
    }
}
