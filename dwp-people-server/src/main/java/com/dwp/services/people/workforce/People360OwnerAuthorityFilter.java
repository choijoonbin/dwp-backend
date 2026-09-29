package com.dwp.services.people.workforce;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmV3PepRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Exact owner-side boundary for the default-off People 360 query projections. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 35)
@ConditionalOnProperty(
        name = "dwp.people.people360-runtime-enabled",
        havingValue = "true",
        matchIfMissing = false)
public final class People360OwnerAuthorityFilter extends OncePerRequestFilter {

    public static final String SEARCH_ROUTE =
            "route.hcm.operations.people360-search.data";
    public static final String DETAIL_ROUTE =
            "route.hcm.operations.people360-detail.data";
    public static final String SELF_ROUTE =
            "route.hcm.personal.people360-self.data";
    public static final String TEAM_ROUTE =
            "route.hcm.team.people360-detail.data";

    private static final String OPERATIONS_PREDICATE =
            "predicate.hcm-workforce-visible-person.v1";
    private static final String TEAM_PREDICATE =
            "predicate.team-target-population.v1";
    private static final String SELF_PREDICATE = "predicate.self-person.v1";
    private static final Map<RouteType, RouteContract> CONTRACTS = Map.of(
            RouteType.SEARCH,
            new RouteContract(
                    SEARCH_ROUTE, "hcm.operations.workforce.read", OPERATIONS_PREDICATE,
                    "TARGET_POPULATION", "hcm.people360.operations-page.v1",
                    "People360PageV1"),
            RouteType.DETAIL,
            new RouteContract(
                    DETAIL_ROUTE, "hcm.operations.workforce.read", OPERATIONS_PREDICATE,
                    "TARGET_POPULATION", "hcm.people360.operations-detail.v1",
                    "People360SnapshotV1"),
            RouteType.SELF,
            new RouteContract(
                    SELF_ROUTE, null, SELF_PREDICATE, "SUBJECT",
                    "hcm.people360.self-detail.v1", "People360SnapshotV1"),
            RouteType.TEAM,
            new RouteContract(
                    TEAM_ROUTE, null, TEAM_PREDICATE, "TARGET_POPULATION",
                    "hcm.people360.team-detail.v1", "People360SnapshotV1"));
    private static final Map<RouteType, Set<String>> ALLOWED_QUERY_PARAMETERS = Map.of(
            RouteType.SEARCH,
            Set.of("projection", "asOf", "query", "status", "cursor", "size"),
            RouteType.DETAIL, Set.of("projection", "asOf"),
            RouteType.SELF, Set.of("projection", "asOf"),
            RouteType.TEAM, Set.of("projection", "asOf", "personId"));

    private final ObjectMapper objectMapper;
    private final Supplier<HcmPepContext.Evidence> evidenceSupplier;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public People360OwnerAuthorityFilter(ObjectMapper objectMapper) {
        this(objectMapper, HcmPepContext::current, Clock.systemUTC());
    }

    People360OwnerAuthorityFilter(
            ObjectMapper objectMapper,
            Supplier<HcmPepContext.Evidence> evidenceSupplier,
            Clock clock) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.evidenceSupplier = Objects.requireNonNull(evidenceSupplier);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return candidate(request) == null || !request.getParameterMap().containsKey("projection");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        RouteType route = candidate(request);
        if (route == null || !validQuery(request, route)) {
            deny(response, ErrorCode.INVALID_INPUT_VALUE,
                    "The People 360 query contract is invalid.");
            return;
        }
        HcmPepContext.Evidence evidence;
        try {
            evidence = evidenceSupplier.get();
        } catch (RuntimeException exception) {
            evidence = null;
        }
        if (!current(evidence)) {
            deny(response, ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Current People 360 owner authority is unavailable.");
            return;
        }
        RouteContract contract = CONTRACTS.get(route);
        if (!matches(evidence.authority(), contract)) {
            deny(response, ErrorCode.FORBIDDEN,
                    "The exact People 360 route authority is required.");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private RouteType candidate(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) return null;
        String path = request.getRequestURI();
        if ("/v1/workforce/people".equals(path)) return RouteType.SEARCH;
        if (path != null && path.matches("/v1/workforce/people/[^/]+")) {
            return RouteType.DETAIL;
        }
        if ("/v1/hr/home".equals(path)) return RouteType.SELF;
        if ("/v1/hr/team".equals(path)) return RouteType.TEAM;
        return null;
    }

    private boolean validQuery(HttpServletRequest request, RouteType route) {
        if (!ALLOWED_QUERY_PARAMETERS.get(route).containsAll(request.getParameterMap().keySet())) {
            return false;
        }
        if (!single(request, "projection", "people360")
                || !canonicalDate(single(request, "asOf"))) {
            return false;
        }
        if (route == RouteType.TEAM && !canonicalUuid(single(request, "personId"))) {
            return false;
        }
        if (route == RouteType.DETAIL) {
            String id = request.getRequestURI().substring(
                    "/v1/workforce/people/".length());
            if (!canonicalUuid(id)) return false;
        }
        return request.getParameterMap().entrySet().stream()
                .allMatch(entry -> entry.getValue() != null && entry.getValue().length == 1);
    }

    private boolean matches(
            HcmV3PepRegistry.RouteAuthority authority,
            RouteContract contract) {
        return authority != null
                && contract.routeContractKey().equals(authority.routeContractKey())
                && "DATA".equals(authority.routeKind())
                && "full-work".equals(authority.profileKey())
                && Objects.equals(
                        contract.capabilityContractKey(), authority.capabilityContractKey())
                && authority.predicatePolicyKeys().contains(contract.predicatePolicyKey())
                && authority.targetBindingKinds().contains(contract.targetBindingKind())
                && contract.projectionPolicyKey().equals(authority.projectionPolicyKey())
                && contract.responseSchemaKey().equals(authority.responseSchemaKey());
    }

    private boolean current(HcmPepContext.Evidence evidence) {
        return evidence != null
                && evidence.revalidateAt() != null
                && evidence.revalidateAt().toInstant().isAfter(clock.instant())
                && evidence.rolloutState() != null
                && evidence.rolloutState().matches("[01]1[01]")
                && evidence.contextKey() != null
                && evidence.contextKey().matches("psc-[0-9a-f]{64}")
                && evidence.scopeKey() != null
                && evidence.scopeKey().matches("scope-[0-9a-f]{32}")
                && evidence.decisionRevision() != null
                && evidence.decisionRevision().matches("psr-[0-9a-f]{64}");
    }

    private String single(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        return values != null && values.length == 1 ? values[0] : null;
    }

    private boolean single(HttpServletRequest request, String name, String expected) {
        return expected.equals(single(request, name));
    }

    private boolean canonicalDate(String value) {
        try {
            return value != null && value.equals(LocalDate.parse(value).toString());
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    private boolean canonicalUuid(String value) {
        try {
            return value != null && value.equals(UUID.fromString(value).toString());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void deny(HttpServletResponse response, ErrorCode code, String message)
            throws IOException {
        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
    }

    private enum RouteType {
        SEARCH,
        DETAIL,
        SELF,
        TEAM
    }

    private record RouteContract(
            String routeContractKey,
            String capabilityContractKey,
            String predicatePolicyKey,
            String targetBindingKind,
            String projectionPolicyKey,
            String responseSchemaKey) {
    }
}
