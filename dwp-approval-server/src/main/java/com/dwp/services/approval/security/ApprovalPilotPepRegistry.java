package com.dwp.services.approval.security;

import com.dwp.core.security.ScopedAuthorityToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Exact release10 PEP, with immutable v2, v7, v8 and v9 projections validated at startup. */
@Component
public final class ApprovalPilotPepRegistry {

    public enum ActiveAccessMode {
        NORMAL,
        ELEVATED,
        PROVIDER_SUPPORT
    }

    static final String RESOURCE =
            "product-authorization/approval-pilot-pep-v2.generated.json";
    static final String V7_RESOURCE =
            "product-authorization/approval-pilot-pep-v7.generated.json";
    static final String V8_RESOURCE =
            "product-authorization/approval-pilot-pep-v8.generated.json";
    static final String V9_RESOURCE =
            "product-authorization/approval-pilot-pep-v9.generated.json";
    static final String V10_RESOURCE =
            "product-authorization/approval-pilot-pep-v10.generated.json";

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<String, JsonNode> capabilities;
    private final Map<String, JsonNode> policies;
    private final Map<String, JsonNode> expressions;
    private final Map<String, JsonNode> predicates;
    private final List<Binding> bindings;

    @Autowired
    public ApprovalPilotPepRegistry(ObjectMapper objectMapper) {
        this(objectMapper, Clock.systemUTC());
    }

    ApprovalPilotPepRegistry(ObjectMapper objectMapper, Clock clock) {
        this(objectMapper, clock, 10);
    }

    ApprovalPilotPepRegistry(ObjectMapper objectMapper, Clock clock, boolean baselineOnly) {
        this(objectMapper, clock, baselineOnly ? 2 : 7);
    }

    ApprovalPilotPepRegistry(ObjectMapper objectMapper, Clock clock, int version) {
        this.objectMapper = objectMapper;
        this.clock = clock;
        require(Set.of(2, 7, 8, 9, 10).contains(version), "Unsupported Approval PEP version");
        if (version != 2) {
            new ApprovalPilotPepRegistry(objectMapper, clock, 2);
        }
        ObjectNode projection = readProjection(switch (version) {
            case 2 -> RESOURCE;
            case 7 -> V7_RESOURCE;
            case 8 -> V8_RESOURCE;
            case 9 -> V9_RESOURCE;
            case 10 -> V10_RESOURCE;
            default -> throw new IllegalStateException("Unsupported Approval PEP version");
        });
        ApprovalPepProjectionLineage.validateEnvelope(objectMapper, projection, version);
        if (version != 2) ApprovalPepProjectionLineage.validateSuperset(readProjection(RESOURCE), projection);
        if (version == 8) {
            new ApprovalPilotPepRegistry(objectMapper, clock, 7);
            ApprovalPepProjectionLineage.validateSuperset(readProjection(V7_RESOURCE), projection);
            ApprovalDocumentProjectionSchemaContract.validateResource(objectMapper);
        }
        if (version == 9) {
            new ApprovalPilotPepRegistry(objectMapper, clock, 8);
            ApprovalPepProjectionLineage.validateSuperset(readProjection(V8_RESOURCE), projection);
            ApprovalExtensionProjectionSchemaContract.validateResource(objectMapper);
        }
        if (version == 10) {
            new ApprovalPilotPepRegistry(objectMapper, clock, 9);
            ApprovalPepProjectionLineage.validateSuperset(readProjection(V9_RESOURCE), projection);
            ApprovalRelease10ProjectionSchemaContract.validateResource(objectMapper);
        }
        if (version != 2) ApprovalWorkProjectionSchemaContract.validateResource(objectMapper);
        capabilities = index(projection, "capabilities", "contractKey");
        policies = index(projection, "accessPolicies", "accessPolicyKey");
        expressions = index(projection, "entitlementExpressions", "expressionKey");
        predicates = index(projection, "predicatePolicies", "predicatePolicyKey");
        bindings = compile(projection);
        validateClosure(projection);
    }

