package com.dwp.services.approval.operations;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalWorkAuthority;
import com.dwp.services.approval.integration.ApprovalIdentityDirectory;
import com.dwp.services.approval.integration.ApprovalRecoveryAuditorResolver;
import com.dwp.services.approval.security.ApprovalDecisionRevisionContext;
import com.dwp.services.approval.security.ApprovalManagementScopeContext;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.dwp.services.approval.security.ApprovalStepUpReplayRepository;
import com.dwp.services.approval.security.ApprovalStepUpVerifier;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApprovalOperationsAuthorityTest {
    private static final Instant NOW = Instant.parse("2026-09-14T01:00:00Z");
    private static final UUID ACTOR_PERSON = UUID.randomUUID();
    private static final UUID AUDITOR_PERSON = UUID.randomUUID();
    private static final UUID CANDIDATE_PERSON = UUID.randomUUID();

    private final ApprovalWorkAuthority work = mock(ApprovalWorkAuthority.class);
    private final ApprovalIdentityDirectory identities = mock(ApprovalIdentityDirectory.class);
    private final ApprovalRecoveryAuditorResolver recovery = mock(ApprovalRecoveryAuditorResolver.class);
    private final ApprovalOperationsAuthority authority = new ApprovalOperationsAuthority(
            work, identities, recovery, mock(ApprovalStepUpVerifier.class),
            mock(ApprovalStepUpReplayRepository.class), Clock.fixed(NOW, ZoneOffset.UTC));
    private final ApprovalOperationsAuthority.Current current = new ApprovalOperationsAuthority.Current(
            actor(),
            new ApprovalManagementScopeContext.Evidence("opaque-rs", "RS_APPROVALS"),
            new ApprovalDecisionRevisionContext.Evidence(
                    "decision-1", OffsetDateTime.ofInstant(NOW.plusSeconds(300), ZoneOffset.UTC),
                    "approval-management", "opaque-rs", "route", "111"),
            "STEPUP-MGMT-HIGH-V1");

    @Test
    void deliveryObservationRequiresTheCurrentBrokerAssignmentAndAuditorIdentity() {
        var row = delivery();
        when(recovery.resolve(42, row.targetId(), 100, "RS_APPROVALS"))
                .thenReturn(new ApprovalRecoveryAuditorResolver.Assignment(
                        300, "RS_APPROVALS", "assignment-1"));
        when(identities.require(42, 300)).thenReturn(subject(
                300, AUDITOR_PERSON, List.of("APPROVAL_RECOVERY_AUDITOR"),
                List.of("ADMIN.APPROVAL_OPERATIONS:VIEW")));

        var observed = authority.observeDelivery(current, row);

        assertThat(observed.authorityUserId()).isEqualTo(300);
        assertThat(observed.authorityPersonPublicId()).isEqualTo(AUDITOR_PERSON);
        assertThat(observed.brokerRevision()).isEqualTo("assignment-1");
        assertThat(observed.observedAt()).isEqualTo(NOW);
    }

    @Test
    void changedBrokerAssignmentFailsClosedAsUnavailable() {
        var row = delivery();
        when(recovery.resolve(42, row.targetId(), 100, "RS_APPROVALS"))
                .thenReturn(new ApprovalRecoveryAuditorResolver.Assignment(
                        301, "RS_APPROVALS", "assignment-2"));

        assertError(
                () -> authority.observeDelivery(current, row),
                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
    }

    @Test
    void taskCandidateMustHaveTheCanonicalStepRoleAndExactPersonIdentity() {
        var row = task();
        when(identities.requireRole(42, "FINANCE_APPROVER"))
                .thenReturn(new ApprovalIdentityDirectory.RoleEligibility(
                        42L, "FINANCE_APPROVER", "ACTIVE", 2, true));
        when(identities.require(42, 301)).thenReturn(subject(
                301, CANDIDATE_PERSON, List.of("FINANCE_APPROVER"),
                List.of("ACTION.APPROVAL_TASK:VIEW")));

        var observed = authority.observeTask(current, row, 301, CANDIDATE_PERSON);

        assertThat(observed.authorityRole()).isEqualTo("FINANCE_APPROVER");
        assertThat(observed.authorityUserId()).isEqualTo(301);
        assertThat(observed.brokerRevision()).isEqualTo(
                "identity-role-v1:FINANCE_APPROVER:ACTIVE:2");
    }

    @Test
    void revokedCandidateRoleIsUnprocessableAndCannotBecomeAnAssignment() {
        var row = task();
        when(identities.requireRole(42, "FINANCE_APPROVER"))
                .thenReturn(new ApprovalIdentityDirectory.RoleEligibility(
                        42L, "FINANCE_APPROVER", "RETIRED", 0, false));
        when(identities.require(42, 301)).thenReturn(subject(
                301, CANDIDATE_PERSON, List.of("FINANCE_APPROVER"),
                List.of("ACTION.APPROVAL_TASK:VIEW")));

        assertThatThrownBy(() -> authority.observeTask(current, row, 301, CANDIDATE_PERSON))
                .isInstanceOf(ApprovalOperationsProtocol.ApprovalOperationsRejected.class);
    }

    @Test
    void revokedCurrentOperatorIsDeniedBeforeAnyTargetAuthorityLookup() {
        when(work.requireCurrent("ADMIN.APPROVAL_OPERATIONS:EXECUTE"))
                .thenThrow(new BaseException(ErrorCode.FORBIDDEN));

        assertError(
                () -> authority.requireCurrent(
                        ApprovalOperationsProtocol.Route.DELIVERY_BATCH_RETRY,
                        com.dwp.services.approval.security.ApprovalStepUpHeaders.of(
                                "signed", "key", "revision", 0L), 0),
                ErrorCode.FORBIDDEN);
    }

    private ApprovalOperationsRepository.DeliveryRow delivery() {
        return new ApprovalOperationsRepository.DeliveryRow(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "FAILED", 4,
                100L, 300L, "RS_APPROVALS", "ASSIGNED", "RS_APPROVALS",
                "assignment-1", NOW.minusSeconds(60), null, "IN_REVIEW");
    }

    private ApprovalOperationsRepository.TaskRow task() {
        return new ApprovalOperationsRepository.TaskRow(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "PENDING", 3,
                200L, UUID.randomUUID(), "FINANCE_APPROVER", null, null,
                100, "IN_REVIEW", "RS_APPROVALS", "IN_PROGRESS", "FINANCE_APPROVER");
    }

    private ApprovalRequestContext.Actor actor() {
        return new ApprovalRequestContext.Actor(
                17L, 42L, ACTOR_PERSON, "Operator", Set.of("APPROVAL_OPERATOR"),
                Set.of("APP.APPROVALS:VIEW", "ADMIN.APPROVAL_OPERATIONS:EXECUTE"));
    }

    private ApprovalIdentityDirectory.Subject subject(
            long userId,
            UUID person,
            List<String> roles,
            List<String> permissions) {
        return new ApprovalIdentityDirectory.Subject(
                42L, userId, UUID.randomUUID(), person, "Subject", "subject@example.test",
                "Approver", "ACTIVE", roles, permissions);
    }

    private void assertError(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(
                BaseException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
    }
}
