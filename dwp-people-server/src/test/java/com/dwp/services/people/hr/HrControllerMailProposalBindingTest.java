package com.dwp.services.people.hr;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class HrControllerMailProposalBindingTest {

    private final HrService service = mock(HrService.class);
    private final HrController controller = new HrController(service);

    @Test
    void partialMailOwnerBindingFailsBeforeTheLeaveMutation() {
        HrDtos.CreateLeaveRequest request = request();

        assertThatThrownBy(() -> controller.createLeaveRequest(
                request,
                "corr-partial",
                UUID.randomUUID(),
                null,
                3L))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

        verify(service, never()).createLeaveRequest(any(), any(), any());
    }

    @Test
    void completeMailOwnerBindingIsPassedToTheTransactionalService() {
        HrDtos.CreateLeaveRequest request = request();
        UUID proposalId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();

        controller.createLeaveRequest(
                request,
                "corr-complete",
                proposalId,
                commandId,
                7L);

        verify(service).createLeaveRequest(
                request,
                "corr-complete",
                new HrMailProposalBinding(proposalId, commandId, 7L));
    }

    private HrDtos.CreateLeaveRequest request() {
        Instant start = Instant.parse("2026-10-05T00:00:00Z");
        return new HrDtos.CreateLeaveRequest(
                UUID.randomUUID(), start, start.plusSeconds(28_800), 480, "Annual leave");
    }
}
