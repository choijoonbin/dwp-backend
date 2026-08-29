package com.dwp.services.platform.calendar;

import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;
import java.util.Optional;

/** Immutable owner-service projection for the Calendar v4 PAGE/DATA/ACTION draft. */
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
                    false));
    private static final List<OwnerCandidate> OWNER_CANDIDATES = BINDINGS.stream()
            .map(binding -> new OwnerCandidate(
                    binding,
                    OWNER_PATTERN_PARSER.parse(binding.servicePath())))
            .toList();

    public Optional<Binding> resolveOwner(String method, String path) {
        if (method == null || path == null) return Optional.empty();
        return BINDINGS.stream()
                .filter(binding -> binding.method().equals(method))
                .filter(binding -> binding.servicePath().equals(path))
                .findFirst();
    }

    /**
     * Recognizes paths Spring MVC can dispatch to a Calendar route while leaving exact
     * canonical-path acceptance to {@link #resolveOwner(String, String)}.
     */
    public boolean ownsOwnerCandidate(String method, String path) {
        if (method == null || path == null) return false;
        String effectiveMethod = "HEAD".equals(method) ? "GET" : method;
        try {
            PathContainer candidatePath = PathContainer.parsePath(path);
            return OWNER_CANDIDATES.stream()
                    .anyMatch(candidate -> candidate.binding().method().equals(effectiveMethod)
                            && candidate.pattern().matches(candidatePath));
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
