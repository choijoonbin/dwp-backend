package com.dwp.services.platform.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Runtime privacy allowlist for governed surfaces, anchored to the immutable W1b v3 registry. */
@Component
public final class ProductSurfaceTelemetryDimensionRegistry {

    static final String RESOURCE =
            "product-authorization/platform-telemetry-dimensions-v3.generated.json";
    static final String W1B_V3_REGISTRY_CHECKSUM =
            "f90c4e3a734204a4619ae77d3476ebc7cc802c43ed8574fcf4f3fc85def67a8e";
    static final String PROJECTION_CHECKSUM =
            "21b78a9c98c958e962ce20e356955da920cefb2543b6dd5df26c4d6283b3b15d";
    private static final Pattern PRODUCT_KEY = Pattern.compile("[a-z][a-z0-9-]{0,47}");
    private static final Pattern DIMENSION_KEY =
            Pattern.compile("[a-z][a-z0-9-]{0,47}(\\.[a-z][a-z0-9-]{0,47})+");
    private static final Set<String> ROOT_FIELDS = Set.of(
            "ownerServiceKey", "productCount", "products", "projectionChecksum",
            "projectionChecksumAlgorithm", "projectionKey", "registryRef", "routeIdCount",
            "schemaVersion", "sourceRegistryRouteCount", "surfaceCount");
    private static final Set<String> REGISTRY_FIELDS =
            Set.of("bundleKey", "sha256", "version");
    private static final Set<String> PRODUCT_FIELDS = Set.of("productKey", "surfaces");
    private static final Set<String> SURFACE_FIELDS =
            Set.of("routeIds", "scopeKinds", "surfaceKey", "taskKinds");

    private final ObjectMapper objectMapper;
    private final Map<String, Map<String, SurfaceDimensions>> products;

    @Autowired
    public ProductSurfaceTelemetryDimensionRegistry(ObjectMapper objectMapper) {
        this(objectMapper, new ClassPathResource(RESOURCE));
    }

    ProductSurfaceTelemetryDimensionRegistry(ObjectMapper objectMapper, Resource resource) {
        this.objectMapper = objectMapper;
        ObjectNode projection = readProjection(resource);
        validateEnvelope(projection);
        this.products = compile(projection);
    }

    void validate(ProductSurfaceTelemetryDtos.EventRequest request) {
        Map<String, SurfaceDimensions> surfaces = products.get(request.productKey());
        if (surfaces == null) {
            throw new IllegalArgumentException("Unknown telemetry product dimension");
        }
        Stream.of(
                        request.surfaceKey(),
                        request.fromSurfaceKey(),
                        request.toSurfaceKey(),
                        request.targetSurfaceKey())
                .filter(value -> value != null)
                .forEach(surface -> {
                    if (!surfaces.containsKey(surface)) {
                        throw new IllegalArgumentException(
                                "Telemetry surface does not belong to the product");
                    }
                });
        if (request.routeId() != null) {
            SurfaceDimensions dimensions = surfaces.get(request.surfaceKey());
            if (dimensions == null || !dimensions.routeIds().contains(request.routeId())) {
                throw new IllegalArgumentException(
                        "Telemetry route does not belong to the current surface");
            }
        }
        SurfaceDimensions current = request.surfaceKey() == null
                ? null
                : surfaces.get(request.surfaceKey());
        if (request.taskKind() != null
                && (current == null || !current.taskKinds().contains(request.taskKind()))) {
            throw new IllegalArgumentException(
                    "Telemetry task is not allowed for the current surface");
        }
        if (request.scopeKind() != null
                && (current == null || !current.scopeKinds().contains(request.scopeKind()))) {
            throw new IllegalArgumentException(
                    "Telemetry scope is not allowed for the current surface");
        }
    }

