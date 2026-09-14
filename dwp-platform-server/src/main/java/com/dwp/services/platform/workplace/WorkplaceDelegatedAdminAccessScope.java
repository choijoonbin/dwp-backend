package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.DelegatedPermission;

/** Internal trusted scope. Never bound from a request body or returned as a public DTO. */
public final class WorkplaceDelegatedAdminAccessScope {
    record Principal(long tenantId, Long userId, Set<UUID> verifiedGroups, boolean global) {
        Principal { verifiedGroups = Set.copyOf(verifiedGroups); }
    }

    private final Principal principal;
    private final UUID siteId;
    private final DelegatedPermission permission;
    private final Set<UUID> floorIds;

    WorkplaceDelegatedAdminAccessScope(Principal principal, UUID siteId,
            DelegatedPermission permission, Set<UUID> floorIds) {
        if (principal == null || siteId == null || permission == null || (floorIds != null && floorIds.isEmpty()))
            throw forbidden();
        this.principal = principal;
        this.siteId = siteId;
        this.permission = permission;
        this.floorIds = floorIds == null ? null : Set.copyOf(floorIds);
    }

    Principal principal() { return principal; }
    public long tenantId() { return principal.tenantId(); }
    public UUID siteId() { return siteId; }
    public DelegatedPermission permission() { return permission; }
    /** Null means a verified whole-site grant for this permission; otherwise a nonempty whitelist. */
    public Set<UUID> floorIds() { return floorIds; }
    public boolean restricted() { return floorIds != null; }

    public void requireFloor(UUID floorId) {
        if (floorId == null || (floorIds != null && !floorIds.contains(floorId))) throw forbidden();
    }

    public void requireSiteWide() {
        if (restricted()) throw forbidden();
    }

    private static BaseException forbidden() {
        return new BaseException(ErrorCode.FORBIDDEN, "The delegated administrator floor scope does not permit this operation.");
    }
}
