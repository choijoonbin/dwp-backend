package com.dwp.services.provider.support;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.util.Locale;

public final class ProviderSupportAccessPolicy {

    public static final String EXECUTABLE_SCOPE = "TENANT_EXPERIENCE_PREVIEW";
    private static final String EXPERIENCE_PREVIEW_PATH =
            "/api/platform/v1/admin/tenant-experience-preview";

    private ProviderSupportAccessPolicy() {
    }

    public static String requiredScope(String method, String resourcePath) {
        String normalizedMethod = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        String normalizedPath = resourcePath == null ? "" : resourcePath.trim();
        if (normalizedPath.equals(EXPERIENCE_PREVIEW_PATH)) {
            if ("GET".equals(normalizedMethod)) return EXECUTABLE_SCOPE;
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "The tenant experience preview is read-only.");
        }
        throw new BaseException(
                ErrorCode.FORBIDDEN,
                "The support session does not permit this resource.");
    }

}
