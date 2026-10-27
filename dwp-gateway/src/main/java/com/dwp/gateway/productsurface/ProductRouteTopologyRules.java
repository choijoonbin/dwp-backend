package com.dwp.gateway.productsurface;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Exact topology validation and canonical encodings for generated PRODUCT bundles. */
final class ProductRouteTopologyRules {

    private ProductRouteTopologyRules() {
    }

    static final Map<String, GenericSessionTopology>
            GENERIC_SESSION_STEP_UP_TOPOLOGIES = Map.ofEntries(
            Map.entry("route.hcm.configuration.configuration-version.data",
                    new GenericSessionTopology("hcm.management", "DATA", "GET",
                            "/api/platform/v1/admin/hris/configurations/{configId}/versions/{version}",
                            "platform",
                            "/v1/admin/hris/configurations/{configId}/versions/{version}",
                            List.of("configuration-manager"))),
            Map.entry("route.hcm.configuration.configurations.data",
                    new GenericSessionTopology("hcm.management", "DATA", "GET",
                            "/api/platform/v1/admin/hris/configurations", "platform",
                            "/v1/admin/hris/configurations",
                            List.of("configuration-manager"))),
            Map.entry("route.hcm.operations.payroll-population.page",
                    new GenericSessionTopology("hcm.operations", "PAGE", "GET",
                            "/api/payroll/v1/workbench/population", "payroll",
                            "/v1/workbench/population",
                            List.of("payroll-support-viewer"))),
            Map.entry("route.hcm.operations.payroll-support.data",
                    new GenericSessionTopology("hcm.operations", "DATA", "GET",
                            "/api/payroll/v1/workbench/population", "payroll",
                            "/v1/workbench/population",
                            List.of("payroll-support-viewer"))),
            Map.entry("route.hcm.operations.pay-tax-social-insurance.page",
                    new GenericSessionTopology("hcm.operations", "PAGE", "GET",
                            "/api/payroll/v1/workbench/pay-tax-social-insurance", "payroll",
                            "/v1/workbench/pay-tax-social-insurance",
                            List.of("payroll-support-viewer"))),
            Map.entry("route.hcm.operations.pay-tax-social-insurance.data",
                    new GenericSessionTopology("hcm.operations", "DATA", "GET",
                            "/api/payroll/v1/workbench/pay-tax-social-insurance", "payroll",
                            "/v1/workbench/pay-tax-social-insurance",
                            List.of("payroll-support-viewer"))),
            Map.entry("route.hcm.operations.pay-retirement.page",
                    new GenericSessionTopology("hcm.operations", "PAGE", "GET",
                            "/api/payroll/v1/workbench/pay-retirement", "payroll",
                            "/v1/workbench/pay-retirement",
                            List.of("payroll-support-viewer"))),
            Map.entry("route.hcm.operations.pay-retirement.data",
                    new GenericSessionTopology("hcm.operations", "DATA", "GET",
                            "/api/payroll/v1/workbench/pay-retirement", "payroll",
                            "/v1/workbench/pay-retirement",
                            List.of("payroll-support-viewer"))),
            Map.entry("route.hcm.operations.payroll-run.data",
                    new GenericSessionTopology("hcm.operations", "DATA", "GET",
                            "/api/payroll/v1/runs/{runId}", "payroll",
                            "/v1/runs/{runId}", List.of("payroll-run-viewer"))),
            Map.entry("route.hcm.operations.time-close-validate.action",
                    new GenericSessionTopology("hcm.operations", "ACTION", "POST",
                            "/api/time/v1/close-periods/{periodId}/validations", "time",
                            "/v1/close-periods/{periodId}/validations",
                            List.of("time-close-operator"))),
            Map.entry("route.hcm.operations.time-interpretation-run.action",
                    new GenericSessionTopology("hcm.operations", "ACTION", "POST",
                            "/api/time/v1/interpretation-runs", "time",
                            "/v1/interpretation-runs",
                            List.of("full-management", "self-service"))),
            Map.entry("route.hcm.operations.time-interpretation-trace.data",
                    new GenericSessionTopology("hcm.operations", "DATA", "GET",
                            "/api/time/v1/interpretation-runs/{runId}/trace", "time",
                            "/v1/interpretation-runs/{runId}/trace",
                            List.of("full-management", "self-service"))),
            Map.entry("route.hcm.operations.time-payroll-handoff-create.action",
                    new GenericSessionTopology("hcm.operations", "ACTION", "POST",
                            "/api/time/v1/close-periods/{periodId}/payroll-handoffs", "time",
                            "/v1/close-periods/{periodId}/payroll-handoffs",
                            List.of("time-close-operator"))),
            Map.entry("route.hcm.operations.time-payroll-handoff.data",
                    new GenericSessionTopology("hcm.operations", "DATA", "GET",
                            "/api/time/v1/payroll-handoffs/{handoffId}", "time",
                            "/v1/payroll-handoffs/{handoffId}",
                            List.of("time-close-operator"))),
            Map.entry("route.hcm.time.timecard-correction.action",
                    new GenericSessionTopology("hcm.operations", "ACTION", "POST",
                            "/api/time/v1/timecards/{timecardId}/corrections", "time",
                            "/v1/timecards/{timecardId}/corrections",
                            List.of("time-operator", "self-submitter"))),
            Map.entry("route.hcm.time.timecard.data",
                    new GenericSessionTopology("hcm.operations", "DATA", "GET",
                            "/api/time/v1/timecards/{timecardId}", "time",
                            "/v1/timecards/{timecardId}",
                            List.of("time-operator", "manager-approver", "self-reader"))),
            Map.entry("route.hcm.configuration.automation-catalog.data",
                    new GenericSessionTopology("hcm.management", "DATA", "GET",
                            "/api/platform/v1/admin/hris/automations", "platform",
                            "/v1/admin/hris/automations",
                            List.of("automation-author", "automation-publisher",
                                    "automation-executor"))),
            Map.entry("route.hcm.configuration.automation-run-detail.data",
                    new GenericSessionTopology("hcm.management", "DATA", "GET",
                            "/api/platform/v1/admin/hris/automation-runs/{runId}", "platform",
                            "/v1/admin/hris/automation-runs/{runId}",
                            List.of("automation-executor"))),
            Map.entry("route.hcm.configuration.connector-execution-detail.data",
                    new GenericSessionTopology("hcm.management", "DATA", "GET",
                            "/api/platform/v1/admin/hris/connector-executions/{executionId}",
                            "platform",
                            "/v1/admin/hris/connector-executions/{executionId}",
                            List.of("integration-control-plane-executor"))),
            Map.entry("route.hcm.configuration.connector-execution-create.action",
                    new GenericSessionTopology("hcm.management", "ACTION", "POST",
                            "/api/platform/v1/admin/hris/connectors/{connectorId}/executions",
                            "platform",
                            "/v1/admin/hris/connectors/{connectorId}/executions",
                            List.of("integration-control-plane-executor"))));
    static final Set<String> OTHER_EXACT_NO_COMMAND_STEP_UP_ROUTE_KEYS = Set.of(
            "route.approvals.admin.form-publish-review-reject.action",
            "route.approvals.admin.retention-policy-publication-command.data",
            "route.approvals.admin.retention-record-command.data",
            "route.hcm.audit.evidence-detail.data",
            "route.hcm.audit.evidence.page");
    static final Set<String> V49_GENERIC_SESSION_STEP_UP_ROUTE_KEYS = Set.of(
            "route.hcm.operations.payroll-population.page",
            "route.hcm.operations.payroll-support.data",
            "route.hcm.operations.payroll-run.data",
            "route.hcm.operations.time-close-validate.action",
            "route.hcm.operations.time-payroll-handoff-create.action",
            "route.hcm.operations.time-payroll-handoff.data");
    static final Set<String> V54_GENERIC_SESSION_STEP_UP_ROUTE_KEYS = Set.of(
            "route.hcm.operations.pay-tax-social-insurance.page",
            "route.hcm.operations.pay-tax-social-insurance.data");
    static final Set<String> V56_GENERIC_SESSION_STEP_UP_ROUTE_KEYS = Set.of(
            "route.hcm.configuration.automation-catalog.data",
            "route.hcm.configuration.automation-run-detail.data");
    static final Set<String> V58_GENERIC_SESSION_STEP_UP_ROUTE_KEYS = Set.of(
            "route.hcm.operations.pay-retirement.page",
            "route.hcm.operations.pay-retirement.data");
    static final Set<String> V60_GENERIC_SESSION_STEP_UP_ROUTE_KEYS = Set.of(
            "route.hcm.configuration.connector-execution-detail.data",
            "route.hcm.configuration.connector-execution-create.action");

