package com.dwp.services.platform.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Closed Platform-owned HCM PEP projection for the append-only SYS-012 v43 carrier. */
@Component
public final class HcmPlatformPepRegistry {

    static final String RESOURCE =
            "product-authorization/hcm-platform-pep-v43.generated.json";
    static final String REGISTRY_V43_CHECKSUM =
            "2222319502c362caf247a7a47fdacfc63c774dbf156f6272cafae797627e2400";
    static final String PROJECTION_V43_CHECKSUM =
            "e7f8c474f7329c34c75da9a3135c744c4284e21171b9eb254a5f1d62ec8137c3";
    private static final String CONFIGURATION_ROOT = "/v1/admin/hris/configurations";
    private static final String WORKBENCH_PATH = "/v1/hris/workbench/settings-reference";
    private static final String NAVIGATION_POLICY_ROOT =
            "/v1/admin/hris/navigation-policies";
    private static final String NAVIGATION_PROJECTION_PATH =
            "/v1/hris/navigation/projection";
    private static final String HCM_AUDIT_EVENTS_ROOT =
            "/v1/admin/audit-control/events";
    private static final String EXPLORER_PATH = "/v1/hris/explorer";
    private static final String EXPLORER_PREFERENCES_PATH =
            "/v1/hris/explorer/preferences";
    private static final String EXPLORER_RECENTS_PATH =
            "/v1/hris/explorer/recents";
    private static final String HRIS_COMMAND_RECEIPT_ROOT =
            "/v1/admin/hris/command-receipts";
    private static final String EXPLORER_ROUTE_PREFIX = "route.hcm.utility.explorer";
    private static final Set<String> EXPLORER_PACKAGES = Set.of(
            "HRIS_EMPLOYEE",
            "HRIS_LINE_MANAGER",
            "HRIS_HR_BUSINESS_PARTNER",
            "HRIS_CONFIG_AUTHOR",
            "HRIS_AUDITOR");
    private static final Set<String> PLATFORM_RECEIPT_PACKAGES = Set.of(
            "HRIS_CONFIG_AUTHOR",
            "HRIS_CONFIG_PUBLISHER",
            "HRIS_INTEGRATION_AUTHOR",
            "HRIS_INTEGRATION_EXECUTOR");
    private static final String EXPLORER_DUTY =
            "HRIS_DUTY_APP_SHELL_READER@SELF";
    private static final Pattern EXPLORER_AUTHORITY_CONTEXT = Pattern.compile(
            "^psc-[a-f0-9]{64}$");
    private static final String EXPLORER_PURPOSE = "HRIS_APP_DISCOVERY";
    private static final Set<String> EXPLORER_RECEIPT_ACTIONS = Set.of(
            "EXPLORER_PREFERENCES_REPLACE",
            "EXPLORER_RECENT_RECORD");
    /**
     * Stable handoff used only by an earlier in-process Explorer owner PEP. Network
     * headers are deliberately not accepted as owner evidence.
     */
    public static final String EXPLORER_OWNER_EVIDENCE_ATTRIBUTE =
            HcmPlatformPepRegistry.class.getName() + ".explorerOwnerEvidence";

    private final ObjectMapper objectMapper;
    private final Map<String, JsonNode> capabilities;
    private final List<Binding> bindings;

