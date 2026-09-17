package com.dwp.services.platform.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Trusted gateway-to-Platform DEVICE-plane contract. */
public final class PlatformDeviceIdentity {
    public static final String PLANE_HEADER = "X-DWP-Identity-Plane";
    public static final String CREDENTIAL_HEADER = "X-DWP-Device-Credential";
    public static final String PLANE = "DEVICE";

    private static final String DEVICE_PREFIX = "/v1/device/workplace/devices";
    private static final String KIOSK_PREFIX = "/v1/workplace/kiosk";

    private PlatformDeviceIdentity() {
    }

    public static boolean owns(String method, String path) {
        if (method == null || path == null) return false;
        if ("POST".equals(method) && path.equals(DEVICE_PREFIX + ":register")) return true;
        if (path.startsWith(DEVICE_PREFIX + "/")) {
            String suffix = path.substring((DEVICE_PREFIX + "/").length());
            int separator = suffix.indexOf('/');
            if (separator <= 0 || !uuid(suffix.substring(0, separator))) return false;
            String operation = suffix.substring(separator + 1);
            return ("POST".equals(method) && operation.equals("heartbeat"))
                    || ("POST".equals(method) && operation.equals("access-pass:pair"))
                    || ("GET".equals(method) && operation.equals("projection"));
        }
        if ("GET".equals(method) && path.equals(KIOSK_PREFIX + "/session")) return true;
        if (path.startsWith(KIOSK_PREFIX + "/visits/")) {
            String suffix = path.substring((KIOSK_PREFIX + "/visits/").length());
            int action = suffix.indexOf(':');
            String id = action < 0 ? suffix : suffix.substring(0, action);
            if (!uuid(id)) return false;
            if (action < 0) return "GET".equals(method);
            String operation = suffix.substring(action + 1);
            return "POST".equals(method)
                    && (operation.equals("arrive") || operation.equals("checkout"));
        }
        if ("POST".equals(method) && path.startsWith(KIOSK_PREFIX + "/devices/")) {
            String suffix = path.substring((KIOSK_PREFIX + "/devices/").length());
            int action = suffix.indexOf(':');
            return action > 0 && uuid(suffix.substring(0, action))
                    && (suffix.substring(action + 1).equals("heartbeat")
                    || suffix.substring(action + 1).equals("help"));
        }
        return false;
    }

    public static boolean validCredential(String value) {
        if (value == null || value.length() < 32 || value.length() > 512) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x21 || character > 0x7e || character == ',') return false;
        }
        return true;
    }

    static String exactHeader(HttpServletRequest request, String name) {
        java.util.Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) return null;
        String value = values.nextElement();
        if (values.hasMoreElements() || value == null || value.isBlank()
                || !value.equals(value.trim()) || value.indexOf(',') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            return null;
        }
        return value;
    }

    static Long canonicalTenant(HttpServletRequest request, String tenantHeader) {
        String value = exactHeader(request, tenantHeader);
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 && Long.toString(parsed).equals(value) ? parsed : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static boolean hasConflictingEvidence(HttpServletRequest request) {
        Set<String> allowed = Set.of(
                PlatformSecurityHeaders.SERVICE_TOKEN.toLowerCase(Locale.ROOT),
                PlatformSecurityHeaders.TENANT.toLowerCase(Locale.ROOT),
                PLANE_HEADER.toLowerCase(Locale.ROOT),
                CREDENTIAL_HEADER.toLowerCase(Locale.ROOT));
        return Collections.list(request.getHeaderNames()).stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .anyMatch(name -> name.startsWith("x-dwp-") && !allowed.contains(name));
    }

    private static boolean uuid(String value) {
        try {
            return UUID.fromString(value).toString().equals(value.toLowerCase(Locale.ROOT));
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