    /**
     * PER-008 is the first feedback transport contract to publish PAGE/DATA pairs on the same
     * wire binding. Keep its complete v59 network topology in code so an older bundle, a partial
     * v59, a duplicate route, or a widened/near-match binding fails before request matching.
     */
    static final Map<String, V59PerformanceFeedbackTopology>
            V59_PERFORMANCE_FEEDBACK_TOPOLOGIES = Map.ofEntries(
            Map.entry("route.hcm.team.performance.page",
                    new V59PerformanceFeedbackTopology(
                            "hcm.team", "PAGE", "GET",
                            "/api/people/v1/hris/performance/workbench/team-performance",
                            "/v1/hris/performance/workbench/team-performance",
                            List.of("team-performance-reader"), Set.of(),
                            "wire-authority.hcm.team.performance.v1")),
            Map.entry("route.hcm.team.performance.data",
                    new V59PerformanceFeedbackTopology(
                            "hcm.team", "DATA", "GET",
                            "/api/people/v1/hris/performance/workbench/team-performance",
                            "/v1/hris/performance/workbench/team-performance",
                            List.of("team-performance-reader"), Set.of(),
                            "wire-authority.hcm.team.performance.v1")),
            Map.entry("route.hcm.personal.performance-feedback.page",
                    new V59PerformanceFeedbackTopology(
                            "hcm.personal", "PAGE", "GET",
                            "/api/people/v1/hris/performance/feedback-assignments/mine",
                            "/v1/hris/performance/feedback-assignments/mine",
                            List.of("self-performance-reader"), Set.of(),
                            "wire-authority.hcm.personal.performance-feedback-inbox.v1")),
            Map.entry("route.hcm.performance.feedback-request-options.data",
                    new V59PerformanceFeedbackTopology(
                            "hcm.operations", "DATA", "GET",
                            "/api/people/v1/hris/performance/feedback-request-options",
                            "/v1/hris/performance/feedback-request-options",
                            List.of("manager-feedback-requester", "self-feedback-requester"),
                            Set.of("cycleVersionId", "subjectWorkerPublicId",
                                    "subjectAssignmentPublicId"), null)),
            Map.entry("route.hcm.performance.feedback-request-create.action",
                    new V59PerformanceFeedbackTopology(
                            "hcm.operations", "ACTION", "POST",
                            "/api/people/v1/hris/performance/feedback-requests",
                            "/v1/hris/performance/feedback-requests",
                            List.of("manager-feedback-requester", "self-feedback-requester"),
                            Set.of(), null)),
            Map.entry("route.hcm.personal.feedback-assignments-mine.data",
                    new V59PerformanceFeedbackTopology(
                            "hcm.personal", "DATA", "GET",
                            "/api/people/v1/hris/performance/feedback-assignments/mine",
                            "/v1/hris/performance/feedback-assignments/mine",
                            List.of("self-performance-reader"), Set.of(),
                            "wire-authority.hcm.personal.performance-feedback-inbox.v1")),
            Map.entry("route.hcm.personal.feedback-assignment-detail.data",
                    new V59PerformanceFeedbackTopology(
                            "hcm.personal", "DATA", "GET",
                            "/api/people/v1/hris/performance/feedback-assignments/"
                                    + "{feedbackAssignmentId}",
                            "/v1/hris/performance/feedback-assignments/"
                                    + "{feedbackAssignmentId}",
                            List.of("assigned-feedback-reader"), Set.of(), null)),
            Map.entry("route.hcm.personal.feedback-response-submit.action",
                    new V59PerformanceFeedbackTopology(
                            "hcm.personal", "ACTION", "POST",
                            "/api/people/v1/hris/performance/feedback-assignments/"
                                    + "{feedbackAssignmentId}/responses",
                            "/v1/hris/performance/feedback-assignments/"
                                    + "{feedbackAssignmentId}/responses",
                            List.of("assigned-feedback-provider"), Set.of(), null)),
            Map.entry("route.hcm.performance.feedback-release.data",
                    new V59PerformanceFeedbackTopology(
                            "hcm.operations", "DATA", "GET",
                            "/api/people/v1/hris/performance/feedback-requests/"
                                    + "{feedbackRequestId}/release",
                            "/v1/hris/performance/feedback-requests/"
                                    + "{feedbackRequestId}/release",
                            List.of("self-feedback-release-reader",
                                    "manager-feedback-release-reader",
                                    "operator-feedback-release-reader"), Set.of(), null)));

