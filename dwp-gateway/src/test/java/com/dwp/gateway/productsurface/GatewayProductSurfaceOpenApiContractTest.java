package com.dwp.gateway.productsurface;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/** Bidirectional guard between the functional Router DTOs and their public artifact. */
class GatewayProductSurfaceOpenApiContractTest {

    private static final Map<String, String> PUBLIC_PATHS = Map.of(
            "/api/auth/product-surface-contexts", "get",
            "/api/auth/product-surface-access/evaluate", "post",
            "/api/auth/governed-route-access/evaluate", "post");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void gatewayOwnedRoutesAreExportedWithoutLeakingIntoTheAuthOwnerSnapshot() throws Exception {
        Path root = repositoryRoot();
        JsonNode owned = read(root, "contracts/openapi/gateway-owned.json");
        JsonNode gateway = read(root, "contracts/openapi/gateway-public.json");
        JsonNode auth = read(root, "contracts/openapi/auth.json");

        PUBLIC_PATHS.forEach((path, method) -> {
            assertThat(owned.path("paths").path(path).has(method)).isTrue();
            assertThat(gateway.path("paths").path(path).has(method)).isTrue();
            assertThat(auth.path("paths").has(path.replaceFirst("^/api", ""))).isFalse();
        });
        JsonNode publicSchemas = gateway.path("components").path("schemas");
        assertThat(publicSchemas.has("auth_BundleView")).isFalse();
        assertThat(publicSchemas.has("people_ProductSurfaceEligibilityResult")).isFalse();
        assertThat(publicSchemas.has("provider_FeatureRolloutInternalEvaluation")).isFalse();
    }

    @Test
    void everyW0OwnerEndpointIsCapturedAndOnlyPublicTelemetryIsComposed() throws Exception {
        Path root = repositoryRoot();
        JsonNode auth = read(root, "contracts/openapi/auth.json");
        JsonNode people = read(root, "contracts/openapi/people.json");
        JsonNode provider = read(root, "contracts/openapi/provider.json");
        JsonNode platform = read(root, "contracts/openapi/platform.json");
        JsonNode gateway = read(root, "contracts/openapi/gateway-public.json");

        assertOperation(auth, "/internal/auth/v1/product-surface-authority/evaluate", "post",
                "evaluateProductSurfaceAuthorityInternal");
        assertOperation(auth, "/internal/auth/v1/governed-route-authority/evaluate", "post",
                "evaluateGovernedRouteAuthorityInternal");
        assertOperation(auth,
                "/internal/auth/v1/product-authorization/bundles/{bundleKey}/active", "get",
                "getActiveProductAuthorizationBundleInternal");
        assertOperation(auth,
                "/internal/auth/v1/product-authorization/bundles/{bundleKey}/versions/{version}",
                "get", "getProductAuthorizationBundleVersionInternal");
        assertOperation(people,
                "/internal/people/v1/product-surface-eligibility/evaluate", "post",
                "evaluateProductSurfaceEligibilityInternal");
        assertOperation(provider,
                "/internal/provider/v1/feature-rollouts/evaluate", "post",
                "evaluateProductSurfaceFeatureRolloutInternal");
        assertOperation(platform, "/v1/observability/product-surface-events", "post",
                "ingestProductSurfaceTelemetry");
        assertOperation(gateway,
                "/api/platform/v1/observability/product-surface-events", "post",
                "platform_ingestProductSurfaceTelemetry");
        JsonNode publicTelemetry = gateway.path("paths")
                .path("/api/platform/v1/observability/product-surface-events")
                .path("post");
        assertThat(publicTelemetry.path("parameters"))
                .noneMatch(parameter -> "X-DWP-Tenant-ID".equals(parameter.path("name").asText())
                        || "X-DWP-Rollout-Cohort".equals(parameter.path("name").asText()));

        assertThat(gateway.path("paths").propertyStream()
                .map(entry -> entry.getKey()))
                .noneMatch(path -> path.contains("/internal/auth/")
                        || path.contains("/internal/people/")
                        || path.contains("/internal/provider/"));
    }

    @Test
    void governedOperationsExposeOneGatewayConsumedScopeSelectionParameter() throws Exception {
        JsonNode gateway = read(repositoryRoot(), "contracts/openapi/gateway-public.json");
        for (Map.Entry<String, String> route : Map.of(
                "/api/approvals/v1/admin/workflows", "get",
                "/api/approvals/v1/admin/workflows/{workflowId}/publish", "post",
                "/api/approvals/v1/home", "get").entrySet()) {
            JsonNode parameters = gateway.path("paths").path(route.getKey())
                    .path(route.getValue()).path("parameters");
            assertThat(parameters)
                    .filteredOn(parameter -> "query".equals(parameter.path("in").asText())
                            && "contextScopeKey".equals(parameter.path("name").asText()))
                    .singleElement()
                    .satisfies(parameter -> {
                        assertThat(parameter.path("required").asBoolean(true)).isFalse();
                        assertThat(parameter.path("schema").path("maxLength").asInt())
                                .isEqualTo(200);
                        assertThat(parameter.path("description").asText())
                                .contains("exactly one", "consumes", "fail closed");
                    });
        }

        assertThat(gateway.path("paths")
                .path("/api/auth/product-surface-step-up-challenges")
                .path("post").path("parameters"))
                .noneMatch(parameter ->
                        "contextScopeKey".equals(parameter.path("name").asText()));
    }

