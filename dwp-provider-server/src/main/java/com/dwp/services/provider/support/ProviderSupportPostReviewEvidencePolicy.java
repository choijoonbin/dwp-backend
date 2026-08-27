package com.dwp.services.provider.support;

import java.util.regex.Pattern;

final class ProviderSupportPostReviewEvidencePolicy {

    static final String PREVIEW_ROUTE =
            "/api/platform/v1/admin/tenant-experience-preview";
    static final String SAFE_DENIAL_ROUTE_PATTERN =
            "^/api/([a-z][a-z0-9-]{0,39}/\\*\\*|\\*\\*)$";
    static final String CANONICAL_CORRELATION_PATTERN =
            "^([0-9a-f]{32}|sha256:[0-9a-f]{64})$";
    private static final Pattern CANONICAL_CORRELATION =
            Pattern.compile(CANONICAL_CORRELATION_PATTERN);

    private ProviderSupportPostReviewEvidencePolicy() {
    }

    static boolean canonicalCorrelation(String value) {
        return value != null && CANONICAL_CORRELATION.matcher(value).matches();
    }
}
