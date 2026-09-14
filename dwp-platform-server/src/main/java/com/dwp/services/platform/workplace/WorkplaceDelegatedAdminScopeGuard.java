package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.dwp.services.platform.workplace.WorkplaceDelegatedAdminRoutePolicy.ScopeMode;
import static com.dwp.services.platform.workplace.WorkplaceDelegatedAdminScopeRepository.DelegatedGrant;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.DelegatedPermission;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.DelegatedScopeType;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.PolicyScopeType;

@Component
class WorkplaceDelegatedAdminScopeGuard {

    static final String ALLOWED_SITE_IDS_ATTRIBUTE =
            WorkplaceDelegatedAdminScopeGuard.class.getName() + ".allowedSiteIds";

    private static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    private static final String USER_HEADER = "X-DWP-User-ID";
    private static final String ROLES_HEADER = "X-DWP-Roles";
    private static final String GROUP_REFS_HEADER = "X-DWP-Group-Refs";
    private static final Set<String> GLOBAL_ROLES =
            Set.of("ADMIN", "TENANT_ADMIN", "PLATFORM_ADMIN");

    private final WorkplaceDelegatedAdminScopeRepository repository;
    private final Clock clock;

    @Autowired
    WorkplaceDelegatedAdminScopeGuard(WorkplaceDelegatedAdminScopeRepository repository) {
        this(repository, Clock.systemUTC());
    }

