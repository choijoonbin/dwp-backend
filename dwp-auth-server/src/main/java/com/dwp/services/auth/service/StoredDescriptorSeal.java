package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;

/** Actual stored rows reconstructed in the exact trusted release order, never a replacement trusted document. */
public final class StoredDescriptorSeal {
    private static final String INDEX = "classpath:product-authorization/product-surfaces-v1.index.generated.json";
    private static final List<Spec> SPECS = List.of(
            new Spec("capabilities", "contractKey", "auth_product_capability_contract", "contract_key", ProductAuthorizationContractDtos.CapabilityContract.class,
                    Map.of("product_key", "/productKey", "surface_key", "/surfaceKey", "lifecycle_state", "/lifecycleState")),
            new Spec("accessPolicies", "accessPolicyKey", "auth_product_access_policy", "access_policy_key", ProductAuthorizationContractDtos.AccessPolicy.class,
                    Map.of("navigation_context_id", "/navigationContextId", "product_key", "/productKey", "surface_key", "/surfaceKey", "lifecycle_state", "/lifecycleState")),
            new Spec("entitlementExpressions", "expressionKey", "auth_product_entitlement_expression", "expression_key", ProductAuthorizationContractDtos.EntitlementExpression.class,
                    Map.of("lifecycle_state", "/lifecycleState")),
            new Spec("predicatePolicies", "predicatePolicyKey", "auth_product_predicate_policy", "predicate_policy_key", ProductAuthorizationContractDtos.PredicatePolicy.class,
                    Map.of("owner_service_key", "/ownerServiceKey", "lifecycle_state", "/lifecycleState")),
            new Spec("routes", "routeContractKey", "auth_governed_route_contract", "route_contract_key", ProductAuthorizationContractDtos.GovernedRoute.class,
                    Map.of("navigation_context_id", "/navigationContextId", "subject_type", "/subject/type", "product_key", "/subject/productKey",
                            "surface_key", "/subject/surfaceKey", "route_kind", "/routeKind", "lifecycle_state", "/lifecycleState")),
            new Spec("authorityEndpoints", "endpointKey", "auth_product_authority_endpoint", "endpoint_key", ProductAuthorizationContractDtos.AuthorityEndpoint.class,
                    Map.of("service_key", "/serviceKey")));
    private final JdbcTemplate jdbc;
    private final ProductAuthorizationContractRepository repository;
    private final ProductAuthorizationContractValidator validator;
    private final ObjectMapper mapper;
    private final ResourceLoader resources;

