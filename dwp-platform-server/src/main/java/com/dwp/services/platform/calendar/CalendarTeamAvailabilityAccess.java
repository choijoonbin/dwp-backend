package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.RolePlaneBoundary;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Additional member-only boundary, after PlatformSecurityFilter verifies the gateway token. */
final class CalendarTeamAvailabilityAccess {
    private CalendarTeamAvailabilityAccess() { }

    record Actor(long tenantId, long userId, UUID personPublicId, Set<UUID> groupRefs) { }

    static Actor require(HttpServletRequest request) {
        long tenantId = positiveLong(exact(request, "X-DWP-Tenant-ID", true));
        long userId = positiveLong(exact(request, "X-DWP-User-ID", true));
        UUID personId = uuid(exact(request, "X-DWP-Person-Public-ID", true));
        if (!"TENANT".equals(exact(request, "X-DWP-Identity-Plane", true))) throw denied();
        Set<String> roles = tokens(exact(request, "X-DWP-Roles", true), 100);
        Set<String> permissions = tokens(exact(request, "X-DWP-Permissions", true), 500);
        if (!roles.contains("WORKSPACE_MEMBER") || RolePlaneBoundary.isProviderIdentity(roles)
                || request.getHeader("X-DWP-Support-Session-ID") != null
                || !permissions.contains("APP.CALENDAR:VIEW")
                || !permissions.contains("APP.PEOPLE_DIRECTORY:VIEW")) throw denied();
        String groups = exact(request, "X-DWP-Group-Refs", false);
        Set<UUID> groupRefs = new LinkedHashSet<>();
        if (groups != null) tokens(groups, 200).forEach(value -> groupRefs.add(uuid(value)));
        return new Actor(tenantId, userId, personId, Set.copyOf(groupRefs));
    }

    private static String exact(HttpServletRequest request, String name, boolean required) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) {
            if (required) throw denied();
            return null;
        }
        String value = values.nextElement();
        if (values.hasMoreElements() || value == null || value.isBlank()
                || value.length() > 32_000 || !value.equals(value.trim())
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) throw denied();
        return value;
    }

    private static Set<String> tokens(String value, int maximum) {
        String[] parts = value.split(",", -1);
        if (parts.length > maximum) throw denied();
        Set<String> result = new LinkedHashSet<>();
        Arrays.stream(parts).forEach(part -> {
            if (part.isBlank() || !part.equals(part.trim()) || !result.add(part)) throw denied();
        });
        return Set.copyOf(result);
    }

    private static long positiveLong(String value) {
        try {
            if (!value.matches("[1-9][0-9]*")) throw denied();
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw denied();
        }
    }

    private static UUID uuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value) || parsed.equals(new UUID(0, 0))) throw denied();
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw denied();
        }
    }

    static BaseException denied() {
        return new BaseException(ErrorCode.FORBIDDEN, "Shared calendar member access is unavailable.");
    }
}
