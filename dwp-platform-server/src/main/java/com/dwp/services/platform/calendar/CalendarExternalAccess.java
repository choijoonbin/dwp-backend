package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Enumeration;
import java.util.UUID;

/** Exact, duplicate-resistant identity extraction for the private external-calendar surface. */
final class CalendarExternalAccess {

    private CalendarExternalAccess() {
    }

    static Actor require(HttpServletRequest request) {
        long tenantId = positiveLong(exact(request, "X-DWP-Tenant-ID"));
        long userId = positiveLong(exact(request, "X-DWP-User-ID"));
        UUID personPublicId = uuid(exact(request, "X-DWP-Person-Public-ID"));
        return new Actor(tenantId, userId, personPublicId);
    }

    private static String exact(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) throw denied();
        String value = values.nextElement();
        if (values.hasMoreElements() || value == null || value.isBlank()
                || value.length() > 100 || !value.equals(value.trim())
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw denied();
        }
        return value;
    }

    private static long positiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0 || !Long.toString(parsed).equals(value)) throw denied();
            return parsed;
        } catch (NumberFormatException exception) {
            throw denied();
        }
    }

    private static UUID uuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (parsed.equals(new UUID(0L, 0L)) || !parsed.toString().equals(value)) throw denied();
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw denied();
        }
    }

    private static BaseException denied() {
        return new BaseException(
                ErrorCode.FORBIDDEN,
                "A verified tenant user and person identity is required for external calendars.");
    }

    record Actor(long tenantId, long userId, UUID personPublicId) {
    }
}