    WorkplaceDelegatedAdminScopeGuard(
            WorkplaceDelegatedAdminScopeRepository repository,
            Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    void authorize(HttpServletRequest request) {
        if (isGlobalAdministrator(request)) return;

        Long tenantId = positiveLong(request.getHeader(TENANT_HEADER));
        Long userId = positiveLong(request.getHeader(USER_HEADER));
        if (tenantId == null || userId == null) throw forbidden();

        WorkplaceDelegatedAdminRoutePolicy.Match route =
                WorkplaceDelegatedAdminRoutePolicy.match(request)
                        .orElseThrow(this::forbidden);
        if (route.scopeMode() == ScopeMode.GLOBAL_ONLY) throw forbidden();

        Set<UUID> groupRefs = verifiedGroupRefs(request.getHeader(GROUP_REFS_HEADER));
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<DelegatedGrant> grants = repository.candidateGrants(
                        tenantId, userId, groupRefs).stream()
                .filter(grant -> matchesSubject(grant, userId, groupRefs))
                .filter(grant -> active(grant, now))
                .toList();

        switch (route.scopeMode()) {
            case SITE_LIST -> authorizeSiteList(request, grants, route.permission());
            case ANY_DELEGATED_SCOPE -> requireAnyScope(grants, route.permission());
            case SITE_QUERY -> requireSite(
                    tenantId, request.getParameter("siteId"), WorkplaceDelegatedAdminTargetType.SITE,
                    grants, route.permission());
            case POLICY_SCOPE_QUERY -> requirePolicyScope(
                    tenantId, request, grants, route.permission());
            case TARGET_AND_POLICY_SCOPE_QUERY -> {
                requireTargets(tenantId, route, grants);
                requirePolicyScope(tenantId, request, grants, route.permission());
            }
            case TARGET_SITE, SITE_CONTEXT -> requireTargets(tenantId, route, grants);
            case GLOBAL_ONLY -> throw forbidden();
        }
    }

    List<WorkplaceDtos.Site> filterVisibleSites(
            HttpServletRequest request,
            List<WorkplaceDtos.Site> sites) {
        if (isGlobalAdministrator(request)) return sites;
        Object attribute = request.getAttribute(ALLOWED_SITE_IDS_ATTRIBUTE);
        if (!(attribute instanceof Set<?> values)) throw forbidden();
        Set<UUID> allowed = values.stream()
                .filter(UUID.class::isInstance)
                .map(UUID.class::cast)
                .collect(Collectors.toUnmodifiableSet());
        if (allowed.isEmpty()) throw forbidden();
        return sites.stream().filter(site -> allowed.contains(site.siteId())).toList();
    }

    Set<UUID> visibleSiteIds(HttpServletRequest request) {
        if (isGlobalAdministrator(request)) return null;
        Object attribute = request.getAttribute(ALLOWED_SITE_IDS_ATTRIBUTE);
        if (!(attribute instanceof Set<?> values)) throw forbidden();
        Set<UUID> allowed = values.stream()
                .filter(UUID.class::isInstance)
                .map(UUID.class::cast)
                .collect(Collectors.toUnmodifiableSet());
        if (allowed.isEmpty()) throw forbidden();
        return allowed;
    }

    private void authorizeSiteList(
            HttpServletRequest request,
            List<DelegatedGrant> grants,
            DelegatedPermission permission) {
        Set<UUID> allowed = grants.stream()
                .filter(grant -> grant.scopeType() == DelegatedScopeType.SITE)
                .filter(grant -> permits(grant, permission))
                .map(DelegatedGrant::siteId)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
        if (allowed.isEmpty()) throw forbidden();
        request.setAttribute(ALLOWED_SITE_IDS_ATTRIBUTE, allowed);
    }

    private void requireAnyScope(
            List<DelegatedGrant> grants,
            DelegatedPermission permission) {
        if (grants.stream()
                .filter(grant -> grant.scopeType() == DelegatedScopeType.SITE)
                .noneMatch(grant -> permission == null
                        ? !grant.permissions().isEmpty()
                        : permits(grant, permission))) {
            throw forbidden();
        }
    }

    private void requireTargets(
            Long tenantId,
            WorkplaceDelegatedAdminRoutePolicy.Match route,
            List<DelegatedGrant> grants) {
        if (grants.stream().anyMatch(grant -> grant.floorIds() != null)) {
            List<WorkplaceDelegatedAdminScopeRepository.CanonicalTarget> targets = route.targets().stream()
                    .map(target -> repository.resolveTarget(tenantId, target.type(), uuid(route.variables().get(target.variable())))
                            .orElseThrow(this::forbidden)).toList();
            Set<UUID> sites = targets.stream().map(WorkplaceDelegatedAdminScopeRepository.CanonicalTarget::siteId).collect(Collectors.toSet());
            Set<UUID> floors = targets.stream().map(WorkplaceDelegatedAdminScopeRepository.CanonicalTarget::floorId)
                    .filter(Objects::nonNull).collect(Collectors.toSet());
            if (sites.size() != 1 || floors.size() > 1) throw forbidden();
            UUID site = sites.iterator().next();
            for (int i = 0; i < targets.size(); i++) {
                var target = targets.get(i);
                boolean parentContext = route.targets().get(i).type() == WorkplaceDelegatedAdminTargetType.SITE
                        && (!floors.isEmpty() || route.scopeMode() == ScopeMode.SITE_CONTEXT);
                requireScopedGrant(grants, site, target.floorId(), target.siteWide() && !parentContext, route.permission());
            }
            return;
        }
        Set<UUID> resolvedSites = new LinkedHashSet<>();
        for (WorkplaceDelegatedAdminRoutePolicy.Target target : route.targets()) {
            String rawId = route.variables().get(target.variable());
            UUID targetId = uuid(rawId);
            UUID siteId = repository.resolveSite(tenantId, target.type(), targetId)
                    .orElseThrow(this::forbidden);
            resolvedSites.add(siteId);
        }
        if (resolvedSites.size() != 1) throw forbidden();
        requireGrant(grants, resolvedSites.iterator().next(), route.permission());
    }

    private void requirePolicyScope(
            Long tenantId,
            HttpServletRequest request,
            List<DelegatedGrant> grants,
            DelegatedPermission permission) {
        PolicyScopeType scopeType;
        try {
            scopeType = PolicyScopeType.valueOf(request.getParameter("scopeType"));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw forbidden();
        }
        WorkplaceDelegatedAdminTargetType targetType = switch (scopeType) {
            case SITE -> WorkplaceDelegatedAdminTargetType.SITE;
            case FLOOR -> WorkplaceDelegatedAdminTargetType.FLOOR;
            case ZONE -> WorkplaceDelegatedAdminTargetType.ZONE;
            case RESOURCE -> WorkplaceDelegatedAdminTargetType.RESOURCE;
            case TENANT, CAMPUS -> throw forbidden();
        };
        requireSite(tenantId, request.getParameter("scopeId"), targetType, grants, permission);
    }

    private void requireSite(
            Long tenantId,
            String rawTargetId,
            WorkplaceDelegatedAdminTargetType targetType,
            List<DelegatedGrant> grants,
            DelegatedPermission permission) {
        if (grants.stream().anyMatch(grant -> grant.floorIds() != null) && targetType != WorkplaceDelegatedAdminTargetType.SITE) {
            var target = repository.resolveTarget(tenantId, targetType, uuid(rawTargetId)).orElseThrow(this::forbidden);
            requireScopedGrant(grants, target.siteId(), target.floorId(), target.siteWide(), permission);
            return;
        }
        UUID siteId = repository.resolveSite(tenantId, targetType, uuid(rawTargetId))
                .orElseThrow(this::forbidden);
        requireGrant(grants, siteId, permission);
    }

    private void requireGrant(
            List<DelegatedGrant> grants,
            UUID siteId,
            DelegatedPermission permission) {
        boolean allowed = grants.stream()
                .filter(grant -> grant.scopeType() == DelegatedScopeType.SITE)
                .filter(grant -> Objects.equals(grant.siteId(), siteId))
                .anyMatch(grant -> permits(grant, permission));
        if (!allowed) throw forbidden();
    }

    private void requireScopedGrant(List<DelegatedGrant> grants, UUID siteId, UUID floorId,
            boolean siteWide, DelegatedPermission permission) {
        if (grants.stream().filter(grant -> grant.scopeType() == DelegatedScopeType.SITE)
                .filter(grant -> Objects.equals(grant.siteId(), siteId) && permits(grant, permission))
                .noneMatch(grant -> grant.floorIds() == null || (!siteWide && (floorId == null || grant.floorIds().contains(floorId)))))
            throw forbidden();
    }

    private boolean permits(DelegatedGrant grant, DelegatedPermission required) {
        return WorkplaceDelegatedPermissionRules.permits(grant.permissions(), required);
    }

    WorkplaceDelegatedAdminAccessScope scope(HttpServletRequest request, UUID siteId, DelegatedPermission permission) {
        var principal = principal(request);
        return effectiveScope(principal, siteId, permission,
                principal.global() ? List.of() : repository.candidateGrants(principal.tenantId(), principal.userId(), principal.verifiedGroups()),
                OffsetDateTime.now(clock));
    }

    WorkplaceDelegatedAdminAccessScope scopeForTarget(HttpServletRequest request, WorkplaceDelegatedAdminTargetType type,
            UUID targetId, DelegatedPermission permission) {
        var principal = principal(request);
        var target = repository.resolveTarget(principal.tenantId(), type, targetId).orElseThrow(this::forbidden);
        var scope = scope(request, target.siteId(), permission);
        if (target.siteWide()) scope.requireSiteWide(); else scope.requireFloor(target.floorId());
        return scope;
    }

    List<WorkplaceDelegatedAdminAccessScope> visibleScopes(HttpServletRequest request, DelegatedPermission permission) {
        var principal = principal(request);
        List<DelegatedGrant> grants = principal.global() ? List.of()
                : repository.candidateGrants(principal.tenantId(), principal.userId(), principal.verifiedGroups());
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<UUID> sites = principal.global() ? repository.tenantSiteIds(principal.tenantId())
                : grants.stream().filter(grant -> matchesSubject(grant, principal.userId(), principal.verifiedGroups()))
                    .filter(grant -> active(grant, now) && permits(grant, permission))
                    .filter(grant -> grant.scopeType() == DelegatedScopeType.SITE).map(DelegatedGrant::siteId)
                    .filter(Objects::nonNull).distinct().sorted().toList();
        if (!principal.global() && sites.isEmpty()) throw forbidden();
        return sites.stream().map(site -> effectiveScope(principal, site, permission, grants, now)).toList();
    }

    WorkplaceDelegatedAdminAccessScope revalidate(WorkplaceDelegatedAdminAccessScope requested) {
        if (requested == null) throw forbidden();
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Delegated scope revalidation requires an actual writable owner transaction.");
        var principal = requested.principal();
        List<DelegatedGrant> grants = principal.global() ? List.of()
                : repository.candidateGrants(principal.tenantId(), principal.userId(), principal.verifiedGroups(), true);
        OffsetDateTime now = repository.databaseNow();
        if (now == null) throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "The database authority clock is unavailable.");
        return effectiveScope(principal, requested.siteId(), requested.permission(), grants, now);
    }

