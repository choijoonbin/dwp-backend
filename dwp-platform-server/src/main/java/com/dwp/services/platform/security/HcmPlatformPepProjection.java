package com.dwp.services.platform.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.dwp.services.platform.security.HcmPlatformPepPaths.*;

/** Validates the sealed projection independently of the registry's published record types. */
final class HcmPlatformPepProjection {
    static final String RESOURCE =
            "product-authorization/hcm-platform-pep-v49.generated.json";
    static final String REGISTRY_V49_CHECKSUM =
            "5c1dccdc27d8b260fbf001de5695e983a7107072824a8087038340d0d84cbc60";
    static final String PROJECTION_V49_CHECKSUM =
            "de70aba5a34df3bfdf1687386fb9c183a1a250e205dc4ac73968e2dfb731dd5e";
    private final ObjectMapper objectMapper;
    private final NavigationBindingFactory navigationBindings;

    HcmPlatformPepProjection(ObjectMapper objectMapper, NavigationBindingFactory navigationBindings) {
        this.objectMapper = objectMapper;
        this.navigationBindings = navigationBindings;
    }

    void validateEnvelope(ObjectNode projection) {
        JsonNode registry = projection.path("registryRef");
        require(projection.path("schemaVersion").asInt() == 1
                        && "hcm-platform-pep-v49".equals(
                        projection.path("projectionKey").asText())
                        && "platform".equals(projection.path("ownerServiceKey").asText())
                        && "product-surfaces".equals(registry.path("bundleKey").asText())
                        && registry.path("version").asInt() == 49
                        && REGISTRY_V49_CHECKSUM.equals(registry.path("sha256").asText()),
                "Unexpected HCM Platform PEP envelope");
        require(projection.path("sourceRegistryRouteCount").asInt() == 1079
                        && projection.path("projectedRouteContractCount").asInt() == 53
                        && projection.path("bindingPairCount").asInt() == 54
                        && requiredArray(projection, "capabilities").size() == 21
                        && requiredArray(projection, "accessPolicies").size() == 4
                        && requiredArray(projection, "entitlementExpressions").size() == 3
                        && requiredArray(projection, "predicatePolicies").size() == 11,
                "HCM Platform projection closure count changed");
        ObjectNode payload = projection.deepCopy();
        JsonNode checksum = payload.remove("projectionChecksum");
        require(checksum != null
                        && PROJECTION_V49_CHECKSUM.equals(checksum.asText())
                        && checksum.asText().equals(sha256(payload)),
                "HCM Platform projection checksum mismatch");
    }

