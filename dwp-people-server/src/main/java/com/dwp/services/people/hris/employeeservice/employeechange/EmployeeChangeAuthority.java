package com.dwp.services.people.hris.employeeservice.employeechange;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.HrisEmployeeChangeSecurityBridge;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmV3PepRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/** Rechecks the exact v37 People route decision at the owner boundary. */
@Component
@ConditionalOnProperty(prefix = "dwp.hris.employee-change", name = "enabled",
        havingValue = "true")
final class EmployeeChangeAuthority {

    static final String READ_CAPABILITY = "hcm.operations.people.view";
    static final String APPLY_CAPABILITY = "hcm.operations.people-change.approve";
    private static final Set<String> CREATE_CAPABILITIES = Set.of(
            "hcm.operations.people.update",
            "hcm.team.people-change.request",
            "hcm.personal.profile.update");
    private static final Map<String, Expected> ROUTES = Map.of(
            "READ", new Expected(
                    HrisEmployeeChangeSecurityBridge.REQUESTS_PAGE_ROUTE,
                    Set.of(READ_CAPABILITY), "GET", true),
            "CREATE", new Expected(
                    HrisEmployeeChangeSecurityBridge.CREATE_ROUTE,
                    CREATE_CAPABILITIES, "POST", false),
            "SUBMIT", new Expected(
                    HrisEmployeeChangeSecurityBridge.SUBMIT_ROUTE,
                    CREATE_CAPABILITIES, "POST", false),
            "APPLY", new Expected(
                    HrisEmployeeChangeSecurityBridge.APPLY_ROUTE,
                    Set.of(APPLY_CAPABILITY), "POST", false));

    String require(String operation) {
        Expected expected = ROUTES.get(operation);
        if (expected == null) throw new IllegalArgumentException("Unknown operation: " + operation);
        HcmPepContext.Evidence evidence = HcmPepContext.current();
        if (evidence == null) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Exact governed HCM authority is required for employee change.");
        }
        HcmV3PepRegistry.RouteAuthority authority = evidence.authority();
        if (authority == null
                || !expected.routeContractKey().equals(authority.routeContractKey())
                || !expected.capabilities().contains(authority.capabilityContractKey())
                || !expected.method().equals(authority.method())
                || expected.readOnly() != authority.readOnly()) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The exact HCM route does not authorize this employee-change operation.");
        }
        return authority.capabilityContractKey();
    }

    private record Expected(
            String routeContractKey,
            Set<String> capabilities,
            String method,
            boolean readOnly) {
    }
}
