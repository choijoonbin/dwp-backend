package com.dwp.services.provider.support;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.util.List;
import java.util.Locale;

public final class ProviderSupportAccessPolicy {

    private static final List<String> PLATFORM_CONFIGURATION_PATHS = List.of(
            "/api/platform/v1/admin/tenant-branding",
            "/api/platform/v1/admin/home-experience",
            "/api/platform/v1/admin/announcements");

    private static final List<String> PEOPLE_READ_PATHS = List.of(
            "/api/people/v1/people",
            "/api/people/v1/org-chart",
            "/api/people/v1/workforce");

    private ProviderSupportAccessPolicy() {
    }

    public static String requiredScope(String method, String resourcePath) {
        String normalizedMethod = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        String normalizedPath = resourcePath == null ? "" : resourcePath.trim();
        boolean read = "GET".equals(normalizedMethod) || "HEAD".equals(normalizedMethod);

        if (matches(normalizedPath, PLATFORM_CONFIGURATION_PATHS)) {
            return read ? "TENANT_CONFIGURATION_READ" : "TENANT_CONFIGURATION_WRITE";
        }
        if (read && matches(normalizedPath, PEOPLE_READ_PATHS)) {
            return "WORKFORCE_READ";
        }
        throw new BaseException(
                ErrorCode.FORBIDDEN,
                "The support session does not permit this resource.");
    }

    private static boolean matches(String path, List<String> prefixes) {
        return prefixes.stream().anyMatch(prefix ->
                path.equals(prefix) || path.startsWith(prefix + "/"));
    }
}
