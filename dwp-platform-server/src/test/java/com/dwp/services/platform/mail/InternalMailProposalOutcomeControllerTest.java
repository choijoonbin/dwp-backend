package com.dwp.services.platform.mail;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailProposalOutcomePort.Owner.HR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalMailProposalOutcomeControllerTest {

    @Mock
    private MailProposalOutcomePort outcomes;

    private InternalMailProposalOutcomeController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalMailProposalOutcomeController(outcomes);
    }

    @Test
    void peopleServicePreflightPinsTheHrOwnerAndFullBinding() {
        UUID proposalId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        var request = request(proposalId, commandId, 5L, null);

        var response = controller.preflight(
                "dwp-people-server", 3L, 17L, request);

        assertThat(response.getData()).isTrue();
        verify(outcomes).validateNewExecution(
                3L, 17L, HR,
                new MailProposalHandoffBinding(proposalId, commandId, 5L),
                new MailProposalOutcomePort.OwnerMutation(
                        null, Map.of("durationDays", 1)));
    }

    @Test
    void peopleServiceRecordPinsIdentityActorBindingResultAndCorrelation() {
        UUID proposalId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        String resultRef = "hr-leave-request:" + UUID.randomUUID();
        var request = request(proposalId, commandId, 5L, resultRef);
        var handoff = new MailDtos.ProposalHandoff(
                proposalId, commandId, "/hr/absence?action=create",
                "/mail/actions?proposalId=" + proposalId,
                "mail-proposal-" + proposalId,
                MailDtos.ProposalHandoffStatus.EXECUTED,
                resultRef, OffsetDateTime.parse("2026-09-17T00:00:00Z"), 6L);
        when(outcomes.executed(
                3L, 17L, HR,
                new MailProposalHandoffBinding(proposalId, commandId, 5L),
                resultRef, "corr-owner"))
                .thenReturn(handoff);

        var response = controller.record(
                "dwp-people-server", 3L, 17L, "corr-owner", request);

        assertThat(response.getData()).isEqualTo(handoff);
        verify(outcomes).executed(
                3L, 17L, HR,
                new MailProposalHandoffBinding(proposalId, commandId, 5L),
                resultRef, "corr-owner");
    }

    @Test
    void forgedServiceIdentityCannotPreflightOrRecordAnHrOutcome() {
        var request = request(
                UUID.randomUUID(), UUID.randomUUID(), 5L,
                "hr-leave-request:" + UUID.randomUUID());

        assertForbidden(() -> controller.preflight(
                "browser-client", 3L, 17L, request));
        assertForbidden(() -> controller.record(
                "dwp-platform-server", 3L, 17L, "corr-forged", request));

        verifyNoInteractions(outcomes);
    }

    private InternalMailProposalOutcomeController.OwnerOutcomeRequest request(
            UUID proposalId,
            UUID commandId,
            long version,
            String resultRef) {
        return new InternalMailProposalOutcomeController.OwnerOutcomeRequest(
                proposalId, commandId, version, resultRef,
                resultRef == null ? Map.of("durationDays", 1) : null);
    }

    private void assertForbidden(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable invocation) {
        assertThatThrownBy(invocation)
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }
}
