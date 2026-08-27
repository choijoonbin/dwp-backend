package com.dwp.services.platform.savedview;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Exact, fail-closed entitlement policy for saved-view product surfaces. */
@Component
class SavedViewSurfaceAccessPolicy {

    static final String UNKNOWN_SURFACE_MESSAGE =
            "Saved views are not enabled for this product surface.";
    static final String ACTOR_NOT_ENTITLED_MESSAGE =
            "The current identity is not entitled to saved views for this product surface.";
    static final String TARGET_NOT_ENTITLED_MESSAGE =
            "The target user is not entitled to every affected saved-view surface.";

    private static final int MAX_PERMISSION_HEADER_LENGTH = 16_384;
    private static final int MAX_PERMISSION_KEYS = 512;

    private static final Map<String, String> VIEW_PERMISSION_BY_SURFACE = Map.of(
            "workspace.work", "APP.WORK:VIEW",
            "workspace.activity", "APP.ACTIVITY:VIEW",
            "workspace.apps", "APP.APPS:VIEW",
            "people.workforce-directory", "APP.PEOPLE_DIRECTORY:VIEW",
            "workforce.operations-overview", "APP.WORKFORCE_MANAGEMENT:VIEW",
            "calendar.schedule", "APP.CALENDAR:VIEW");

    void requireRead(String surfaceKey, String permissionHeader) {
        requireActor(surfaceKey, permissionHeader);
    }

    void requireWrite(String surfaceKey, String permissionHeader) {
        // Saving filters personalizes a surface; it does not mutate the underlying product data.
        requireActor(surfaceKey, permissionHeader);
    }

    void requireUse(String surfaceKey, String permissionHeader) {
        requireActor(surfaceKey, permissionHeader);
    }

    void requireEligibleTarget(
            String surfaceKey, SavedViewSubjectDirectory.Subject target) {
        String permissionKey = requiredPermission(surfaceKey);
        if (target == null || !target.hasPermission(permissionKey)) {
            throw new BaseException(
                    ErrorCode.SAVED_VIEW_TARGET_INELIGIBLE,
                    TARGET_NOT_ENTITLED_MESSAGE);
        }
    }

    boolean targetEntitled(
            String surfaceKey, SavedViewSubjectDirectory.Subject target) {
        return target != null && target.hasPermission(requiredPermission(surfaceKey));
    }

    String requiredPermission(String surfaceKey) {
        String normalized = surfaceKey == null
                ? "" : surfaceKey.strip().toLowerCase(Locale.ROOT);
        String permissionKey = VIEW_PERMISSION_BY_SURFACE.get(normalized);
        if (permissionKey == null) {
            throw new BaseException(ErrorCode.FORBIDDEN, UNKNOWN_SURFACE_MESSAGE);
        }
        return permissionKey;
    }

    private void requireActor(String surfaceKey, String permissionHeader) {
        String required = requiredPermission(surfaceKey);
        if (!authorities(permissionHeader).contains(required)) {
            throw new BaseException(ErrorCode.FORBIDDEN, ACTOR_NOT_ENTITLED_MESSAGE);
        }
    }

    private Set<String> authorities(String permissionHeader) {
        if (permissionHeader == null || permissionHeader.isBlank()
                || permissionHeader.length() > MAX_PERMISSION_HEADER_LENGTH) return Set.of();
        String[] values = permissionHeader.split(",", MAX_PERMISSION_KEYS + 1);
        if (values.length > MAX_PERMISSION_KEYS) return Set.of();
        return Arrays.stream(values)
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