    public Decision authorize(RequestEvidence evidence) {
        if (evidence.activeAccessMode() == null) {
            return Decision.denied("TRUSTED_ACCESS_MODE_REQUIRED");
        }
        if (evidence.trustedRouteContractKey() == null
                || evidence.trustedRouteContractKey().isBlank()) {
            return Decision.denied("TRUSTED_ROUTE_KEY_REQUIRED");
        }
        Binding trusted = bindings.stream()
                .filter(binding -> binding.routeContractKey()
                        .equals(evidence.trustedRouteContractKey()))
                .findFirst().orElse(null);
        if (trusted == null) return Decision.denied("TRUSTED_ROUTE_KEY_UNKNOWN");
        boolean literalRouteMismatch = bindings.stream()
                .filter(binding -> binding.method().equals(evidence.method()))
                .filter(binding -> binding.path().template().equals(evidence.path()))
                .filter(binding -> binding.query().matches(evidence.rawQuery()))
                .anyMatch(binding -> !sameAuthority(binding, trusted));
        if (literalRouteMismatch) return Decision.denied("UNKNOWN_METHOD_PATH_BINDING");
        List<Binding> matches = collapseEquivalent(bindings.stream()
                .filter(binding -> binding.method().equals(evidence.method()))
                .filter(binding -> binding.path().matches(evidence.path()))
                .filter(binding -> binding.query().matches(evidence.rawQuery()))
                .filter(binding -> sameAuthority(binding, trusted))
                .toList());
        if (matches.isEmpty()) return Decision.denied("UNKNOWN_METHOD_PATH_BINDING");

        List<RouteAuthority> authorities = new ArrayList<>();
        for (Binding binding : matches) {
            binding.profiles().stream()
                    .sorted(Comparator.comparingInt(Profile::precedence).reversed())
                    .filter(profile -> profileAllows(profile, evidence))
                    .filter(profile -> !ApprovalPep10AuthorityKeys.ROUTE.equals(binding.routeContractKey()) || ApprovalPep10AuthorityKeys.sameResourceSet(evidence.resourceRoles(), capabilities))
                    .findFirst()
                    .ifPresent(profile -> authorities.addAll(authorityContributions(binding, profile)));
        }
        return authorities.isEmpty()
                ? Decision.denied("EXACT_ROUTE_AUTHORITY_REQUIRED")
                : Decision.allowed(authorities);
    }

    private boolean sameAuthority(Binding candidate, Binding trusted) {
        if (candidate.routeContractKey().equals(trusted.routeContractKey())) return true;
        return trusted.authorizationEquivalenceKey() != null
                && trusted.authorizationEquivalenceKey()
                .equals(candidate.authorizationEquivalenceKey());
    }

    private List<Binding> collapseEquivalent(List<Binding> matches) {
        if (matches.size() < 2) return matches;
        String equivalence = matches.getFirst().authorizationEquivalenceKey();
        if (equivalence == null || matches.stream().anyMatch(
                binding -> !equivalence.equals(binding.authorizationEquivalenceKey()))) {
            return List.of();
        }
        return List.of(matches.stream().min(
                Comparator.comparing(Binding::routeContractKey)).orElseThrow());
    }

    public List<BindingContract> bindingContracts() {
        return bindings.stream().map(binding -> new BindingContract(
                binding.routeContractKey(), binding.routeKind(), binding.method(),
                binding.publicPath(), binding.path().template())).toList();
    }

    private boolean profileAllows(Profile profile, RequestEvidence evidence) {
        if (!profile.activeModes().contains(evidence.activeAccessMode())) return false;
        JsonNode access = profile.requiredAccess();
        return switch (access.path("type").asText()) {
            case "CAPABILITY" -> capabilityAllows(
                    access.path("capabilityContractKey").asText(), profile, evidence);
            case "CAPABILITY_EXPRESSION" -> capabilityExpressionAllows(
                    access, profile, evidence);
            case "POLICY" -> policyAllows(access.path("accessPolicyKey").asText(), evidence);
            default -> false;
        };
    }

