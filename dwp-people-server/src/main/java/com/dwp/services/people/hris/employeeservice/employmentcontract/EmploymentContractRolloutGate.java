package com.dwp.services.people.hris.employeeservice.employmentcontract;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Default-OFF and zero-enrollment gate for the unmounted HRM-011 slice. */
@Component
@ConditionalOnProperty(prefix = EmploymentContractRolloutGate.PREFIX,
        name = "enabled", havingValue = "true", matchIfMissing = false)
public final class EmploymentContractRolloutGate {
    public static final String PREFIX = "dwp.hris.contract-compensation-workbench";
    public static final String FEATURE = "HRIS_HRM_CONTRACT_COMPENSATION_WORKBENCH";

    private final Set<Long> tenants;

    @Autowired
    public EmploymentContractRolloutGate(
            @Value("${dwp.hris.contract-compensation-workbench.enrolled-tenants:}")
            String tenants) {
        this(parse(tenants));
    }

    public EmploymentContractRolloutGate(Set<Long> tenants) {
        this.tenants = Set.copyOf(tenants);
        if (this.tenants.stream().anyMatch(value -> value == null || value < 1)) {
            throw new IllegalArgumentException("HRM-011 tenant enrollment is invalid.");
        }
    }

    public void requireEnrolled(Long tenantId) {
        if (tenantId == null || !tenants.contains(tenantId)) {
            throw EmploymentContractException.denied(
                    "HRM-011 is not enrolled for the trusted tenant.");
        }
    }

    public boolean enrolled(long tenantId) {
        return tenants.contains(tenantId);
    }

    private static Set<Long> parse(String value) {
        if (value == null || value.isBlank()) return Set.of();
        try {
            return Arrays.stream(value.split(","))
                    .map(String::trim).filter(item -> !item.isBlank())
                    .map(Long::valueOf).collect(Collectors.toUnmodifiableSet());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "HRM-011 tenant enrollment is invalid.", exception);
        }
    }
}