    /**
     * PAGE routes introduced by the v47 canonical-entry migration intentionally share the exact
     * first-read wire contract of their pre-existing DATA/PAGE source. The contract generator
     * validates each pair's complete authority semantics, but deliberately leaves the historical
     * source active and does not add a general-purpose equivalence marker. Keep the runtime
     * collapse boundary to this exact, code-owned set. API forwarding must keep the source route:
     * owner-service PEPs and command/read adapters still validate that exact route key, while the
     * canonical PAGE route is used independently for UI admission.
     */
    static void validateV59PerformanceFeedbackTopology(
            JsonNode route,
            V59PerformanceFeedbackTopology topology) {
        String routeKey = route.path("routeContractKey").asText();
        String bindingKey = routeKey + ".binding.01";
        JsonNode subject = route.path("subject");
        JsonNode gateways = route.path("gatewayApiBindings");
        JsonNode services = route.path("servicePepBindings");
        JsonNode profiles = route.path("accessProfiles");
        boolean exact = "ACTIVE".equals(route.path("lifecycleState").asText())
                && route.path("policyVersion").asLong() == 1
                && !route.has("stepUpCommandBindings")
                && "PRODUCT".equals(subject.path("type").asText())
                && "hcm".equals(subject.path("productKey").asText())
                && topology.surfaceKey().equals(subject.path("surfaceKey").asText())
                && topology.surfaceKey().equals(route.path("navigationContextId").asText())
                && topology.routeKind().equals(route.path("routeKind").asText())
                && java.util.Objects.equals(
                        topology.authorizationEquivalenceKey(),
                        textOrNull(route, "authorizationEquivalenceKey"))
                && ("DATA".equals(topology.routeKind())
                        ? route.path("sideEffectFree").asBoolean(false)
                        : route.path("sideEffectFree").isNull())
                && gateways.isArray() && gateways.size() == 1
                && services.isArray() && services.size() == 1
                && profiles.isArray() && profiles.size() == topology.profileKeys().size();
        if (exact) {
            JsonNode gateway = gateways.get(0);
            JsonNode service = services.get(0);
            exact = bindingKey.equals(gateway.path("bindingKey").asText())
                    && topology.method().equals(gateway.path("method").asText())
                    && topology.publicPath().equals(gateway.path("path").asText())
                    && gateway.path("pathParameterConstraints").isObject()
                    && gateway.path("pathParameterConstraints").isEmpty()
                    && exactRequiredQueryParameters(
                            gateway.path("queryParameterConstraints"),
                            topology.requiredQueryParameters())
                    && bindingKey.equals(service.path("bindingKey").asText())
                    && "people".equals(service.path("serviceKey").asText())
                    && topology.method().equals(service.path("method").asText())
                    && topology.servicePath().equals(service.path("path").asText())
                    && service.path("pathParameterConstraints").isObject()
                    && service.path("pathParameterConstraints").isEmpty()
                    && exactRequiredQueryParameters(
                            service.path("queryParameterConstraints"),
                            topology.requiredQueryParameters());
        }
        if (exact) {
            for (int index = 0; index < profiles.size(); index++) {
                if (!topology.profileKeys().get(index)
                        .equals(profiles.get(index).path("profileKey").asText())) {
                    exact = false;
                    break;
                }
            }
        }
        if (!exact) {
            throw new IllegalStateException(
                    "A PRODUCT v59 performance-feedback topology is invalid: " + routeKey);
        }
    }

