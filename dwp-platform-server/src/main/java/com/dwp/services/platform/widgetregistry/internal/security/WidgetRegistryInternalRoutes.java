package com.dwp.services.platform.widgetregistry.internal.security;

import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryTrustPorts.AssertionKind;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Closed method/path/query allowlist for the internal Widget Registry trust plane. */
final class WidgetRegistryInternalRoutes {

    static final String PREFIX = "/internal/provider/v1/widget-registry";
    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}"
            + "-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

    private WidgetRegistryInternalRoutes() {
    }

    static boolean isPlanePath(String path) {
        return path != null && (path.equals(PREFIX) || path.startsWith(PREFIX + "/"));
    }

    static Resolution resolve(String path, String method) {
        Set<String> allowedMethods = new LinkedHashSet<>();
        for (Route route : Route.values()) {
            if (!route.pathPattern().matcher(path).matches()) continue;
            allowedMethods.add(route.method());
            if ("GET".equals(route.method())) allowedMethods.add("HEAD");
            if (route.method().equals(method) || "GET".equals(route.method()) && "HEAD".equals(method)) {
                return Resolution.matched(new Match(route, path, method));
            }
        }
        if (!allowedMethods.isEmpty()) return Resolution.methodNotAllowed(allowedMethods);
        return Resolution.notFound();
    }

    enum ResolutionStatus {
        MATCHED,
        NOT_FOUND,
        METHOD_NOT_ALLOWED
    }

    record Resolution(ResolutionStatus status, Match match, Set<String> allowedMethods) {
        static Resolution matched(Match match) {
            Set<String> methods = "GET".equals(match.route().method())
                    ? Set.of("GET", "HEAD")
                    : Set.of(match.route().method());
            return new Resolution(ResolutionStatus.MATCHED, match, methods);
        }

        static Resolution notFound() {
            return new Resolution(ResolutionStatus.NOT_FOUND, null, Set.of());
        }

        static Resolution methodNotAllowed(Set<String> methods) {
            return new Resolution(ResolutionStatus.METHOD_NOT_ALLOWED, null, Set.copyOf(methods));
        }
    }

    record Match(Route route, String actualPath, String method) {
    }

    enum Route {
        LIST_DEFINITIONS(
                "listWidgetRegistryDefinitionsInternal",
                "GET",
                "/definitions",
                "/definitions",
                keys("page", "size", "sort", "ownerProductKey", "definitionState", "riskTier", "q",
                        "readRevision"),
                "widget-registry.read",
                "WIDGET_CATALOG_READ",
                AssertionKind.WIDGET,
                null),
        GET_DEFINITION(
                "getWidgetRegistryDefinitionInternal",
                "GET",
                "/definitions/{definitionId}",
                "/definitions/" + UUID,
                Set.of(),
                "widget-registry.read",
                "WIDGET_CATALOG_READ",
                AssertionKind.WIDGET,
                null),
        LIST_DEFINITION_VERSIONS(
                "listWidgetRegistryDefinitionVersionsInternal",
                "GET",
                "/definitions/{definitionId}/versions",
                "/definitions/" + UUID + "/versions",
                keys("page", "size", "sort", "workflowState", "releaseState", "safetyState", "channel",
                        "readRevision"),
                "widget-registry.read",
                "WIDGET_CATALOG_READ",
                AssertionKind.WIDGET,
                null),
        GET_VERSION(
                "getWidgetRegistryVersionInternal",
                "GET",
                "/versions/{versionId}",
                "/versions/" + UUID,
                Set.of(),
                "widget-registry.read",
                "WIDGET_CATALOG_READ",
                AssertionKind.WIDGET,
                null),
        GET_RETIREMENT_IMPACT(
                "getWidgetRegistryRetirementImpactInternal",
                "GET",
                "/definitions/{definitionId}/retirement-impact",
                "/definitions/" + UUID + "/retirement-impact",
                keys("replacementDefinitionId"),
                "widget-registry.read",
                "WIDGET_DEFINITION_RELEASE",
                AssertionKind.WIDGET,
                null),
        GET_RELEASE_CHANNEL(
                "getWidgetRegistryReleaseChannelInternal",
                "GET",
                "/definitions/{definitionId}/channels/{channel}",
                "/definitions/" + UUID + "/channels/(?:STABLE|PREVIEW)",
                Set.of(),
                "widget-registry.read",
                "WIDGET_CATALOG_READ",
                AssertionKind.WIDGET,
                null),
        LIST_EVIDENCE(
                "listWidgetRegistryEvidenceInternal",
                "GET",
                "/versions/{versionId}/evidence",
                "/versions/" + UUID + "/evidence",
                keys("page", "size", "sort", "evidenceType", "status", "readRevision"),
                "widget-registry.read",
                "WIDGET_CATALOG_READ",
                AssertionKind.WIDGET,
                null),
        GET_EVIDENCE(
                "getWidgetRegistryEvidenceInternal",
                "GET",
                "/versions/{versionId}/evidence/{evidenceId}",
                "/versions/" + UUID + "/evidence/" + UUID,
                Set.of(),
                "widget-registry.read",
                "WIDGET_CATALOG_READ",
                AssertionKind.WIDGET,
                null),
        LIST_RUNTIME_CONTROLS(
                "listWidgetRegistryRuntimeControlsInternal",
                "GET",
                "/runtime-controls",
                "/runtime-controls",
                keys("page", "size", "sort", "scope", "targetType", "state", "readRevision"),
                "widget-registry.read",
                "WIDGET_CATALOG_READ",
                AssertionKind.WIDGET,
                null),
        GET_COMMAND_COMPLETION(
                "getWidgetRegistryCommandCompletionInternal",
                "GET",
                "/command-completions/{commandId}",
                "/command-completions/" + UUID,
                Set.of(),
                "widget-registry.reconcile",
                null,
                AssertionKind.RECONCILE,
                "READ_COMPLETION"),
        SEAL_COMMAND_NOT_EXECUTED(
                "sealWidgetRegistryCommandNotExecutedInternal",
                "POST",
                "/command-completions/{commandId}/seal-not-executed",
                "/command-completions/" + UUID + "/seal-not-executed",
                Set.of(),
                "widget-registry.reconcile",
                null,
                AssertionKind.RECONCILE,
                "SEAL_NOT_EXECUTED"),
        EXECUTE_COMMAND(
                "executeWidgetRegistryCommandInternal",
                "POST",
                "/commands",
                "/commands",
                Set.of(),
                null,
                null,
                AssertionKind.WIDGET,
                null);

        private final String operationId;
        private final String method;
        private final String pathTemplate;
        private final Pattern pathPattern;
        private final Set<String> allowedQueryKeys;
        private final String serviceScope;
        private final String providerPermission;
        private final AssertionKind assertionKind;
        private final String assertionPurpose;

        Route(
                String operationId,
                String method,
                String pathTemplateSuffix,
                String pathPatternSuffix,
                Set<String> allowedQueryKeys,
                String serviceScope,
                String providerPermission,
                AssertionKind assertionKind,
                String assertionPurpose) {
            this.operationId = operationId;
            this.method = method;
            this.pathTemplate = PREFIX + pathTemplateSuffix;
            this.pathPattern = Pattern.compile("^" + Pattern.quote(PREFIX) + pathPatternSuffix + "$");
            this.allowedQueryKeys = Set.copyOf(allowedQueryKeys);
            this.serviceScope = serviceScope;
            this.providerPermission = providerPermission;
            this.assertionKind = assertionKind;
            this.assertionPurpose = assertionPurpose;
        }

        String operationId() {
            return operationId;
        }

        String method() {
            return method;
        }

        String pathTemplate() {
            return pathTemplate;
        }

        Pattern pathPattern() {
            return pathPattern;
        }

        Set<String> allowedQueryKeys() {
            return allowedQueryKeys;
        }

        String serviceScope() {
            return serviceScope;
        }

        String providerPermission() {
            return providerPermission;
        }

        AssertionKind assertionKind() {
            return assertionKind;
        }

        String assertionPurpose() {
            return assertionPurpose;
        }
    }

    private static Set<String> keys(String... values) {
        return Set.copyOf(Arrays.asList(values));
    }
}