    private ObjectNode readProjection(Resource resource) {
        try (InputStream input = resource.getInputStream()) {
            JsonNode value = objectMapper.readTree(input);
            if (!(value instanceof ObjectNode object)) {
                throw new IllegalStateException(
                        "Generated telemetry dimension projection must be an object");
            }
            return object;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Generated telemetry dimension projection is missing or unreadable",
                    exception);
        }
    }

    private void validateEnvelope(ObjectNode projection) {
        require(fields(projection).equals(ROOT_FIELDS),
                "Telemetry dimension projection fields changed");
        JsonNode registry = projection.path("registryRef");
        require(registry.isObject() && fields(registry).equals(REGISTRY_FIELDS),
                "Telemetry dimension registry reference fields changed");
        require(projection.path("schemaVersion").asInt() == 2
                        && "platform-telemetry-dimensions-v3".equals(
                        projection.path("projectionKey").asText())
                        && "platform".equals(projection.path("ownerServiceKey").asText())
                        && "product-surfaces".equals(registry.path("bundleKey").asText())
                        && registry.path("version").asInt() == 3
                        && W1B_V3_REGISTRY_CHECKSUM.equals(registry.path("sha256").asText()),
                "Telemetry dimension projection is not pinned to registry v3");
        require(projection.path("sourceRegistryRouteCount").asInt() == 129
                        && projection.path("productCount").asInt() == 11
                        && projection.path("surfaceCount").asInt() == 24
                        && projection.path("routeIdCount").asInt() == 127,
                "Telemetry dimension v3 release counts changed");
        require("SHA-256".equals(
                        projection.path("projectionChecksumAlgorithm").asText()),
                "Unsupported telemetry dimension checksum algorithm");
        ObjectNode payload = projection.deepCopy();
        JsonNode checksum = payload.remove("projectionChecksum");
        require(checksum != null
                        && PROJECTION_CHECKSUM.equals(checksum.asText())
                        && checksum.asText().equals(sha256(payload)),
                "Telemetry dimension projection checksum mismatch");
    }

    private Map<String, Map<String, SurfaceDimensions>> compile(ObjectNode projection) {
        ArrayNode descriptors = requiredArray(projection, "products");
        Map<String, Map<String, SurfaceDimensions>> result = new LinkedHashMap<>();
        Set<String> allSurfaces = new LinkedHashSet<>();
        Set<String> allRoutes = new LinkedHashSet<>();
        for (JsonNode descriptor : descriptors) {
            require(descriptor.isObject() && fields(descriptor).equals(PRODUCT_FIELDS),
                    "Invalid telemetry product descriptor");
            String product = requiredText(descriptor, "productKey", PRODUCT_KEY);
            require(!result.containsKey(product),
                    "Duplicate telemetry product dimension");
            Map<String, SurfaceDimensions> surfaces = compileSurfaces(
                    product, requiredArray(descriptor, "surfaces"), allSurfaces, allRoutes);
            result.put(product, Map.copyOf(surfaces));
        }
        require(result.size() == projection.path("productCount").asInt()
                        && allSurfaces.size() == projection.path("surfaceCount").asInt()
                        && allRoutes.size() == projection.path("routeIdCount").asInt(),
                "Telemetry dimension projection counts do not match its content");
        return Map.copyOf(result);
    }

    private Map<String, SurfaceDimensions> compileSurfaces(
            String product,
            ArrayNode descriptors,
            Set<String> allSurfaces,
            Set<String> allRoutes) {
        Map<String, SurfaceDimensions> result = new LinkedHashMap<>();
        for (JsonNode descriptor : descriptors) {
            require(descriptor.isObject() && fields(descriptor).equals(SURFACE_FIELDS),
                    "Invalid telemetry surface descriptor");
            String surface = requiredText(descriptor, "surfaceKey", DIMENSION_KEY);
            require(surface.startsWith(product + ".") && allSurfaces.add(surface),
                    "Telemetry surface escaped its product or is duplicated");
            Set<String> routes = new LinkedHashSet<>();
            for (JsonNode routeNode : requiredArray(descriptor, "routeIds")) {
                require(routeNode.isTextual(), "Telemetry route identifier must be text");
                String route = routeNode.asText();
                require(DIMENSION_KEY.matcher(route).matches()
                                && route.startsWith(surface + ".")
                                && routes.add(route)
                                && allRoutes.add(route),
                        "Telemetry route escaped its surface or is duplicated");
            }
            require(!routes.isEmpty(), "Telemetry surface requires a registered UI route");
            Set<ProductSurfaceTelemetryDtos.TaskKind> taskKinds = enumValues(
                    descriptor, "taskKinds", ProductSurfaceTelemetryDtos.TaskKind.class);
            Set<ProductSurfaceTelemetryDtos.ScopeKind> scopeKinds = enumValues(
                    descriptor, "scopeKinds", ProductSurfaceTelemetryDtos.ScopeKind.class);
            result.put(surface, new SurfaceDimensions(
                    Set.copyOf(routes), taskKinds, scopeKinds));
        }
        require(!result.isEmpty(), "Telemetry product requires a registered surface");
        return result;
    }

    private String requiredText(JsonNode source, String field, Pattern pattern) {
        JsonNode value = source.path(field);
        require(value.isTextual() && pattern.matcher(value.asText()).matches(),
                "Invalid telemetry dimension field " + field);
        return value.asText();
    }

    private <T extends Enum<T>> Set<T> enumValues(
            JsonNode source,
            String field,
            Class<T> type) {
        Set<T> values = new LinkedHashSet<>();
        for (JsonNode value : requiredArray(source, field)) {
            require(value.isTextual(), "Telemetry " + field + " value must be text");
            try {
                require(values.add(Enum.valueOf(type, value.asText())),
                        "Duplicate telemetry " + field + " value");
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException(
                        "Unknown telemetry " + field + " value", exception);
            }
        }
        require(!values.isEmpty(), "Telemetry surface requires " + field);
        return Set.copyOf(values);
    }

    private Set<String> fields(JsonNode value) {
        Set<String> result = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(result::add);
        return Set.copyOf(result);
    }

    private String sha256(JsonNode value) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(canonical(value));
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "Telemetry dimension projection checksum failed", exception);
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> result.set(name, canonical(value.get(name))));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            value.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value.deepCopy();
    }

    private static ArrayNode requiredArray(JsonNode source, String field) {
        JsonNode value = source.path(field);
        require(value instanceof ArrayNode, field + " must be an array");
        return (ArrayNode) value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private record SurfaceDimensions(
            Set<String> routeIds,
            Set<ProductSurfaceTelemetryDtos.TaskKind> taskKinds,
            Set<ProductSurfaceTelemetryDtos.ScopeKind> scopeKinds) {
    }
}
