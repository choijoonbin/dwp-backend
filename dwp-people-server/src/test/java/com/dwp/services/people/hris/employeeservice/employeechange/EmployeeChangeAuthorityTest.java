package com.dwp.services.people.hris.employeeservice.employeechange;

import com.dwp.services.people.security.PeopleRequestContext;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EmployeeChangeAuthorityTest {

    private final EmployeeChangeAuthority authority = new EmployeeChangeAuthority();

    @Test
    void exposesActionsOnlyForExactResolvedCapabilityCodes() {
        EmployeeChangeAuthority.ActionTruth operations = authority.actionTruth(actor(Set.of(
                "DATA.HRIS_WORKFORCE:UPDATE")));
        EmployeeChangeAuthority.ActionTruth approver = authority.actionTruth(actor(Set.of(
                "ACTION.HRIS_WORKFORCE_CHANGE_APPROVE:APPROVE")));
        EmployeeChangeAuthority.ActionTruth teamOrSelfOnly = authority.actionTruth(actor(Set.of(
                "ACTION.HRIS_PEOPLE_CHANGE_REQUEST:CREATE",
                "DATA.HRIS_SELF_PROFILE:UPDATE")));
        EmployeeChangeAuthority.ActionTruth unrelated = authority.actionTruth(actor(Set.of(
                "DATA.HRIS_WORKFORCE:VIEW", "ROLE.HRIS_ADMIN:VIEW")));

        assertThat(operations).isEqualTo(
                new EmployeeChangeAuthority.ActionTruth(true, true, false));
        assertThat(approver).isEqualTo(
                new EmployeeChangeAuthority.ActionTruth(false, false, true));
        assertThat(teamOrSelfOnly).isEqualTo(EmployeeChangeAuthority.ActionTruth.none());
        assertThat(unrelated).isEqualTo(EmployeeChangeAuthority.ActionTruth.none());
    }

    private PeopleRequestContext.Actor actor(Set<String> permissions) {
        return new PeopleRequestContext.Actor(
                19L, 77L, UUID.randomUUID(), Set.of("HRIS_OPERATOR"), permissions);
    }
}
