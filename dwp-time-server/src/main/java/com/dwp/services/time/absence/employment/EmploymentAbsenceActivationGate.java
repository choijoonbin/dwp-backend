package com.dwp.services.time.absence.employment;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Both a global default-false switch and explicit tenant enrollment are mandatory. */
public final class EmploymentAbsenceActivationGate {

    private final boolean enabled;
    private final Set<Long> enrolledTenants;

    public EmploymentAbsenceActivationGate(boolean enabled, String enrolledTenants) {
        this(enabled, parse(enrolledTenants));
    }

    EmploymentAbsenceActivationGate(boolean enabled, Set<Long> enrolledTenants) {
        this.enabled = enabled;
        this.enrolledTenants = Set.copyOf(enrolledTenants);
    }

    public boolean permits(long tenantId) {
        return enabled && tenantId > 0 && enrolledTenants.contains(tenantId);
    }

    public boolean enabled() {
        return enabled;
    }

    public Set<Long> enrolledTenants() {
        return enrolledTenants;
    }

    private static Set<Long> parse(String value) {
        if (value == null || value.isBlank()) return Set.of();
        try {
            Set<Long> parsed = Arrays.stream(value.split(",", -1))
                    .map(String::strip)
                    .map(Long::parseLong)
                    .peek(tenant -> {
                        if (tenant <= 0) throw new IllegalArgumentException();
                    })
                    .collect(Collectors.toUnmodifiableSet());
            if (parsed.size() != value.split(",", -1).length) {
                throw new IllegalArgumentException("duplicate tenant enrollment");
            }
            return parsed;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "dwp.hris.time.employment-absence.enrolled-tenants is invalid",
                    exception);
        }
    }
}