    @Test
    void everyActiveStateChangingProductBindingExposesTheConditionalRevisionHeader()
            throws Exception {
        Path root = repositoryRoot();
        JsonNode registry = read(root,
                "contracts/product-authorization/product-surfaces-v1.bundle-v3.json");
        JsonNode gateway = read(root, "contracts/openapi/gateway-public.json");
        Set<GatewayOperation> expected = new HashSet<>();

        for (JsonNode route : registry.path("routes")) {
            if (!"ACTIVE".equals(route.path("lifecycleState").asText())
                    || !"PRODUCT".equals(route.path("subject").path("type").asText())
                    || !"ACTION".equals(route.path("routeKind").asText())
                    || route.path("sideEffectFree").asBoolean(false)) {
                continue;
            }
            for (JsonNode binding : route.path("gatewayApiBindings")) {
                expected.add(new GatewayOperation(
                        binding.path("path").asText(),
                        binding.path("method").asText().toLowerCase()));
            }
        }
        assertThat(expected).isNotEmpty();

        expected.forEach(binding -> {
            JsonNode operation = gateway.path("paths").path(binding.path())
                    .path(binding.method());
            assertThat(operation.isObject())
                    .as("exported governed mutation %s %s", binding.method(), binding.path())
                    .isTrue();
            assertThat(StreamSupport.stream(
                            operation.path("parameters").spliterator(), false)
                    .filter(parameter -> "header".equalsIgnoreCase(
                            parameter.path("in").asText()))
                    .filter(parameter -> "X-DWP-Expected-Decision-Revision".equalsIgnoreCase(
                            parameter.path("name").asText()))
                    .toList())
                    .as("revision header for %s %s", binding.method(), binding.path())
                    .singleElement()
                    .satisfies(parameter -> {
                        assertThat(parameter.path("required").asBoolean(true)).isFalse();
                        assertThat(parameter.path("description").asText())
                                .contains("110/111", "000/100", "fail-closed");
                        assertThat(parameter.path("schema").path("type").asText())
                                .isEqualTo("string");
                        assertThat(parameter.path("schema").path("minLength").asInt())
                                .isEqualTo(1);
                        assertThat(parameter.path("schema").path("maxLength").asInt())
                                .isEqualTo(200);
                        assertThat(parameter.path("x-dwp-conditional-required")
                                .path("enforcement").asText()).isEqualTo("FAIL_CLOSED");
                        assertThat(parameter.path("x-dwp-conditional-required")
                                .path("rolloutStates"))
                                .extracting(JsonNode::asText)
                                .containsExactly("110", "111");
                    });
        });

        Set<GatewayOperation> actualConditional = new HashSet<>();
        gateway.path("paths").properties().forEach(path ->
                path.getValue().properties().forEach(method -> {
                    boolean conditionalRevision = StreamSupport.stream(
                                    method.getValue().path("parameters").spliterator(), false)
                            .anyMatch(parameter -> "header".equalsIgnoreCase(
                                            parameter.path("in").asText())
                                    && "X-DWP-Expected-Decision-Revision".equalsIgnoreCase(
                                            parameter.path("name").asText())
                                    && "FAIL_CLOSED".equals(parameter
                                            .path("x-dwp-conditional-required")
                                            .path("enforcement").asText()));
                    if (conditionalRevision) {
                        actualConditional.add(new GatewayOperation(
                                path.getKey(), method.getKey()));
                    }
                }));
        assertThat(actualConditional).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void publicRecordAndEnumSchemasMatchTheRuntimeDtosInBothDirections() throws Exception {
        Path root = repositoryRoot();
        JsonNode schemas = read(root, "contracts/openapi/gateway-owned.json")
                .path("components").path("schemas");
        Map<String, Class<?>> records = Map.ofEntries(
                Map.entry("ProductEvaluationRequest",
                        ProductSurfaceContextDtos.ProductEvaluationRequest.class),
                Map.entry("GovernedEvaluationRequest",
                        ProductSurfaceContextDtos.GovernedEvaluationRequest.class),
                Map.entry("GovernedTarget", ProductSurfaceContextDtos.GovernedTarget.class),
                Map.entry("Subject", ProductSurfaceContextDtos.Subject.class),
                Map.entry("ContextListData", ProductSurfaceContextDtos.ContextListData.class),
                Map.entry("ProductRollout", ProductSurfaceContextDtos.ProductRollout.class),
                Map.entry("RolloutFlags", ProductSurfaceContextDtos.RolloutFlags.class),
                Map.entry("ProductEvaluationData",
                        ProductSurfaceContextDtos.ProductEvaluationData.class),
                Map.entry("GovernedEvaluationData",
                        ProductSurfaceContextDtos.GovernedEvaluationData.class),
                Map.entry("GovernedRouteAccessContext",
                        ProductSurfaceContextDtos.GovernedRouteAccessContext.class),
                Map.entry("SourceRevisions", ProductSurfaceContextDtos.SourceRevisions.class),
                Map.entry("EffectiveContext", ProductSurfaceContextDtos.EffectiveContext.class),
                Map.entry("EffectiveScope", ProductSurfaceContextDtos.EffectiveScope.class),
                Map.entry("Responsibility", ProductSurfaceContextDtos.Responsibility.class));
        records.forEach((schema, type) -> assertRecordProperties(schemas.path(schema), type));
        assertThat(schemas.path("ProductSubject").path("properties").propertyStream()
                .map(entry -> entry.getKey())).containsExactlyInAnyOrder(
                        "type", "productKey", "surfaceKey");
        assertThat(schemas.path("GovernedSubject").path("properties").propertyStream()
                .map(entry -> entry.getKey())).containsExactly("type");
        assertThat(schemas.path("ProductEvaluationRequest")
                .path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(schemas.path("GovernedEvaluationRequest")
                .path("additionalProperties").asBoolean(true)).isFalse();

        assertEnum(schemas.path("AccessMode"), ProductSurfaceContextDtos.AccessMode.class);
        assertEnum(schemas.path("AccessSource"), ProductSurfaceContextDtos.AccessSource.class);
        assertEnum(schemas.path("Decision"), ProductSurfaceContextDtos.Decision.class);
        assertEnum(schemas.path("GovernedDecision"),
                ProductSurfaceContextDtos.GovernedDecision.class);
        assertEnum(schemas.path("AuthorityStatus"),
                ProductSurfaceContextDtos.AuthorityStatus.class);
    }

    @Test
    void requestExamplesRoundTripThroughTheExactRouterTypes() throws Exception {
        String product = """
                {"subject":{"type":"PRODUCT","productKey":"approvals",\
                "surfaceKey":"approvals.management"},\
                "routeContractKey":"route.approvals.management.forms.page",\
                "contextKey":"context-1","contextScopeKey":"scope-1"}
                """;
        String governed = """
                {"subject":{"type":"GOVERNED_CONTEXT"},"navigationContextId":"work.work",\
                "routeContractKey":"route.context.work__work.review-detail.data",\
                "target":{"opaqueTargetRef":"f1c98d2c-6093-4553-8633-9b11589472f8",\
                "expectedObjectVersion":"11"},"contextKey":"context-2"}
                """;

        var productValue = objectMapper.readValue(
                product, ProductSurfaceContextDtos.ProductEvaluationRequest.class);
        var governedValue = objectMapper.readValue(
                governed, ProductSurfaceContextDtos.GovernedEvaluationRequest.class);
        JsonNode productRoundTrip = objectMapper.valueToTree(productValue);
        JsonNode governedRoundTrip = objectMapper.valueToTree(governedValue);

        assertThat(productRoundTrip).isEqualTo(objectMapper.readTree(product));
        assertThat(governedRoundTrip).isEqualTo(objectMapper.readTree(governed));
    }

    private void assertRecordProperties(JsonNode schema, Class<?> type) {
        Set<String> runtime = Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toUnmodifiableSet());
        Set<String> artifact = schema.path("properties").propertyStream()
                .map(entry -> entry.getKey())
                .collect(Collectors.toUnmodifiableSet());
        assertThat(artifact).as(type.getSimpleName()).isEqualTo(runtime);
    }

    private void assertMethod(JsonNode document, String path, String method) {
        assertThat(document.path("paths").path(path).has(method))
                .as("%s %s", method, path)
                .isTrue();
    }

    private void assertOperation(
            JsonNode document,
            String path,
            String method,
            String operationId) {
        assertMethod(document, path, method);
        assertThat(document.path("paths").path(path).path(method)
                .path("operationId").asText())
                .as("%s %s operationId", method, path)
                .isEqualTo(operationId);
    }

    private void assertEnum(JsonNode schema, Class<? extends Enum<?>> type) {
        assertThat(schema.path("enum"))
                .extracting(JsonNode::asText)
                .containsExactly(Arrays.stream(type.getEnumConstants())
                        .map(Enum::name).toArray(String[]::new));
    }

    private JsonNode read(Path root, String relative) throws Exception {
        return objectMapper.readTree(root.resolve(relative).toFile());
    }

    private Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("contracts/openapi/auth.json"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Backend repository root could not be located.");
    }

    private record GatewayOperation(String path, String method) {
    }
}
