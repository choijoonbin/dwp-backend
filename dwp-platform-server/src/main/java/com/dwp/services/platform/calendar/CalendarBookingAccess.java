package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Enumeration;
import java.util.Set;
import java.util.UUID;

final class CalendarBookingAccess {

    static final String TENANT = "X-DWP-Tenant-ID";
    static final String USER = "X-DWP-User-ID";
    static final String PERSON = "X-DWP-Person-Public-ID";
    static final String GROUPS = "X-DWP-Group-Refs";
    static final String CORRELATION = "X-Correlation-ID";
    static final String LOCALE = "Accept-Language";

    private CalendarBookingAccess() {
    }

    static Actor require(HttpServletRequest request) {
        if (request == null || !request.getRequestURI().startsWith("/v1/calendar/booking")) {
            throw denied();
        }
        Long tenantId = canonicalPositiveLong(exactRequired(request, TENANT));
        Long userId = canonicalPositiveLong(exactRequired(request, USER));
        UUID personPublicId = canonicalUuid(exactRequired(request, PERSON));
        if (tenantId == null || userId == null || personPublicId == null) throw denied();
        String groupRefs = exactOptional(request, GROUPS, 7_400);
        Set<UUID> verifiedGroups = CalendarVerifiedGroups.parse(groupRefs);
        return new Actor(tenantId, userId, personPublicId, groupRefs, verifiedGroups);
    }

    static String correlationId(HttpServletRequest request) {
        return exactOptional(request, CORRELATION, 160);
    }

    static String locale(HttpServletRequest request) {
        return exactOptional(request, LOCALE, 80);
    }

    private static String exactRequired(HttpServletRequest request, String name) {
        String value = exactOptional(request, name, 100);
        if (value == null) throw denied();
        return value;
    }

    private static String exactOptional(HttpServletRequest request, String name, int maximum) {
        Enumeration<String> headers = request.getHeaders(name);
        if (headers == null || !headers.hasMoreElements()) return null;
        String value = headers.nextElement();
        if (headers.hasMoreElements() || value == null || value.isBlank()
                || value.length() > maximum || !value.equals(value.trim())
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw denied();
        }
        return value;
    }

    private static Long canonicalPositiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 && Long.toString(parsed).equals(value) ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static UUID canonicalUuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equals(value) ? parsed : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    static BaseException denied() {
        return new BaseException(
                ErrorCode.FORBIDDEN,
                "A verified tenant user and person identity is required for Calendar booking.");
    }

    record Actor(
            long tenantId,
            long userId,
            UUID personPublicId,
            String groupRefs,
            Set<UUID> verifiedGroups) {

        boolean hasGroup(UUID groupRef) {
            return groupRef != null && verifiedGroups.contains(groupRef);
        }
    }
}
