package com.dwp.services.platform.workplace;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.dwp.services.platform.workplace.WorkplaceDelegatedAdminScopeRepository.SiteTargetType;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.DelegatedPermission;

final class WorkplaceDelegatedAdminRoutePolicy {

    enum ScopeMode {
        GLOBAL_ONLY,
        SITE_LIST,
        SITE_QUERY,
        POLICY_SCOPE_QUERY,
        TARGET_AND_POLICY_SCOPE_QUERY,
        TARGET_SITE,
        ANY_DELEGATED_SCOPE
    }

    record Target(String variable, SiteTargetType type) {
    }

    record Match(
            DelegatedPermission permission,
            ScopeMode scopeMode,
            List<Target> targets,
            Map<String, String> variables) {
    }

    private record Rule(
            HttpMethod method,
            PathPattern pattern,
            DelegatedPermission permission,
            ScopeMode scopeMode,
            List<Target> targets) {

        Optional<Match> match(HttpMethod candidateMethod, PathContainer path) {
            if (method != candidateMethod) return Optional.empty();
            PathPattern.PathMatchInfo info = pattern.matchAndExtract(path);
            if (info == null) return Optional.empty();
            return Optional.of(new Match(
                    permission, scopeMode, targets, info.getUriVariables()));
        }
    }

    private static final PathPatternParser PATHS = new PathPatternParser();
    private static final List<Rule> RULES = rules();

    private WorkplaceDelegatedAdminRoutePolicy() {
    }