    static boolean exactRequiredQueryParameters(JsonNode constraints, Set<String> required) {
        if (required.isEmpty()) {
            return constraints.isMissingNode() || constraints.isNull()
                    || constraints.isObject() && constraints.isEmpty();
        }
        if (!constraints.isObject() || constraints.size() != required.size()) return false;
        for (String parameter : required) {
            JsonNode constraint = constraints.path(parameter);
            if (!constraint.isObject() || constraint.size() != 1
                    || !"REQUIRED".equals(constraint.path("kind").asText())) {
                return false;
            }
        }
        return true;
    }

    static Map<String, Boolean> highRiskCapabilities(JsonNode bundle) {
        Map<String, Boolean> result = new HashMap<>();
        for (JsonNode capability : bundle.path("capabilities")) {
            result.put(capability.path("contractKey").asText(),
                    "ACTIVE".equals(capability.path("lifecycleState").asText())
                            && Set.of("HIGH", "CRITICAL").contains(
                                    capability.path("riskTier").asText())
                            && capability.path("activationPolicy").asText().startsWith("STEPUP-"));
        }
        return result;
    }

    static boolean highRiskStepUp(JsonNode route, Map<String, Boolean> capabilities) {
        for (JsonNode profile : route.path("accessProfiles")) {
            JsonNode required = profile.path("requiredAccess");
            for (String capabilityKey : requiredCapabilityKeys(required)) {
                Boolean activated = capabilities.get(capabilityKey);
                if (activated == null) {
                    throw new IllegalStateException(
                            "A PRODUCT route references an unknown capability.");
                }
                if (activated) return true;
            }
        }
        return false;
    }

