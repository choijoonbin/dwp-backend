package com.dwp.services.people.hr.assignment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmV3PepRegistry;
import com.dwp.services.people.security.PeopleRequestContext;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class AssignmentProposalAuthority {

    public static final String READ_CAPABILITY = "hcm.operations.workforce.read";
    public static final String CREATE_CAPABILITY =
            "hcm.operations.assignment-proposal.create";
    public static final String VALIDATE_CAPABILITY =
            "hcm.operations.assignment-proposal.validate";
    public static final String SUBMIT_CAPABILITY =
            "hcm.operations.assignment-proposal.submit";
    public static final String CANCEL_CAPABILITY =
            "hcm.operations.assignment-proposal.cancel";

    public static final String ASSIGNMENT_DETAIL_ROUTE =
            "route.hcm.operations.assignment-detail.data";
    public static final String ASSIGNMENT_TIMELINE_ROUTE =
            "route.hcm.operations.assignment-timeline.data";
    public static final String PROPOSAL_DETAIL_ROUTE =
            "route.hcm.operations.assignment-proposal-detail.data";
    public static final String CREATE_ROUTE =
            "route.hcm.operations.assignment-proposal-create.action";
    public static final String VALIDATE_ROUTE =
            "route.hcm.operations.assignment-proposal-validate.action";
    public static final String SUBMIT_ROUTE =
            "route.hcm.operations.assignment-proposal-submit.action";
    public static final String CANCEL_ROUTE =
            "route.hcm.operations.assignment-proposal-cancel.action";

    private static final Map<String, ExpectedAuthority> COMMANDS = Map.of(
            "CREATE", new ExpectedAuthority(CREATE_ROUTE, CREATE_CAPABILITY, "POST"),
            "VALIDATE", new ExpectedAuthority(VALIDATE_ROUTE, VALIDATE_CAPABILITY, "POST"),
            "SUBMIT", new ExpectedAuthority(SUBMIT_ROUTE, SUBMIT_CAPABILITY, "POST"),
            "CANCEL", new ExpectedAuthority(CANCEL_ROUTE, CANCEL_CAPABILITY, "POST"));

    public void requireRead(String routeContractKey) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        requirePermissionOrExactPep(actor, routeContractKey, READ_CAPABILITY, "GET", false);
    }

    public void requireCommand(String action) {
        ExpectedAuthority expected = COMMANDS.get(action);
        if (expected == null) {
            throw new IllegalArgumentException("Unknown assignment proposal action: " + action);
        }
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        if ("SUBMIT".equals(action) && HcmPepContext.current() == null) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Exact governed HCM authority is required to submit an assignment proposal.");
        }
        requirePermissionOrExactPep(
                actor, expected.routeContractKey(), expected.capabilityContractKey(),
                expected.method(), true);
    }

    private void requirePermissionOrExactPep(
            PeopleRequestContext.Actor actor,
            String routeContractKey,
            String capabilityContractKey,
            String method,
            boolean mutation) {
        HcmPepContext.Evidence evidence = HcmPepContext.current();
        if (evidence != null) {
            HcmV3PepRegistry.RouteAuthority authority = evidence.authority();
            if (authority == null
                    || !routeContractKey.equals(authority.routeContractKey())
                    || !capabilityContractKey.equals(authority.capabilityContractKey())
                    || !method.equals(authority.method())
                    || (mutation && authority.readOnly())) {
                throw forbidden("The exact HCM route does not authorize this assignment operation.");
            }
            return;
        }
        boolean permitted = mutation
                ? actor.hasPermission("DATA.WORKFORCE", "MANAGE")
                : actor.hasPermission("DATA.WORKFORCE", "VIEW", "MANAGE");
        if (!permitted) {
            throw forbidden(mutation
                    ? "Workforce management permission is required."
                    : "Workforce read permission is required.");
        }
    }

    private BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }

    private record ExpectedAuthority(
            String routeContractKey,
            String capabilityContractKey,
            String method) {
    }
}
