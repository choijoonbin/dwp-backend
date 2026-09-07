package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.api.MeetingFollowupSourceDtos;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MeetingFollowupSourceIngressServiceTest {

    @Test
    void consumesReplayFenceBeforeResolvingTheMeetingSource() {
        MeetingFollowupAssertionReplayRepository replay =
                mock(MeetingFollowupAssertionReplayRepository.class);
        MeetingFollowupSourceService source = mock(MeetingFollowupSourceService.class);
        var assertion = mock(MeetingFollowupAssertionVerifier.VerifiedAssertion.class);
        var request = mock(MeetingFollowupSourceDtos.Request.class);
        var response = mock(MeetingFollowupSourceDtos.Response.class);
        when(source.resolve(request)).thenReturn(response);
        var ingress = new MeetingFollowupSourceIngressService(replay, source);

        assertThat(ingress.resolve(assertion, request)).isSameAs(response);

        InOrder order = inOrder(replay, source);
        order.verify(replay).consume(assertion);
        order.verify(source).resolve(request);
    }
}
