package com.dwp.services.people.hr.assignment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.PeopleRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssignmentProposalAuthorityTest {

    private final AssignmentProposalAuthority authority =
            new AssignmentProposalAuthority();

    @AfterEach
    void clear() {
        ReflectionTestUtils.invokeMethod(HcmPepContext.class, "clear");
        PeopleRequestContext.clear();
    }

    @Test
    void submitNeverFallsBackToLegacyWorkforcePermission() {
        PeopleRequestContext.set(
                17L, 3L, Set.of("HR_ADMIN"), Set.of("DATA.WORKFORCE:MANAGE"));

        assertThatThrownBy(() -> authority.requireCommand("SUBMIT"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }

    @Test
    void draftCommandsRetainExplicitPermissionCompatibility() {
        PeopleRequestContext.set(
                17L, 3L, Set.of(), Set.of("DATA.WORKFORCE:MANAGE"));

        authority.requireCommand("CREATE");
        authority.requireCommand("VALIDATE");
        authority.requireCommand("CANCEL");
    }
}
