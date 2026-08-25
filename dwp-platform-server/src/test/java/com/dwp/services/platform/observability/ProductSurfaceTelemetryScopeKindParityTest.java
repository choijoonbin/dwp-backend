package com.dwp.services.platform.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductSurfaceTelemetryScopeKindParityTest {

    private static final List<String> CANONICAL_SCOPE_KINDS = List.of(
            "TENANT",
            "SELF",
            "TEAM",
            "ORG_UNIT",
            "LEGAL_ENTITY",
            "DOMAIN",
            "RESOURCE_SET",
            "RESOURCE",
            "POLICY_NODE",
            "TARGET_POPULATION",
            "SUPPORT_SESSION");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void strictTelemetryParsingAcceptsExactlyTheCanonicalScopeKinds() throws Exception {
        assertThat(Arrays.stream(ProductSurfaceTelemetryDtos.ScopeKind.values())
                .map(Enum::name))
                .containsExactlyElementsOf(CANONICAL_SCOPE_KINDS);

        for (String scopeKind : CANONICAL_SCOPE_KINDS) {
            var payload = objectMapper.readTree("""
                    {
                      "schemaVersion": 1,
                      "eventName": "surface.scope.invalid",
                      "productKey": "hcm",
                      "surfaceKey": "hcm.operations",
                      "scopeKind": "%s",
                      "reasonCode": "SCOPE_INVALID"
                    }
                    """.formatted(scopeKind));

            ProductSurfaceTelemetryDtos.EventRequest request =
                    ProductSurfaceTelemetryDtos.parseStrict(
                            payload,
                            objectMapper,
                            Validation.buildDefaultValidatorFactory().getValidator());

            assertThat(request.scopeKind().name()).isEqualTo(scopeKind);
            ProductSurfaceTelemetryService.validateEvent(request);
        }

        var unknown = objectMapper.readTree("""
                {
                  "schemaVersion": 1,
                  "eventName": "surface.scope.invalid",
                  "productKey": "hcm",
                  "surfaceKey": "hcm.operations",
                  "scopeKind": "UNKNOWN_SCOPE",
                  "reasonCode": "SCOPE_INVALID"
                }
                """);
        assertThatThrownBy(() -> ProductSurfaceTelemetryDtos.parseStrict(
                unknown,
                objectMapper,
                Validation.buildDefaultValidatorFactory().getValidator()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nextMigrationReplacesThePostgresCheckWithTheCanonicalScopeKinds()
            throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V181__align_product_surface_scope_kind_telemetry.sql"));
        Matcher matcher = Pattern.compile("'([A-Z][A-Z_]*)'").matcher(migration);
        Set<String> constrainedKinds = new LinkedHashSet<>();
        while (matcher.find()) constrainedKinds.add(matcher.group(1));

        assertThat(constrainedKinds).containsExactlyElementsOf(CANONICAL_SCOPE_KINDS);
        assertThat(migration)
                .contains("DROP CONSTRAINT IF EXISTS "
                        + "plt_product_surface_ux_event_scope_kind_check")
                .contains("ADD CONSTRAINT ck_plt_product_surface_ux_event_scope_kind")
                .contains("scope_kind IS NULL");
    }

    @Test
    void approvedPlatformAndGatewayOpenApiArtifactsExposeTheCanonicalScopeKinds()
            throws Exception {
        assertOpenApiScopeKinds(
                "platform.json",
                "/components/schemas/ProductSurfaceTelemetryEventRequest/"
                        + "properties/scopeKind/enum");
        assertOpenApiScopeKinds(
                "gateway-public.json",
                "/components/schemas/platform_ProductSurfaceTelemetryEventRequest/"
                        + "properties/scopeKind/enum");
    }

    private void assertOpenApiScopeKinds(String artifact, String pointer) throws Exception {
        var values = objectMapper.readTree(Files.readString(
                Path.of("../contracts/openapi", artifact))).at(pointer);

        assertThat(values.isArray()).isTrue();
        assertThat(StreamSupport.stream(values.spliterator(), false)
                .map(value -> value.asText()))
                .containsExactlyElementsOf(CANONICAL_SCOPE_KINDS);
    }
}
