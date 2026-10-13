package com.dwp.services.people.hris.employment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmV3PepRegistry;
import com.dwp.services.people.security.PeopleRequestContext;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;

/** Owner-side exact-route recheck for HRM-008 public operations. */
@Component
final class EmploymentEventAuthority {

    static final String SELF_READ_CAPABILITY = "hcm.personal.profile.view";
    static final String OPERATIONS_READ_CAPABILITY = "hcm.operations.people.view";
    static final String MANAGE_CAPABILITY = "hcm.operations.assignment.manage";
    static final String PUBLISH_CAPABILITY = "hcm.operations.people-change.approve";
    static final String RECEIPT_CAPABILITY = "hcm.command-receipt.view";

    static final String RECEIPT_BINDING_ID = "PEP-HRM-014";
    static final String EVENT_DETAIL_BINDING_ID = "PEP-HRM-015";

    static final String SELF_WORKBENCH_ROUTE =
            "route.hcm.personal.employment-documents.page";
    static final String OPERATIONS_WORKBENCH_ROUTE =
            "route.hcm.operations.employment-assignments.page";
    static final String EVENT_DETAIL_ROUTE =
            "route.hcm.operations.assignment-event-detail.data";
    static final String RECEIPT_ROUTE =
            "route.hcm.operations.assignment-command-receipt.data";
    static final String CREATE_ROUTE =
            "route.hcm.operations.assignment-event-create.action";
    static final String VALIDATE_ROUTE =
            "route.hcm.operations.assignment-event-validate.action";
    static final String SUBMIT_ROUTE =
            "route.hcm.operations.assignment-event-submit.action";
    static final String PUBLISH_ROUTE =
            "route.hcm.operations.assignment-event-publish.action";

    private static final Map<String, Expected> EXPECTED = Map.of(
            "SELF_WORKBENCH", new Expected(
                    SELF_WORKBENCH_ROUTE, Set.of(SELF_READ_CAPABILITY), "GET", true),
            "OPERATIONS_WORKBENCH", new Expected(
                    OPERATIONS_WORKBENCH_ROUTE, Set.of(OPERATIONS_READ_CAPABILITY), "GET", true),
            "EVENT_DETAIL", new Expected(
                    EVENT_DETAIL_ROUTE, Set.of(OPERATIONS_READ_CAPABILITY, MANAGE_CAPABILITY),
                    "GET", true),
            "RECEIPT", new Expected(
                    RECEIPT_ROUTE, Set.of(RECEIPT_CAPABILITY), "GET", true),
            "CREATE", new Expected(CREATE_ROUTE, Set.of(MANAGE_CAPABILITY), "POST", false),
            "VALIDATE", new Expected(VALIDATE_ROUTE, Set.of(MANAGE_CAPABILITY), "POST", false),
            "SUBMIT", new Expected(SUBMIT_ROUTE, Set.of(MANAGE_CAPABILITY), "POST", false),
            "PUBLISH", new Expected(PUBLISH_ROUTE, Set.of(PUBLISH_CAPABILITY), "POST", false));

    String require(String operation) {
        Expected expected = EXPECTED.get(operation);
        if (expected == null) throw new IllegalArgumentException(
                "Unknown employment operation: " + operation);
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        if (actor.tenantId() == null || actor.tenantId() < 1
                || actor.userId() == null || actor.userId() < 1
                || actor.personPublicId() == null) {
            throw new BaseException(ErrorCode.UNAUTHORIZED,
                    "A tenant-bound People identity is required.");
        }
        HcmPepContext.Evidence evidence = HcmPepContext.current();
        if (evidence == null || evidence.revalidateAt() == null
                || !evidence.revalidateAt().isAfter(OffsetDateTime.now())
                || evidence.authority() == null) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Exact governed HRM authority is required.");
        }
        HcmV3PepRegistry.RouteAuthority authority = evidence.authority();
        if (!expected.route().equals(authority.routeContractKey())
                || !expected.capabilities().contains(authority.capabilityContractKey())
                || !expected.method().equals(authority.method())
                || expected.readOnly() != authority.readOnly()) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The exact HCM route does not authorize this employment operation.");
        }
        return authority.capabilityContractKey();
    }

    private record Expected(
            String route,
            Set<String> capabilities,
            String method,
            boolean readOnly) {
    }
}
