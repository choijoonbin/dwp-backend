package com.dwp.services.time.close;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.close.TimeCloseModels.ApprovalEvidence;
import com.dwp.services.time.close.TimeCloseModels.CloseAction;
import com.dwp.services.time.close.TimeCloseModels.CloseBlocker;
import com.dwp.services.time.close.TimeCloseModels.CloseCommand;
import com.dwp.services.time.close.TimeCloseModels.CloseEvidence;
import com.dwp.services.time.close.TimeCloseModels.ClosePeriod;
import com.dwp.services.time.close.TimeCloseModels.CloseState;
import com.dwp.services.time.close.TimeCloseModels.CommandKind;
import com.dwp.services.time.close.TimeCloseModels.PayrollTimeHandoffCommandV1;
import com.dwp.services.time.close.TimeCloseModels.PlannedCloseCommand;
import com.dwp.services.time.close.TimeCloseModels.TimeCloseCommandV1;
import com.dwp.services.time.close.TimeCloseModels.TimeReopenCommandV1;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TimeCloseHandoffContractTest {

    private TimeCloseRepository repository;
    private TimeCloseEvidenceSource evidence;
    private TimeCloseApprovalVerifier approval;
    private TimeCloseStepUpVerifier stepUp;
    private TimeCloseService service;

    @BeforeEach
    void setUp() {
        repository = mock(TimeCloseRepository.class);
        evidence = mock(TimeCloseEvidenceSource.class);
        approval = mock(TimeCloseApprovalVerifier.class);
        stepUp = mock(TimeCloseStepUpVerifier.class);
        service = new TimeCloseService(
                repository, evidence, approval, stepUp,
                tenant -> tenant == TimeCloseTestFixtures.TENANT,
                Clock.fixed(TimeCloseTestFixtures.NOW, ZoneOffset.UTC));
    }

    @Test
    void missingPeriodIsOpaqueBeforeEvidenceApprovalOutboxOrHandoffWork() {
        var authority = TimeCloseTestFixtures.authority(CloseAction.CLOSE);
        String key = "missing-period-0001";
        when(repository.findReceipt(
                TimeCloseTestFixtures.TENANT,
                TimeCloseTestFixtures.SUBJECT,
                TimeCloseOwnerRoute.CLOSE.routeContractKey(), key))
                .thenReturn(Optional.empty());
        when(repository.findPeriod(
                TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.PERIOD))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.close(
                authority, TimeCloseTestFixtures.PERIOD, key, 7L,
                "fresh-step-up", new TimeCloseCommandV1(
                        TimeCloseTestFixtures.NOW,
                        TimeCloseTestFixtures.APPROVAL_RECEIPT)))
                .isInstanceOfSatisfying(BaseException.class, failure -> {
                    assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(failure.getMessage())
                            .isEqualTo("The requested TIM close resource was not found.");
                });

        verify(repository).findReceipt(
                TimeCloseTestFixtures.TENANT,
                TimeCloseTestFixtures.SUBJECT,
                TimeCloseOwnerRoute.CLOSE.routeContractKey(), key);
        verify(repository).findPeriod(
                TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.PERIOD);
        verify(repository, never()).findResult(anyLong(), any());
        verify(repository, never()).findHandoff(anyLong(), any());
        verify(repository, never()).execute(any());
        verify(repository, never()).applyOutcome(anyLong(), any(), any(), any());
        verifyNoInteractions(evidence, approval, stepUp);
    }

    @Test
    void closeBindsFreshStepUpApprovalEvidenceAndFrozenResultInput() {
        var authority = TimeCloseTestFixtures.authority(CloseAction.CLOSE);
        ClosePeriod period = TimeCloseTestFixtures.period(CloseState.OPEN);
        String key = "close-command-0001";
        when(repository.findReceipt(
                TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.SUBJECT,
                TimeCloseOwnerRoute.CLOSE.routeContractKey(), key))
                .thenReturn(Optional.empty());
        when(repository.findPeriod(TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.PERIOD))
                .thenReturn(Optional.of(period));
        when(evidence.freeze(period, authority, TimeCloseTestFixtures.NOW))
                .thenReturn(TimeCloseTestFixtures.evidence());
        when(approval.verify(any(), eq(period))).thenAnswer(invocation -> {
            CloseCommand command = invocation.getArgument(0);
            return approval(command, TimeCloseTestFixtures.APPROVER,
                    "TIME_PERIOD_CLOSE", null);
        });
        when(repository.execute(any())).thenReturn(
                TimeCloseTestFixtures.receipt(CommandKind.CLOSE,
                        TimeCloseTestFixtures.SUBJECT));

        service.close(authority, TimeCloseTestFixtures.PERIOD, key, 7L,
                "fresh-step-up", new TimeCloseCommandV1(
                        TimeCloseTestFixtures.NOW,
                        TimeCloseTestFixtures.APPROVAL_RECEIPT));

        ArgumentCaptor<PlannedCloseCommand> planned =
                ArgumentCaptor.forClass(PlannedCloseCommand.class);
        verify(stepUp).verify(any(CloseCommand.class), eq(period));
        verify(evidence).freeze(period, authority, TimeCloseTestFixtures.NOW);
        verify(repository).execute(planned.capture());
        assertThat(planned.getValue().command().kind()).isEqualTo(CommandKind.CLOSE);
        assertThat(planned.getValue().command().stepUpChallenge())
                .isEqualTo("fresh-step-up");
        assertThat(planned.getValue().approval().approvalActorSubjectId())
                .isEqualTo(TimeCloseTestFixtures.APPROVER);
        assertThat(planned.getValue().evidence())
                .isEqualTo(TimeCloseTestFixtures.evidence());
        assertThat(planned.getValue().selectedResult()).isNull();
    }

    @Test
    void closeMakerCannotApproveOwnCloseAndNoMutationIsAttempted() {
        var authority = TimeCloseTestFixtures.authority(CloseAction.CLOSE);
        ClosePeriod period = TimeCloseTestFixtures.period(CloseState.OPEN);
        String key = "close-sod-negative";
        when(repository.findReceipt(
                TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.SUBJECT,
                TimeCloseOwnerRoute.CLOSE.routeContractKey(), key))
                .thenReturn(Optional.empty());
        when(repository.findPeriod(TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.PERIOD))
                .thenReturn(Optional.of(period));
        when(approval.verify(any(), eq(period))).thenAnswer(invocation ->
                approval(invocation.getArgument(0), TimeCloseTestFixtures.SUBJECT,
                        "TIME_PERIOD_CLOSE", null));

        assertThatThrownBy(() -> service.close(
                authority, TimeCloseTestFixtures.PERIOD, key, 7L,
                "fresh-step-up", new TimeCloseCommandV1(
                        TimeCloseTestFixtures.NOW,
                        TimeCloseTestFixtures.APPROVAL_RECEIPT)))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.SOD_CONFLICT));

        verify(repository, never()).execute(any());
        verifyNoInteractions(evidence);
    }

    @Test
    void immutableBlockersRejectCloseBeforeAnyRepositoryWrite() {
        var authority = TimeCloseTestFixtures.authority(CloseAction.CLOSE);
        ClosePeriod period = TimeCloseTestFixtures.period(CloseState.OPEN);
        String key = "close-blocked-0001";
        CloseEvidence blocked = new CloseEvidence(
                java.util.List.of(new CloseBlocker(
                        "TIMECARD_MISSING", TimeCloseTestFixtures.PERIOD,
                        "7".repeat(64))),
                java.util.List.of(), TimeCloseTestFixtures.POPULATION, 9L, 1L, 1L,
                TimeCloseResultIntegrity.sourceDigest(java.util.List.of()),
                TimeCloseTestFixtures.RULE_DIGEST, "9".repeat(64),
                "8".repeat(64), java.util.Map.of());
        when(repository.findReceipt(
                TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.SUBJECT,
                TimeCloseOwnerRoute.CLOSE.routeContractKey(), key))
                .thenReturn(Optional.empty());
        when(repository.findPeriod(TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.PERIOD))
                .thenReturn(Optional.of(period));
        when(evidence.freeze(period, authority, TimeCloseTestFixtures.NOW))
                .thenReturn(blocked);
        when(approval.verify(any(), eq(period))).thenAnswer(invocation ->
                approval(invocation.getArgument(0), TimeCloseTestFixtures.APPROVER,
                        "TIME_PERIOD_CLOSE", null));

        assertThatThrownBy(() -> service.close(
                authority, TimeCloseTestFixtures.PERIOD, key, 7L,
                "fresh-step-up", new TimeCloseCommandV1(
                        TimeCloseTestFixtures.NOW,
                        TimeCloseTestFixtures.APPROVAL_RECEIPT)))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE));

        verify(repository, never()).execute(any());
    }

    @Test
    void evidenceFenceBindsExactPopulationMembershipAndWorkerHighWater() {
        var authority = TimeCloseTestFixtures.authority(CloseAction.CLOSE);
        var member = new JdbcTimeCloseEvidenceSource.Member(
                TimeCloseTestFixtures.SUBJECT, TimeCloseTestFixtures.APPROVER,
                31L, "6".repeat(64));
        List<JdbcTimeCloseEvidenceSource.Member> members = List.of(member);
        Map<java.util.UUID, Long> highWater = Map.of(
                TimeCloseTestFixtures.SUBJECT, 47L);
        var card = new JdbcTimeCloseEvidenceSource.Card(
                TimeCloseTestFixtures.SUBJECT, TimeCloseTestFixtures.RESULT,
                "APPROVED", 47L);
        List<JdbcTimeCloseEvidenceSource.Card> cards = List.of(card);
        CloseEvidence frozen = new CloseEvidence(
                List.of(), List.of(), TimeCloseTestFixtures.POPULATION, 9L,
                1L, 1L, TimeCloseResultIntegrity.sourceDigest(List.of()),
                TimeCloseTestFixtures.RULE_DIGEST,
                JdbcTimeCloseEvidenceSource.membershipDigest(members),
                JdbcTimeCloseEvidenceSource.timecardDigest(
                        java.util.Set.of(TimeCloseTestFixtures.SUBJECT), cards),
                highWater);
        var population = new TimeCloseEvidenceFence.PopulationState(
                TimeCloseTestFixtures.POPULATION, 9L, "ACTIVE",
                java.time.LocalDate.parse("2026-01-01"), null,
                TimeCloseTestFixtures.POPULATION_SOURCE_DIGEST);
        var grant = new TimeCloseEvidenceFence.GrantState(
                72L, "scope-key", TimeCloseTestFixtures.POPULATION, 9L, 11L,
                "ACTIVE", TimeCloseTestFixtures.NOW.minusSeconds(60), null,
                TimeCloseTestFixtures.GRANT_SOURCE_DIGEST);

        assertThat(TimeCloseEvidenceFence.isCurrent(
                authority, frozen, population, grant, members, cards, highWater,
                TimeCloseTestFixtures.NOW)).isTrue();
        assertThat(TimeCloseEvidenceFence.isCurrent(
                authority, frozen,
                new TimeCloseEvidenceFence.PopulationState(
                        TimeCloseTestFixtures.POPULATION, 10L, "ACTIVE",
                        java.time.LocalDate.parse("2026-01-01"), null,
                        TimeCloseTestFixtures.POPULATION_SOURCE_DIGEST),
                grant, members, cards, highWater, TimeCloseTestFixtures.NOW)).isFalse();
        assertThat(TimeCloseEvidenceFence.isCurrent(
                authority, frozen, population, grant,
                List.of(new JdbcTimeCloseEvidenceSource.Member(
                        TimeCloseTestFixtures.SUBJECT, TimeCloseTestFixtures.APPROVER,
                        32L, "6".repeat(64))),
                cards, highWater, TimeCloseTestFixtures.NOW)).isFalse();
        assertThat(TimeCloseEvidenceFence.isCurrent(
                authority, frozen, population, grant, members, cards,
                Map.of(TimeCloseTestFixtures.SUBJECT, 48L),
                TimeCloseTestFixtures.NOW)).isFalse();
        assertThat(TimeCloseEvidenceFence.isCurrent(
                authority, frozen, population,
                new TimeCloseEvidenceFence.GrantState(
                        72L, "scope-key", TimeCloseTestFixtures.POPULATION, 9L, 12L,
                        "ACTIVE", TimeCloseTestFixtures.NOW.minusSeconds(60), null,
                        TimeCloseTestFixtures.GRANT_SOURCE_DIGEST),
                members, cards, highWater, TimeCloseTestFixtures.NOW)).isFalse();
        List<JdbcTimeCloseEvidenceSource.Card> reopened = List.of(
                new JdbcTimeCloseEvidenceSource.Card(
                        TimeCloseTestFixtures.SUBJECT, TimeCloseTestFixtures.HANDOFF,
                        "OPEN", 48L));
        assertThat(TimeCloseEvidenceFence.isCurrent(
                authority, frozen, population, grant, members, reopened, highWater,
                TimeCloseTestFixtures.NOW)).isFalse();
        assertThat(JdbcTimeCloseEvidenceSource.hasOneEligibleCardPerWorker(
                java.util.Set.of(TimeCloseTestFixtures.SUBJECT), cards, highWater)).isTrue();
        assertThat(JdbcTimeCloseEvidenceSource.hasOneEligibleCardPerWorker(
                java.util.Set.of(TimeCloseTestFixtures.SUBJECT), reopened,
                highWater)).isFalse();
        assertThat(JdbcTimeCloseEvidenceSource.hasOneEligibleCardPerWorker(
                java.util.Set.of(TimeCloseTestFixtures.SUBJECT), List.of(),
                highWater)).isFalse();
        assertThat(JdbcTimeCloseEvidenceSource.hasOneEligibleCardPerWorker(
                java.util.Set.of(TimeCloseTestFixtures.SUBJECT),
                List.of(new JdbcTimeCloseEvidenceSource.Card(
                        TimeCloseTestFixtures.SUBJECT, TimeCloseTestFixtures.RESULT,
                        "APPROVED", 46L)), highWater)).isFalse();
    }

    @Test
    void reopenPreservesForwardCorrectionLineageWithoutRefreezingEvidence() {
        var authority = TimeCloseTestFixtures.authority(CloseAction.REOPEN);
        ClosePeriod period = TimeCloseTestFixtures.period(CloseState.CLOSED);
        String key = "reopen-command-001";
        when(repository.findReceipt(
                TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.REOPEN_SUBJECT,
                TimeCloseOwnerRoute.REOPEN.routeContractKey(), key))
                .thenReturn(Optional.empty());
        when(repository.findPeriod(TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.PERIOD))
                .thenReturn(Optional.of(period));
        when(approval.verify(any(), eq(period))).thenAnswer(invocation -> {
            CloseCommand command = invocation.getArgument(0);
            return approval(command, TimeCloseTestFixtures.REOPEN_APPROVER,
                    "TIME_PERIOD_REOPEN", "PAYROLL_CORRECTION");
        });
        when(repository.execute(any())).thenReturn(
                TimeCloseTestFixtures.receipt(CommandKind.REOPEN,
                        TimeCloseTestFixtures.REOPEN_SUBJECT));

        service.reopen(authority, TimeCloseTestFixtures.PERIOD, key, 7L,
                "fresh-step-up", new TimeReopenCommandV1(
                        TimeCloseTestFixtures.NOW,
                        TimeCloseTestFixtures.APPROVAL_RECEIPT,
                        "PAYROLL_CORRECTION"));

        ArgumentCaptor<PlannedCloseCommand> planned =
                ArgumentCaptor.forClass(PlannedCloseCommand.class);
        verify(repository).execute(planned.capture());
        assertThat(planned.getValue().command().kind()).isEqualTo(CommandKind.REOPEN);
        assertThat(planned.getValue().command().reasonCode())
                .isEqualTo("PAYROLL_CORRECTION");
        assertThat(planned.getValue().approval().approvalActorSubjectId())
                .isEqualTo(TimeCloseTestFixtures.REOPEN_APPROVER);
        assertThat(planned.getValue().evidence()).isNull();
        verifyNoInteractions(evidence);
    }

    @Test
    void originalCloserCannotActAsReopenApprover() {
        var authority = TimeCloseTestFixtures.authority(CloseAction.REOPEN);
        ClosePeriod period = TimeCloseTestFixtures.period(CloseState.CLOSED);
        String key = "reopen-sod-negative";
        when(repository.findReceipt(
                TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.REOPEN_SUBJECT,
                TimeCloseOwnerRoute.REOPEN.routeContractKey(), key))
                .thenReturn(Optional.empty());
        when(repository.findPeriod(TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.PERIOD))
                .thenReturn(Optional.of(period));
        when(approval.verify(any(), eq(period))).thenAnswer(invocation ->
                approval(invocation.getArgument(0), TimeCloseTestFixtures.SUBJECT,
                        "TIME_PERIOD_REOPEN", "PAYROLL_CORRECTION"));

        assertThatThrownBy(() -> service.reopen(
                authority, TimeCloseTestFixtures.PERIOD, key, 7L,
                "fresh-step-up", new TimeReopenCommandV1(
                        TimeCloseTestFixtures.NOW,
                        TimeCloseTestFixtures.APPROVAL_RECEIPT,
                        "PAYROLL_CORRECTION")))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.SOD_CONFLICT));

        verify(repository, never()).execute(any());
        verifyNoInteractions(evidence);
    }

    @Test
    void handoffSelectsOnlyTheCurrentImmutableResultAndServerOwnedFacts() {
        var authority = TimeCloseTestFixtures.authority(CloseAction.HANDOFF_CREATE);
        ClosePeriod period = TimeCloseTestFixtures.period(CloseState.CLOSED);
        String key = "handoff-command-01";
        when(repository.findReceipt(
                TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.SUBJECT,
                TimeCloseOwnerRoute.HANDOFF_CREATE.routeContractKey(), key))
                .thenReturn(Optional.empty());
        when(repository.findPeriod(TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.PERIOD))
                .thenReturn(Optional.of(period));
        when(repository.findResult(TimeCloseTestFixtures.TENANT, TimeCloseTestFixtures.RESULT))
                .thenReturn(Optional.of(TimeCloseTestFixtures.persistedResult()));
        when(repository.execute(any())).thenReturn(
                TimeCloseTestFixtures.receipt(CommandKind.HANDOFF,
                        TimeCloseTestFixtures.SUBJECT));

        service.createHandoff(
                authority, TimeCloseTestFixtures.PERIOD, key, 7L,
                new PayrollTimeHandoffCommandV1(
                        TimeCloseTestFixtures.RESULT,
                        TimeCloseTestFixtures.PAYROLL_ENTITY,
                        TimeCloseTestFixtures.PAY_PERIOD));

        ArgumentCaptor<PlannedCloseCommand> planned =
                ArgumentCaptor.forClass(PlannedCloseCommand.class);
        verify(repository).execute(planned.capture());
        assertThat(planned.getValue().command().kind()).isEqualTo(CommandKind.HANDOFF);
        assertThat(planned.getValue().command().asOf()).isEqualTo(TimeCloseTestFixtures.NOW);
        assertThat(planned.getValue().selectedResult())
                .isEqualTo(TimeCloseTestFixtures.persistedResult());
        assertThat(planned.getValue().evidence()).isNull();
        assertThat(planned.getValue().approval()).isNull();
        verifyNoInteractions(evidence, approval, stepUp);
    }

    private static ApprovalEvidence approval(
            CloseCommand command,
            java.util.UUID approver,
            String action,
            String reason) {
        return new ApprovalEvidence(
                TimeCloseTestFixtures.APPROVAL_RECEIPT, approver, action,
                "time-period:" + TimeCloseTestFixtures.PERIOD,
                command.expectedVersion(), command.requestDigest(),
                TimeCloseTestFixtures.DECISION_REVISION, "8".repeat(64), reason,
                TimeCloseTestFixtures.NOW.plusSeconds(120));
    }
}
