package com.dwp.services.platform.config;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Configuration
class PublicOpenApiConfiguration {

    private static final Set<String> TRUSTED_IDENTITY_HEADERS = Set.of(
            "x-dwp-tenant-id",
            "x-dwp-user-id",
            "x-dwp-person-public-id",
            "x-dwp-display-name-b64",
            "x-dwp-permissions");

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

    private void removeTrustedHeaders(Operation operation) {
        if (operation.getParameters() == null) return;
        operation.getParameters().removeIf(parameter ->
                "header".equalsIgnoreCase(parameter.getIn())
                        && parameter.getName() != null
                        && TRUSTED_IDENTITY_HEADERS.contains(
                                parameter.getName().toLowerCase(Locale.ROOT)));
    }
}