    static Optional<Match> match(HttpServletRequest request) {
        HttpMethod method;
        try {
            method = HttpMethod.valueOf("HEAD".equals(request.getMethod())
                    ? "GET" : request.getMethod());
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
        PathContainer path = PathContainer.parsePath(request.getRequestURI());
        return RULES.stream()
                .map(rule -> rule.match(method, path))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static List<Rule> rules() {
        List<Rule> rules = new ArrayList<>();

        global(rules, HttpMethod.GET, "/v1/admin/workplace/overview");
        siteList(rules, HttpMethod.GET, "/v1/admin/workplace/sites",
                DelegatedPermission.CATALOG_VIEW);
        global(rules, HttpMethod.POST, "/v1/admin/workplace/sites");
        target(rules, HttpMethod.PUT, "/v1/admin/workplace/sites/{siteId}",
                DelegatedPermission.CATALOG_MANAGE, site("siteId"));
        siteQuery(rules, HttpMethod.GET, "/v1/admin/workplace/floors",
                DelegatedPermission.CATALOG_VIEW);
        target(rules, HttpMethod.POST, "/v1/admin/workplace/sites/{siteId}/floors",
                DelegatedPermission.CATALOG_MANAGE, site("siteId"));
        target(rules, HttpMethod.PUT,
                "/v1/admin/workplace/sites/{siteId}/floors/{floorId}",
                DelegatedPermission.CATALOG_MANAGE, site("siteId"), floor("floorId"));
        target(rules, HttpMethod.POST, "/v1/admin/workplace/floors/{floorId}/background",
                DelegatedPermission.CATALOG_MANAGE, floor("floorId"));
        target(rules, HttpMethod.GET, "/v1/admin/workplace/floors/{floorId}/resources",
                DelegatedPermission.CATALOG_VIEW, floor("floorId"));
        target(rules, HttpMethod.POST, "/v1/admin/workplace/floors/{floorId}/resources",
                DelegatedPermission.CATALOG_MANAGE, floor("floorId"));
        target(rules, HttpMethod.PUT,
                "/v1/admin/workplace/floors/{floorId}/resources/{resourceId}",
                DelegatedPermission.CATALOG_MANAGE,
                floor("floorId"), target("resourceId", SiteTargetType.RESOURCE));
        target(rules, HttpMethod.PUT, "/v1/admin/workplace/floors/{floorId}/layout",
                DelegatedPermission.CATALOG_MANAGE, floor("floorId"));
        global(rules, HttpMethod.GET, "/v1/admin/workplace/policy");
        global(rules, HttpMethod.PUT, "/v1/admin/workplace/policy");

        global(rules, HttpMethod.GET, "/v1/admin/workplace/bookings");
        global(rules, HttpMethod.PUT,
                "/v1/admin/workplace/bookings/{bookingId}/force-cancel");
        global(rules, HttpMethod.PUT,
                "/v1/admin/workplace/bookings/{bookingId}/legal-hold");
        global(rules, HttpMethod.GET, "/v1/admin/workplace/audit-events");

        siteList(rules, HttpMethod.GET, "/v1/admin/workplace/governance/campuses",
                DelegatedPermission.CATALOG_VIEW);
        global(rules, HttpMethod.POST, "/v1/admin/workplace/governance/campuses");
        global(rules, HttpMethod.PUT,
                "/v1/admin/workplace/governance/campuses/{campusId}");
        global(rules, HttpMethod.PUT,
                "/v1/admin/workplace/governance/sites/{siteId}/campus");
        target(rules, HttpMethod.GET,
                "/v1/admin/workplace/governance/floors/{floorId}/zones",
                DelegatedPermission.CATALOG_VIEW, floor("floorId"));
        target(rules, HttpMethod.POST,
                "/v1/admin/workplace/governance/floors/{floorId}/zones",
                DelegatedPermission.CATALOG_MANAGE, floor("floorId"));
        target(rules, HttpMethod.PUT,
                "/v1/admin/workplace/governance/floors/{floorId}/zones/{zoneId}",
                DelegatedPermission.CATALOG_MANAGE,
                floor("floorId"), target("zoneId", SiteTargetType.ZONE));
        target(rules, HttpMethod.GET,
                "/v1/admin/workplace/governance/zones/{zoneId}/sections",
                DelegatedPermission.CATALOG_VIEW, target("zoneId", SiteTargetType.ZONE));
        target(rules, HttpMethod.POST,
                "/v1/admin/workplace/governance/zones/{zoneId}/sections",
                DelegatedPermission.CATALOG_MANAGE, target("zoneId", SiteTargetType.ZONE));
        target(rules, HttpMethod.PUT,
                "/v1/admin/workplace/governance/zones/{zoneId}/sections/{sectionId}",
                DelegatedPermission.CATALOG_MANAGE,
                target("zoneId", SiteTargetType.ZONE),
                target("sectionId", SiteTargetType.SECTION));
        target(rules, HttpMethod.GET,
                "/v1/admin/workplace/governance/sites/{siteId}/access-rules",
                DelegatedPermission.ACCESS_MANAGE, site("siteId"));
        target(rules, HttpMethod.POST,
                "/v1/admin/workplace/governance/sites/{siteId}/access-rules",
                DelegatedPermission.ACCESS_MANAGE, site("siteId"));
        target(rules, HttpMethod.PUT,
                "/v1/admin/workplace/governance/sites/{siteId}/access-rules/{ruleId}",
                DelegatedPermission.ACCESS_MANAGE,
                site("siteId"), target("ruleId", SiteTargetType.ACCESS_RULE));
        target(rules, HttpMethod.GET,
                "/v1/admin/workplace/governance/sites/{siteId}/access-preview",
                DelegatedPermission.ACCESS_MANAGE, site("siteId"));
        policyScopeQuery(rules, HttpMethod.GET,
                "/v1/admin/workplace/governance/policy-overrides",
                DelegatedPermission.POLICY_MANAGE);
        policyScopeQuery(rules, HttpMethod.POST,
                "/v1/admin/workplace/governance/policy-overrides",
                DelegatedPermission.POLICY_MANAGE);
        targetAndPolicyScopeQuery(rules, HttpMethod.PUT,
                "/v1/admin/workplace/governance/policy-overrides/{overrideId}",
                DelegatedPermission.POLICY_MANAGE,
                target("overrideId", SiteTargetType.POLICY_OVERRIDE));
        policyScopeQuery(rules, HttpMethod.GET,
                "/v1/admin/workplace/governance/policy-preview",
                DelegatedPermission.POLICY_MANAGE);
        target(rules, HttpMethod.GET,
                "/v1/admin/workplace/governance/floors/{floorId}/floor-plan-revisions",
                DelegatedPermission.FLOOR_PLAN_MANAGE, floor("floorId"));
        target(rules, HttpMethod.POST,
                "/v1/admin/workplace/governance/floors/{floorId}/floor-plan-revisions",
                DelegatedPermission.FLOOR_PLAN_MANAGE, floor("floorId"));
        target(rules, HttpMethod.GET,
                "/v1/admin/workplace/governance/floor-plan-revisions/{revisionId}/snapshot",
                DelegatedPermission.FLOOR_PLAN_MANAGE,
                target("revisionId", SiteTargetType.FLOOR_PLAN_REVISION));
        target(rules, HttpMethod.PUT,
                "/v1/admin/workplace/governance/floor-plan-revisions/{revisionId}",
                DelegatedPermission.FLOOR_PLAN_MANAGE,
                target("revisionId", SiteTargetType.FLOOR_PLAN_REVISION));
        for (String transition : List.of("review", "publish", "restore")) {
            target(rules, HttpMethod.POST,
                    "/v1/admin/workplace/governance/floor-plan-revisions/{revisionId}/"
                            + transition,
                    DelegatedPermission.FLOOR_PLAN_MANAGE,
                    target("revisionId", SiteTargetType.FLOOR_PLAN_REVISION));
        }
        target(rules, HttpMethod.GET,
                "/v1/admin/workplace/governance/floors/{floorId}/projection",
                DelegatedPermission.FLOOR_PLAN_MANAGE, floor("floorId"));
        global(rules, HttpMethod.GET,
                "/v1/admin/workplace/governance/delegated-admin-scopes");
        global(rules, HttpMethod.POST,
                "/v1/admin/workplace/governance/delegated-admin-scopes");
        global(rules, HttpMethod.PUT,
                "/v1/admin/workplace/governance/delegated-admin-scopes/{delegationId}");
        anyScope(rules, HttpMethod.GET,
                "/v1/admin/workplace/governance/delegated-admin-scopes/effective",
                null);

        return List.copyOf(rules);
    }

    private static Target site(String variable) {
        return target(variable, SiteTargetType.SITE);
    }

    private static Target floor(String variable) {
        return target(variable, SiteTargetType.FLOOR);
    }

    private static Target target(String variable, SiteTargetType type) {
        return new Target(variable, type);
    }

    private static void global(List<Rule> rules, HttpMethod method, String path) {
        rules.add(rule(method, path, null, ScopeMode.GLOBAL_ONLY));
    }

    private static void siteList(
            List<Rule> rules,
            HttpMethod method,
            String path,
            DelegatedPermission permission) {
        rules.add(rule(method, path, permission, ScopeMode.SITE_LIST));
    }

    private static void siteQuery(
            List<Rule> rules,
            HttpMethod method,
            String path,
            DelegatedPermission permission) {
        rules.add(rule(method, path, permission, ScopeMode.SITE_QUERY));
    }

    private static void policyScopeQuery(
            List<Rule> rules,
            HttpMethod method,
            String path,
            DelegatedPermission permission) {
        rules.add(rule(method, path, permission, ScopeMode.POLICY_SCOPE_QUERY));
    }

    private static void anyScope(
            List<Rule> rules,
            HttpMethod method,
            String path,
            DelegatedPermission permission) {
        rules.add(rule(method, path, permission, ScopeMode.ANY_DELEGATED_SCOPE));
    }

    private static void targetAndPolicyScopeQuery(
            List<Rule> rules,
            HttpMethod method,
            String path,
            DelegatedPermission permission,
            Target... targets) {
        rules.add(rule(method, path, permission,
                ScopeMode.TARGET_AND_POLICY_SCOPE_QUERY, targets));
    }

    private static void target(
            List<Rule> rules,
            HttpMethod method,
            String path,
            DelegatedPermission permission,
            Target... targets) {
        rules.add(rule(method, path, permission, ScopeMode.TARGET_SITE, targets));
    }

    private static Rule rule(
            HttpMethod method,
            String path,
            DelegatedPermission permission,
            ScopeMode mode,
            Target... targets) {
        return new Rule(method, PATHS.parse(path), permission, mode, List.of(targets));
    }
}