    private boolean capabilityExpressionAllows(
            JsonNode access, Profile profile, RequestEvidence evidence) {
        List<Boolean> decisions = new ArrayList<>();
        access.path("capabilityContractKeys").forEach(key -> decisions.add(
                capabilityAllows(key.asText(), profile, evidence)));
        return !decisions.isEmpty() && ("ALL".equals(access.path("mode").asText())
                ? decisions.stream().allMatch(Boolean::booleanValue)
                : "ANY".equals(access.path("mode").asText())
                && decisions.stream().anyMatch(Boolean::booleanValue));
    }

    private boolean policyAllows(String policyKey, RequestEvidence evidence) {
        JsonNode policy = descriptor(policies, policyKey, "policy");
        if (!"SINGLE".equals(policy.path("evaluationType").asText())
                || !policy.path("requiresProductEntitlement").asBoolean()) return false;
        JsonNode expression = descriptor(
                expressions, policy.path("entitlementExpressionKey").asText(), "expression");
        return expressionAllows(expression.path("expression"), evidence.permissions());
    }

    private boolean capabilityAllows(
            String capabilityKey, Profile profile, RequestEvidence evidence) {
        JsonNode capability = descriptor(capabilities, capabilityKey, "capability");
        if (!evidence.permissions().contains(
                capability.path("resolvedCapabilityCode").asText().toUpperCase(Locale.ROOT))) {
            return false;
        }
        boolean scoped = scopedSpecialist(capability);
        Set<String> scopedSets = scoped
                ? scopedAuthoritySets(evidence.resourceRoles(), capability)
                : Set.of();
        if (scoped && scopedSets.isEmpty()) {
            return false;
        }
        String requirement = capability.path("responsibilityRequirement").asText();
        if ("REQUIRED".equals(requirement)
                && (scoped
                ? !hasPairedResourceRole(evidence.resourceRoles(), capability, scopedSets)
                : !hasResourceRole(evidence.resourceRoles(), capability))) {
            return false;
        }
        if ("LEGACY_OVERSIGHT".equals(requirement)) {
            if (!profile.readOnly() || !"GET".equals(evidence.method())
                    || !evidence.roles().contains("TENANT_ADMIN")) return false;
            String sunsetAt = capability.path("sunsetAt").asText();
            if (sunsetAt.isBlank() || !clock.instant().isBefore(Instant.parse(sunsetAt))) return false;
        }
        return !staticSodConflict(capability, evidence);
    }

    private boolean staticSodConflict(JsonNode capability, RequestEvidence evidence) {
        Set<String> targetSets = scopedAuthoritySets(evidence.resourceRoles(), capability);
        if (capability.path("sodPolicyId").asText().isBlank() || targetSets.isEmpty()) {
            return false;
        }
        return switch (capability.path("contractKey").asText()) {
            case "approvals.design.publish" ->
                    overlaps(targetSets, scopedAuthoritySets(
                            evidence.resourceRoles(), "approvals.design.create",
                            "ADMIN.APPROVAL_DESIGN:CREATE"))
                            || overlaps(targetSets, scopedAuthoritySets(
                                    evidence.resourceRoles(), "approvals.design.update",
                                    "ADMIN.APPROVAL_DESIGN:UPDATE"));
            case "approvals.policy.publish" ->
                    overlaps(targetSets, scopedAuthoritySets(
                            evidence.resourceRoles(), "approvals.policy.update",
                            "ADMIN.APPROVAL_POLICY:UPDATE"));
            case "approvals.operations.execute" ->
                    overlaps(targetSets, scopedAuthoritySets(
                            evidence.resourceRoles(), "approvals.audit.operations.read",
                            "ADMIN.APPROVAL_OPERATIONS:VIEW"));
            default -> false;
        };
    }

