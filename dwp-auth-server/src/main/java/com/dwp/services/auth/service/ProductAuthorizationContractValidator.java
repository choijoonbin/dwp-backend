package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.dwp.services.auth.service.ProductAuthorizationContractRules.*;

@Component
public class ProductAuthorizationContractValidator {
    private final ObjectMapper objectMapper;
    private final ObjectReader strictDocumentReader;
    private final ProductAuthorizationRouteContractValidator routeValidator =
            new ProductAuthorizationRouteContractValidator();

    public ProductAuthorizationContractValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.strictDocumentReader = this.objectMapper.readerFor(JsonNode.class)
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
    }

    public ProductAuthorizationContractDtos.BundleContract validateDocument(InputStream input) {
        return validateDocument(readStrict(input, "Registry JSON document is invalid."));
    }

    public ProductAuthorizationContractDtos.SeedIndex validateSeedIndexDocument(InputStream input) {
        return validateSeedIndexDocument(readStrict(input, "Registry seed index JSON is invalid."));
    }

    public ProductAuthorizationContractDtos.BundleContract validateDocument(JsonNode document) {
        require(document != null && document.isObject(), "Registry document must be an object.");
        JsonNode checksumNode = document.get("checksum");
        require(checksumNode != null && checksumNode.isTextual(), "Registry checksum is required.");
        String expected = checksumNode.textValue();
        require(CHECKSUM_PATTERN.matcher(expected).matches(), "Registry checksum format is invalid.");
        require(expected.equals(checksum(document)), "Registry checksum does not match canonical content.");
        try {
            ProductAuthorizationContractDtos.BundleContract contract = objectMapper.treeToValue(
                    document, ProductAuthorizationContractDtos.BundleContract.class);
            validate(contract);
            return contract;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Registry DTO contract is invalid.", exception);
        }
    }

    public ProductAuthorizationContractDtos.SeedIndex validateSeedIndexDocument(JsonNode document) {
        require(document != null && document.isObject(), "Registry seed index must be an object.");
        JsonNode checksumNode = document.get("indexChecksum");
        require(checksumNode != null && checksumNode.isTextual(), "Registry index checksum is required.");
        String expected = checksumNode.textValue();
        require(CHECKSUM_PATTERN.matcher(expected).matches(), "Registry index checksum format is invalid.");
        require(expected.equals(indexChecksum(document)), "Registry index checksum does not match canonical content.");
        try {
            ProductAuthorizationContractDtos.SeedIndex index = objectMapper.treeToValue(
                    document, ProductAuthorizationContractDtos.SeedIndex.class);
            ProductAuthorizationReleaseLineage.validateSeedIndex(index);
            return index;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Registry seed index DTO contract is invalid.", exception);
        }
    }

    public void validate(ProductAuthorizationContractDtos.BundleContract contract) {
        require(contract != null, "Registry contract is required.");
        require(contract.schemaVersion() == 1, "Unsupported registry schemaVersion.");
        require("product-surfaces".equals(contract.bundleKey()), "Unexpected registry bundleKey.");
        require(supportsDescriptorStructure(contract.version()),
                "Registry descriptor version must be a sealed release version.");
        require(Set.of("DRAFT", "APPROVED", "ACTIVE", "RETIRED").contains(contract.bundleStatus()),
                "Invalid bundle status.");
        require("SHA-256".equals(contract.checksumAlgorithm()), "Only SHA-256 is supported.");
        require(CHECKSUM_PATTERN.matcher(contract.checksum()).matches(), "Invalid checksum.");
        require(text(contract.owner()), "Bundle owner is required.");
        require(!contract.bundleKey().startsWith("test."), "Test registry keys cannot enter runtime.");

        Map<String, ProductAuthorizationContractDtos.CapabilityContract> capabilities = index(
                contract.capabilities(), ProductAuthorizationContractDtos.CapabilityContract::contractKey,
                "capability");
        Map<String, ProductAuthorizationContractDtos.AccessPolicy> policies = index(
                contract.accessPolicies(), ProductAuthorizationContractDtos.AccessPolicy::accessPolicyKey,
                "access policy");
        Map<String, ProductAuthorizationContractDtos.EntitlementExpression> expressions = index(
                contract.entitlementExpressions(),
                ProductAuthorizationContractDtos.EntitlementExpression::expressionKey,
                "entitlement expression");
        Map<String, ProductAuthorizationContractDtos.PredicatePolicy> predicates = index(
                contract.predicatePolicies(),
                ProductAuthorizationContractDtos.PredicatePolicy::predicatePolicyKey,
                "predicate policy");
        Map<String, ProductAuthorizationContractDtos.GovernedRoute> routes = index(
                contract.routes(), ProductAuthorizationContractDtos.GovernedRoute::routeContractKey,
                "route");

        Map<String, Set<String>> capabilityRoutes = new HashMap<>();
        Map<String, Set<String>> policyRoutes = new HashMap<>();
        Map<String, Set<String>> predicateRoutes = new HashMap<>();
        capabilities.keySet().forEach(key -> capabilityRoutes.put(key, new LinkedHashSet<>()));
        policies.keySet().forEach(key -> policyRoutes.put(key, new LinkedHashSet<>()));
        predicates.keySet().forEach(key -> predicateRoutes.put(key, new LinkedHashSet<>()));

        expressions.values().forEach(expression -> {
            revision(expression.owner(), expression.policyVersion(), expression.lifecycleState(),
                    expression.expressionKey());
            validateExpression(expression.expression(), expression.expressionKey());
        });
        predicates.values().forEach(predicate -> validatePredicate(predicate));
        capabilities.values().forEach(value -> validateCapability(value, contract.version()));
        policies.values().forEach(policy -> validatePolicy(policy, capabilities, expressions));
        routes.values().forEach(route -> routeValidator.validateRoute(
                route, capabilities, policies, predicates,
                capabilityRoutes, policyRoutes, predicateRoutes, contract.version()));
        routeValidator.validateApprovalProjectionSchemaCoverage(contract);
        ProductAuthorizationExtensionProjectionSchema.validateCoverage(contract);
        ProductAuthorizationRelease10ProjectionSchema.validateCoverage(contract);
        ProductAuthorizationRecovery11ProjectionSchema.validateCoverage(contract);
        routeValidator.validateAuthorityEndpoints(contract);
        ProductAuthorizationGateTopologyValidator.validateBundle(contract);

        require(routes.keySet().stream().noneMatch(key -> key.startsWith("route.test.")),
                "Test route keys cannot enter runtime.");
        require(capabilities.keySet().stream().noneMatch(key -> key.startsWith("test.")),
                "Test capabilities cannot enter runtime.");

        policyRoutes.forEach((policyKey, routeKeys) -> {
            ProductAuthorizationContractDtos.AccessPolicy policy = policies.get(policyKey);
            nullSafe(policy.modeBranches()).stream()
                    .filter(branch -> "CAPABILITY".equals(branch.resultGrantKind()))
                    .flatMap(branch -> nullSafe(branch.capabilityContractKeys()).stream())
                    .forEach(capabilityKey -> capabilityRoutes.get(capabilityKey).addAll(routeKeys));
        });

        capabilities.forEach((key, value) -> require(
                sorted(value.routeContractKeys()).equals(sorted(capabilityRoutes.get(key))),
                key + ": capability route reverse index drift."));
        policies.forEach((key, value) -> require(
                sorted(value.routeContractKeys()).equals(sorted(policyRoutes.get(key))),
                key + ": policy route reverse index drift."));
        predicates.forEach((key, value) -> require(
                sorted(value.routeContractKeys()).equals(sorted(predicateRoutes.get(key))),
                key + ": predicate route reverse index drift."));
        validateReleaseLineage(contract);
    }

    /**
     * Package-scoped seams keep production lineage pinned while allowing the native
     * governance integration fixture to exercise an isolated, test-only release.
     */
    boolean supportsDescriptorStructure(long version) {
        return ProductAuthorizationReleaseLineage.supportsDescriptorStructure(version);
    }

    void validateReleaseLineage(ProductAuthorizationContractDtos.BundleContract contract) {
        ProductAuthorizationReleaseLineage.validateBundle(contract);
    }

    public String checksum(JsonNode document) {
        ObjectNode payload = ((ObjectNode) document).deepCopy();
        payload.remove("checksum");
        payload.remove("bundleStatus");
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(canonical(payload));
            return java.util.HexFormat.of().formatHex(sha256(bytes));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Registry checksum serialization failed.", exception);
        }
    }

    public String indexChecksum(JsonNode document) {
        ObjectNode payload = ((ObjectNode) document).deepCopy();
        payload.remove("indexChecksum");
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(canonical(payload));
            return java.util.HexFormat.of().formatHex(sha256(bytes));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Registry index checksum serialization failed.", exception);
        }
    }

    private JsonNode readStrict(InputStream input, String error) {
        try {
            return strictDocumentReader.readTree(input);
        } catch (IOException exception) {
            throw new IllegalArgumentException(error, exception);
        }
    }

    private void validateCapability(
            ProductAuthorizationContractDtos.CapabilityContract value,
            long bundleVersion) {
        revision(value.owner(), value.policyVersion(), value.lifecycleState(), value.contractKey());
        require(value.mappingVersion() == 1, value.contractKey() + ": invalid mappingVersion.");
        require(text(value.productKey()) && text(value.surfaceKey()),
                value.contractKey() + ": product and surface are required.");
        require(text(value.resolvedCapabilityCode()) && value.resolvedCapabilityCode().contains(":"),
                value.contractKey() + ": exact capability code is required.");
        String expected = value.resourceKey() + ":" + value.action();
        require(expected.equals(value.resolvedCapabilityCode()),
                value.contractKey() + ": exact capability mapping drift.");
        require(Set.of("PERMISSION", "PERMISSION_AND_RELATIONSHIP", "PERMISSION_OR_RELATIONSHIP")
                        .contains(value.authorityMode()),
                value.contractKey() + ": invalid authority mode.");
        require(Set.of("REQUIRED", "NOT_REQUIRED", "LEGACY_OVERSIGHT")
                        .contains(value.responsibilityRequirement()),
                value.contractKey() + ": invalid responsibility requirement.");
        if ("REQUIRED".equals(value.responsibilityRequirement())) {
            require("APP_CONFIG_ADMIN".equals(value.requiredResponsibilityCode()),
                    value.contractKey() + ": exact required responsibility code is required.");
        } else {
            require(value.requiredResponsibilityCode() == null,
                    value.contractKey() + ": responsibility code is forbidden for this descriptor.");
        }
        require(text(value.scopeResolver()), value.contractKey() + ": scope resolver is required.");
        require(Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(value.riskTier()),
                value.contractKey() + ": invalid risk tier.");
    }

    private void validatePolicy(
            ProductAuthorizationContractDtos.AccessPolicy value,
            Map<String, ProductAuthorizationContractDtos.CapabilityContract> capabilities,
            Map<String, ProductAuthorizationContractDtos.EntitlementExpression> expressions) {
        String key = value.accessPolicyKey();
        revision(value.owner(), value.policyVersion(), value.lifecycleState(), key);
        require(CONTEXT_PATTERN.matcher(value.navigationContextId()).matches()
                        && !value.navigationContextId().contains("_"),
                key + ": invalid navigation context.");
        require((value.productKey() == null) == (value.surfaceKey() == null),
                key + ": incomplete subject.");
        if (value.productKey() == null) {
            require(nullSafe(value.surfaceEntryKeys()).isEmpty(),
                    key + ": governed context cannot have surface entries.");
        } else {
            require(!nullSafe(value.surfaceEntryKeys()).isEmpty(),
                    key + ": product policy requires a surface entry.");
        }
        require(text(value.scopeResolver()), key + ": scope resolver is required.");
        if ("SINGLE".equals(value.evaluationType())) {
            require(nullSafe(value.modeBranches()).isEmpty(), key + ": SINGLE branches are forbidden.");
            require(Set.of("ENTITLEMENT", "RELATIONSHIP", "ENTITLEMENT_AND_RELATIONSHIP", "SUPPORT_SESSION")
                            .contains(value.authorityMode()),
                    key + ": invalid authority mode.");
            boolean entitlementMode = Set.of("ENTITLEMENT", "ENTITLEMENT_AND_RELATIONSHIP")
                    .contains(value.authorityMode());
            require(entitlementMode == (value.entitlementExpressionKey() != null),
                    key + ": entitlement expression union mismatch.");
            if (entitlementMode) {
                require(expressions.containsKey(value.entitlementExpressionKey()),
                        key + ": unknown entitlement expression.");
            }
            require("SUPPORT_SESSION".equals(value.authorityMode())
                            ? !nullSafe(value.supportScopes()).isEmpty()
                            : nullSafe(value.supportScopes()).isEmpty(),
                    key + ": support scope union mismatch.");
            return;
        }
        require("MODE_BRANCH".equals(value.evaluationType()), key + ": invalid evaluation type.");
        require(value.authorityMode() == null && value.entitlementExpressionKey() == null
                        && nullSafe(value.supportScopes()).isEmpty(),
                key + ": MODE_BRANCH top-level union fields are forbidden.");
        Set<String> modes = new HashSet<>();
        require(!nullSafe(value.modeBranches()).isEmpty(), key + ": mode branches are required.");
        for (ProductAuthorizationContractDtos.ModeBranch branch : value.modeBranches()) {
            require(ACCESS_MODES.contains(branch.activeAccessMode()) && modes.add(branch.activeAccessMode()),
                    key + ": duplicate or invalid branch mode.");
            if ("CAPABILITY".equals(branch.resultGrantKind())) {
                require(!"PROVIDER_SUPPORT".equals(branch.activeAccessMode())
                                && Set.of("ANY", "ALL").contains(branch.capabilityMode())
                                && !nullSafe(branch.capabilityContractKeys()).isEmpty(),
                        key + ": invalid capability branch.");
                require(branch.capabilityContractKeys().stream().allMatch(capabilities::containsKey),
                        key + ": unknown branch capability.");
                require(branch.authorityMode() == null
                                && nullSafe(branch.supportScopes()).isEmpty(),
                        key + ": capability branch support union fields are forbidden.");
            } else {
                require("POLICY".equals(branch.resultGrantKind())
                                && "PROVIDER_SUPPORT".equals(branch.activeAccessMode())
                                && "SUPPORT_SESSION".equals(branch.authorityMode())
                                && branch.capabilityMode() == null
                                && nullSafe(branch.capabilityContractKeys()).isEmpty()
                                && branch.responsibilityRequirement() == null
                                && !nullSafe(branch.supportScopes()).isEmpty(),
                        key + ": invalid support branch.");
            }
        }
    }

    private void validatePredicate(ProductAuthorizationContractDtos.PredicatePolicy value) {
        String key = value.predicatePolicyKey();
        revision(value.owner(), value.policyVersion(), value.lifecycleState(), key);
        require(key.startsWith("predicate."), key + ": invalid predicate namespace.");
        require(SERVICE_KEYS.contains(value.ownerServiceKey()), key + ": invalid owner service.");
        require(nonEmptyUniqueSubset(value.targetBindingKinds(), TARGET_KINDS),
                key + ": invalid target kinds.");
        require(text(value.inputEvidenceSchemaKey()) && text(value.parameterSchemaKey()),
                key + ": evidence and parameter schemas are required.");
        JsonNode schema = value.parameterSchema();
        require(schema != null && schema.isObject()
                        && "object".equals(schema.path("type").asText())
                        && schema.has("additionalProperties")
                        && !schema.path("additionalProperties").asBoolean(true),
                key + ": a closed parameter schema is required.");
    }

    private void validateExpression(JsonNode node, String key) {
        require(node != null && node.isObject(), key + ": expression node must be an object.");
        String type = node.path("type").asText();
        require(Set.of("LEAF", "ANY", "ALL").contains(type), key + ": invalid expression node.");
        if ("LEAF".equals(type)) {
            require(node.size() == 2 && node.path("entitlement").asText().startsWith("APP.")
                            && node.path("entitlement").asText().contains(":"),
                    key + ": invalid entitlement leaf.");
            return;
        }
        JsonNode children = node.get("children");
        require(node.size() == 2 && children != null && children.isArray() && !children.isEmpty(),
                key + ": empty entitlement expression.");
        children.forEach(child -> validateExpression(child, key));
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

    private byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

}
