package com.dwp.services.time.close;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Pre-storage activation and tenant-enrollment probe for close-owned provider seams. */
@FunctionalInterface
public interface TimeCloseActivationGate {
    boolean allows(long tenantId);
}

@Component
@ConditionalOnProperty(
        name = "dwp.hris.time.close.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class ConfiguredTimeCloseActivationGate implements TimeCloseActivationGate {

    private final Set<Long> enrolledTenants;

    ConfiguredTimeCloseActivationGate(
            @Value("${dwp.hris.time.close.enrolled-tenants:}") String enrolledTenants) {
        this.enrolledTenants = parse(enrolledTenants);
    }

    @Override
    public boolean allows(long tenantId) {
        return tenantId > 0 && enrolledTenants.contains(tenantId);
    }

    private static Set<Long> parse(String value) {
        if (value == null || value.isBlank()) return Set.of();
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        for (String token : value.split(",", -1)) {
            try {
                long tenantId = Long.parseLong(token.strip());
                if (tenantId <= 0 || !result.add(tenantId)) {
                    throw new IllegalArgumentException();
                }
            } catch (RuntimeException invalid) {
                throw new IllegalStateException(
                        "dwp.hris.time.close.enrolled-tenants is invalid", invalid);
            }
        }
        return Set.copyOf(result);
    }
}
