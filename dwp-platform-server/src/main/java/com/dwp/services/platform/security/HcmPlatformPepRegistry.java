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

/** Closed Platform-owned HCM PEP projection for the append-only R6 navigation v47 carrier. */
@Component
public final class HcmPlatformPepRegistry {

    static final String RESOURCE =
            "product-authorization/hcm-platform-pep-v47.generated.json";
    static final String REGISTRY_V47_CHECKSUM =
            "925e8bfc406bc7e4ee62aee08582fdd7c67c629ffde470051704eaec5404800e";
    static final String PROJECTION_V47_CHECKSUM =
            "4afb76601a7c0e3dbf7647b075ad17433ca70107f825071584c4f3ba1bb1caae";
    private static final String CONFIGURATION_ROOT = "/v1/admin/hris/configurations";
    private static final String WORKBENCH_PATH = "/v1/hris/workbench/settings-reference";
    private static final String NAVIGATION_POLICY_ROOT =
            "/v1/admin/hris/navigation-policies";
    private static final String NAVIGATION_PROJECTION_PATH =
            "/v1/hris/navigation/projection";
    private static final String NAVIGATION_V2_POLICY_ROOT =
            "/v2/admin/hris/navigation-policies";
    private static final String NAVIGATION_V2_PROJECTION_PATH =
            "/v2/hris/navigation/projection";
    private static final String NAVIGATION_V2_DEEP_LINK_PATH =
            "/v2/hris/navigation/deep-link-decision";
    private static final String NAVIGATION_V3_PROJECTION_PATH =
            "/v3/hris/navigation/projection";
    private static final String NAVIGATION_V3_DEEP_LINK_PATH =
            "/v3/hris/navigation/deep-link-decision";
    private static final String HCM_AUDIT_EVENTS_ROOT =
            "/v1/admin/audit-control/events";
    private static final String EXPLORER_PATH = "/v1/hris/explorer";
    private static final String EXPLORER_PREFERENCES_PATH =
            "/v1/hris/explorer/preferences";
    private static final String EXPLORER_RECENTS_PATH =
            "/v1/hris/explorer/recents";
    private static final String HRIS_COMMAND_RECEIPT_ROOT =
            "/v1/admin/hris/command-receipts";
    private static final String CONTROLLED_TRANSFER_PATH =
            "/v1/admin/hris/file-transfers";
    private static final String CONTROLLED_TRANSFER_ROUTE_PREFIX =
            "route.hcm.management.controlled-transfer";
    private static final String NAVIGATION_SHELL_ROUTE_PREFIX =
            "route.hcm.shell.navigation-";
    private static final Set<String> NAVIGATION_LEGAL_ENTITY_ROUTE_KEYS = Set.of(
            "route.hcm.shell.navigation-aggregate-legal-entity.data",
            "route.hcm.shell.navigation-deep-link-decision-legal-entity.data");
    private static final Set<String> NAVIGATION_OPERATIONS_CAPABILITIES = Set.of(
            "hcm.operations.workforce.read",
            "hcm.operations.time.read",
            "hcm.operations.absence.read",
            "hcm.operations.benefits.read",
            "hcm.operations.pay.read",
            "hcm.operations.talent.read",
            "hcm.compensation.plan.view");
    private static final String CONTROLLED_TRANSFER_PURPOSE =
            "HRIS_INTEGRATION_ADMIN";
    private static final String CONTROLLED_TRANSFER_READER_DUTY =
            "HRIS_DUTY_CONTROLLED_TRANSFER_READER";
    private static final String CONTROLLED_TRANSFER_CREATOR_DUTY =
            "HRIS_DUTY_CONTROLLED_TRANSFER_CREATOR";
    private static final Set<String> CONTROLLED_TRANSFER_READER_PACKAGES = Set.of(
            "HRIS_INTEGRATION_AUTHOR", "HRIS_INTEGRATION_EXECUTOR");
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
                || path.equals(NAVIGATION_V2_PROJECTION_PATH)
                || path.startsWith(NAVIGATION_V2_PROJECTION_PATH + ";")
                || path.startsWith(NAVIGATION_V2_PROJECTION_PATH + "%")
                || path.equals(NAVIGATION_V2_DEEP_LINK_PATH)
                || path.startsWith(NAVIGATION_V2_DEEP_LINK_PATH + ";")
                || path.startsWith(NAVIGATION_V2_DEEP_LINK_PATH + "%")
                || path.equals(NAVIGATION_V3_PROJECTION_PATH)
                || path.startsWith(NAVIGATION_V3_PROJECTION_PATH + ";")
                || path.startsWith(NAVIGATION_V3_PROJECTION_PATH + "%")
                || path.equals(NAVIGATION_V3_DEEP_LINK_PATH)
                || path.startsWith(NAVIGATION_V3_DEEP_LINK_PATH + ";")
                || path.startsWith(NAVIGATION_V3_DEEP_LINK_PATH + "%")
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
                || path.equals(CONTROLLED_TRANSFER_PATH)
                || path.startsWith(CONTROLLED_TRANSFER_PATH + "/")
                || path.startsWith(CONTROLLED_TRANSFER_PATH + ";")
                || path.startsWith(CONTROLLED_TRANSFER_PATH + "%")
                || path.equals(CONFIGURATION_ROOT)
                || path.startsWith(CONFIGURATION_ROOT + "/")
                || path.startsWith(CONFIGURATION_ROOT + ";")
                || path.startsWith(CONFIGURATION_ROOT + "%")
                || path.equals(NAVIGATION_POLICY_ROOT)
                || path.startsWith(NAVIGATION_POLICY_ROOT + "/")
                || path.startsWith(NAVIGATION_POLICY_ROOT + ";")
                || path.startsWith(NAVIGATION_POLICY_ROOT + "%")
                || path.equals(NAVIGATION_V2_POLICY_ROOT)
                || path.startsWith(NAVIGATION_V2_POLICY_ROOT + "/")
                || path.startsWith(NAVIGATION_V2_POLICY_ROOT + ";")
                || path.startsWith(NAVIGATION_V2_POLICY_ROOT + "%");
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
        require(result.size() == 43, "HCM Platform SYS binding count changed");
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

