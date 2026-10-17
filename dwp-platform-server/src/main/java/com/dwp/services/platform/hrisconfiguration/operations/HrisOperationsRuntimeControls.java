package com.dwp.services.platform.hrisconfiguration.operations;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

@Component
@ConditionalOnProperty(
        name = "dwp.hris.operations-exception-center.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class HrisOperationsRuntimeControls {

    static final String FEATURE = "HRIS_OPERATIONS_EXCEPTION_CENTER";

    private final boolean enabled;
    private final Set<Long> enrolledTenants;

    HrisOperationsRuntimeControls(
            @Value("${dwp.hris.operations-exception-center.enabled:false}")
            boolean enabled,
            @Value("${dwp.hris.operations-exception-center.enrolled-tenants:}")
            String enrolledTenants) {
        this.enabled = enabled;
        this.enrolledTenants = parse(enrolledTenants);
    }

    boolean enrolled(long tenantId) {
        return enabled && tenantId > 0 && enrolledTenants.contains(tenantId);
    }

    void requireEnrolled(long tenantId) {
        if (!enrolled(tenantId)) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The requested HRIS operations resource is not available");
        }
    }

    int enrollmentCount() {
        return enrolledTenants.size();
    }

    private static Set<Long> parse(String value) {
        if (value == null || value.isBlank()) return Set.of();
        try {
            Set<Long> parsed = new LinkedHashSet<>();
            for (String token : value.split(",", -1)) {
                String candidate = token.trim();
                if (candidate.isEmpty() || !candidate.equals(token)) {
                    throw new IllegalArgumentException();
                }
                long tenant = Long.parseLong(candidate);
                if (tenant <= 0 || !parsed.add(tenant)) {
                    throw new IllegalArgumentException();
                }
            }
            return Set.copyOf(parsed);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "HRIS operations enrolled tenants must be unique positive integers",
                    exception);
        }
    }
}
