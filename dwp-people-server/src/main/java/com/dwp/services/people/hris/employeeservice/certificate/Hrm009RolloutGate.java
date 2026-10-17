package com.dwp.services.people.hris.employeeservice.certificate;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Default-OFF owner boundary for the unmounted HRM-009 Stage-A slice. */
@Component
@ConditionalOnProperty(prefix = Hrm009RolloutGate.PREFIX, name = "enabled",
        havingValue = "true", matchIfMissing = false)
final class Hrm009RolloutGate {

    static final String PREFIX = "dwp.hris.certificate-workbench";
    static final String FEATURE = "HRIS_HRM_CERTIFICATE_WORKBENCH";

    private final Set<Long> enrolledTenants;

    Hrm009RolloutGate(
            @Value("${dwp.hris.certificate-workbench.enrolled-tenants:}")
            String enrolledTenants) {
        this(parse(enrolledTenants));
    }

    Hrm009RolloutGate(Set<Long> enrolledTenants) {
        this.enrolledTenants = Set.copyOf(enrolledTenants);
        if (this.enrolledTenants.stream().anyMatch(value -> value == null || value < 1)) {
            throw new IllegalArgumentException("HRM-009 tenant enrollment is invalid.");
        }
    }

    void requireEnrolled(Long tenantId) {
        if (tenantId == null || !enrolledTenants.contains(tenantId)) {
            throw CertificateException.denied(
                    "HRM-009 is not enrolled for the trusted tenant.");
        }
    }

    boolean enrolled(long tenantId) {
        return enrolledTenants.contains(tenantId);
    }

    private static Set<Long> parse(String value) {
        if (value == null || value.isBlank()) return Set.of();
        try {
            return Arrays.stream(value.split(","))
                    .map(String::trim)
                    .filter(item -> !item.isBlank())
                    .map(Long::valueOf)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "HRM-009 tenant enrollment is invalid.", exception);
        }
    }
}