    public StoredDescriptorSeal(JdbcTemplate jdbc, ProductAuthorizationContractRepository repository,
            ProductAuthorizationContractValidator validator, ObjectMapper mapper) {
        this(jdbc, repository, validator, mapper, new DefaultResourceLoader());
    }
    StoredDescriptorSeal(JdbcTemplate jdbc, ProductAuthorizationContractRepository repository,
            ProductAuthorizationContractValidator validator, ObjectMapper source, ResourceLoader resources) {
        this.jdbc = jdbc; this.repository = repository; this.validator = validator; this.resources = resources;
        mapper = source.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(262144).maxNumberLength(64).build());
    }

    public ProductAuthorizationContractDtos.BundleContract loadVersion(ProductAuthorizationContractRepository.StoredBundle expected) {
        require(expected != null && expected.equals(repository.find(expected.bundleKey(), expected.version()).orElseThrow(StoredDescriptorSeal::invalid)));
        var index = validator.validateSeedIndexDocument(read(INDEX));
        var entry = index.versions().stream().filter(value -> value.version() == expected.version()).findFirst().orElseThrow(StoredDescriptorSeal::invalid);
        JsonNode reference = read("classpath:product-authorization/" + entry.authSeedArtifact());
        var trusted = validator.validateDocument(reference);
        require(entry.checksum().equals(trusted.checksum()) && trusted.version() == expected.version()
                && trusted.bundleKey().equals(expected.bundleKey()) && trusted.schemaVersion() == expected.schemaVersion()
                && trusted.checksumAlgorithm().equals(expected.checksumAlgorithm()) && trusted.checksum().equals(expected.checksum()) && trusted.owner().equals(expected.owner()));
        var result = validator.validateDocument(actualDocument(expected, reference));
        // Re-read the actual descriptor vector as well as metadata; never rely solely on the stored hash column.
        require(result.equals(validator.validateDocument(actualDocument(expected, reference))));
        require(expected.equals(repository.find(expected.bundleKey(), expected.version()).orElseThrow(StoredDescriptorSeal::invalid)));
        return result;
    }

    private ObjectNode actualDocument(ProductAuthorizationContractRepository.StoredBundle expected, JsonNode reference) {
        var actual = mapper.createObjectNode();
        actual.put("schemaVersion", expected.schemaVersion()); actual.put("bundleKey", expected.bundleKey()); actual.put("version", expected.version());
        actual.put("bundleStatus", expected.bundleStatus()); actual.put("owner", expected.owner());
        actual.put("checksumAlgorithm", expected.checksumAlgorithm()); actual.put("checksum", expected.checksum());
        for (Spec spec : SPECS) actualRows(expected, reference, actual, spec);
        return actual;
    }

    public ProductAuthorizationContractDtos.BundleContract loadActive(ProductAuthorizationContractRepository.StoredBundle expected,
            ProductAuthorizationContractRepository.ActivePointer pointer) {
        require(expected != null && pointer != null && "ACTIVE".equals(expected.bundleStatus()) && expected.bundleId().equals(pointer.bundleId()));
        require(expected.equals(repository.findActive(expected.bundleKey()).orElseThrow(StoredDescriptorSeal::invalid))
                && pointer.equals(repository.findActivePointer(expected.bundleKey()).orElseThrow(StoredDescriptorSeal::invalid)));
        var result = loadVersion(expected);
        require(expected.equals(repository.findActive(expected.bundleKey()).orElseThrow(StoredDescriptorSeal::invalid))
                && pointer.equals(repository.findActivePointer(expected.bundleKey()).orElseThrow(StoredDescriptorSeal::invalid)));
        return result;
    }

    private void actualRows(ProductAuthorizationContractRepository.StoredBundle bundle, JsonNode reference, ObjectNode document, Spec spec) {
        JsonNode ordered = reference.get(spec.field());
        int count = ordered == null || ordered.isNull() ? 0 : ordered.size();
        require(ordered == null || ordered.isNull() || ordered.isArray());
        var stored = new HashMap<String, JsonNode>();
        String columns = String.join(",", spec.metadata().keySet());
        jdbc.query("SELECT " + spec.column() + ",descriptor::text," + columns + " FROM " + spec.table() + " WHERE bundle_id=? ORDER BY " + spec.column() + " LIMIT ?", row -> {
            String key = row.getString(spec.column()); JsonNode raw;
            try { raw = mapper.readTree(row.getString("descriptor")); }
            catch (IOException error) { throw invalid(); }
            require(raw != null && raw.isObject() && raw.path(spec.key()).isTextual() && key.equals(raw.path(spec.key()).textValue()) && !stored.containsKey(key));
            for (var metadata : spec.metadata().entrySet()) {
                var value = raw.at(metadata.getValue()); String text = value.isNull() ? null : value.isTextual() ? value.textValue() : "\u0000";
                require(java.util.Objects.equals(text, row.getString(metadata.getKey())));
            }
            stored.put(key, raw);
        }, bundle.bundleId(), count + 1);
        require(stored.size() == count);
        if (ordered == null) return;
        if (ordered.isNull()) { document.putNull(spec.field()); return; }
        var rebuilt = mapper.createArrayNode();
        for (JsonNode shape : ordered) {
            String key = shape.path(spec.key()).textValue(); var raw = stored.remove(key); require(raw != null);
            JsonNode normalized = normalize(raw, spec.type()), trustedNormalized = normalize(shape, spec.type());
            require(validator.checksum(wrap(normalized)).equals(validator.checksum(wrap(trustedNormalized))));
            rebuilt.add(project(raw, normalized, shape));
        }
        require(stored.isEmpty()); document.set(spec.field(), rebuilt);
    }
    private JsonNode normalize(JsonNode value, Class<?> type) {
        try { return mapper.valueToTree(mapper.treeToValue(value, type)); }
        catch (IOException | IllegalArgumentException error) { throw invalid(); }
    }
    private ObjectNode wrap(JsonNode value) { var result = mapper.createObjectNode(); result.set("value", value); return result; }
    private JsonNode project(JsonNode raw, JsonNode normalized, JsonNode shape) {
        require(raw != null && normalized != null);
        if (shape.isObject()) {
            require(raw.isObject() && normalized.isObject()); var result = mapper.createObjectNode();
            shape.fieldNames().forEachRemaining(key -> { require(raw.has(key)); result.set(key, project(raw.get(key), normalized.get(key), shape.get(key))); });
            // The seeded typed serializer may add only declared nullable defaults omitted in the immutable document.
            raw.fieldNames().forEachRemaining(key -> { if (!shape.has(key)) require(normalized.has(key) && normalized.get(key).isNull()); });
            return result;
        }
        if (shape.isArray()) {
            require(raw.isArray() && normalized.isArray() && raw.size() == shape.size() && normalized.size() == shape.size());
            var result = mapper.createArrayNode(); for (int index = 0; index < shape.size(); index++) result.add(project(raw.get(index), normalized.get(index), shape.get(index))); return result;
        }
        return normalized.deepCopy();
    }
    private JsonNode read(String location) {
        try (var input = resources.getResource(location).getInputStream()) { return mapper.readTree(input); }
        catch (IOException | IllegalArgumentException error) { throw invalid(); }
    }
    private static void require(boolean valid) { if (!valid) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Stored authorization descriptors do not match the exact trusted release."); }
    private record Spec(String field, String key, String table, String column, Class<?> type, Map<String, String> metadata) { }
}