    private boolean overlaps(Set<String> left, Set<String> right) {
        return left.stream().anyMatch(right::contains);
    }

    private boolean expressionAllows(JsonNode expression, Set<String> permissions) {
        String type = expression.path("type").asText();
        if ("LEAF".equals(type)) {
            return permissions.contains(
                    expression.path("entitlement").asText().toUpperCase(Locale.ROOT));
        }
        List<Boolean> children = new ArrayList<>();
        expression.path("children").forEach(child ->
                children.add(expressionAllows(child, permissions)));
        return !children.isEmpty() && ("ALL".equals(type)
                ? children.stream().allMatch(Boolean::booleanValue)
                : "ANY".equals(type) && children.stream().anyMatch(Boolean::booleanValue));
    }

    private RouteAuthority authority(Binding binding, Profile profile) {
        JsonNode access = profile.requiredAccess();
        String capabilityKey = "CAPABILITY".equals(access.path("type").asText())
                ? access.path("capabilityContractKey").asText() : null;
        return authority(binding, profile, capabilityKey);
    }

    private List<RouteAuthority> authorityContributions(Binding binding, Profile profile) {
        if (ApprovalPep10AuthorityKeys.ROUTE.equals(binding.routeContractKey())) {
            return ApprovalPep10AuthorityKeys.planning(binding.routeKind(), profile.profileKey(),
                    profile.readOnly(), profile.requiredAccess()).stream()
                    .map(key -> authority(binding, profile, key)).toList();
        }
        if (!"route.approvals.admin.policy-impact.data".equals(binding.routeContractKey())) {
            return List.of(authority(binding, profile));
        }
        JsonNode access = profile.requiredAccess();
        List<String> keys = List.copyOf(textValues(access.path("capabilityContractKeys")));
        require("DATA".equals(binding.routeKind()) && "full-management".equals(profile.profileKey())
                        && profile.readOnly() && "CAPABILITY_EXPRESSION".equals(access.path("type").asText())
                        && "ALL".equals(access.path("mode").asText()) && keys.size() == 3
                        && access.path("capabilityContractKeys").size() == 3
                        && Set.copyOf(keys).equals(Set.of("approvals.policy.read", "approvals.design.read",
                        "approvals.operations.read")),
                "Policy impact requires three independent management authorities");
        return keys.stream().map(key -> authority(binding, profile, key)).toList();
    }

    private RouteAuthority authority(Binding binding, Profile profile, String capabilityKey) {
        JsonNode capability = capabilityKey == null ? null : capabilities.get(capabilityKey);
        ProjectionBinding projection = profile.projections().get(binding.bindingKey());
        return new RouteAuthority(
                binding.routeContractKey(), binding.routeKind(), profile.profileKey(),
                profile.readOnly(), profile.predicateKeys(), capabilityKey,
                capability == null ? null : textOrNull(capability, "activationPolicy"),
                capability == null ? null : textOrNull(capability, "sodPolicyId"),
                capability != null && "HIGH".equals(capability.path("riskTier").asText()),
                projection == null ? null : projection.projectionPolicyKey(),
                projection == null ? null : projection.responseSchemaKey(),
                projection == null ? null : projection.schemaVersion(),
                projection == null ? null : projection.openApiSchemaSha256(),
                projection == null ? null : projection.additionalProperties(),
                capability == null ? null
                        : capability.path("resolvedCapabilityCode").asText(null),
                capability == null ? null
                        : textOrNull(capability, "requiredResponsibilityCode"));
    }