    void requireTarget(WorkplaceDelegatedAdminAccessScope scope, WorkplaceDelegatedAdminTargetType type, UUID id) {
        if (scope == null) throw forbidden();
        var target = repository.resolveTarget(scope.tenantId(), type, id).orElseThrow(this::forbidden);
        if (!Objects.equals(scope.siteId(), target.siteId())) throw forbidden();
        if (target.siteWide()) scope.requireSiteWide(); else scope.requireFloor(target.floorId());
    }

    private WorkplaceDelegatedAdminAccessScope effectiveScope(WorkplaceDelegatedAdminAccessScope.Principal principal,
            UUID siteId, DelegatedPermission permission, List<DelegatedGrant> candidates, OffsetDateTime now) {
        if (siteId == null || permission == null || repository.resolveTarget(principal.tenantId(), WorkplaceDelegatedAdminTargetType.SITE, siteId).isEmpty())
            throw forbidden();
        if (principal.global()) return new WorkplaceDelegatedAdminAccessScope(principal, siteId, permission, null);
        List<DelegatedGrant> grants = candidates.stream()
                .filter(grant -> matchesSubject(grant, principal.userId(), principal.verifiedGroups()))
                .filter(grant -> active(grant, now) && grant.scopeType() == DelegatedScopeType.SITE)
                .filter(grant -> Objects.equals(grant.siteId(), siteId) && permits(grant, permission)).toList();
        if (grants.isEmpty()) throw forbidden();
        if (grants.stream().anyMatch(grant -> grant.floorIds() == null))
            return new WorkplaceDelegatedAdminAccessScope(principal, siteId, permission, null);
        Set<UUID> floors = grants.stream().flatMap(grant -> grant.floorIds().stream()).collect(Collectors.toUnmodifiableSet());
        if (floors.isEmpty()) throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "A restricted scope has no persisted floors.");
        return new WorkplaceDelegatedAdminAccessScope(principal, siteId, permission, floors);
    }

    private WorkplaceDelegatedAdminAccessScope.Principal principal(HttpServletRequest request) {
        if (request == null) throw forbidden();
        Long tenant = positiveLong(request.getHeader(TENANT_HEADER));
        Long actor = positiveLong(request.getHeader(USER_HEADER));
        boolean global = isGlobalAdministrator(request);
        if (tenant == null || (!global && actor == null)) throw forbidden();
        return new WorkplaceDelegatedAdminAccessScope.Principal(tenant, actor,
                global ? Set.of() : verifiedGroupRefs(request.getHeader(GROUP_REFS_HEADER)), global);
    }

    private boolean active(DelegatedGrant grant, OffsetDateTime now) {
        return (grant.validFrom() == null || !grant.validFrom().isAfter(now))
                && (grant.validUntil() == null || grant.validUntil().isAfter(now));
    }

    private boolean matchesSubject(
            DelegatedGrant grant,
            Long userId,
            Set<UUID> verifiedGroupRefs) {
        return switch (grant.delegateType()) {
            case USER -> Objects.equals(grant.delegateUserId(), userId)
                    && grant.delegateGroupRef() == null;
            case GROUP_REF -> grant.delegateUserId() == null
                    && verifiedGroupRefs.contains(grant.delegateGroupRef());
        };
    }

    boolean globalAdministrator(HttpServletRequest request) { return isGlobalAdministrator(request); }

    private boolean isGlobalAdministrator(HttpServletRequest request) {
        String roles = request.getHeader(ROLES_HEADER);
        if (roles == null || roles.isBlank()) return false;
        return Arrays.stream(roles.split(","))
                .map(String::trim)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .anyMatch(GLOBAL_ROLES::contains);
    }

    private Set<UUID> verifiedGroupRefs(String header) {
        if (header == null || header.isBlank()) return Set.of();
        try {
            return Arrays.stream(header.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .map(UUID::fromString)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IllegalArgumentException exception) {
            throw forbidden();
        }
    }

    private UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw forbidden();
        }
    }

    private Long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException | NullPointerException exception) {
            return null;
        }
    }

    private BaseException forbidden() {
        return new BaseException(
                ErrorCode.FORBIDDEN,
                "The delegated Workplace administration scope does not permit this operation.");
    }
}
