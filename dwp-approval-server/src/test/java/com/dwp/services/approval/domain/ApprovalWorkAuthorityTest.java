package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.security.ApprovalRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class ApprovalWorkAuthorityTest {
    private static final long USER_ID = 17L;
    private static final long TENANT_ID = 42L;
    private static final UUID PERSON_ID = UUID.randomUUID();
    private static final String VIEW = "ADMIN.APPROVAL_OPERATIONS:VIEW";
    private static final String EXECUTE = "ADMIN.APPROVAL_OPERATIONS:EXECUTE";
    private static final String MANAGE = "ADMIN.APPROVAL_OPERATIONS:MANAGE";

    private final ApprovalIdentityDirectory identities = mock(ApprovalIdentityDirectory.class);
    private final ApprovalWorkAuthority authority = new ApprovalWorkAuthority(identities);

    @AfterEach
    void clearContext() {
        ApprovalRequestContext.clear();
    }

    @Test
    void currentActorUsesTheExactRoleAndPermissionIntersection() {
        actor(Set.of("APPROVAL_ADMIN", "APPROVAL_AUDITOR"),
                Set.of(VIEW, EXECUTE, MANAGE));
        doReturn(subject(
                "ACTIVE", PERSON_ID, List.of("APPROVAL_ADMIN"),
                List.of("APP.APPROVALS:VIEW", VIEW)))
                .when(identities).require(TENANT_ID, USER_ID);

        ApprovalRequestContext.Actor current = authority.requireCurrent(VIEW);

        assertThat(current.roles()).containsExactly("APPROVAL_ADMIN");
        assertThat(current.permissions()).containsExactly(VIEW);
    }

    @Test
    void revokedPermissionAndInactiveIdentityAreForbidden() {
        actor(Set.of("APPROVAL_ADMIN"), Set.of(VIEW, EXECUTE));
        doReturn(subject(
                "ACTIVE", PERSON_ID, List.of("APPROVAL_ADMIN"),
                List.of("APP.APPROVALS:VIEW", VIEW)))
                .when(identities).require(TENANT_ID, USER_ID);

        assertError(ErrorCode.FORBIDDEN, () -> authority.requireCurrent(EXECUTE));

        doReturn(subject(
                "INACTIVE", PERSON_ID, List.of("APPROVAL_ADMIN"),
                List.of("APP.APPROVALS:VIEW", VIEW, EXECUTE)))
                .when(identities).require(TENANT_ID, USER_ID);
        assertError(ErrorCode.FORBIDDEN, () -> authority.requireCurrent(EXECUTE));
    }

    @Test
    void identityLookupFailureIsUnavailableAndIdentityMismatchIsForbidden() {
        actor(Set.of("APPROVAL_ADMIN"), Set.of(VIEW));
        doThrow(new BaseException(ErrorCode.EXTERNAL_SERVICE_ERROR))
                .when(identities).require(TENANT_ID, USER_ID);

        assertError(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                () -> authority.requireCurrent(VIEW));

        doReturn(subject(
                "ACTIVE", UUID.randomUUID(), List.of("APPROVAL_ADMIN"),
                List.of("APP.APPROVALS:VIEW", VIEW)))
                .when(identities).require(TENANT_ID, USER_ID);
        assertError(ErrorCode.FORBIDDEN, () -> authority.requireCurrent(VIEW));
    }

    @Test
    void anyOfCurrentPermissionAcceptsOnlyAStillCurrentPermission() {
        actor(Set.of("APPROVAL_ADMIN"), Set.of(VIEW, EXECUTE, MANAGE));
        doReturn(subject(
                "ACTIVE", PERSON_ID, List.of("APPROVAL_ADMIN"),
                List.of("APP.APPROVALS:VIEW", MANAGE)))
                .when(identities).require(TENANT_ID, USER_ID);

        ApprovalRequestContext.Actor current = authority.requireAnyCurrent(
                Set.of(EXECUTE, MANAGE));

        assertThat(current.permissions()).containsExactly(MANAGE);
    }

    private void actor(Set<String> roles, Set<String> permissions) {
        ApprovalRequestContext.set(
                USER_ID, TENANT_ID, PERSON_ID, "Approval administrator", roles, permissions);
    }

    private ApprovalIdentityDirectory.Subject subject(
            String status,
            UUID personId,
            List<String> roles,
            List<String> permissions) {
        return new ApprovalIdentityDirectory.Subject(
                TENANT_ID, USER_ID, UUID.randomUUID(), personId,
                "Approval administrator", "approval@example.test", "Administrator",
                status, roles, permissions);
    }

    private void assertError(
            ErrorCode expected,
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        Throwable thrown = catchThrowable(action);
        assertThat(thrown).isInstanceOf(BaseException.class);
        assertThat(((BaseException) thrown).getErrorCode()).isEqualTo(expected);
    }
}
