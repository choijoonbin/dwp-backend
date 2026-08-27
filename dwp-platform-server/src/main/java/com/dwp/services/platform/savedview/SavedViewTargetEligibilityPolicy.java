package com.dwp.services.platform.savedview;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Evaluates successor eligibility without exposing roles, groups, or raw permission evidence. */
@Component
class SavedViewTargetEligibilityPolicy {

    static final String IDENTITY_NOT_ELIGIBLE = "IDENTITY_NOT_ELIGIBLE";
    static final String MISSING_SURFACE_ACCESS = "MISSING_SURFACE_ACCESS";
    static final String MISSING_TEAM_MEMBERSHIP = "MISSING_TEAM_MEMBERSHIP";
    static final String MISSING_SHARED_VIEW_ADMIN_ROLE = "MISSING_SHARED_VIEW_ADMIN_ROLE";

    private static final Set<String> SHARED_VIEW_ROLES = Set.of(
            "ADMIN", "TENANT_ADMIN", "PLATFORM_ADMIN");

    private final SavedViewSurfaceAccessPolicy surfaces;

    SavedViewTargetEligibilityPolicy(SavedViewSurfaceAccessPolicy surfaces) {
        this.surfaces = surfaces;
    }

    List<String> reasons(
            List<SavedViewRepository.Row> views,
            SavedViewSubjectDirectory.Subject target) {
        if (target == null || !target.active() || !target.tenantPlane()) {
            return List.of(IDENTITY_NOT_ELIGIBLE);
        }
        Set<String> reasons = new LinkedHashSet<>();
        for (SavedViewRepository.Row view : views) {
            if (!surfaces.targetEntitled(view.surfaceKey(), target)) {
                reasons.add(MISSING_SURFACE_ACCESS);
            }
            if ("TEAM".equals(view.scope()) && !target.belongsTo(view.ownerGroupRef())) {
                reasons.add(MISSING_TEAM_MEMBERSHIP);
            }
            if ("TENANT".equals(view.scope()) && !target.hasAnyRole(SHARED_VIEW_ROLES)) {
                reasons.add(MISSING_SHARED_VIEW_ADMIN_ROLE);
            }
        }
        return List.copyOf(reasons);
    }

    void require(
            List<SavedViewRepository.Row> views,
            SavedViewSubjectDirectory.Subject target) {
        List<String> reasons = reasons(views, target);
        if (reasons.isEmpty()) return;
        if (reasons.contains(IDENTITY_NOT_ELIGIBLE)) {
            throw new BaseException(
                    ErrorCode.SAVED_VIEW_TARGET_INELIGIBLE,
                    "Saved views can only be transferred to an active tenant user.");
        }
        if (reasons.contains(MISSING_SURFACE_ACCESS)) {
            throw new BaseException(
                    ErrorCode.SAVED_VIEW_TARGET_INELIGIBLE,
                    SavedViewSurfaceAccessPolicy.TARGET_NOT_ENTITLED_MESSAGE);
        }
        if (reasons.contains(MISSING_TEAM_MEMBERSHIP)) {
            throw new BaseException(
                    ErrorCode.SAVED_VIEW_TARGET_INELIGIBLE,
                    "The target user must belong to every team that owns an affected view.");
        }
        throw new BaseException(
                ErrorCode.SAVED_VIEW_TARGET_INELIGIBLE,
                "The target user must be a tenant shared-view administrator.");
    }
}
