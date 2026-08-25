package com.dwp.gateway.productsurface;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fail-closed projection of product/surface candidates from the generated
 * authorization bundle. The latest immutable snapshot is deliberately a
 * superset; Auth's active pointer remains authoritative for each decision.
 */
@Component
public class GeneratedProductSurfaceCandidateCatalog
        implements ProductSurfaceCandidateCatalog {

    private static final String BUNDLE_KEY = "product-surfaces";
    private static final Pattern CHECKSUM = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern PRODUCT_KEY = Pattern.compile("^[a-z][a-z0-9-]{0,47}$");
    private static final Pattern SURFACE_KEY = Pattern.compile(
            "^[a-z][a-z0-9-]{0,47}(\\.[a-z][a-z0-9-]{0,47})+$");
    private static final Set<String> ROUTE_KINDS = Set.of("PAGE", "DATA", "ACTION");
    private static final Set<String> LIFECYCLE_STATES = Set.of("ACTIVE", "RETIRED");
    private static final Set<String> ROLLOUT_PRODUCTS = Set.of(
            "approvals", "calendar", "communications", "dwaion", "hcm", "mail",
            "messaging", "notifications", "services", "spaces", "workplace");

    private final List<ProductSurfaceContextDtos.ProductCandidate> candidates;
    private final List<String> rolloutProductKeys;

    @Autowired
    public GeneratedProductSurfaceCandidateCatalog(
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader,
            @Value("${dwp.product-surface.candidate-catalog-location:"
                    + "classpath:product-authorization/product-surfaces-v1.generated.json}")
            String catalogLocation,
            @Value("${dwp.product-surface.candidate-catalog-index-location:"
                    + "classpath:product-authorization/"
                    + "product-surfaces-v1.index.generated.json}")
            String indexLocation,
            @Value("${dwp.product-surface.rollout-inventory-location:"
                    + "classpath:product-authorization/"
                    + "product-surface-rollout-inventory.v1.generated.json}")
            String rolloutInventoryLocation) {
        this(objectMapper,
                resourceLoader.getResource(catalogLocation),
                resourceLoader.getResource(indexLocation),
                resourceLoader.getResource(rolloutInventoryLocation));
    }

    GeneratedProductSurfaceCandidateCatalog(
            ObjectMapper objectMapper,
            Resource catalogResource,
            Resource indexResource,
            Resource rolloutInventoryResource) {
        JsonNode catalog = read(objectMapper, catalogResource, "candidate catalog");
        JsonNode index = read(objectMapper, indexResource, "candidate catalog index");
        JsonNode rolloutInventory = read(
                objectMapper, rolloutInventoryResource, "rollout inventory");
        validateBundleBinding(objectMapper, catalog, index);
        this.rolloutProductKeys = rolloutProductKeys(objectMapper, rolloutInventory);
        this.candidates = candidates(catalog);
    }

    @Override
    public List<ProductSurfaceContextDtos.ProductCandidate> activeCandidates() {
        return candidates;
    }

    @Override
    public List<String> rolloutProductKeys() {
        return rolloutProductKeys;
    }

    private List<String> rolloutProductKeys(ObjectMapper objectMapper, JsonNode inventory) {
        if (!Set.of(
                        "schemaVersion", "inventoryKey", "products",
                        "checksumAlgorithm", "checksum")
                .equals(iterableFieldNames(inventory))) {
            throw invalid("Generated rollout inventory field set is invalid");
        }
        String checksum = requiredText(inventory, "checksum");
        JsonNode products = inventory.get("products");
        if (inventory.path("schemaVersion").asInt(-1) != 1
                || !"product-surface-rollout-products.v1".equals(
                        requiredText(inventory, "inventoryKey"))
                || !"SHA-256".equals(requiredText(inventory, "checksumAlgorithm"))
                || !CHECKSUM.matcher(checksum).matches()
                || !checksum.equals(documentChecksum(
                        objectMapper, inventory, Set.of("checksum")))
                || products == null || !products.isArray() || products.size() != 11) {
            throw invalid("Generated candidate catalog has no complete rollout inventory");
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode product : products) {
            if (!product.isTextual()
                    || !PRODUCT_KEY.matcher(product.textValue()).matches()
                    || !values.add(product.textValue())) {
                throw invalid("Generated rollout product inventory is invalid");
            }
        }
        if (!values.equals(ROLLOUT_PRODUCTS)) {
            throw invalid("Generated rollout product inventory is not the exact v1 set");
        }
        return values.stream().sorted().toList();
    }

    private Set<String> iterableFieldNames(JsonNode document) {
        Set<String> fields = new HashSet<>();
        document.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private JsonNode read(ObjectMapper objectMapper, Resource resource, String label) {
        if (resource == null || !resource.exists() || !resource.isReadable()) {
            throw invalid("Generated " + label + " is not readable");
        }
        try (InputStream input = resource.getInputStream()) {
            JsonNode document = objectMapper.readTree(input);
            if (document == null || !document.isObject()) {
                throw invalid("Generated " + label + " must be an object");
            }
            return document;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Generated " + label + " could not be read", exception);
        }
    }

    private void validateBundleBinding(
            ObjectMapper objectMapper,
            JsonNode catalog,
            JsonNode index) {
        String checksum = requiredText(catalog, "checksum");
        String indexChecksum = requiredText(index, "indexChecksum");
        if (catalog.path("schemaVersion").asInt(-1) != 1
                || index.path("schemaVersion").asInt(-1) != 1
                || !BUNDLE_KEY.equals(requiredText(catalog, "bundleKey"))
                || !BUNDLE_KEY.equals(requiredText(index, "bundleKey"))
                || catalog.path("version").asLong(-1) < 1
                || catalog.path("version").asLong(-1)
                        != index.path("latestVersion").asLong(-2)
                || !CHECKSUM.matcher(checksum).matches()
                || !checksum.equals(requiredText(index, "latestChecksum"))
                || !"SHA-256".equals(requiredText(catalog, "checksumAlgorithm"))
                || !"SHA-256".equals(requiredText(index, "indexChecksumAlgorithm"))
                || !CHECKSUM.matcher(indexChecksum).matches()
                || !checksum.equals(documentChecksum(
                        objectMapper, catalog, Set.of("checksum", "bundleStatus")))
                || !indexChecksum.equals(documentChecksum(
                        objectMapper, index, Set.of("indexChecksum")))) {
            throw invalid("Generated candidate catalog and index are not bound");
        }
    }

    private String documentChecksum(
            ObjectMapper objectMapper,
            JsonNode document,
            Set<String> excludedFields) {
        ObjectNode canonical = ((ObjectNode) document).deepCopy();
        excludedFields.forEach(canonical::remove);
        try {
            byte[] bytes = objectMapper.writeValueAsString(canonical)
                    .getBytes(StandardCharsets.UTF_8);
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "Generated candidate catalog checksum could not be verified", exception);
        }
    }

    private List<ProductSurfaceContextDtos.ProductCandidate> candidates(JsonNode catalog) {
        JsonNode routes = catalog.get("routes");
        if (routes == null || !routes.isArray() || routes.isEmpty()) {
            throw invalid("Generated candidate catalog has no routes");
        }
        Set<String> routeKeys = new HashSet<>();
        LinkedHashSet<ProductSurfaceContextDtos.ProductCandidate> values =
                new LinkedHashSet<>();
        for (JsonNode route : routes) {
            validateRoute(route, routeKeys, values);
        }
        if (values.isEmpty()) throw invalid("Generated candidate catalog has no product pages");
        List<ProductSurfaceContextDtos.ProductCandidate> sorted = new ArrayList<>(values);
        sorted.sort(Comparator
                .comparing(ProductSurfaceContextDtos.ProductCandidate::productKey)
                .thenComparing(ProductSurfaceContextDtos.ProductCandidate::surfaceKey));
        return List.copyOf(sorted);
    }

    private void validateRoute(
            JsonNode route,
            Set<String> routeKeys,
            Set<ProductSurfaceContextDtos.ProductCandidate> values) {
        if (route == null || !route.isObject()) throw invalid("Generated route is invalid");
        String routeKey = requiredText(route, "routeContractKey");
        String kind = requiredText(route, "routeKind");
        String lifecycle = requiredText(route, "lifecycleState");
        JsonNode subject = route.get("subject");
        if (!routeKeys.add(routeKey)
                || !ROUTE_KINDS.contains(kind)
                || !LIFECYCLE_STATES.contains(lifecycle)
                || subject == null || !subject.isObject()) {
            throw invalid("Generated route projection is invalid");
        }
        String type = requiredText(subject, "type");
        if ("GOVERNED_CONTEXT".equals(type)) {
            if (subject.hasNonNull("productKey")
                    || subject.hasNonNull("surfaceKey")) {
                throw invalid("Governed context cannot project a product surface");
            }
            return;
        }
        if (!"PRODUCT".equals(type)) throw invalid("Unknown generated route subject");
        String productKey = requiredText(subject, "productKey");
        String surfaceKey = requiredText(subject, "surfaceKey");
        if (!PRODUCT_KEY.matcher(productKey).matches()
                || !SURFACE_KEY.matcher(surfaceKey).matches()
                || !surfaceKey.startsWith(productKey + '.')) {
            throw invalid("Generated product route subject is invalid");
        }
        if ("ACTIVE".equals(lifecycle) && "PAGE".equals(kind)) {
            values.add(new ProductSurfaceContextDtos.ProductCandidate(productKey, surfaceKey));
        }
    }

    private String requiredText(JsonNode document, String field) {
        JsonNode value = document.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalid("Generated candidate catalog is missing " + field);
        }
        return value.textValue();
    }

    private IllegalStateException invalid(String message) {
        return new IllegalStateException(message);
    }
}