    public HcmPlatformPepRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        ObjectNode projection = readProjection();
        validateEnvelope(projection);
        capabilities = index(requiredArray(projection, "capabilities"),
                "contractKey", "capabilities");
        bindings = compile(projection);
        validateClosure(projection);
    }

    /** Claims only the canonical HRIS configuration families, never generic /v1/admin. */
    public boolean governsPathFamily(String path) {
        if (path == null) return false;
        return claimed(path) || claimed(decodeForClaim(path));
    }

    private boolean claimed(String path) {
        return path.equals(WORKBENCH_PATH)
                || path.startsWith(WORKBENCH_PATH + ";")
                || path.equals(NAVIGATION_PROJECTION_PATH)
                || path.startsWith(NAVIGATION_PROJECTION_PATH + ";")
                || path.equals(HCM_AUDIT_EVENTS_ROOT)
                || path.startsWith(HCM_AUDIT_EVENTS_ROOT + "/")
                || path.startsWith(HCM_AUDIT_EVENTS_ROOT + ";")
                || path.startsWith(HCM_AUDIT_EVENTS_ROOT + "%")
                || path.equals(EXPLORER_PATH)
                || path.equals(EXPLORER_PREFERENCES_PATH)
                || path.equals(EXPLORER_RECENTS_PATH)
                || path.startsWith(HRIS_COMMAND_RECEIPT_ROOT + "/")
                || path.startsWith(HRIS_COMMAND_RECEIPT_ROOT + ";")
                || path.startsWith(HRIS_COMMAND_RECEIPT_ROOT + "%")
                || path.equals(CONFIGURATION_ROOT)
                || path.startsWith(CONFIGURATION_ROOT + "/")
                || path.startsWith(CONFIGURATION_ROOT + ";")
                || path.startsWith(CONFIGURATION_ROOT + "%")
                || path.equals(NAVIGATION_POLICY_ROOT)
                || path.startsWith(NAVIGATION_POLICY_ROOT + "/")
                || path.startsWith(NAVIGATION_POLICY_ROOT + ";")
                || path.startsWith(NAVIGATION_POLICY_ROOT + "%");
    }

    public Decision authorize(RequestEvidence evidence) {
        if (!governsPathFamily(evidence.path())) {
            return Decision.denied("NOT_HCM_PLATFORM_ROUTE");
        }
        if (evidence.trustedRouteContractKey() == null
                || evidence.trustedRouteContractKey().isBlank()) {
            return Decision.denied("TRUSTED_ROUTE_KEY_REQUIRED");
        }
        List<Binding> matches = bindings.stream()
                .filter(binding -> binding.routeContractKey()
                        .equals(evidence.trustedRouteContractKey()))
                .filter(binding -> binding.method().equals(evidence.method()))
                .filter(binding -> binding.path().matches(evidence.path()))
                .toList();
        if (matches.size() != 1) {
            return Decision.denied(matches.isEmpty()
                    ? "UNKNOWN_METHOD_PATH_BINDING" : "AMBIGUOUS_METHOD_PATH_BINDING");
        }
        Binding binding = matches.getFirst();
        Profile profile = binding.profiles().stream()
                .sorted(Comparator.comparingInt(Profile::precedence).reversed())
                .filter(value -> value.allows(evidence.permissions(), capabilities))
                .filter(value -> !binding.routeContractKey().startsWith(
                        EXPLORER_ROUTE_PREFIX)
                        || allowsExplorerProfile(value, evidence))
                .findFirst().orElse(null);
        if (profile == null) return Decision.denied("EXACT_ROUTE_AUTHORITY_REQUIRED");
        String capabilityKey = profile.requiredAccess().path(
                "capabilityContractKey").asText(null);
        JsonNode capability = capabilityKey == null ? null : capabilities.get(capabilityKey);
        return Decision.allowed(new RouteAuthority(
                binding.routeContractKey(), binding.routeKind(), binding.method(),
                binding.path().template(), profile.profileKey(), binding.highRisk(),
                capabilityKey,
                capability == null ? null : capability.path("activationPolicy").asText(null),
                binding.stepUpCommandBinding()));
    }

    private boolean allowsExplorerProfile(Profile profile, RequestEvidence evidence) {
        if ("platform-originating-command-receipt-reader".equals(
                profile.profileKey())) {
            return evidence.roles().stream().anyMatch(
                    PLATFORM_RECEIPT_PACKAGES::contains);
        }
        if (!profile.profileKey().startsWith("explorer-")) return false;
        if (!evidence.permissions().contains("APP.HCM:VIEW")
                || evidence.roles().stream().noneMatch(EXPLORER_PACKAGES::contains)
                || !evidence.resourceRoles().contains(EXPLORER_DUTY)) {
            return false;
        }
        ExplorerOwnerEvidence owner = evidence.explorerOwnerEvidence();
        if (owner == null
                || evidence.currentDecisionRevision() == null
                || !evidence.currentDecisionRevision().equals(
                owner.currentDecisionRevision())
                || evidence.contextKey() == null
                || !EXPLORER_AUTHORITY_CONTEXT.matcher(evidence.contextKey()).matches()
                || !evidence.contextKey().equals(owner.contextKey())
                || evidence.contextScopeKey() == null
                || !evidence.contextScopeKey().equals(owner.contextScopeKey())
                || !EXPLORER_PURPOSE.equals(owner.purposeCode())
                || owner.fieldPolicyRevision() < 1
                || owner.authorizationRevision() < 1
                || owner.allowedDescendantPageRouteKeys().stream()
                .noneMatch(HcmPlatformPepRegistry::isDescendantPageRoute)) {
            return false;
        }
        if (!"explorer-originating-receipt-reader".equals(profile.profileKey())) {
            return true;
        }
        ExplorerReceiptEvidence receipt = owner.receiptEvidence();
        return receipt != null
                && owner.subjectPrincipalPublicId() != null
                && owner.subjectPrincipalPublicId().equals(
                receipt.subjectPrincipalPublicId())
                && EXPLORER_RECEIPT_ACTIONS.contains(receipt.originatingAction())
                && evidence.contextKey().equals(receipt.contextKey())
                && evidence.contextScopeKey().equals(receipt.contextScopeKey());
    }

    private static boolean isDescendantPageRoute(String routeKey) {
        return routeKey != null
                && routeKey.length() <= 200
                && routeKey.startsWith("route.hcm.")
                && routeKey.endsWith(".page")
                && !routeKey.startsWith(EXPLORER_ROUTE_PREFIX);
    }

    public List<BindingContract> bindingContracts() {
        return bindings.stream().map(binding -> new BindingContract(
                binding.routeContractKey(), binding.routeKind(), binding.method(),
                binding.path().template(), binding.highRisk())).toList();
    }

    private List<Binding> compile(ObjectNode projection) {
        List<Binding> result = new ArrayList<>();
        for (JsonNode route : requiredArray(projection, "routes")) {
            String routeKey = route.path("routeContractKey").asText();
            List<Profile> profiles = new ArrayList<>();
            for (JsonNode profile : requiredArray(route, "accessProfiles")) {
                profiles.add(new Profile(
                        profile.path("profileKey").asText(),
                        profile.path("precedence").asInt(),
                        profile.path("requiredAccess").deepCopy()));
            }
            for (JsonNode service : requiredArray(route, "servicePepBindings")) {
                require("platform".equals(service.path("serviceKey").asText()),
                        routeKey + ": foreign service binding escaped HCM Platform PEP");
                String path = service.path("path").asText();
                if (!claimed(path)) continue;
                StepUpCommandBinding stepUp = stepUpBinding(
                        route, service.path("bindingKey").asText());
                boolean highRisk = stepUp != null || profiles.stream()
                        .anyMatch(this::requiresElevatedActivation);
                result.add(new Binding(
                        routeKey,
                        route.path("routeKind").asText(),
                        service.path("method").asText(),
                        PathTemplate.compile(path),
                        List.copyOf(profiles), highRisk,
                        stepUp));
            }
        }
        require(result.size() == 27, "HCM Platform SYS binding count changed");
        return List.copyOf(result);
    }

    private boolean requiresElevatedActivation(Profile profile) {
        JsonNode access = profile.requiredAccess();
        if (!"CAPABILITY".equals(access.path("type").asText())) return false;
        JsonNode capability = capabilities.get(
                access.path("capabilityContractKey").asText());
        return capability != null
                && capability.path("activationPolicy").asText("").startsWith("STEPUP-");
    }

    private StepUpCommandBinding stepUpBinding(JsonNode route, String bindingKey) {
        JsonNode values = route.path("stepUpCommandBindings");
        if (!values.isArray()) return null;
        List<JsonNode> matches = new ArrayList<>();
        values.forEach(value -> {
            if (bindingKey.equals(value.path("bindingKey").asText())) matches.add(value);
        });
        require(matches.size() <= 1, bindingKey + ": ambiguous step-up binding");
        if (matches.isEmpty()) return null;
        JsonNode value = matches.getFirst();
        return new StepUpCommandBinding(
                value.path("targetType").asText(),
                value.path("targetIdPathParameter").asText(null),
                value.path("expectedObjectVersionSource").asText(),
                value.path("expectedObjectVersionName").asText(),
                value.path("ownerServiceKey").asText(),
                value.path("audience").asText());
    }

    private void validateEnvelope(ObjectNode projection) {
        JsonNode registry = projection.path("registryRef");
        require(projection.path("schemaVersion").asInt() == 1
                        && "hcm-platform-pep-v43".equals(
                        projection.path("projectionKey").asText())
                        && "platform".equals(projection.path("ownerServiceKey").asText())
                        && "product-surfaces".equals(registry.path("bundleKey").asText())
                        && registry.path("version").asInt() == 43
                        && REGISTRY_V43_CHECKSUM.equals(registry.path("sha256").asText()),
                "Unexpected HCM Platform PEP envelope");
        require(projection.path("sourceRegistryRouteCount").asInt() == 1032
                        && projection.path("projectedRouteContractCount").asInt() == 34
                        && projection.path("bindingPairCount").asInt() == 35
                        && requiredArray(projection, "capabilities").size() == 18
                        && requiredArray(projection, "accessPolicies").size() == 4
                        && requiredArray(projection, "entitlementExpressions").size() == 3
                        && requiredArray(projection, "predicatePolicies").size() == 7,
                "HCM Platform projection closure count changed");
        ObjectNode payload = projection.deepCopy();
        JsonNode checksum = payload.remove("projectionChecksum");
        require(checksum != null
                        && PROJECTION_V43_CHECKSUM.equals(checksum.asText())
                        && checksum.asText().equals(sha256(payload)),
                "HCM Platform projection checksum mismatch");
    }

    private void validateClosure(ObjectNode projection) {
        for (Binding binding : bindings) {
            require(binding.routeContractKey().startsWith("route.hcm.configuration.")
                            || binding.routeContractKey().equals(
                            "route.hcm.operations.navigation-projection.data")
                            || binding.routeContractKey().equals(
                            HcmAuditEvidenceSecurityPolicy.PAGE_ROUTE)
                            || binding.routeContractKey().equals(
                            HcmAuditEvidenceSecurityPolicy.DETAIL_ROUTE)
                            || binding.routeContractKey().startsWith(
                            "route.hcm.utility.explorer"),
                    "Non-configuration route entered the SYS handoff");
            for (Profile profile : binding.profiles()) {
                JsonNode access = profile.requiredAccess();
                String type = access.path("type").asText();
                if ("CAPABILITY".equals(type)) {
                    require(capabilities.containsKey(
                                    access.path("capabilityContractKey").asText()),
                            "Unknown HCM Platform capability");
                } else if ("CAPABILITY_EXPRESSION".equals(type)) {
                    require("ANY".equals(access.path("mode").asText())
                                    && access.path("capabilityContractKeys").isArray()
                                    && !access.path("capabilityContractKeys").isEmpty(),
                            "Invalid HCM Platform capability expression");
                    access.path("capabilityContractKeys").forEach(value -> require(
                            capabilities.containsKey(value.asText()),
                            "Unknown HCM Platform expression capability"));
                } else {
                    throw new IllegalStateException(
                            "Policy access is forbidden in the SYS exact handoff");
                }
            }
        }
        Set<String> routeKeys = bindings.stream()
                .map(Binding::routeContractKey).collect(Collectors.toUnmodifiableSet());
        require(routeKeys.size() == 27
                        && routeKeys.contains("route.hcm.configuration.settings-reference.page")
                        && routeKeys.contains(
                        "route.hcm.configuration.configuration-version-publish.action")
                        && routeKeys.contains(
                        "route.hcm.configuration.navigation-policy-revision-publish.action")
                        && routeKeys.contains(
                        "route.hcm.operations.navigation-projection.data")
                        && routeKeys.contains(HcmAuditEvidenceSecurityPolicy.PAGE_ROUTE)
                        && routeKeys.contains(HcmAuditEvidenceSecurityPolicy.DETAIL_ROUTE)
                        && routeKeys.contains("route.hcm.utility.explorer.page")
                        && routeKeys.contains(
                        "route.hcm.utility.explorer-preferences-update.action")
                        && routeKeys.contains(
                        "route.hcm.utility.explorer-recent-create.action")
                        && routeKeys.contains(
                        "route.hcm.utility.explorer-command-receipt.data"),
                "HCM Platform SYS route closure changed");
        validateAuditClosure(projection);
    }

    private void validateAuditClosure(ObjectNode projection) {
        JsonNode auditCapability = capabilities.get("hcm.audit.evidence.view");
        require(auditCapability != null
                        && "hcm".equals(auditCapability.path("productKey").asText())
                        && "hcm.management".equals(
                        auditCapability.path("surfaceKey").asText())
                        && "ADMIN.AUDIT_VIEW:VIEW".equals(
                        auditCapability.path("resolvedCapabilityCode").asText())
                        && "PERMISSION_AND_RELATIONSHIP".equals(
                        auditCapability.path("authorityMode").asText())
                        && auditCapability.path("requiresProductEntitlement").asBoolean(false)
                        && "REQUIRED".equals(
                        auditCapability.path("responsibilityRequirement").asText())
                        && "HRIS_DUTY_AUDITOR".equals(
                        auditCapability.path("requiredResponsibilityCode").asText())
                        && "APP_RESOURCE_SET:RS_HCM_CONFIG".equals(
                        auditCapability.path("scopeResolver").asText())
                        && "SOD_HRIS_AUDIT_INDEPENDENCE".equals(
                        auditCapability.path("sodPolicyId").asText())
                        && "STEPUP-MGMT-HIGH-V1".equals(
                        auditCapability.path("activationPolicy").asText())
                        && "HIGH".equals(auditCapability.path("riskTier").asText()),
                "HCM audit capability authority drifted");
        List<JsonNode> auditRoutes = new ArrayList<>();
        requiredArray(projection, "routes").forEach(route -> {
            String key = route.path("routeContractKey").asText();
            if (HcmAuditEvidenceSecurityPolicy.PAGE_ROUTE.equals(key)
                    || HcmAuditEvidenceSecurityPolicy.DETAIL_ROUTE.equals(key)) {
                auditRoutes.add(route);
            }
        });
        require(auditRoutes.size() == 2, "Exact HCM audit route pair is required");
        for (JsonNode route : auditRoutes) {
            String key = route.path("routeContractKey").asText();
            boolean page = HcmAuditEvidenceSecurityPolicy.PAGE_ROUTE.equals(key);
            require(route.path("sideEffectFree").asBoolean(false)
                            && (page
                            ? "PAGE".equals(route.path("routeKind").asText())
                            : "DATA".equals(route.path("routeKind").asText()))
                            && "hcm.management".equals(
                            route.path("navigationContextId").asText())
                            && (!page || ("hcm.audit.hris".equals(
                            route.path("uiRouteId").asText())
                            && "/admin/governance/audit-overview".equals(
                            route.path("uiRoutePattern").asText())))
                            && !route.path("stepUpCommandBindings").isArray(),
                    key + ": invalid read-only audit route envelope");
            ArrayNode profiles = requiredArray(route, "accessProfiles");
            require(profiles.size() == 1, key + ": one closed audit profile required");
            JsonNode profile = profiles.get(0);
            require("hcm-audit-evidence-reader".equals(
                            profile.path("profileKey").asText())
                            && profile.path("readOnly").asBoolean(false)
                            && profile.path("activeAccessModes").size() == 1
                            && "ELEVATED".equals(
                            profile.path("activeAccessModes").get(0).asText())
                            && "CAPABILITY".equals(
                            profile.path("requiredAccess").path("type").asText())
                            && "hcm.audit.evidence.view".equals(
                            profile.path("requiredAccess")
                                    .path("capabilityContractKey").asText())
                            && profile.path("targetBindingKinds").size() == 1
                            && "CONFIG_SCOPE".equals(
                            profile.path("targetBindingKinds").get(0).asText())
                            && profile.path("predicatePolicyKeys").size() == 1
                            && "predicate.hcm-audit-evidence-authority.v1".equals(
                            profile.path("predicatePolicyKeys").get(0).asText())
                            && profile.path("responseProjectionBindings").size() == 1
                            && (page ? "HcmAuditEvidencePageV1" : "HcmAuditEvidenceV1")
                            .equals(profile.path("responseProjectionBindings").get(0)
                                    .path("responseSchemaKey").asText()),
                    key + ": HCM audit profile drifted");
            ArrayNode services = requiredArray(route, "servicePepBindings");
            require(services.size() == 1
                            && "GET".equals(services.get(0).path("method").asText())
                            && "platform".equals(services.get(0).path("serviceKey").asText())
                            && (page
                            ? "/v1/admin/audit-control/events".equals(
                            services.get(0).path("path").asText())
                            : "/v1/admin/audit-control/events/{eventId}".equals(
                            services.get(0).path("path").asText()))
                            && services.get(0).path("queryParameterConstraints").size() == 1
                            && "FIXED".equals(services.get(0)
                            .path("queryParameterConstraints").path("product")
                            .path("kind").asText())
                            && "HCM".equals(services.get(0)
                            .path("queryParameterConstraints").path("product")
                            .path("value").asText()),
                    key + ": fixed product=HCM service binding is required");
        }
    }

    private ObjectNode readProjection() {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Generated HCM Platform PEP is absent.");
            }
            JsonNode value = objectMapper.readTree(input);
            if (!(value instanceof ObjectNode object)) {
                throw new IllegalStateException("Generated HCM Platform PEP must be an object.");
            }
            return object;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Generated HCM Platform PEP cannot be read.", exception);
        }
    }

    private Map<String, JsonNode> index(
            JsonNode values, String keyField, String label) {
        require(values.isArray(), label + " must be an array");
        Map<String, JsonNode> result = new LinkedHashMap<>();
        values.forEach(value -> {
            String key = value.path(keyField).asText();
            require(!key.isBlank() && result.putIfAbsent(key, value) == null,
                    label + ": duplicate or empty key");
        });
        return Map.copyOf(result);
    }

    private String decodeForClaim(String path) {
        String current = path;
        for (int pass = 0; pass < 2; pass++) {
            StringBuilder decoded = new StringBuilder(current.length());
            boolean changed = false;
            for (int index = 0; index < current.length(); index++) {
                if (current.charAt(index) == '%' && index + 2 < current.length()) {
                    int high = Character.digit(current.charAt(index + 1), 16);
                    int low = Character.digit(current.charAt(index + 2), 16);
                    int value = high < 0 || low < 0 ? -1 : (high << 4) + low;
                    if (value >= 0x20 && value <= 0x7e) {
                        decoded.append((char) value);
                        index += 2;
                        changed = true;
                        continue;
                    }
                }
                decoded.append(current.charAt(index));
            }
            if (!changed) return current;
            current = decoded.toString();
        }
        return current;
    }

    private String sha256(JsonNode value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(canonical(value))));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("HCM Platform checksum failed.", exception);
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name ->
                    result.set(name, canonical(value.get(name))));
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

    public record RequestEvidence(
            String method,
            String path,
            Set<String> permissions,
            Set<String> roles,
            Set<String> resourceRoles,
            String currentDecisionRevision,
            String contextKey,
            String contextScopeKey,
            ExplorerOwnerEvidence explorerOwnerEvidence,
            String trustedRouteContractKey) {
        public RequestEvidence {
            method = method == null ? "" : method.toUpperCase(Locale.ROOT);
            path = path == null ? "" : path;
            permissions = permissions == null ? Set.of() : permissions.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.trim().toUpperCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
            roles = normalized(roles);
            resourceRoles = normalized(resourceRoles);
            currentDecisionRevision = normalizedText(currentDecisionRevision);
            contextKey = normalizedText(contextKey);
            contextScopeKey = normalizedText(contextScopeKey);
            trustedRouteContractKey = normalizedText(trustedRouteContractKey);
        }

        public RequestEvidence(
                String method,
                String path,
                Set<String> permissions,
                String trustedRouteContractKey) {
            this(method, path, permissions, Set.of(), Set.of(),
                    null, null, null, null, trustedRouteContractKey);
        }

        private static Set<String> normalized(Set<String> values) {
            return values == null ? Set.of() : values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.trim().toUpperCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
        }

        private static String normalizedText(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    /** Trusted, typed evidence populated by the future Explorer owner PEP. */
    public record ExplorerOwnerEvidence(
            String currentDecisionRevision,
            String contextKey,
            String contextScopeKey,
            String purposeCode,
            String subjectPrincipalPublicId,
            long fieldPolicyRevision,
            long authorizationRevision,
            Set<String> allowedDescendantPageRouteKeys,
            ExplorerReceiptEvidence receiptEvidence) {
        public ExplorerOwnerEvidence {
            currentDecisionRevision = clean(currentDecisionRevision);
            contextKey = clean(contextKey);
            contextScopeKey = clean(contextScopeKey);
            purposeCode = clean(purposeCode);
            subjectPrincipalPublicId = clean(subjectPrincipalPublicId);
            allowedDescendantPageRouteKeys = allowedDescendantPageRouteKeys == null
                    ? Set.of()
                    : allowedDescendantPageRouteKeys.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .collect(Collectors.toUnmodifiableSet());
        }

        private static String clean(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    /** Resource-bound fields loaded from the stored receipt by the owner PEP. */
    public record ExplorerReceiptEvidence(
            String subjectPrincipalPublicId,
            String originatingAction,
            String contextKey,
            String contextScopeKey) {
        public ExplorerReceiptEvidence {
            subjectPrincipalPublicId = ExplorerOwnerEvidence.clean(
                    subjectPrincipalPublicId);
            originatingAction = ExplorerOwnerEvidence.clean(originatingAction);
            contextKey = ExplorerOwnerEvidence.clean(contextKey);
            contextScopeKey = ExplorerOwnerEvidence.clean(contextScopeKey);
        }
    }

    public record Decision(
            boolean allowed,
            String denialCode,
            RouteAuthority authority) {
        static Decision allowed(RouteAuthority authority) {
            return new Decision(true, null, authority);
        }

        static Decision denied(String code) {
            return new Decision(false, code, null);
        }
    }

    public record RouteAuthority(
            String routeContractKey,
            String routeKind,
            String method,
            String servicePath,
            String profileKey,
            boolean highRisk,
            String capabilityContractKey,
            String activationPolicy,
            StepUpCommandBinding stepUpCommandBinding) {
    }

    public record StepUpCommandBinding(
            String targetType,
            String targetIdPathParameter,
            String expectedObjectVersionSource,
            String expectedObjectVersionName,
            String ownerServiceKey,
            String audience) {
    }

    public record BindingContract(
            String routeContractKey,
            String routeKind,
            String method,
            String servicePath,
            boolean highRisk) {
    }

    private record Binding(
            String routeContractKey,
            String routeKind,
            String method,
            PathTemplate path,
            List<Profile> profiles,
            boolean highRisk,
            StepUpCommandBinding stepUpCommandBinding) {
    }

    private record Profile(
            String profileKey,
            int precedence,
            JsonNode requiredAccess) {
        boolean allows(Set<String> permissions, Map<String, JsonNode> capabilities) {
            String type = requiredAccess.path("type").asText();
            if ("CAPABILITY".equals(type)) {
                return hasCapability(requiredAccess.path(
                        "capabilityContractKey").asText(), permissions, capabilities);
            }
            if (!"CAPABILITY_EXPRESSION".equals(type)) return false;
            List<String> keys = new ArrayList<>();
            requiredAccess.path("capabilityContractKeys").forEach(value ->
                    keys.add(value.asText()));
            boolean all = "ALL".equals(requiredAccess.path("mode").asText());
            return all
                    ? keys.stream().allMatch(key ->
                    hasCapability(key, permissions, capabilities))
                    : keys.stream().anyMatch(key ->
                    hasCapability(key, permissions, capabilities));
        }

        private boolean hasCapability(
                String key,
                Set<String> permissions,
                Map<String, JsonNode> capabilities) {
            JsonNode capability = capabilities.get(key);
            return capability != null && permissions.contains(
                    capability.path("resolvedCapabilityCode").asText()
                            .toUpperCase(Locale.ROOT));
        }
    }

    private record PathTemplate(Pattern pattern, String template) {
        static PathTemplate compile(String template) {
            StringBuilder expression = new StringBuilder("^");
            Matcher matcher = Pattern.compile(
                    "\\{([A-Za-z][A-Za-z0-9]*)}").matcher(template);
            int offset = 0;
            while (matcher.find()) {
                expression.append(Pattern.quote(
                                template.substring(offset, matcher.start())))
                        .append("([^/;]+)");
                offset = matcher.end();
            }
            expression.append(Pattern.quote(template.substring(offset))).append('$');
            return new PathTemplate(Pattern.compile(expression.toString()), template);
        }

        boolean matches(String value) {
            String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
            return !normalized.contains("%")
                    && !normalized.contains("\\")
                    && !normalized.contains("//")
                    && !normalized.contains("/./")
                    && !normalized.contains("/../")
                    && !normalized.contains(";")
                    && pattern.matcher(value).matches();
        }
    }
}
