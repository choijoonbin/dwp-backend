package com.dwp.services.platform.calendar;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Enumeration;
import java.util.UUID;

final class CalendarSettingsAccess {

    static final String TENANT = "X-DWP-Tenant-ID";
    static final String USER = "X-DWP-User-ID";
    static final String PERSON = "X-DWP-Person-Public-ID";
    static final String CORRELATION = "X-Correlation-ID";

    private CalendarSettingsAccess() {
    }

    static Actor require(HttpServletRequest request) {
        Long tenantId = canonicalPositiveLong(exactRequired(request, TENANT));
        Long userId = canonicalPositiveLong(exactRequired(request, USER));
        UUID personPublicId = canonicalUuid(exactRequired(request, PERSON));
        if (tenantId == null || userId == null || personPublicId == null) throw denied();
        return new Actor(tenantId, userId, personPublicId);
    }

    static String correlationId(HttpServletRequest request) {
        Enumeration<String> headers = request.getHeaders(CORRELATION);
        if (headers == null || !headers.hasMoreElements()) return null;
        String value = headers.nextElement();
        if (headers.hasMoreElements() || value == null || value.isBlank()
                || value.length() > 160 || !value.equals(value.trim())
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw denied();
        }
        return value;
    }

    private static String exactRequired(HttpServletRequest request, String name) {
        Enumeration<String> headers = request.getHeaders(name);
        if (headers == null || !headers.hasMoreElements()) throw denied();
        String value = headers.nextElement();
        if (headers.hasMoreElements() || value == null || value.isBlank()
                || value.length() > 100 || !value.equals(value.trim())
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
                "A verified tenant user and person identity is required for Calendar settings.");
    }

    record Actor(long tenantId, long userId, UUID personPublicId) {
    }
}