    private List<Binding> compile(ObjectNode projection) {
        List<Binding> result = new ArrayList<>();
        for (JsonNode route : requiredArray(projection, "routes")) {
            String routeKey = route.path("routeContractKey").asText();
            Map<String, JsonNode> publicBindings = indexArray(
                    route.path("gatewayApiBindings"), "bindingKey", routeKey);
            List<Profile> profiles = new ArrayList<>();
            for (JsonNode value : requiredArray(route, "accessProfiles")) {
                String profileKey = value.path("profileKey").asText();
                Set<ActiveAccessMode> activeAccessModes = activeAccessModes(
                        value.path("activeAccessModes"), profileKey);
                Map<String, ProjectionBinding> projections = new LinkedHashMap<>();
                JsonNode projectionValues = value.path("responseProjectionBindings");
                if (projectionValues.isArray()) projectionValues.forEach(bindingProjection -> {
                    String bindingKey = bindingProjection.path("apiBindingKey").asText();
                    Integer schemaVersion = integerOrNull(
                            bindingProjection, "schemaVersion");
                    String schemaSha256 = textOrNull(
                            bindingProjection, "openApiSchemaSha256");
                    Boolean additionalProperties = booleanOrNull(
                            bindingProjection, "additionalProperties");
                    ApprovalProjectionMetadata.validate(routeKey, profileKey, bindingProjection);
                    require(!bindingKey.isBlank() && projections.putIfAbsent(
                            bindingKey,
                            new ProjectionBinding(
                                    bindingProjection.path("projectionPolicyKey").asText(),
                                    bindingProjection.path("responseSchemaKey").asText(),
                                    schemaVersion, schemaSha256,
                                    additionalProperties)) == null,
                            routeKey + ": duplicate response projection binding");
                });
                profiles.add(new Profile(
                        profileKey, value.path("precedence").asInt(),
                        value.path("readOnly").asBoolean(), activeAccessModes,
                        textValues(value.path("predicatePolicyKeys")), value.path("requiredAccess"),
                        Map.copyOf(projections)));
            }
            for (JsonNode service : requiredArray(route, "servicePepBindings")) {
                require("approval".equals(service.path("serviceKey").asText()),
                        routeKey + ": wrong PEP owner");
                JsonNode publicBinding = publicBindings.get(service.path("bindingKey").asText());
                require(publicBinding != null, routeKey + ": public binding missing");
                require(service.path("method").asText().equals(publicBinding.path("method").asText()),
                        routeKey + ": public/service method mismatch");
                result.add(new Binding(
                        routeKey, route.path("routeKind").asText(),
                        textOrNull(route, "authorizationEquivalenceKey"),
                        service.path("bindingKey").asText(),
                        service.path("method").asText(), publicBinding.path("path").asText(),
                        ApprovalPepBindingConstraints.PathTemplate.compile(
                                service.path("path").asText(),
                                service.path("pathParameterConstraints")),
                        ApprovalPepBindingConstraints.QueryConstraints.compile(
                                service.path("queryParameterConstraints")),
                        List.copyOf(profiles)));
            }
        }
        require(result.size() == projection.path("bindingPairCount").asInt(),
                "Approval Pilot binding count changed");
        return List.copyOf(result);
    }

