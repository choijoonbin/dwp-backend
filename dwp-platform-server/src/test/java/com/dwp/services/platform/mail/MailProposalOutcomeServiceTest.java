package com.dwp.services.platform.mail;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailProposalOutcomePort.Owner.HR;
import static com.dwp.services.platform.mail.MailTypes.ProposalType.CREATE_CALENDAR_EVENT;
import static com.dwp.services.platform.mail.MailTypes.ProposalType.CREATE_LEAVE_REQUEST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MailProposalOutcomeServiceTest {

    private static final long TENANT_ID = 1L;
    private static final long ACTOR_ID = 7L;

    @Mock
    private MailQueryRepository queries;
    @Mock
    private MailCommandRepository commands;

    private MailProposalOutcomeService service;

    @BeforeEach
    void setUp() {
        service = new MailProposalOutcomeService(queries, commands);
    }

    @Test
    void correctHrOwnerActorAndBindingRecordTheExecutedOutcome() {
        UUID proposalId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        UUID leaveRequestId = UUID.randomUUID();
        String resultRef = "hr-leave-request:" + leaveRequestId;
        var binding = new MailProposalHandoffBinding(proposalId, commandId, 3L);
        var before = row(
                proposalId, commandId, CREATE_LEAVE_REQUEST, ACTOR_ID,
                "EXECUTING", null, 3L);
        var after = row(
                proposalId, commandId, CREATE_LEAVE_REQUEST, ACTOR_ID,
                "EXECUTED", resultRef, 4L);
        when(queries.ownerProposalHandoff(TENANT_ID, proposalId, true))
                .thenReturn(Optional.of(before));
        when(commands.updateProposalOutcomeFromOwner(
                TENANT_ID, ACTOR_ID, proposalId, commandId,
                CREATE_LEAVE_REQUEST, "EXECUTED", resultRef, 3L))
                .thenReturn(1);
        when(queries.ownerProposalHandoff(TENANT_ID, proposalId, false))
                .thenReturn(Optional.of(after));

        MailDtos.ProposalHandoff result = service.executed(
                TENANT_ID, ACTOR_ID, HR, binding, resultRef, "corr-executed");

        assertThat(result.status()).isEqualTo(MailDtos.ProposalHandoffStatus.EXECUTED);
        assertThat(result.resultRef()).isEqualTo(resultRef);
        assertThat(result.version()).isEqualTo(4L);
        verify(commands).audit(
                eq(TENANT_ID), eq(ACTOR_ID), eq("mail.action.owner-outcome"),
                eq("MAIL_ACTION_PROPOSAL"), eq(proposalId.toString()),
                eq("corr-executed"), anyMap(), anyMap());
        verify(commands).domainEvent(
                eq(TENANT_ID), eq("MAIL_ACTION_PROPOSAL"), eq(proposalId),
                eq("mail.action.owner-outcome"), anyMap(), eq("corr-executed"));
    }

    @Test
    void exactExecutedReplayReturnsTheRecordedResultWithoutAnotherMutation() {
        UUID proposalId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        String resultRef = "hr-leave-request:" + UUID.randomUUID();
        var completed = row(
                proposalId, commandId, CREATE_LEAVE_REQUEST, ACTOR_ID,
                "EXECUTED", resultRef, 4L);
        when(queries.ownerProposalHandoff(TENANT_ID, proposalId, true))
                .thenReturn(Optional.of(completed));

        MailDtos.ProposalHandoff result = service.executed(
                TENANT_ID, ACTOR_ID, HR,
                new MailProposalHandoffBinding(proposalId, commandId, 3L),
                resultRef, "corr-replay");

        assertThat(result.status()).isEqualTo(MailDtos.ProposalHandoffStatus.EXECUTED);
        assertThat(result.version()).isEqualTo(4L);
        verify(commands, never()).updateProposalOutcomeFromOwner(
                eq(TENANT_ID), eq(ACTOR_ID), eq(proposalId), eq(commandId),
                eq(CREATE_LEAVE_REQUEST), eq("EXECUTED"), eq(resultRef), eq(3L));
        verify(commands, never()).audit(
                eq(TENANT_ID), eq(ACTOR_ID), eq("mail.action.owner-outcome"),
                eq("MAIL_ACTION_PROPOSAL"), eq(proposalId.toString()),
                eq("corr-replay"), anyMap(), anyMap());
    }

    @Test
    void forgedActorCannotRecordAnotherUsersOwnerOutcome() {
        UUID proposalId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        var binding = new MailProposalHandoffBinding(proposalId, commandId, 3L);
        when(queries.ownerProposalHandoff(TENANT_ID, proposalId, true))
                .thenReturn(Optional.of(row(
                        proposalId, commandId, CREATE_LEAVE_REQUEST, 99L,
                        "ACCEPTED", null, 3L)));

        assertError(ErrorCode.FORBIDDEN, () -> service.executed(
                TENANT_ID, ACTOR_ID, HR, binding,
                "hr-leave-request:" + UUID.randomUUID(), "corr-forged-actor"));

        verify(commands, never()).updateProposalOutcomeFromOwner(
                eq(TENANT_ID), eq(ACTOR_ID), eq(proposalId), eq(commandId),
                eq(CREATE_LEAVE_REQUEST), eq("EXECUTED"),
                org.mockito.ArgumentMatchers.anyString(), eq(3L));
    }

    @Test
    void forgedOwnerCannotExecuteAProposalOwnedByAnotherApplication() {
        UUID proposalId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        when(queries.ownerProposalHandoff(TENANT_ID, proposalId, true))
                .thenReturn(Optional.of(row(
                        proposalId, commandId, CREATE_CALENDAR_EVENT, ACTOR_ID,
                        "ACCEPTED", null, 3L)));

        assertError(ErrorCode.FORBIDDEN, () -> service.executed(
                TENANT_ID, ACTOR_ID, HR,
                new MailProposalHandoffBinding(proposalId, commandId, 3L),
                "hr-leave-request:" + UUID.randomUUID(), "corr-forged-owner"));
    }

    @Test
    void forgedCommandBindingCannotExecuteTheProposal() {
        UUID proposalId = UUID.randomUUID();
        UUID recordedCommandId = UUID.randomUUID();
        UUID forgedCommandId = UUID.randomUUID();
        when(queries.ownerProposalHandoff(TENANT_ID, proposalId, true))
                .thenReturn(Optional.of(row(
                        proposalId, recordedCommandId, CREATE_LEAVE_REQUEST, ACTOR_ID,
                        "ACCEPTED", null, 3L)));

        assertError(ErrorCode.INVALID_STATE, () -> service.executed(
                TENANT_ID, ACTOR_ID, HR,
                new MailProposalHandoffBinding(proposalId, forgedCommandId, 3L),
                "hr-leave-request:" + UUID.randomUUID(), "corr-forged-command"));
    }

    @Test
    void acceptingActorCanCancelAnUnexecutedHandoff() {
        UUID proposalId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        var before = row(
                proposalId, commandId, CREATE_LEAVE_REQUEST, ACTOR_ID,
                "ACCEPTED", null, 3L);
        var after = row(
                proposalId, commandId, CREATE_LEAVE_REQUEST, ACTOR_ID,
                "CANCELLED", "cancelled-by-user:" + ACTOR_ID, 4L);
        when(queries.accounts(TENANT_ID, ACTOR_ID)).thenReturn(List.of(account()));
        when(queries.proposalHandoff(TENANT_ID, ACTOR_ID, proposalId))
                .thenReturn(Optional.of(visible(before)));
        when(queries.ownerProposalHandoff(TENANT_ID, proposalId, true))
                .thenReturn(Optional.of(before));
        when(commands.cancelProposalOutcome(
                TENANT_ID, ACTOR_ID, proposalId, commandId, 3L,
                "cancelled-by-user:" + ACTOR_ID))
                .thenReturn(1);
        when(queries.ownerProposalHandoff(TENANT_ID, proposalId, false))
                .thenReturn(Optional.of(after));

        MailDtos.ProposalHandoff result = service.cancel(
                TENANT_ID, ACTOR_ID, proposalId, commandId, 3L, "corr-cancel");

        assertThat(result.status()).isEqualTo(MailDtos.ProposalHandoffStatus.CANCELLED);
        assertThat(result.resultRef()).isEqualTo("cancelled-by-user:" + ACTOR_ID);
        verify(commands).cancelProposalOutcome(
                TENANT_ID, ACTOR_ID, proposalId, commandId, 3L,
                "cancelled-by-user:" + ACTOR_ID);
    }

    @Test
    void remotePreflightDurablyReservesExecutionBeforeTheOwnerWrite() {
        UUID proposalId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        var binding = new MailProposalHandoffBinding(proposalId, commandId, 3L);
        var accepted = row(
                proposalId, commandId, CREATE_LEAVE_REQUEST, ACTOR_ID,
                "ACCEPTED", null, 3L);
        when(queries.ownerProposalHandoff(TENANT_ID, proposalId, true))
                .thenReturn(Optional.of(accepted));
        when(commands.reserveProposalExecution(
                TENANT_ID, ACTOR_ID, proposalId, commandId,
                CREATE_LEAVE_REQUEST, 3L)).thenReturn(1);

        service.validateNewExecution(
                TENANT_ID, ACTOR_ID, HR, binding,
                new MailProposalOutcomePort.OwnerMutation(
                        null, Map.of("durationDays", 1)));

        verify(commands).reserveProposalExecution(
                TENANT_ID, ACTOR_ID, proposalId, commandId,
                CREATE_LEAVE_REQUEST, 3L);
        verify(commands).audit(
                eq(TENANT_ID), eq(ACTOR_ID), eq("mail.action.owner-reserved"),
                eq("MAIL_ACTION_PROPOSAL"), eq(proposalId.toString()),
                eq(null), anyMap(), anyMap());
    }

    @Test
    void executingAndUnknownHandoffsAreReconcileOnlyAndCannotBeCancelled() {
        for (String state : List.of("EXECUTING", "UNKNOWN")) {
            UUID proposalId = UUID.randomUUID();
            UUID commandId = UUID.randomUUID();
            var row = row(
                    proposalId, commandId, CREATE_LEAVE_REQUEST, ACTOR_ID,
                    state, state.equals("UNKNOWN") ? "owner-result-unknown" : null, 3L);
            when(queries.accounts(TENANT_ID, ACTOR_ID)).thenReturn(List.of(account()));
            when(queries.proposalHandoff(TENANT_ID, ACTOR_ID, proposalId))
                    .thenReturn(Optional.of(visible(row)));
            when(queries.ownerProposalHandoff(TENANT_ID, proposalId, true))
                    .thenReturn(Optional.of(row));

            assertError(ErrorCode.INVALID_STATE, () -> service.cancel(
                    TENANT_ID, ACTOR_ID, proposalId, commandId, 3L,
                    "corr-cancel-race"));
        }
    }

    @Test
    void ownerRollbackEvidenceReleasesTheReservationBeforeUserCancellation() {
        UUID proposalId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        var binding = new MailProposalHandoffBinding(proposalId, commandId, 3L);
        var executing = row(
                proposalId, commandId, CREATE_LEAVE_REQUEST, ACTOR_ID,
                "EXECUTING", null, 3L);
        var released = row(
                proposalId, commandId, CREATE_LEAVE_REQUEST, ACTOR_ID,
                "ACCEPTED", "owner-not-executed:HR:OWNER_TRANSACTION_ROLLED_BACK", 3L);
        when(queries.ownerProposalHandoff(TENANT_ID, proposalId, true))
                .thenReturn(Optional.of(executing));
        when(commands.releaseProposalExecution(
                TENANT_ID, ACTOR_ID, proposalId, commandId,
                CREATE_LEAVE_REQUEST, 3L,
                "owner-not-executed:HR:OWNER_TRANSACTION_ROLLED_BACK"))
                .thenReturn(1);
        when(queries.ownerProposalHandoff(TENANT_ID, proposalId, false))
                .thenReturn(Optional.of(released));

        MailDtos.ProposalHandoff result = service.notExecuted(
                TENANT_ID, ACTOR_ID, HR, binding,
                "OWNER_TRANSACTION_ROLLED_BACK", "corr-rollback");

        assertThat(result.status()).isEqualTo(MailDtos.ProposalHandoffStatus.ACCEPTED);
        assertThat(result.resultRef())
                .isEqualTo("owner-not-executed:HR:OWNER_TRANSACTION_ROLLED_BACK");
    }

    @Test
    void exactNotExecutedReplayReturnsTheExistingReleaseWithoutAnotherMutation() {
        UUID proposalId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        var binding = new MailProposalHandoffBinding(proposalId, commandId, 3L);
        String evidence = "owner-not-executed:HR:OWNER_TRANSACTION_ROLLED_BACK";
        var released = row(
                proposalId, commandId, CREATE_LEAVE_REQUEST, ACTOR_ID,
                "ACCEPTED", evidence, 3L);
        when(queries.ownerProposalHandoff(TENANT_ID, proposalId, true))
                .thenReturn(Optional.of(released));

        MailDtos.ProposalHandoff result = service.notExecuted(
                TENANT_ID, ACTOR_ID, HR, binding,
                "OWNER_TRANSACTION_ROLLED_BACK", "corr-release-replay");

        assertThat(result.status()).isEqualTo(MailDtos.ProposalHandoffStatus.ACCEPTED);
        assertThat(result.resultRef()).isEqualTo(evidence);
        verify(commands, never()).releaseProposalExecution(
                eq(TENANT_ID), eq(ACTOR_ID), eq(proposalId), eq(commandId),
                eq(CREATE_LEAVE_REQUEST), eq(3L), eq(evidence));
        verify(commands, never()).audit(
                eq(TENANT_ID), eq(ACTOR_ID), eq("mail.action.owner-not-executed"),
                eq("MAIL_ACTION_PROPOSAL"), eq(proposalId.toString()),
                eq("corr-release-replay"), anyMap(), anyMap());
    }

    @Test
    void unknownOwnerOutcomeCanOnlyReconcileToTheBoundExecutedResult() {
        UUID proposalId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        UUID leaveRequestId = UUID.randomUUID();
        String resultRef = "hr-leave-request:" + leaveRequestId;
        var binding = new MailProposalHandoffBinding(proposalId, commandId, 3L);
        var unknown = row(
                proposalId, commandId, CREATE_LEAVE_REQUEST, ACTOR_ID,
                "UNKNOWN", "owner-result-unknown", 3L);
        var reconciled = row(
                proposalId, commandId, CREATE_LEAVE_REQUEST, ACTOR_ID,
                "EXECUTED", resultRef, 4L);
        when(queries.ownerProposalHandoff(TENANT_ID, proposalId, true))
                .thenReturn(Optional.of(unknown));
        when(commands.updateProposalOutcomeFromOwner(
                TENANT_ID, ACTOR_ID, proposalId, commandId,
                CREATE_LEAVE_REQUEST, "EXECUTED", resultRef, 3L))
                .thenReturn(1);
        when(queries.ownerProposalHandoff(TENANT_ID, proposalId, false))
                .thenReturn(Optional.of(reconciled));

        MailDtos.ProposalHandoff result = service.executed(
                TENANT_ID, ACTOR_ID, HR, binding, resultRef, "corr-reconcile");

        assertThat(result.status()).isEqualTo(MailDtos.ProposalHandoffStatus.EXECUTED);
        assertThat(result.resultRef()).isEqualTo(resultRef);
        verify(commands).updateProposalOutcomeFromOwner(
                TENANT_ID, ACTOR_ID, proposalId, commandId,
                CREATE_LEAVE_REQUEST, "EXECUTED", resultRef, 3L);
    }

    @Test
    void visibleProposalStillCannotBeCancelledByANonAcceptingActor() {
        long forgedActor = 19L;
        UUID proposalId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        var before = row(
                proposalId, commandId, CREATE_LEAVE_REQUEST, ACTOR_ID,
                "ACCEPTED", null, 3L);
        when(queries.accounts(TENANT_ID, forgedActor)).thenReturn(List.of(account()));
        when(queries.proposalHandoff(TENANT_ID, forgedActor, proposalId))
                .thenReturn(Optional.of(visible(before)));
        when(queries.ownerProposalHandoff(TENANT_ID, proposalId, true))
                .thenReturn(Optional.of(before));

        assertError(ErrorCode.FORBIDDEN, () -> service.cancel(
                TENANT_ID, forgedActor, proposalId, commandId, 3L,
                "corr-cancel-forged"));

        verify(commands, never()).cancelProposalOutcome(
                eq(TENANT_ID), eq(forgedActor), eq(proposalId), eq(commandId),
                eq(3L), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void terminalExecutedOutcomeRejectsAChangedReplayResult() {
        UUID proposalId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        var completed = row(
                proposalId, commandId, CREATE_LEAVE_REQUEST, ACTOR_ID,
                "EXECUTED", "hr-leave-request:" + UUID.randomUUID(), 4L);
        when(queries.ownerProposalHandoff(TENANT_ID, proposalId, true))
                .thenReturn(Optional.of(completed));

        assertError(ErrorCode.INVALID_STATE, () -> service.executed(
                TENANT_ID, ACTOR_ID, HR,
                new MailProposalHandoffBinding(proposalId, commandId, 3L),
                "hr-leave-request:" + UUID.randomUUID(), "corr-terminal"));

        verify(commands, never()).updateProposalOutcomeFromOwner(
                eq(TENANT_ID), eq(ACTOR_ID), eq(proposalId), eq(commandId),
                eq(CREATE_LEAVE_REQUEST), eq("EXECUTED"),
                org.mockito.ArgumentMatchers.anyString(), eq(3L));
    }

    @Test
    void remotePreflightRejectsCancelledExecutedAndUnknownOwnerStates() {
        for (String state : List.of("CANCELLED", "EXECUTED", "UNKNOWN")) {
            UUID proposalId = UUID.randomUUID();
            UUID commandId = UUID.randomUUID();
            long version = 4L;
            when(queries.ownerProposalHandoff(TENANT_ID, proposalId, true))
                    .thenReturn(Optional.of(row(
                            proposalId, commandId, CREATE_LEAVE_REQUEST, ACTOR_ID,
                            state, state.equals("CANCELLED")
                                    ? "cancelled-by-user:" + ACTOR_ID
                                    : "hr-leave-request:" + UUID.randomUUID(),
                            version)));

            assertError(ErrorCode.INVALID_STATE, () -> service.validateNewExecution(
                    TENANT_ID, ACTOR_ID, HR,
                    new MailProposalHandoffBinding(proposalId, commandId, version),
                    new MailProposalOutcomePort.OwnerMutation(
                            null, Map.of("durationDays", 1))));
        }
    }

    @Test
    void newExecutionRequiresTheAcceptedOwnerMeaningAndMailSourceThread() {
        UUID hrProposalId = UUID.randomUUID();
        UUID hrCommandId = UUID.randomUUID();
        var hr = new MailQueryRepository.OwnerProposalHandoffRow(
                hrProposalId, hrCommandId, CREATE_LEAVE_REQUEST,
                UUID.randomUUID(), Map.of(
                        "durationDays", 2,
                        "requiresConfirmation", true),
                ACTOR_ID, "/hr/absence?action=create", "ACCEPTED", null,
                OffsetDateTime.parse("2026-09-17T00:00:00Z"), 3L);
        when(queries.ownerProposalHandoff(TENANT_ID, hrProposalId, true))
                .thenReturn(Optional.of(hr));

        assertError(ErrorCode.INVALID_STATE, () -> service.validateNewExecution(
                TENANT_ID, ACTOR_ID, HR,
                new MailProposalHandoffBinding(hrProposalId, hrCommandId, 3L),
                new MailProposalOutcomePort.OwnerMutation(
                        null, Map.of("durationDays", 1))));

        UUID mailProposalId = UUID.randomUUID();
        UUID mailCommandId = UUID.randomUUID();
        UUID sourceThreadId = UUID.randomUUID();
        var mail = new MailQueryRepository.OwnerProposalHandoffRow(
                mailProposalId, mailCommandId, MailTypes.ProposalType.DRAFT_REPLY,
                sourceThreadId, Map.of(
                        "tone", "PROFESSIONAL",
                        "language", "ko",
                        "requiresConfirmation", true),
                ACTOR_ID, "/mail/inbox", "ACCEPTED", null,
                OffsetDateTime.parse("2026-09-17T00:00:00Z"), 3L);
        when(queries.ownerProposalHandoff(TENANT_ID, mailProposalId, true))
                .thenReturn(Optional.of(mail));
        when(commands.reserveProposalExecution(
                TENANT_ID, ACTOR_ID, mailProposalId, mailCommandId,
                MailTypes.ProposalType.DRAFT_REPLY, 3L)).thenReturn(1);

        assertError(ErrorCode.INVALID_STATE, () -> service.validateNewExecution(
                TENANT_ID, ACTOR_ID, MailProposalOutcomePort.Owner.MAIL,
                new MailProposalHandoffBinding(mailProposalId, mailCommandId, 3L),
                new MailProposalOutcomePort.OwnerMutation(
                        UUID.randomUUID(), Map.of())));
        service.validateNewExecution(
                TENANT_ID, ACTOR_ID, MailProposalOutcomePort.Owner.MAIL,
                new MailProposalHandoffBinding(mailProposalId, mailCommandId, 3L),
                new MailProposalOutcomePort.OwnerMutation(sourceThreadId, Map.of()));
    }

    private void assertError(
            ErrorCode expected,
            org.assertj.core.api.ThrowableAssert.ThrowingCallable invocation) {
        assertThatThrownBy(invocation)
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    private MailQueryRepository.OwnerProposalHandoffRow row(
            UUID proposalId,
            UUID commandId,
            MailTypes.ProposalType type,
            Long decidedBy,
            String state,
            String resultRef,
            long version) {
        return new MailQueryRepository.OwnerProposalHandoffRow(
                proposalId, commandId, type,
                UUID.nameUUIDFromBytes(("source:" + proposalId).getBytes(
                        java.nio.charset.StandardCharsets.UTF_8)),
                switch (type) {
                    case DRAFT_REPLY -> Map.of(
                            "tone", "PROFESSIONAL", "requiresConfirmation", true);
                    case CREATE_CALENDAR_EVENT -> Map.of(
                            "durationMinutes", 60, "requiresConfirmation", true);
                    case CREATE_TASK -> Map.of(
                            "priority", "HIGH", "requiresConfirmation", true);
                    case CREATE_LEAVE_REQUEST -> Map.of(
                            "durationDays", 1, "requiresConfirmation", true);
                    case ESCALATE_NOTIFICATION -> Map.of(
                            "channel", "IN_APP", "requiresConfirmation", true);
                },
                decidedBy,
                "/hr/absence?action=create", state, resultRef,
                OffsetDateTime.parse("2026-09-17T00:00:00Z"), version);
    }

    private MailQueryRepository.ProposalHandoffRow visible(
            MailQueryRepository.OwnerProposalHandoffRow row) {
        return new MailQueryRepository.ProposalHandoffRow(
                row.proposalId(), row.commandId(), row.ownerRoute(), row.ownerState(),
                row.resultRef(), row.updatedAt(), row.version());
    }

    private MailDtos.AccountSummary account() {
        return new MailDtos.AccountSummary(
                UUID.randomUUID(), "member@sk.com", "Member", "PERSONAL",
                MailTypes.ProviderType.DWP_SANDBOX, "ACTIVE", "READY", true);
    }
}