    static boolean genericSessionStepUp(
            JsonNode route,
            long bundleVersion,
            boolean highRiskStepUp,
            boolean commandBoundStepUp) {
        String key = route.path("routeContractKey").asText();
        GenericSessionTopology topology = GENERIC_SESSION_STEP_UP_TOPOLOGIES.get(key);
        if (topology == null) return false;
        if (bundleVersion < 49 && V49_GENERIC_SESSION_STEP_UP_ROUTE_KEYS.contains(key)) {
            return false;
        }
        if (bundleVersion < 54 && V54_GENERIC_SESSION_STEP_UP_ROUTE_KEYS.contains(key)) {
            return false;
        }
        if (bundleVersion < 56 && V56_GENERIC_SESSION_STEP_UP_ROUTE_KEYS.contains(key)) {
            return false;
        }
        if (bundleVersion < 58 && V58_GENERIC_SESSION_STEP_UP_ROUTE_KEYS.contains(key)) {
            return false;
        }
        JsonNode subject = route.path("subject");
        JsonNode gateways = route.path("gatewayApiBindings");
        JsonNode services = route.path("servicePepBindings");
        JsonNode profiles = route.path("accessProfiles");
        String bindingKey = key + ".binding.01";
        boolean exact = highRiskStepUp
                && !commandBoundStepUp
                && !route.has("stepUpCommandBindings")
                && "ACTIVE".equals(route.path("lifecycleState").asText())
                && "PRODUCT".equals(subject.path("type").asText())
                && "hcm".equals(subject.path("productKey").asText())
                && topology.surfaceKey().equals(subject.path("surfaceKey").asText())
                && topology.surfaceKey().equals(route.path("navigationContextId").asText())
                && topology.routeKind().equals(route.path("routeKind").asText())
                && ("DATA".equals(topology.routeKind())
                        ? route.path("sideEffectFree").asBoolean(false)
                        : route.path("sideEffectFree").isNull())
                && gateways.isArray() && gateways.size() == 1
                && services.isArray() && services.size() == 1
                && bindingKey.equals(gateways.get(0).path("bindingKey").asText())
                && topology.method().equals(gateways.get(0).path("method").asText())
                && topology.publicPath().equals(gateways.get(0).path("path").asText())
                && bindingKey.equals(services.get(0).path("bindingKey").asText())
                && topology.serviceKey().equals(services.get(0).path("serviceKey").asText())
                && topology.method().equals(services.get(0).path("method").asText())
                && topology.servicePath().equals(services.get(0).path("path").asText())
                && profiles.isArray()
                && profiles.size() == topology.profileKeys().size();
        if (exact) {
            for (int index = 0; index < profiles.size(); index++) {
                if (!topology.profileKeys().get(index)
                        .equals(profiles.get(index).path("profileKey").asText())) {
                    exact = false;
                    break;
                }
            }
        }
        if (!exact) {
            throw new IllegalStateException(
                    "A PRODUCT generic session step-up topology is invalid: " + key);
        }
        return true;
    }

