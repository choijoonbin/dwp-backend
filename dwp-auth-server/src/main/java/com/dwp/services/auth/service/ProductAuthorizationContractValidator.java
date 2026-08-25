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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class ProductAuthorizationContractValidator {

    private static final Set<String> ACCESS_MODES =
            Set.of("NORMAL", "ELEVATED", "PROVIDER_SUPPORT");
    private static final Set<String> TARGET_KINDS =
            Set.of("SELF", "OBJECT", "RELATIONSHIP", "TARGET_POPULATION", "CONFIG_SCOPE");
    private static final Set<String> ROUTE_KINDS = Set.of("PAGE", "DATA", "ACTION");
    private static final Set<String> SERVICE_KEYS = Set.of("auth", "platform", "approval", "people");
    private static final Pattern CONTEXT_PATTERN =
            Pattern.compile("^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+$");
    private static final Pattern CHECKSUM_PATTERN = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern ARTIFACT_PATTERN = Pattern.compile("^[a-z0-9.-]+\\.json$");
    private static final Map<String, String> APPROVAL_FIELD_MASK_SCHEMA_PROFILES = Map.of(
            "ApprovalOversightAdminPulseV1", "legacy-oversight",
            "ApprovalOversightWorkflowV1", "legacy-oversight",
            "ApprovalOversightFormV1", "legacy-oversight",
            "ApprovalOversightPolicyV1", "legacy-oversight",
            "ApprovalAuditorOperationsV1", "auditor",
            "ApprovalOversightOperationsV1", "legacy-oversight",
            "ApprovalOversightSignatureV1", "legacy-oversight");

    private final ObjectMapper objectMapper;
    private final ObjectReader strictDocumentReader;

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
            validateSeedIndex(index);
            return index;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Registry seed index DTO contract is invalid.", exception);
        }
    }

    private void validateSeedIndex(ProductAuthorizationContractDtos.SeedIndex index) {
        require(index.schemaVersion() == 1, "Unsupported registry seed index schemaVersion.");
        require("product-surfaces".equals(index.bundleKey()), "Unexpected registry seed index bundleKey.");
        require("SHA-256".equals(index.indexChecksumAlgorithm()), "Only SHA-256 index checksums are supported.");
        require(index.latestVersion() == 3, "Registry latest version must be 3.");
        require(CHECKSUM_PATTERN.matcher(index.latestChecksum()).matches(), "Invalid latest registry checksum.");
        require(ARTIFACT_PATTERN.matcher(index.latestArtifact()).matches(), "Invalid latest registry artifact.");
        require(ARTIFACT_PATTERN.matcher(index.latestAuthSeedArtifact()).matches(),
                "Invalid latest auth seed artifact.");
        require(index.versions() != null && !index.versions().isEmpty(), "Registry index versions are required.");
        require(index.versions().size() == 3,
                "Registry index must contain only versions 1, 2, and 3.");

        long expectedVersion = 1;
        Set<String> checksums = new HashSet<>();
        for (ProductAuthorizationContractDtos.SeedIndexEntry entry : index.versions()) {
            require(entry.version() == expectedVersion++, "Registry index versions must be contiguous and ordered.");
            require("DRAFT".equals(entry.bundleStatus()),
                    "Generated registry seed index may import DRAFT snapshots only.");
            require(CHECKSUM_PATTERN.matcher(entry.checksum()).matches() && checksums.add(entry.checksum()),
                    "Registry index checksums must be valid and unique.");
            require(ARTIFACT_PATTERN.matcher(entry.artifact()).matches(), "Invalid registry artifact name.");
            require(ARTIFACT_PATTERN.matcher(entry.authSeedArtifact()).matches(),
                    "Invalid auth seed artifact name.");
            require(entry.counts() != null && entry.counts().keySet().equals(Set.of(
                            "capabilities", "accessPolicies", "entitlementExpressions",
                            "predicatePolicies", "routes"))
                            && entry.counts().values().stream().allMatch(value -> value != null && value > 0),
                    "Registry index descriptor counts are invalid.");
        }
        ProductAuthorizationContractDtos.SeedIndexEntry latest =
                index.versions().get(index.versions().size() - 1);
        require(latest.version() == index.latestVersion()
                        && latest.checksum().equals(index.latestChecksum())
                        && latest.artifact().equals(index.latestArtifact())
                        && latest.authSeedArtifact().equals(index.latestAuthSeedArtifact()),
                "Registry latest pointer does not match the final immutable snapshot.");
    }

    public void validate(ProductAuthorizationContractDtos.BundleContract contract) {
        require(contract != null, "Registry contract is required.");
        require(contract.schemaVersion() == 1, "Unsupported registry schemaVersion.");
        require("product-surfaces".equals(contract.bundleKey()), "Unexpected registry bundleKey.");
        require(Set.of(1L, 2L, 3L).contains(contract.version()),
                "Registry version must be 1, 2, or 3.");
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
        routes.values().forEach(route -> validateRoute(
                route, capabilities, policies, predicates,
                capabilityRoutes, policyRoutes, predicateRoutes, contract.version()));
        validateApprovalProjectionSchemaCoverage(contract);
        validateAuthorityEndpoints(contract);
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

    private void validateRoute(
            ProductAuthorizationContractDtos.GovernedRoute route,
            Map<String, ProductAuthorizationContractDtos.CapabilityContract> capabilities,
            Map<String, ProductAuthorizationContractDtos.AccessPolicy> policies,
            Map<String, ProductAuthorizationContractDtos.PredicatePolicy> predicates,
            Map<String, Set<String>> capabilityRoutes,
            Map<String, Set<String>> policyRoutes,
            Map<String, Set<String>> predicateRoutes,
            long bundleVersion) {
        String key = route.routeContractKey();
        revision(route.owner(), route.policyVersion(), route.lifecycleState(), key);
        require(ROUTE_KINDS.contains(route.routeKind()), key + ": invalid route kind.");
        require(CONTEXT_PATTERN.matcher(route.navigationContextId()).matches()
                        && !route.navigationContextId().contains("_"),
                key + ": invalid navigation context.");
        require(route.subject() != null, key + ": subject is required.");
        if ("PRODUCT".equals(route.subject().type())) {
            require(text(route.subject().productKey()) && text(route.subject().surfaceKey())
                            && !key.startsWith("route.context."),
                    key + ": invalid product subject.");
        } else {
            require("GOVERNED_CONTEXT".equals(route.subject().type())
                            && route.subject().productKey() == null
                            && route.subject().surfaceKey() == null,
                    key + ": invalid governed context subject.");
            String token = route.navigationContextId().replace(".", "__");
            require(key.startsWith("route.context." + token + "."),
                    key + ": context token is not reversible.");
        }
        require("PAGE".equals(route.routeKind())
                        ? text(route.uiRouteId()) && text(route.uiRoutePattern())
                        : route.uiRouteId() == null && route.uiRoutePattern() == null,
                key + ": UI route fields violate the route kind.");
        if ("ACTION".equals(route.routeKind())) {
            require(route.sideEffectFree() == null, key + ": ACTION cannot be side-effect-free DATA.");
        }
        validateBindings(route);
        validateProfiles(route, capabilities, policies, predicates,
                capabilityRoutes, policyRoutes, predicateRoutes, bundleVersion);
        ProductAuthorizationGateTopologyValidator.validateRoute(route, capabilities);
    }

    private void validateBindings(ProductAuthorizationContractDtos.GovernedRoute route) {
        String key = route.routeContractKey();
        Map<String, ProductAuthorizationContractDtos.GatewayBinding> gateway = index(
                route.gatewayApiBindings(), ProductAuthorizationContractDtos.GatewayBinding::bindingKey,
                key + " gateway binding");
        Map<String, ProductAuthorizationContractDtos.ServicePepBinding> service = index(
                route.servicePepBindings(), ProductAuthorizationContractDtos.ServicePepBinding::bindingKey,
                key + " service binding");
        require(!gateway.isEmpty() && gateway.keySet().equals(service.keySet()),
                key + ": public/service binding pair mismatch.");
        gateway.forEach((bindingKey, publicBinding) -> {
            ProductAuthorizationContractDtos.ServicePepBinding pep = service.get(bindingKey);
            require(publicBinding.method().equals(pep.method()), bindingKey + ": method mismatch.");
            require(nullSafeMap(publicBinding.pathParameterConstraints())
                            .equals(nullSafeMap(pep.pathParameterConstraints())),
                    bindingKey + ": path constraint mismatch.");
            require(nullSafeMap(publicBinding.queryParameterConstraints())
                            .equals(nullSafeMap(pep.queryParameterConstraints())),
                    bindingKey + ": query constraint mismatch.");
            require(SERVICE_KEYS.contains(pep.serviceKey()), bindingKey + ": unknown service.");
            String prefix = "auth".equals(pep.serviceKey()) ? "/auth/" : "/v1/";
            require(pep.path().startsWith(prefix) && !pep.path().contains("/**")
                            && !publicBinding.path().contains("/**"),
                    bindingKey + ": invalid service path grammar.");
            nullSafeMap(publicBinding.pathParameterConstraints()).forEach((parameter, constraint) -> {
                require(Set.of("FIXED", "ALLOWLIST").contains(constraint.kind()),
                        bindingKey + ": invalid path constraint kind.");
                if ("FIXED".equals(constraint.kind())) {
                    require(text(constraint.value()) && nullSafe(constraint.values()).isEmpty(),
                            bindingKey + ": invalid fixed constraint.");
                } else {
                    require(!nullSafe(constraint.values()).isEmpty()
                                    && new HashSet<>(constraint.values()).size() == constraint.values().size(),
                            bindingKey + ": invalid allowlist constraint.");
                }
            });
            nullSafeMap(publicBinding.queryParameterConstraints()).forEach((parameter, constraint) -> {
                require(Set.of("FIXED", "ALLOWLIST", "ABSENT").contains(constraint.kind()),
                        bindingKey + ": invalid query constraint kind.");
                if ("FIXED".equals(constraint.kind())) {
                    require(text(constraint.value()) && nullSafe(constraint.values()).isEmpty(),
                            bindingKey + ": invalid fixed query constraint.");
                } else if ("ALLOWLIST".equals(constraint.kind())) {
                    require(!nullSafe(constraint.values()).isEmpty()
                                    && new HashSet<>(constraint.values()).size()
                                    == constraint.values().size(),
                            bindingKey + ": invalid query allowlist constraint.");
                } else {
                    require(constraint.value() == null && nullSafe(constraint.values()).isEmpty(),
                            bindingKey + ": invalid absent query constraint.");
                }
            });
        });
    }

    private void validateAuthorityEndpoints(
            ProductAuthorizationContractDtos.BundleContract contract) {
        List<ProductAuthorizationContractDtos.AuthorityEndpoint> endpoints =
                nullSafe(contract.authorityEndpoints());
        if (contract.version() == 1) {
            require(endpoints.isEmpty(), "Authority endpoints are forbidden in registry v1.");
            return;
        }
        require(endpoints.size() == 1,
                "Registry v2 and v3 require the exact step-up authority endpoint.");
        ProductAuthorizationContractDtos.AuthorityEndpoint endpoint = endpoints.get(0);
        require("product-surface-step-up-challenge.issue".equals(endpoint.endpointKey())
                        && "POST".equals(endpoint.method())
                        && "/api/auth/product-surface-step-up-challenges".equals(endpoint.publicPath())
                        && "auth".equals(endpoint.serviceKey())
                        && "/auth/product-surface-step-up-challenges".equals(endpoint.servicePath())
                        && endpoint.requiresAuthentication()
                        && endpoint.requiresCsrf()
                        && "X-DWP-Expected-Decision-Revision".equals(
                                endpoint.expectedDecisionRevisionHeader()),
                "Registry step-up authority endpoint drift.");
    }

    private void validateProfiles(
            ProductAuthorizationContractDtos.GovernedRoute route,
            Map<String, ProductAuthorizationContractDtos.CapabilityContract> capabilities,
            Map<String, ProductAuthorizationContractDtos.AccessPolicy> policies,
            Map<String, ProductAuthorizationContractDtos.PredicatePolicy> predicates,
            Map<String, Set<String>> capabilityRoutes,
            Map<String, Set<String>> policyRoutes,
            Map<String, Set<String>> predicateRoutes,
            long bundleVersion) {
        String routeKey = route.routeContractKey();
        require(!nullSafe(route.accessProfiles()).isEmpty(), routeKey + ": access profiles are required.");
        Set<String> profileKeys = new HashSet<>();
        Set<Integer> precedences = new HashSet<>();
        Set<String> bindingKeys = route.gatewayApiBindings().stream()
                .map(ProductAuthorizationContractDtos.GatewayBinding::bindingKey)
                .collect(Collectors.toSet());
        for (ProductAuthorizationContractDtos.AccessProfile profile : route.accessProfiles()) {
            String profileRef = routeKey + "/" + profile.profileKey();
            require(text(profile.profileKey()) && profileKeys.add(profile.profileKey()),
                    profileRef + ": duplicate profile key.");
            require(precedences.add(profile.precedence()), profileRef + ": duplicate precedence.");
            require(nonEmptyUniqueSubset(profile.activeAccessModes(), ACCESS_MODES),
                    profileRef + ": invalid access modes.");
            ProductAuthorizationContractDtos.RequiredAccess access = profile.requiredAccess();
            require(access != null, profileRef + ": required access is missing.");
            if ("CAPABILITY".equals(access.type())) {
                require(capabilities.containsKey(access.capabilityContractKey()),
                        profileRef + ": unknown capability.");
                capabilityRoutes.get(access.capabilityContractKey()).add(routeKey);
            } else if ("CAPABILITY_EXPRESSION".equals(access.type())) {
                require(Set.of("ANY", "ALL").contains(access.mode())
                                && !nullSafe(access.capabilityContractKeys()).isEmpty()
                                && access.capabilityContractKeys().stream().allMatch(capabilities::containsKey),
                        profileRef + ": invalid capability expression.");
                access.capabilityContractKeys().forEach(key -> capabilityRoutes.get(key).add(routeKey));
            } else {
                require("POLICY".equals(access.type()) && policies.containsKey(access.accessPolicyKey()),
                        profileRef + ": unknown access policy.");
                policyRoutes.get(access.accessPolicyKey()).add(routeKey);
            }

            List<String> targets = nullSafe(profile.targetBindingKinds());
            List<String> predicateKeys = nullSafe(profile.predicatePolicyKeys());
            require(new HashSet<>(targets).size() == targets.size() && TARGET_KINDS.containsAll(targets),
                    profileRef + ": invalid target bindings.");
            require(new HashSet<>(predicateKeys).size() == predicateKeys.size(),
                    profileRef + ": duplicate predicates.");
            Set<String> covered = new HashSet<>();
            for (String predicateKey : predicateKeys) {
                ProductAuthorizationContractDtos.PredicatePolicy predicate = predicates.get(predicateKey);
                require(predicate != null, profileRef + ": unknown predicate.");
                Set<String> effective = new HashSet<>(targets);
                effective.retainAll(predicate.targetBindingKinds());
                require(!effective.isEmpty(), profileRef + ": predicate target mismatch.");
                covered.addAll(effective);
                predicateRoutes.get(predicateKey).add(routeKey);
            }
            if (!predicateKeys.isEmpty()) {
                require(covered.equals(new HashSet<>(targets)),
                        profileRef + ": predicate union does not cover targets.");
            }

            List<ProductAuthorizationContractDtos.ResponseProjectionBinding> projections =
                    nullSafe(profile.responseProjectionBindings());
            if ("ACTION".equals(route.routeKind())) {
                require(projections.isEmpty(), profileRef + ": ACTION projection is forbidden.");
            } else {
                require(projections.stream()
                                .map(ProductAuthorizationContractDtos.ResponseProjectionBinding::apiBindingKey)
                                .collect(Collectors.toSet()).equals(bindingKeys)
                                && projections.size() == bindingKeys.size(),
                        profileRef + ": incomplete response projection bindings.");
                require(projections.stream().allMatch(value ->
                                text(value.projectionPolicyKey()) && text(value.responseSchemaKey())),
                        profileRef + ": projection keys are required.");
                boolean approvalFieldMaskProfile = bundleVersion >= 2
                        && "PRODUCT".equals(route.subject().type())
                        && "approvals".equals(route.subject().productKey())
                        && Set.of("auditor", "legacy-oversight").contains(profile.profileKey());
                for (ProductAuthorizationContractDtos.ResponseProjectionBinding projection
                        : projections) {
                    if (approvalFieldMaskProfile) {
                        require(profile.profileKey().equals(
                                        APPROVAL_FIELD_MASK_SCHEMA_PROFILES.get(
                                                projection.responseSchemaKey()))
                                        && Integer.valueOf(1).equals(projection.schemaVersion())
                                        && text(projection.openApiSchemaSha256())
                                        && CHECKSUM_PATTERN.matcher(
                                                projection.openApiSchemaSha256()).matches()
                                        && Boolean.FALSE.equals(
                                                projection.additionalProperties()),
                                profileRef + ": invalid Approval projection schema metadata.");
                    } else {
                        require(projection.schemaVersion() == null
                                        && projection.openApiSchemaSha256() == null
                                        && projection.additionalProperties() == null,
                                profileRef + ": projection schema metadata is forbidden.");
                    }
                }
            }
        }
    }

    private void validateApprovalProjectionSchemaCoverage(
            ProductAuthorizationContractDtos.BundleContract contract) {
        if (contract.version() < 2) {
            return;
        }
        Set<String> schemas = contract.routes().stream()
                .filter(route -> "PRODUCT".equals(route.subject().type())
                        && "approvals".equals(route.subject().productKey()))
                .flatMap(route -> route.accessProfiles().stream())
                .filter(profile -> Set.of("auditor", "legacy-oversight")
                        .contains(profile.profileKey()))
                .flatMap(profile -> nullSafe(profile.responseProjectionBindings()).stream())
                .map(ProductAuthorizationContractDtos.ResponseProjectionBinding::responseSchemaKey)
                .collect(Collectors.toSet());
        require(schemas.equals(APPROVAL_FIELD_MASK_SCHEMA_PROFILES.keySet()),
                "Approval projection schema coverage drift.");
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

    private void revision(String owner, int policyVersion, String lifecycleState, String key) {
        require(text(owner) && policyVersion == 1 && Set.of("ACTIVE", "RETIRED").contains(lifecycleState),
                key + ": invalid revision metadata.");
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

    private static <T> Map<String, T> index(
            List<T> values, Function<T, String> keyExtractor, String label) {
        require(values != null, label + " list is required.");
        Map<String, T> result = new LinkedHashMap<>();
        for (T value : values) {
            String key = keyExtractor.apply(value);
            require(text(key) && result.putIfAbsent(key, value) == null,
                    "Duplicate or empty " + label + " key: " + key);
        }
        return result;
    }

    private static boolean nonEmptyUniqueSubset(List<String> values, Set<String> allowed) {
        return values != null && !values.isEmpty()
                && new HashSet<>(values).size() == values.size() && allowed.containsAll(values);
    }

    private static List<String> sorted(Iterable<String> values) {
        List<String> result = new ArrayList<>();
        if (values != null) values.forEach(result::add);
        return result.stream().sorted().toList();
    }

    private static <T> List<T> nullSafe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static <K, V> Map<K, V> nullSafeMap(Map<K, V> values) {
        return values == null ? Map.of() : values;
    }

    private static boolean text(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
