package com.dwp.services.platform.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.dwp.services.platform.security.HcmPlatformPepPaths.*;
import static com.dwp.services.platform.security.HcmPlatformPepProjection.*;

/** Closed Platform-owned HCM PEP projection for the append-only R7 v49 carrier. */
@Component
public final class HcmPlatformPepRegistry {

    static final String RESOURCE = HcmPlatformPepProjection.RESOURCE;
    static final String REGISTRY_V49_CHECKSUM = HcmPlatformPepProjection.REGISTRY_V49_CHECKSUM;
    static final String PROJECTION_V49_CHECKSUM = HcmPlatformPepProjection.PROJECTION_V49_CHECKSUM;
    public static final String EXPLORER_OWNER_EVIDENCE_ATTRIBUTE =
            HcmPlatformPepRegistry.class.getName() + ".explorerOwnerEvidence";

    private final ObjectMapper objectMapper;
    private final Map<String, JsonNode> capabilities;
    private final List<Binding> bindings;

    public HcmPlatformPepRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        HcmPlatformPepProjection document = new HcmPlatformPepProjection(
                objectMapper, ExpectedNavigationV2Binding::new);
        ObjectNode projection = document.readProjection();
        document.validateEnvelope(projection);
        capabilities = document.index(requiredArray(projection, "capabilities"),
                "contractKey", "capabilities");
        bindings = compile(projection);
        document.validateClosure(projection, capabilities, bindings);
    }

    /** Claims only the canonical HRIS configuration families, never generic /v1/admin. */
    public boolean governsPathFamily(String path) {
        if (path == null) return false;
        String decoded = decodeForClaim(path);
        return claimed(path) || claimed(decoded)
                || claimed(normalizeForClaimOnly(decoded));
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
                .filter(value -> !binding.routeContractKey().startsWith(
                        CONTROLLED_TRANSFER_ROUTE_PREFIX)
                        || allowsControlledTransferProfile(binding, value, evidence))
                .filter(value -> !binding.routeContractKey().startsWith(
                        NAVIGATION_SHELL_ROUTE_PREFIX)
                        || allowsNavigationShellProfile(binding, value, evidence))
                .filter(value -> !isOperationsRoute(binding.routeContractKey())
                        || allowsOperationsProfile(binding, value, evidence))
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

    private boolean allowsControlledTransferProfile(
            Binding binding, Profile profile, RequestEvidence evidence) {
        if (!CONTROLLED_TRANSFER_PURPOSE.equals(evidence.purposeCode())
                || !evidence.permissions().contains("APP.HCM:VIEW")
                || evidence.contextKey() == null
                || evidence.contextScopeKey() == null) {
            return false;
        }
        boolean create = "POST".equals(binding.method());
        String requiredDuty = create
                ? CONTROLLED_TRANSFER_CREATOR_DUTY
                : CONTROLLED_TRANSFER_READER_DUTY;
        boolean scopedDuty = evidence.resourceRoles().stream().anyMatch(value ->
                value.startsWith(requiredDuty + "@")
                        && value.length() > requiredDuty.length() + 1);
        if (!scopedDuty) return false;
        if (create) {
            return "controlled-transfer-creator".equals(profile.profileKey())
                    && evidence.roles().contains("HRIS_INTEGRATION_EXECUTOR");
        }
        return "controlled-transfer-reader".equals(profile.profileKey())
                && evidence.roles().stream().anyMatch(
                CONTROLLED_TRANSFER_READER_PACKAGES::contains);
    }

    private boolean allowsNavigationShellProfile(
            Binding binding, Profile profile, RequestEvidence evidence) {
        if (evidence.currentDecisionRevision() == null
                || evidence.contextKey() == null
                || evidence.contextScopeKey() == null) {
            return false;
        }
        JsonNode access = profile.requiredAccess();
        if (NAVIGATION_LEGAL_ENTITY_ROUTE_KEYS.contains(binding.routeContractKey())) {
            return "hr-operations-reader".equals(profile.profileKey())
                    && evidence.permissions().contains("APP.HCM:VIEW")
                    && "TARGET_POPULATION".equals(evidence.populationKind())
                    && "CAPABILITY_EXPRESSION".equals(access.path("type").asText())
                    && "ANY".equals(access.path("mode").asText())
                    && textSet(access.path("capabilityContractKeys"))
                    .equals(NAVIGATION_OPERATIONS_CAPABILITIES);
        }
        return "navigation-shell-reader".equals(profile.profileKey())
                && evidence.permissions().contains("APP.HCM:VIEW")
                && evidence.permissions().contains("DATA.HRIS_APP_SHELL:VIEW")
                && evidence.resourceRoles().contains(EXPLORER_DUTY)
                && "SELF".equals(evidence.populationKind())
                && "CAPABILITY".equals(access.path("type").asText())
                && "hcm.app.shell.view".equals(
                access.path("capabilityContractKey").asText());
    }

    private boolean allowsOperationsProfile(
            Binding binding, Profile profile, RequestEvidence evidence) {
        if (evidence.currentDecisionRevision() == null
                || evidence.contextKey() == null
                || evidence.contextScopeKey() == null) {
            return false;
        }
        if (SETTINGS_OPERATIONS_ROUTE.equals(binding.routeContractKey())) {
            return "configuration-reader".equals(profile.profileKey())
                    && SETTINGS_OPERATIONS_PURPOSE.equals(evidence.purposeCode())
                    && OPERATIONS_SCOPE_KEY.matcher(evidence.contextScopeKey())
                    .matches();
        }
        if (!"operation-exception-reader".equals(profile.profileKey())
                || !OPERATION_EXCEPTION_PURPOSE.equals(evidence.purposeCode())
                || !"TARGET_POPULATION".equals(evidence.populationKind())
                || !evidence.permissions().contains("APP.HCM:VIEW")
                || !OPERATIONS_SCOPE_KEY.matcher(evidence.contextScopeKey())
                .matches()
                || evidence.roles().stream().noneMatch(
                OPERATION_EXCEPTION_PACKAGES::contains)
                || evidence.resourceRoles().stream().noneMatch(
                value -> OPERATION_EXCEPTION_RESOURCE_ROLE.matcher(value).matches())) {
            return false;
        }
        if (OPERATION_EXCEPTION_DETAIL_ROUTE.equals(binding.routeContractKey())) {
            String prefix = OPERATION_EXCEPTION_ROOT + "/";
            if (!evidence.path().startsWith(prefix)) return false;
            String identifier = evidence.path().substring(prefix.length());
            try {
                return java.util.UUID.fromString(identifier).toString().equals(identifier);
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }
        return OPERATION_EXCEPTION_ROUTE.equals(binding.routeContractKey())
                && OPERATION_EXCEPTION_ROOT.equals(evidence.path());
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
        require(result.size() == 46, "HCM Platform SYS binding count changed");
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
        List<String> targetIdBodyFields = new ArrayList<>();
        value.path("targetIdBodyFields").forEach(field ->
                targetIdBodyFields.add(field.asText()));
        return new StepUpCommandBinding(
                value.path("targetType").asText(),
                value.path("targetIdPathParameter").asText(null),
                List.copyOf(targetIdBodyFields),
                value.path("expectedObjectVersionSource").asText(),
                value.path("expectedObjectVersionName").asText(),
                value.path("ownerServiceKey").asText(),
                value.path("audience").asText());
    }

    private record ExpectedNavigationV2Binding(
            String method,
            String path,
            String routeKind,
            String profileKey,
            String capabilityKey,
            boolean stepUp) implements HcmPlatformPepProjection.NavigationBindingView { }

    public record RequestEvidence(
            String method,
            String path,
            Set<String> permissions,
            Set<String> roles,
            Set<String> resourceRoles,
            String currentDecisionRevision,
            String contextKey,
            String contextScopeKey,
            String populationKind,
            String purposeCode,
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
            resourceRoles = exactValues(resourceRoles);
            currentDecisionRevision = normalizedText(currentDecisionRevision);
            contextKey = normalizedText(contextKey);
            contextScopeKey = normalizedText(contextScopeKey);
            populationKind = normalizedText(populationKind);
            purposeCode = normalizedText(purposeCode);
            trustedRouteContractKey = normalizedText(trustedRouteContractKey);
        }

        public RequestEvidence(
                String method,
                String path,
                Set<String> permissions,
                Set<String> roles,
                Set<String> resourceRoles,
                String currentDecisionRevision,
                String contextKey,
                String contextScopeKey,
                String purposeCode,
                ExplorerOwnerEvidence explorerOwnerEvidence,
                String trustedRouteContractKey) {
            this(method, path, permissions, roles, resourceRoles,
                    currentDecisionRevision, contextKey, contextScopeKey, null, purposeCode,
                    explorerOwnerEvidence, trustedRouteContractKey);
        }

        public RequestEvidence(
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
            this(method, path, permissions, roles, resourceRoles,
                    currentDecisionRevision, contextKey, contextScopeKey, null, null,
                    explorerOwnerEvidence, trustedRouteContractKey);
        }

        public RequestEvidence(
                String method,
                String path,
                Set<String> permissions,
                String trustedRouteContractKey) {
            this(method, path, permissions, Set.of(), Set.of(),
                    null, null, null, null, null, null, trustedRouteContractKey);
        }

        private static Set<String> normalized(Set<String> values) {
            return values == null ? Set.of() : values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(value -> value.trim().toUpperCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
        }

        private static Set<String> exactValues(Set<String> values) {
            return values == null ? Set.of() : values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
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
            List<String> targetIdBodyFields,
            String expectedObjectVersionSource,
            String expectedObjectVersionName,
            String ownerServiceKey,
            String audience) {
        public StepUpCommandBinding(
                String targetType,
                String targetIdPathParameter,
                String expectedObjectVersionSource,
                String expectedObjectVersionName,
                String ownerServiceKey,
                String audience) {
            this(targetType, targetIdPathParameter, List.of(),
                    expectedObjectVersionSource, expectedObjectVersionName,
                    ownerServiceKey, audience);
        }
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
            StepUpCommandBinding stepUpCommandBinding) implements HcmPlatformPepProjection.BindingView {
    }

    private record Profile(
            String profileKey,
            int precedence,
            JsonNode requiredAccess) implements HcmPlatformPepProjection.ProfileView {
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
