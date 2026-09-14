package com.dwp.services.platform.workplace;

import java.util.Set;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.DelegatedPermission;

final class WorkplaceDelegatedPermissionRules {
    private WorkplaceDelegatedPermissionRules() { }

    static boolean permits(Set<DelegatedPermission> permissions, DelegatedPermission required) {
        if (permissions.contains(required)) return true;
        return required == DelegatedPermission.CATALOG_VIEW && permissions.stream().anyMatch(permission -> switch (permission) {
            case CATALOG_MANAGE, ACCESS_MANAGE, POLICY_MANAGE, FLOOR_PLAN_MANAGE -> true;
            case CATALOG_VIEW, DELEGATION_VIEW -> false;
        });
    }
}
