package com.dwp.services.platform.calendar;

import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Calendar owner-service projection for the attested v4 and additive v2 routes. */
@Component
public final class CalendarProductSurfaceContract {

    private static final PathPatternParser OWNER_PATTERN_PARSER = new PathPatternParser();

    public static final String POLICY_ID = "P-CALENDAR";
    public static final String PRODUCT_ID = "calendar";
    public static final String SURFACE_KEY = "calendar.work";
    public static final String ACCESS_POLICY_KEY = "calendar.work-access.v1";
    public static final String EVENT_CREATE_CAPABILITY_KEY =
            "calendar.work.event.create";
    public static final String OWNER_SERVICE = "dwp-platform-server";
    public static final String SERVICE_KEY = "platform";

    public static final String HOME_PAGE_ROUTE = "route.calendar.work.home.page";
    public static final String SCHEDULE_DATA_ROUTE = "route.calendar.work.schedule.data";
    public static final String EVENT_CREATE_ACTION_ROUTE =
            "route.calendar.work.event-create.action";
    public static final String EVENTS_V2_DATA_ROUTE =
            "route.calendar.work.events-v2.data";
    public static final String SCHEDULING_EVALUATION_DATA_ROUTE =
            "route.calendar.work.scheduling-evaluation.data";
    public static final String SCHEDULING_HARD_RECHECK_DATA_ROUTE =
            "route.calendar.work.scheduling-hard-recheck.data";

    private static final List<Binding> BINDINGS = List.of(
            new Binding(
                    HOME_PAGE_ROUTE,
                    RouteKind.PAGE,
                    "GET",
                    "/api/platform/v1/calendar/home",
                    "/v1/calendar/home",
                    AccessContractType.POLICY,
                    ACCESS_POLICY_KEY,
                    "APP.CALENDAR:VIEW",
                    true),
            new Binding(
                    SCHEDULE_DATA_ROUTE,
                    RouteKind.DATA,
                    "GET",
                    "/api/platform/v1/calendar/events",
                    "/v1/calendar/events",
                    AccessContractType.POLICY,
                    ACCESS_POLICY_KEY,
                    "APP.CALENDAR:VIEW",
                    true),
            new Binding(
                    EVENT_CREATE_ACTION_ROUTE,
                    RouteKind.ACTION,
                    "POST",
                    "/api/platform/v1/calendar/events",
                    "/v1/calendar/events",
                    AccessContractType.CAPABILITY,
                    EVENT_CREATE_CAPABILITY_KEY,
                    "APP.CALENDAR:CREATE",
                    false),
            new Binding(
                    EVENTS_V2_DATA_ROUTE,
                    RouteKind.DATA,
                    "GET",
                    "/api/platform/v2/calendar/events",
                    "/v2/calendar/events",
                    AccessContractType.POLICY,
                    ACCESS_POLICY_KEY,
                    "APP.CALENDAR:VIEW",
                    true),
            new Binding(
                    SCHEDULING_EVALUATION_DATA_ROUTE,
                    RouteKind.DATA,
                    "POST",
                    "/api/platform/v2/calendar/scheduling/evaluations",
                    "/v2/calendar/scheduling/evaluations",
                    AccessContractType.POLICY,
                    ACCESS_POLICY_KEY,
                    "APP.CALENDAR:VIEW",
                    true),
            new Binding(
                    SCHEDULING_HARD_RECHECK_DATA_ROUTE,
                    RouteKind.DATA,
                    "POST",
                    "/api/platform/v2/calendar/scheduling/evaluations/{evaluationId}/hard-recheck",
                    "/v2/calendar/scheduling/evaluations/{evaluationId}/hard-recheck",
                    AccessContractType.POLICY,
                    ACCESS_POLICY_KEY,
                    "APP.CALENDAR:VIEW",
                    true));
    private static final List<OwnerCandidate> OWNER_CANDIDATES = BINDINGS.stream()
            .map(binding -> new OwnerCandidate(
                    binding,
                    OWNER_PATTERN_PARSER.parse(binding.servicePath())))
            .toList();

    public Optional<Binding> resolveOwner(String method, String path) {
        if (method == null || path == null) return Optional.empty();
        try {
            PathContainer candidatePath = PathContainer.parsePath(path);
            return OWNER_CANDIDATES.stream()
                    .filter(candidate -> candidate.binding().method().equals(method))
                    .filter(candidate -> candidate.pattern().matches(candidatePath))
                    .map(OwnerCandidate::binding)
                    .filter(binding -> canonicalOwnerPath(binding.servicePath(), path))
                    .findFirst();
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /**
     * Recognizes paths Spring MVC can dispatch to a Calendar route while leaving exact
     * canonical-path acceptance to {@link #resolveOwner(String, String)}.
     */
    public boolean ownsOwnerCandidate(String method, String path) {
        if (method == null || path == null) return false;
        String effectiveMethod = "HEAD".equals(method) ? "GET" : method;
        if (path.equals("/v2/calendar") || path.startsWith("/v2/calendar/")) {
            return true;
        }
        try {
            PathContainer candidatePath = PathContainer.parsePath(path);
            return OWNER_CANDIDATES.stream()
                    .anyMatch(candidate -> candidate.binding().method().equals(effectiveMethod)
                            && candidate.pattern().matches(candidatePath));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean canonicalOwnerPath(String template, String path) {
        if (!template.contains("{")) return template.equals(path);
        if (!path.startsWith("/") || path.indexOf('%') >= 0 || path.indexOf(';') >= 0
                || path.indexOf('\\') >= 0 || path.indexOf('?') >= 0
                || path.indexOf('#') >= 0) {
            return false;
        }
        String[] expected = template.substring(1).split("/", -1);
        String[] actual = path.substring(1).split("/", -1);
        if (expected.length != actual.length) return false;
        for (int index = 0; index < expected.length; index++) {
            if ("{evaluationId}".equals(expected[index])) {
                if (!canonicalUuid(actual[index])) return false;
            } else if (!expected[index].equals(actual[index])) {
                return false;
            }
        }
        return true;
    }

    private boolean canonicalUuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public List<BindingContract> bindingContracts() {
        return BINDINGS.stream().map(binding -> new BindingContract(
                POLICY_ID,
                PRODUCT_ID,
                SURFACE_KEY,
                OWNER_SERVICE,
                SERVICE_KEY,
                binding.routeContractKey(),
                binding.routeKind(),
                binding.method(),
                binding.gatewayPath(),
                binding.servicePath(),
                binding.accessContractType(),
                binding.accessContractKey(),
                binding.resolvedAuthority(),
                binding.readOnly())).toList();
    }

    public enum RouteKind {
        PAGE,
        DATA,
        ACTION
    }

    public enum AccessContractType {
        POLICY,
        CAPABILITY
    }

    public record Binding(
            String routeContractKey,
            RouteKind routeKind,
            String method,
            String gatewayPath,
            String servicePath,
            AccessContractType accessContractType,
            String accessContractKey,
            String resolvedAuthority,
            boolean readOnly) {
    }

    private record OwnerCandidate(Binding binding, PathPattern pattern) {
    }

    public record BindingContract(
            String policyId,
            String productId,
            String surfaceKey,
            String ownerService,
            String serviceKey,
            String routeContractKey,
            RouteKind routeKind,
            String method,
            String gatewayPath,
            String servicePath,
            AccessContractType accessContractType,
            String accessContractKey,
            String resolvedAuthority,
            boolean readOnly) {
    }
}
