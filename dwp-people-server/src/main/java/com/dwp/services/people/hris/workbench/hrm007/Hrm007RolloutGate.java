package com.dwp.services.people.hris.workbench.hrm007;

import com.dwp.services.people.security.PeopleRequestContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Tenant activation gate. It must run before all authority, cursor, and source work. */
@Component
@ConditionalOnProperty(prefix = Hrm007RolloutGate.PREFIX, name = "enabled",
        havingValue = "true", matchIfMissing = false)
final class Hrm007RolloutGate {

    static final String FEATURE = "HRIS_HRM_APPOINTMENT_WORKBENCH";
    static final String PREFIX = "dwp.hris.hrm007";

    private final Set<Long> enrolledTenants;

    Hrm007RolloutGate(
            @Value("${dwp.hris.hrm007.enrolled-tenants:}") String enrolledTenants) {
        this.enrolledTenants = parse(enrolledTenants);
    }

    long requireEnrolledTenant() {
        PeopleRequestContext.Actor actor;
        try {
            actor = PeopleRequestContext.require();
        } catch (RuntimeException exception) {
            throw Hrm007Exception.denied("Trusted People request context is unavailable.");
        }
        if (actor.tenantId() == null || actor.tenantId() < 1
                || actor.userId() == null || actor.userId() < 1
                || actor.personPublicId() == null
                || !enrolledTenants.contains(actor.tenantId())) {
            throw Hrm007Exception.denied("The HRM-007 tenant is not enrolled.");
        }
        return actor.tenantId();
    }

    boolean isEmpty() {
        return enrolledTenants.isEmpty();
    }

    private Set<Long> parse(String source) {
        if (source == null || source.isBlank()) return Set.of();
        try {
            Set<Long> result = Arrays.stream(source.split(",", -1))
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .map(Long::valueOf)
                    .filter(value -> value > 0)
                    .collect(Collectors.toUnmodifiableSet());
            long nonBlank = Arrays.stream(source.split(",", -1))
                    .map(String::trim).filter(value -> !value.isEmpty()).count();
            if (result.size() != nonBlank) {
                throw new IllegalArgumentException(
                        "HRM-007 enrolled tenants must be unique positive identifiers.");
            }
            return result;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "HRM-007 enrolled tenants must be positive identifiers.", exception);
        }
    }
}