    private void validateClosure(ObjectNode projection) {
        int version = projection.path("registryRef").path("version").asInt();
        require(capabilities.size() == (version == 10 ? 37 : version == 9 ? 32 : version == 8 ? 28 : 24) && policies.size() == 1
                        && expressions.size() == 1 && predicates.size()
                        == (version == 2 ? 6 : version == 7 ? 7 : version == 8 ? 10 : version == 9 ? 13 : 16),
                "Approval Pilot descriptor closure count changed");
        capabilities.forEach((capabilityKey, capability) -> {
            String requirement = capability.path("responsibilityRequirement").asText();
            String requiredCode = textOrNull(capability, "requiredResponsibilityCode");
            if ("REQUIRED".equals(requirement)) {
                require(requiredCode != null
                                && capability.path("scopeResolver").asText()
                                .startsWith("APP_RESOURCE_SET:")
                                && capability.path("scopeResolver").asText().length()
                                > "APP_RESOURCE_SET:".length(),
                        capabilityKey + ": required responsibility binding is incomplete");
            } else {
                require(requiredCode == null,
                        capabilityKey + ": non-required capability declares a responsibility code");
            }
        });
        Set<String> routes = new LinkedHashSet<>();
        Set<String> fieldMaskSchemas = new LinkedHashSet<>();
        for (JsonNode route : requiredArray(projection, "routes")) {
            String routeKey = route.path("routeContractKey").asText();
            require(routes.add(routeKey), "Duplicate Approval Pilot route " + routeKey);
            for (JsonNode profile : requiredArray(route, "accessProfiles")) {
                String profileKey = profile.path("profileKey").asText();
                JsonNode responseProjections = profile.path("responseProjectionBindings");
                if (responseProjections.isArray()) {
                    for (JsonNode binding : responseProjections) {
                        if (ApprovalProjectionSchemaContract.isFieldMaskProfile(profileKey)) {
                            String schemaKey = binding.path("responseSchemaKey").asText();
                            require(ApprovalProjectionSchemaContract.matches(
                                            profileKey,
                                            schemaKey,
                                            integerOrNull(binding, "schemaVersion"),
                                            textOrNull(binding, "openApiSchemaSha256"),
                                            booleanOrNull(binding, "additionalProperties")),
                                    routeKey + ": field-mask projection schema mismatch");
                            fieldMaskSchemas.add(schemaKey);
                        }
                    }
                }
                JsonNode access = profile.path("requiredAccess");
                if ("CAPABILITY".equals(access.path("type").asText())) {
                    descriptor(capabilities, access.path("capabilityContractKey").asText(), "capability");
                } else if ("CAPABILITY_EXPRESSION".equals(access.path("type").asText())) {
                    for (String capabilityKey : textValues(
                            access.path("capabilityContractKeys"))) {
                        descriptor(capabilities, capabilityKey, "capability");
                    }
                } else if ("POLICY".equals(access.path("type").asText())) {
                    descriptor(policies, access.path("accessPolicyKey").asText(), "policy");
                } else throw new IllegalStateException(routeKey + ": unsupported access type");
                for (String predicateKey : textValues(profile.path("predicatePolicyKeys"))) {
                    JsonNode predicate = descriptor(predicates, predicateKey, "predicate");
                    require("approval".equals(predicate.path("ownerServiceKey").asText())
                                    && textValues(predicate.path("routeContractKeys")).contains(routeKey),
                            routeKey + ": predicate owner or route allowlist mismatch");
                }
            }
        }
        require(fieldMaskSchemas.equals(ApprovalProjectionSchemaContract.schemaKeys()),
                "Approval field-mask projection schema coverage changed");
    }