    private void validateEnvelope(ObjectNode projection) {
        JsonNode registry = projection.path("registryRef");
        require(projection.path("schemaVersion").asInt() == 1
                        && "hcm-platform-pep-v47".equals(
                        projection.path("projectionKey").asText())
                        && "platform".equals(projection.path("ownerServiceKey").asText())
                        && "product-surfaces".equals(registry.path("bundleKey").asText())
                        && registry.path("version").asInt() == 47
                        && REGISTRY_V47_CHECKSUM.equals(registry.path("sha256").asText()),
                "Unexpected HCM Platform PEP envelope");
        require(projection.path("sourceRegistryRouteCount").asInt() == 1053
                        && projection.path("projectedRouteContractCount").asInt() == 50
                        && projection.path("bindingPairCount").asInt() == 51
                        && requiredArray(projection, "capabilities").size() == 20
                        && requiredArray(projection, "accessPolicies").size() == 4
                        && requiredArray(projection, "entitlementExpressions").size() == 3
                        && requiredArray(projection, "predicatePolicies").size() == 9,
                "HCM Platform projection closure count changed");
        ObjectNode payload = projection.deepCopy();
        JsonNode checksum = payload.remove("projectionChecksum");
        require(checksum != null
                        && PROJECTION_V47_CHECKSUM.equals(checksum.asText())
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
                            CONTROLLED_TRANSFER_ROUTE_PREFIX)
                            || binding.routeContractKey().startsWith(
                            NAVIGATION_SHELL_ROUTE_PREFIX)
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
        require(routeKeys.size() == 43
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
                        "route.hcm.utility.explorer-command-receipt.data")
                        && routeKeys.contains(
                        "route.hcm.management.controlled-transfer.data")
                        && routeKeys.contains(
                        "route.hcm.management.controlled-transfer-create.action")
                        && routeKeys.contains(
                        "route.hcm.shell.navigation-aggregate.data")
                        && routeKeys.contains(
                        "route.hcm.shell.navigation-deep-link-decision.data")
                        && routeKeys.contains(
                                "route.hcm.shell.navigation-aggregate-legal-entity.data")
                        && routeKeys.contains(
                                "route.hcm.shell.navigation-deep-link-decision-legal-entity.data")
                        && routeKeys.contains(
                                "route.hcm.shell.navigation-aggregate-v3.data")
                        && routeKeys.contains(
                                "route.hcm.shell.navigation-deep-link-decision-v3.data")
                        && routeKeys.contains(
                        "route.hcm.configuration.navigation-policy-v2-workspace.data")
                        && routeKeys.contains(
                        "route.hcm.configuration.navigation-policy-v2-revision-publish.action"),
                "HCM Platform SYS route closure changed");
        validateAuditClosure(projection);
        validateControlledTransferClosure(projection);
        validateNavigationClosure(projection);
    }

    private void validateControlledTransferClosure(ObjectNode projection) {
        JsonNode reader = capabilities.get("hcm.integration.transfer.view");
        JsonNode creator = capabilities.get("hcm.integration.transfer.create");
        require(reader != null
                        && "DATA.HRIS_CONTROLLED_TRANSFER:VIEW".equals(
                        reader.path("resolvedCapabilityCode").asText())
                        && "HRIS_DUTY_CONTROLLED_TRANSFER_READER".equals(
                        reader.path("requiredResponsibilityCode").asText())
                        && "CONNECTOR_OR_LEGAL_ENTITY_SCOPE".equals(
                        reader.path("scopeResolver").asText())
                        && "HIGH".equals(reader.path("riskTier").asText())
                        && creator != null
                        && "ACTION.HRIS_CONTROLLED_TRANSFER:CREATE".equals(
                        creator.path("resolvedCapabilityCode").asText())
                        && "HRIS_DUTY_CONTROLLED_TRANSFER_CREATOR".equals(
                        creator.path("requiredResponsibilityCode").asText())
                        && "CRITICAL".equals(creator.path("riskTier").asText())
                        && "STEPUP-MGMT-HIGH-V1".equals(
                        creator.path("activationPolicy").asText())
                        && "NO_EXPORT_OWN_APPROVAL".equals(
                        creator.path("sodPolicyId").asText()),
                "Controlled-transfer capability authority drifted");
        List<JsonNode> routes = new ArrayList<>();
        requiredArray(projection, "routes").forEach(route -> {
            if (route.path("routeContractKey").asText()
                    .startsWith(CONTROLLED_TRANSFER_ROUTE_PREFIX)) {
                routes.add(route);
            }
        });
        require(routes.size() == 2, "Exact controlled-transfer route pair is required");
        for (JsonNode route : routes) {
            boolean create = route.path("routeContractKey").asText()
                    .endsWith("-create.action");
            JsonNode profile = requiredArray(route, "accessProfiles").get(0);
            JsonNode service = requiredArray(route, "servicePepBindings").get(0);
            require(requiredArray(route, "accessProfiles").size() == 1
                            && requiredArray(route, "servicePepBindings").size() == 1
                            && CONTROLLED_TRANSFER_PATH.equals(service.path("path").asText())
                            && "platform".equals(service.path("serviceKey").asText())
                            && (create ? "POST" : "GET").equals(
                            service.path("method").asText())
                            && (create ? "ACTION" : "DATA").equals(
                            route.path("routeKind").asText())
                            && (create ? "controlled-transfer-creator"
                            : "controlled-transfer-reader").equals(
                            profile.path("profileKey").asText())
                            && (create ? "hcm.integration.transfer.create"
                            : "hcm.integration.transfer.view").equals(
                            profile.path("requiredAccess")
                                    .path("capabilityContractKey").asText()),
                    route.path("routeContractKey").asText()
                            + ": invalid controlled-transfer PEP closure");
        }
    }

    private void validateNavigationClosure(ObjectNode projection) {
        Map<String, ExpectedNavigationV2Binding> expected = Map.ofEntries(
                Map.entry("route.hcm.shell.navigation-aggregate.data",
                        new ExpectedNavigationV2Binding(
                                "GET", NAVIGATION_V2_PROJECTION_PATH, "DATA",
                                "navigation-shell-reader", "hcm.app.shell.view", false)),
                Map.entry("route.hcm.shell.navigation-deep-link-decision.data",
                        new ExpectedNavigationV2Binding(
                                "GET", NAVIGATION_V2_DEEP_LINK_PATH, "DATA",
                                "navigation-shell-reader", "hcm.app.shell.view", false)),
                Map.entry("route.hcm.shell.navigation-aggregate-legal-entity.data",
                        new ExpectedNavigationV2Binding(
                                "GET", NAVIGATION_V2_PROJECTION_PATH, "DATA",
                                "hr-operations-reader", null, false)),
                Map.entry(
                        "route.hcm.shell.navigation-deep-link-decision-legal-entity.data",
                        new ExpectedNavigationV2Binding(
                                "GET", NAVIGATION_V2_DEEP_LINK_PATH, "DATA",
                                "hr-operations-reader", null, false)),
                Map.entry("route.hcm.shell.navigation-aggregate-v3.data",
                        new ExpectedNavigationV2Binding(
                                "GET", NAVIGATION_V3_PROJECTION_PATH, "DATA",
                                "navigation-shell-reader", "hcm.app.shell.view", false)),
                Map.entry("route.hcm.shell.navigation-deep-link-decision-v3.data",
                        new ExpectedNavigationV2Binding(
                                "GET", NAVIGATION_V3_DEEP_LINK_PATH, "DATA",
                                "navigation-shell-reader", "hcm.app.shell.view", false)),
                Map.entry("route.hcm.configuration.navigation-policy-v2-workspace.data",
                        new ExpectedNavigationV2Binding(
                                "GET", NAVIGATION_V2_POLICY_ROOT + "/workspace", "DATA",
                                "configuration-reader",
                                "hcm.configuration.navigation-policy.view", false)),
                Map.entry("route.hcm.configuration.navigation-policy-v2-draft-create.action",
                        new ExpectedNavigationV2Binding(
                                "POST", NAVIGATION_V2_POLICY_ROOT + "/drafts", "ACTION",
                                "configuration-author",
                                "hcm.configuration.navigation-policy.update", false)),
                Map.entry("route.hcm.configuration.navigation-policy-v2-draft-update.action",
                        new ExpectedNavigationV2Binding(
                                "PUT", NAVIGATION_V2_POLICY_ROOT + "/drafts/{revisionId}",
                                "ACTION", "configuration-author",
                                "hcm.configuration.navigation-policy.update", false)),
                Map.entry("route.hcm.configuration.navigation-policy-v2-draft-validate.action",
                        new ExpectedNavigationV2Binding(
                                "POST", NAVIGATION_V2_POLICY_ROOT
                                + "/drafts/{revisionId}/validate", "ACTION",
                                "configuration-author",
                                "hcm.configuration.navigation-policy.update", false)),
                Map.entry("route.hcm.configuration.navigation-policy-v2-draft-submit.action",
                        new ExpectedNavigationV2Binding(
                                "POST", NAVIGATION_V2_POLICY_ROOT
                                + "/drafts/{revisionId}/submit", "ACTION",
                                "configuration-author",
                                "hcm.configuration.navigation-policy.update", false)),
                Map.entry("route.hcm.configuration.navigation-policy-v2-revision-approve.action",
                        new ExpectedNavigationV2Binding(
                                "POST", NAVIGATION_V2_POLICY_ROOT
                                + "/revisions/{revisionId}/approve", "ACTION",
                                "configuration-publisher",
                                "hcm.configuration.navigation-policy.publish", true)),
                Map.entry("route.hcm.configuration.navigation-policy-v2-revision-publish.action",
                        new ExpectedNavigationV2Binding(
                                "POST", NAVIGATION_V2_POLICY_ROOT
                                + "/revisions/{revisionId}/publish", "ACTION",
                                "configuration-publisher",
                                "hcm.configuration.navigation-policy.publish", true)),
                Map.entry("route.hcm.configuration.navigation-policy-v2-revision-restore.action",
                        new ExpectedNavigationV2Binding(
                                "POST", NAVIGATION_V2_POLICY_ROOT
                                + "/revisions/{revisionId}/restore", "ACTION",
                                "configuration-publisher",
                                "hcm.configuration.navigation-policy.publish", true)));

        Map<String, JsonNode> routes = new LinkedHashMap<>();
        requiredArray(projection, "routes").forEach(route -> {
            String key = route.path("routeContractKey").asText();
            if (expected.containsKey(key)) routes.put(key, route);
        });
        require(routes.keySet().equals(expected.keySet()),
                "Exact R6 navigation route set is required");
        routes.forEach((key, route) -> {
            ExpectedNavigationV2Binding sealed = expected.get(key);
            ArrayNode profiles = requiredArray(route, "accessProfiles");
            ArrayNode services = requiredArray(route, "servicePepBindings");
            require(profiles.size() == 1 && services.size() == 1,
                    key + ": one profile and one service binding are required");
            JsonNode profile = profiles.get(0);
            JsonNode service = services.get(0);
            JsonNode access = profile.path("requiredAccess");
            boolean legalEntityRuntime = NAVIGATION_LEGAL_ENTITY_ROUTE_KEYS.contains(key);
            boolean tenantRuntime = key.startsWith(NAVIGATION_SHELL_ROUTE_PREFIX)
                    && !legalEntityRuntime;
            boolean accessMatches = legalEntityRuntime
                    ? "CAPABILITY_EXPRESSION".equals(access.path("type").asText())
                    && "ANY".equals(access.path("mode").asText())
                    && textSet(access.path("capabilityContractKeys"))
                    .equals(NAVIGATION_OPERATIONS_CAPABILITIES)
                    : "CAPABILITY".equals(access.path("type").asText())
                    && sealed.capabilityKey().equals(
                    access.path("capabilityContractKey").asText());
            require(sealed.method().equals(service.path("method").asText())
                            && sealed.path().equals(service.path("path").asText())
                            && "platform".equals(service.path("serviceKey").asText())
                            && sealed.routeKind().equals(route.path("routeKind").asText())
                            && sealed.profileKey().equals(profile.path("profileKey").asText())
                            && accessMatches,
                    key + ": navigation authority binding drifted");
            if (tenantRuntime || legalEntityRuntime) {
                String expectedScope = legalEntityRuntime ? "LEGAL_ENTITY" : "TENANT";
                String expectedLegalEntityConstraint = legalEntityRuntime
                        ? "REQUIRED" : "ABSENT";
                JsonNode query = service.path("queryParameterConstraints");
                require((legalEntityRuntime ? "hcm.operations" : "hcm.utility").equals(
                                route.path("subject").path("surfaceKey").asText())
                                && (legalEntityRuntime ? "hcm.operations" : "hcm.utility")
                                .equals(route.path("navigationContextId").asText())
                                && profile.path("targetBindingKinds").size() == 1
                                && (legalEntityRuntime ? "TARGET_POPULATION" : "SELF").equals(
                                profile.path("targetBindingKinds").get(0).asText())
                                && query.size() == 2
                                && "FIXED".equals(query.path("navigationScope")
                                .path("kind").asText())
                                && expectedScope.equals(query.path("navigationScope")
                                .path("value").asText())
                                && expectedLegalEntityConstraint.equals(
                                query.path("legalEntityPublicId").path("kind").asText()),
                        key + ": navigation scope split drifted");
            }
            JsonNode stepUps = route.path("stepUpCommandBindings");
            require(sealed.stepUp()
                            ? stepUps.isArray() && stepUps.size() == 1
                            && "HRIS_NAVIGATION_POLICY_REVISION".equals(
                            stepUps.get(0).path("targetType").asText())
                            && "revisionId".equals(
                            stepUps.get(0).path("targetIdPathParameter").asText())
                            && "COMMAND_BODY".equals(
                            stepUps.get(0).path("expectedObjectVersionSource").asText())
                            && "expectedVersion".equals(
                            stepUps.get(0).path("expectedObjectVersionName").asText())
                            && "dwp-platform-server".equals(
                            stepUps.get(0).path("audience").asText())
                            : !stepUps.isArray(),
                    key + ": navigation step-up binding drifted");
            if (key.endsWith("-v3.data")) {
                ArrayNode gatewayBindings = requiredArray(route, "gatewayApiBindings");
                ArrayNode projections = requiredArray(
                        profile, "responseProjectionBindings");
                String aggregate = "route.hcm.shell.navigation-aggregate-v3.data";
                String expectedGatewayPath = aggregate.equals(key)
                        ? "/api/platform/v3/hris/navigation/projection"
                        : "/api/platform/v3/hris/navigation/deep-link-decision";
                String expectedSchema = aggregate.equals(key)
                        ? "HrisNavigationAuthorityAggregateV3"
                        : "HrisNavigationAuthorityDeepLinkDecisionV3";
                require(gatewayBindings.size() == 1 && projections.size() == 1
                                && expectedGatewayPath.equals(
                                gatewayBindings.get(0).path("path").asText())
                                && expectedSchema.equals(
                                projections.get(0).path("responseSchemaKey").asText())
                                && projections.get(0).path("projectionPolicyKey").asText()
                                .endsWith(".exact-v46-policy-alias-set.projection.v3"),
                        key + ": navigation v3 projection binding drifted");
            }
        });

        JsonNode shell = capabilities.get("hcm.app.shell.view");
        require(shell != null
                        && "DATA.HRIS_APP_SHELL:VIEW".equals(
                        shell.path("resolvedCapabilityCode").asText())
                        && "HRIS_DUTY_APP_SHELL_READER".equals(
                        shell.path("requiredResponsibilityCode").asText())
                        && "SELF".equals(shell.path("scopeResolver").asText()),
                "R6 navigation shell authority drifted");
    }

    private record ExpectedNavigationV2Binding(
            String method,
            String path,
            String routeKind,
            String profileKey,
            String capabilityKey,
            boolean stepUp) { }

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

    private static Set<String> textSet(JsonNode values) {
        if (!values.isArray()) return Set.of();
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return Set.copyOf(result);
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
            resourceRoles = normalized(resourceRoles);
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