    void validateClosure(ObjectNode projection, Map<String, JsonNode> capabilities,
            List<? extends BindingView> bindings) {
        for (BindingView binding : bindings) {
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
                            "route.hcm.operations.exception")
                            || binding.routeContractKey().startsWith(
                            "route.hcm.utility.explorer"),
                    "Non-configuration route entered the SYS handoff");
            for (ProfileView profile : binding.profiles()) {
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
                .map(BindingView::routeContractKey).collect(Collectors.toUnmodifiableSet());
        require(routeKeys.size() == 46
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
                        "route.hcm.configuration.navigation-policy-v2-revision-publish.action")
                        && routeKeys.contains(SETTINGS_OPERATIONS_ROUTE)
                        && routeKeys.contains(OPERATION_EXCEPTION_ROUTE)
                        && routeKeys.contains(OPERATION_EXCEPTION_DETAIL_ROUTE),
                "HCM Platform SYS route closure changed");
        validateAuditClosure(projection, capabilities);
        validateControlledTransferClosure(projection, capabilities);
        validateNavigationClosure(projection, capabilities);
        validateOperationsClosure(projection, capabilities);
    }

    private void validateOperationsClosure(ObjectNode projection, Map<String, JsonNode> capabilities) {
        JsonNode settingsCapability = capabilities.get(
                "hcm.configuration.workbench.view");
        require(settingsCapability != null
                        && "CONFIG.HRIS_WORKBENCH:VIEW".equals(
                        settingsCapability.path("resolvedCapabilityCode").asText())
                        && !settingsCapability.path(
                        "requiresProductEntitlement").asBoolean(true)
                        && "NOT_REQUIRED".equals(settingsCapability.path(
                        "responsibilityRequirement").asText())
                        && "APP_RESOURCE_SET:RS_HCM_CONFIG".equals(
                        settingsCapability.path("scopeResolver").asText()),
                "Settings-operations capability authority drifted");
        JsonNode capability = capabilities.get("hcm.operations.exception.view");
        require(capability != null
                        && "DATA.HRIS_OPERATION_EXCEPTION:VIEW".equals(
                        capability.path("resolvedCapabilityCode").asText())
                        && "PERMISSION_AND_RELATIONSHIP".equals(
                        capability.path("authorityMode").asText())
                        && capability.path("requiresProductEntitlement").asBoolean(false)
                        && "REQUIRED".equals(
                        capability.path("responsibilityRequirement").asText())
                        && "HRIS_DUTY_OPERATION_EXCEPTION_READER".equals(
                        capability.path("requiredResponsibilityCode").asText())
                        && "AUTHORIZED_PACKAGE_SCOPE".equals(
                        capability.path("scopeResolver").asText()),
                "Operational-exception capability authority drifted");
        Map<String, JsonNode> routes = new LinkedHashMap<>();
        requiredArray(projection, "routes").forEach(route -> {
            String key = route.path("routeContractKey").asText();
            if (isOperationsRoute(key)) routes.put(key, route);
        });
        require(routes.keySet().equals(Set.of(
                        SETTINGS_OPERATIONS_ROUTE,
                        OPERATION_EXCEPTION_ROUTE,
                        OPERATION_EXCEPTION_DETAIL_ROUTE)),
                "Exact SYS-002 route closure is required");
        routes.forEach((key, route) -> {
            boolean settings = SETTINGS_OPERATIONS_ROUTE.equals(key);
            boolean detail = OPERATION_EXCEPTION_DETAIL_ROUTE.equals(key);
            JsonNode profile = requiredArray(route, "accessProfiles").get(0);
            JsonNode service = requiredArray(route, "servicePepBindings").get(0);
            require(requiredArray(route, "accessProfiles").size() == 1
                            && requiredArray(route, "servicePepBindings").size() == 1
                            && "GET".equals(service.path("method").asText())
                            && "platform".equals(service.path("serviceKey").asText())
                            && (settings ? SETTINGS_OPERATIONS_PATH
                            : detail ? OPERATION_EXCEPTION_ROOT + "/{exceptionId}"
                            : OPERATION_EXCEPTION_ROOT).equals(
                            service.path("path").asText())
                            && (settings ? "PAGE" : "DATA").equals(
                            route.path("routeKind").asText())
                            && (settings ? "configuration-reader"
                            : "operation-exception-reader").equals(
                            profile.path("profileKey").asText())
                            && profile.path("readOnly").asBoolean(false)
                            && profile.path("activeAccessModes").size() == 2
                            && profile.path("predicatePolicyKeys").size() == 1
                            && (settings
                            ? "predicate.hcm-settings-operations-authority.v1"
                            : "predicate.hcm-operation-exception-authority.v1").equals(
                            profile.path("predicatePolicyKeys").get(0).asText())
                            && profile.path("targetBindingKinds").size()
                            == (detail ? 2 : 1)
                            && textSet(profile.path("targetBindingKinds")).equals(
                            settings ? Set.of("CONFIG_SCOPE")
                                    : detail
                                    ? Set.of("TARGET_POPULATION", "OBJECT")
                                    : Set.of("TARGET_POPULATION")),
                    key + ": invalid SYS-002 PEP closure");
        });
    }

    private void validateControlledTransferClosure(ObjectNode projection, Map<String, JsonNode> capabilities) {
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

    private void validateNavigationClosure(ObjectNode projection, Map<String, JsonNode> capabilities) {
        Map<String, NavigationBindingView> expected = Map.ofEntries(
                Map.entry("route.hcm.shell.navigation-aggregate.data",
                        navigationBindings.create(
                                "GET", NAVIGATION_V2_PROJECTION_PATH, "DATA",
                                "navigation-shell-reader", "hcm.app.shell.view", false)),
                Map.entry("route.hcm.shell.navigation-deep-link-decision.data",
                        navigationBindings.create(
                                "GET", NAVIGATION_V2_DEEP_LINK_PATH, "DATA",
                                "navigation-shell-reader", "hcm.app.shell.view", false)),
                Map.entry("route.hcm.shell.navigation-aggregate-legal-entity.data",
                        navigationBindings.create(
                                "GET", NAVIGATION_V2_PROJECTION_PATH, "DATA",
                                "hr-operations-reader", null, false)),
                Map.entry(
                        "route.hcm.shell.navigation-deep-link-decision-legal-entity.data",
                        navigationBindings.create(
                                "GET", NAVIGATION_V2_DEEP_LINK_PATH, "DATA",
                                "hr-operations-reader", null, false)),
                Map.entry("route.hcm.shell.navigation-aggregate-v3.data",
                        navigationBindings.create(
                                "GET", NAVIGATION_V3_PROJECTION_PATH, "DATA",
                                "navigation-shell-reader", "hcm.app.shell.view", false)),
                Map.entry("route.hcm.shell.navigation-deep-link-decision-v3.data",
                        navigationBindings.create(
                                "GET", NAVIGATION_V3_DEEP_LINK_PATH, "DATA",
                                "navigation-shell-reader", "hcm.app.shell.view", false)),
                Map.entry("route.hcm.configuration.navigation-policy-v2-workspace.data",
                        navigationBindings.create(
                                "GET", NAVIGATION_V2_POLICY_ROOT + "/workspace", "DATA",
                                "configuration-reader",
                                "hcm.configuration.navigation-policy.view", false)),
                Map.entry("route.hcm.configuration.navigation-policy-v2-draft-create.action",
                        navigationBindings.create(
                                "POST", NAVIGATION_V2_POLICY_ROOT + "/drafts", "ACTION",
                                "configuration-author",
                                "hcm.configuration.navigation-policy.update", false)),
                Map.entry("route.hcm.configuration.navigation-policy-v2-draft-update.action",
                        navigationBindings.create(
                                "PUT", NAVIGATION_V2_POLICY_ROOT + "/drafts/{revisionId}",
                                "ACTION", "configuration-author",
                                "hcm.configuration.navigation-policy.update", false)),
                Map.entry("route.hcm.configuration.navigation-policy-v2-draft-validate.action",
                        navigationBindings.create(
                                "POST", NAVIGATION_V2_POLICY_ROOT
                                + "/drafts/{revisionId}/validate", "ACTION",
                                "configuration-author",
                                "hcm.configuration.navigation-policy.update", false)),
                Map.entry("route.hcm.configuration.navigation-policy-v2-draft-submit.action",
                        navigationBindings.create(
                                "POST", NAVIGATION_V2_POLICY_ROOT
                                + "/drafts/{revisionId}/submit", "ACTION",
                                "configuration-author",
                                "hcm.configuration.navigation-policy.update", false)),
                Map.entry("route.hcm.configuration.navigation-policy-v2-revision-approve.action",
                        navigationBindings.create(
                                "POST", NAVIGATION_V2_POLICY_ROOT
                                + "/revisions/{revisionId}/approve", "ACTION",
                                "configuration-publisher",
                                "hcm.configuration.navigation-policy.publish", true)),
                Map.entry("route.hcm.configuration.navigation-policy-v2-revision-publish.action",
                        navigationBindings.create(
                                "POST", NAVIGATION_V2_POLICY_ROOT
                                + "/revisions/{revisionId}/publish", "ACTION",
                                "configuration-publisher",
                                "hcm.configuration.navigation-policy.publish", true)),
                Map.entry("route.hcm.configuration.navigation-policy-v2-revision-restore.action",
                        navigationBindings.create(
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
            NavigationBindingView sealed = expected.get(key);
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

    private void validateAuditClosure(ObjectNode projection, Map<String, JsonNode> capabilities) {
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

    ObjectNode readProjection() {
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

    Map<String, JsonNode> index(
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

    static ArrayNode requiredArray(JsonNode source, String field) {
        JsonNode value = source.path(field);
        require(value instanceof ArrayNode, field + " must be an array");
        return (ArrayNode) value;
    }

    static Set<String> textSet(JsonNode values) {
        if (!values.isArray()) return Set.of();
        List<String> result = new ArrayList<>();
        values.forEach(value -> result.add(value.asText()));
        return Set.copyOf(result);
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    interface BindingView {
        String routeContractKey();
        List<? extends ProfileView> profiles();
    }

    interface ProfileView {
        JsonNode requiredAccess();
    }

    interface NavigationBindingView {
        String method();
        String path();
        String routeKind();
        String profileKey();
        String capabilityKey();
        boolean stepUp();
    }

    @FunctionalInterface
    interface NavigationBindingFactory {
        NavigationBindingView create(String method, String path, String routeKind,
                String profileKey, String capabilityKey, boolean stepUp);
    }
}