    private ObjectNode readProjection(String resource) {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Generated Approval Pilot PEP is absent.");
            JsonNode value = objectMapper.readTree(input);
            if (!(value instanceof ObjectNode object)) {
                throw new IllegalStateException("Generated Approval Pilot PEP must be an object.");
            }
            return object;
        } catch (IOException exception) {
            throw new IllegalStateException("Generated Approval Pilot PEP cannot be read.", exception);
        }
    }

    private Map<String, JsonNode> index(ObjectNode root, String field, String keyField) {
        return indexArray(requiredArray(root, field), keyField, field);
    }

    private Map<String, JsonNode> indexArray(JsonNode values, String keyField, String label) {
        require(values.isArray(), label + " must be an array");
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode value : values) {
            String key = value.path(keyField).asText();
            require(!key.isBlank() && result.putIfAbsent(key, value) == null,
                    label + ": duplicate or empty " + keyField);
        }
        return Map.copyOf(result);
    }

    private JsonNode descriptor(Map<String, JsonNode> values, String key, String label) {
        JsonNode value = values.get(key);
        if (value == null) throw new IllegalStateException("Unknown Approval " + label + " " + key);
        return value;
    }

    private Set<String> textValues(JsonNode value) {
        require(value.isArray(), "Expected a generated string array");
        Set<String> result = new LinkedHashSet<>();
        value.forEach(item -> require(item.isTextual() && result.add(item.asText()),
                "Generated string array contains an invalid or duplicate value"));
        return Set.copyOf(result);
    }

    private Set<ActiveAccessMode> activeAccessModes(JsonNode value, String profileKey) {
        require(value.isArray(), profileKey + ": active access modes must be an array");
        Set<ActiveAccessMode> result = new LinkedHashSet<>();
        for (JsonNode item : value) {
            require(item.isTextual(), profileKey + ": active access mode must be text");
            ActiveAccessMode mode;
            try {
                mode = ActiveAccessMode.valueOf(item.asText());
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException(
                        profileKey + ": unknown active access mode " + item.asText(), exception);
            }
            require(result.add(mode), profileKey + ": duplicate active access mode " + mode);
        }
        require(!result.isEmpty(), profileKey + ": active access modes are required");
        return Set.copyOf(result);
    }

    private boolean hasResourceRole(String header, JsonNode capability) {
        String prefix = "APP_RESOURCE_SET:";
        String scopeResolver = capability.path("scopeResolver").asText();
        String requiredCode = capability.path("requiredResponsibilityCode").asText();
        if (!scopeResolver.startsWith(prefix) || header == null) return false;
        if (requiredCode.isBlank() || scopeResolver.length() == prefix.length()) return false;
        String expected = (requiredCode + "@" + scopeResolver.substring(prefix.length()))
                .toUpperCase(Locale.ROOT);
        return Arrays.stream(header.split(","))
                .map(String::trim).map(value -> value.toUpperCase(Locale.ROOT))
                .anyMatch(expected::equals);
    }

    private boolean hasPairedResourceRole(
            String header, JsonNode capability, Set<String> scopedSets) {
        String requiredCode = capability.path("requiredResponsibilityCode").asText();
        if (requiredCode.isBlank() || header == null) return false;
        Set<String> roles = Arrays.stream(header.split(","))
                .map(String::trim)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        return scopedSets.stream().anyMatch(set ->
                roles.contains(requiredCode.toUpperCase(Locale.ROOT) + '@' + set));
    }

    private boolean scopedSpecialist(JsonNode capability) {
        return "approvals.admin".equals(capability.path("surfaceKey").asText())
                && !"LEGACY_OVERSIGHT".equals(
                        capability.path("responsibilityRequirement").asText());
    }

    private Set<String> scopedAuthoritySets(String header, JsonNode capability) {
        return scopedAuthoritySets(
                header,
                capability.path("contractKey").asText(),
                capability.path("resolvedCapabilityCode").asText());
    }

    private Set<String> scopedAuthoritySets(
            String header,
            String capabilityContractKey,
            String resolvedCapabilityCode) {
        if (header == null) return Set.of();
        try {
            return ScopedAuthorityToken.matchingResourceSetKeys(
                    Arrays.stream(header.split(",")).map(String::trim).toList(),
                    capabilityContractKey, resolvedCapabilityCode);
        } catch (IllegalArgumentException exception) {
            return Set.of();
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private static Integer integerOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isInt() ? value.intValue() : null;
    }

    private static Boolean booleanOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isBoolean() ? value.booleanValue() : null;
    }

    private static ArrayNode requiredArray(JsonNode source, String field) {
        JsonNode value = source.path(field);
        require(value instanceof ArrayNode, field + " must be an array");
        return (ArrayNode) value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    public record RequestEvidence(
            String method, String path, Set<String> permissions, String resourceRoles,
            Set<String> roles, String trustedRouteContractKey, String rawQuery,
            ActiveAccessMode activeAccessMode) {
        public RequestEvidence(
                String method, String path, Set<String> permissions, String resourceRoles,
                Set<String> roles, String trustedRouteContractKey,
                ActiveAccessMode activeAccessMode) {
            this(method, path, permissions, resourceRoles, roles,
                    trustedRouteContractKey, null, activeAccessMode);
        }

        public RequestEvidence {
            method = method == null ? "" : method.toUpperCase(Locale.ROOT);
            path = path == null ? "" : path;
            permissions = normalize(permissions);
            resourceRoles = resourceRoles == null ? "" : resourceRoles;
            roles = normalize(roles);
        }

        private static Set<String> normalize(Set<String> values) {
            if (values == null) return Set.of();
            return values.stream().filter(value -> value != null && !value.isBlank())
                    .map(value -> value.trim().toUpperCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    public record Decision(boolean allowed, String denialCode, List<RouteAuthority> authorities) {
        static Decision allowed(List<RouteAuthority> values) {
            return new Decision(true, null, List.copyOf(values));
        }

        static Decision denied(String code) {
            return new Decision(false, code, List.of());
        }
    }

    public record RouteAuthority(
            String routeContractKey, String routeKind, String profileKey, boolean readOnly,
            Set<String> predicatePolicyKeys, String capabilityContractKey,
            String activationPolicy, String sodPolicyId, boolean highRisk,
            String projectionPolicyKey, String responseSchemaKey,
            Integer projectionSchemaVersion, String openApiSchemaSha256,
            Boolean projectionAdditionalProperties, String resolvedCapabilityCode,
            String requiredResponsibilityCode) {

        public RouteAuthority(
                String routeContractKey, String routeKind, String profileKey,
                boolean readOnly, Set<String> predicatePolicyKeys,
                String capabilityContractKey, String activationPolicy,
                String sodPolicyId, boolean highRisk, String projectionPolicyKey,
                String responseSchemaKey) {
            this(routeContractKey, routeKind, profileKey, readOnly,
                    predicatePolicyKeys, capabilityContractKey, activationPolicy,
                    sodPolicyId, highRisk, projectionPolicyKey, responseSchemaKey,
                    null, null, null, null, null);
        }

        public RouteAuthority(
                String routeContractKey, String routeKind, String profileKey,
                boolean readOnly, Set<String> predicatePolicyKeys,
                String capabilityContractKey, String activationPolicy,
                String sodPolicyId, boolean highRisk, String projectionPolicyKey,
                String responseSchemaKey, Integer projectionSchemaVersion,
                String openApiSchemaSha256, Boolean projectionAdditionalProperties) {
            this(routeContractKey, routeKind, profileKey, readOnly,
                    predicatePolicyKeys, capabilityContractKey, activationPolicy,
                    sodPolicyId, highRisk, projectionPolicyKey, responseSchemaKey,
                    projectionSchemaVersion, openApiSchemaSha256,
                    projectionAdditionalProperties, null, null);
        }
    }

    public record BindingContract(
            String routeContractKey, String routeKind, String method,
            String publicPath, String servicePath) {
    }

    private record Profile(
            String profileKey, int precedence, boolean readOnly,
            Set<ActiveAccessMode> activeModes,
            Set<String> predicateKeys, JsonNode requiredAccess,
            Map<String, ProjectionBinding> projections) {
    }

    private record Binding(
            String routeContractKey, String routeKind, String authorizationEquivalenceKey,
            String bindingKey,
            String method, String publicPath,
            ApprovalPepBindingConstraints.PathTemplate path,
            ApprovalPepBindingConstraints.QueryConstraints query,
            List<Profile> profiles) {
    }

    private record ProjectionBinding(
            String projectionPolicyKey, String responseSchemaKey,
            Integer schemaVersion, String openApiSchemaSha256,
            Boolean additionalProperties) {
    }

}