    static List<String> requiredCapabilityKeys(JsonNode required) {
        String type = required.path("type").asText();
        if ("POLICY".equals(type)) return List.of();
        if ("CAPABILITY".equals(type)) {
            String key = required.path("capabilityContractKey").asText();
            if (key.isBlank()) {
                throw new IllegalStateException("A PRODUCT capability reference is invalid.");
            }
            return List.of(key);
        }
        if (!"CAPABILITY_EXPRESSION".equals(type)
                || !Set.of("ANY", "ALL").contains(required.path("mode").asText())
                || !required.path("capabilityContractKeys").isArray()
                || required.path("capabilityContractKeys").isEmpty()) {
            throw new IllegalStateException("A PRODUCT required-access traversal is invalid.");
        }
        List<String> keys = new ArrayList<>();
        for (JsonNode value : required.path("capabilityContractKeys")) {
            if (!value.isTextual() || value.asText().isBlank()
                    || keys.contains(value.asText())) {
                throw new IllegalStateException(
                        "A PRODUCT capability expression is invalid.");
            }
            keys.add(value.asText());
        }
        return List.copyOf(keys);
    }

    static Pattern compile(String template, JsonNode constraints) {
        StringBuilder regex = new StringBuilder("^");
        java.util.regex.Matcher matcher = Pattern.compile("\\{([^/{}]+)}").matcher(template);
        int cursor = 0;
        while (matcher.find()) {
            regex.append(Pattern.quote(template.substring(cursor, matcher.start())));
            regex.append(parameterPattern(matcher.group(1), constraints));
            cursor = matcher.end();
        }
        regex.append(Pattern.quote(template.substring(cursor))).append('$');
        return Pattern.compile(regex.toString());
    }

    static String parameterPattern(String parameter, JsonNode constraints) {
        if (constraints == null || constraints.isMissingNode() || !constraints.isObject()) {
            return "[^/]+";
        }
        JsonNode constraint = constraints.path(parameter);
        return switch (constraint.path("kind").asText()) {
            case "FIXED" -> Pattern.quote(constraint.path("value").asText());
            case "ALLOWLIST" -> allowlistPattern(constraint.path("values"));
            default -> "[^/]+";
        };
    }

    static String allowlistPattern(JsonNode values) {
        if (!values.isArray() || values.isEmpty()) return "(?!)";
        List<String> encoded = new ArrayList<>();
        values.forEach(value -> encoded.add(Pattern.quote(value.asText())));
        return "(?:" + String.join("|", encoded) + ")";
    }

    static Map<String, List<String>> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return Map.of();
        Map<String, List<String>> result = new HashMap<>();
        for (String pair : rawQuery.split("&", -1)) {
            if (pair.isEmpty()) throw new IllegalArgumentException("Empty query component");
            int separator = pair.indexOf('=');
            String key = decode(separator < 0 ? pair : pair.substring(0, separator));
            String value = decode(separator < 0 ? "" : pair.substring(separator + 1));
            if (key.isEmpty() || key.indexOf('\u0000') >= 0 || value.indexOf('\u0000') >= 0) {
                throw new IllegalArgumentException("Invalid query component");
            }
            result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        }
        return result.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid query encoding", exception);
        }
    }

    static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank()
                ? null : value.asText();
    }

    static String normalizeMethod(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private record GenericSessionTopology(
            String surfaceKey,
            String routeKind,
            String method,
            String publicPath,
            String serviceKey,
            String servicePath,
            List<String> profileKeys) {
    }

    record V59PerformanceFeedbackTopology(
            String surfaceKey,
            String routeKind,
            String method,
            String publicPath,
            String servicePath,
            List<String> profileKeys,
            Set<String> requiredQueryParameters,
            String authorizationEquivalenceKey) {
    }

}
