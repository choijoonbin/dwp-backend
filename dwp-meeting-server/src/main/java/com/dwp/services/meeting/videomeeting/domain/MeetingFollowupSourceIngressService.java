package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.api.MeetingFollowupSourceDtos.Request;
import com.dwp.services.meeting.videomeeting.api.MeetingFollowupSourceDtos.Response;
import org.springframework.stereotype.Service;

/**
 * Application boundary for the signed Platform Work callback. Replay consumption is committed
 * before the content-free source decision is evaluated so a received assertion cannot be reused.
 */
@Service
public class MeetingFollowupSourceIngressService {

    private final MeetingFollowupAssertionReplayRepository replay;
    private final MeetingFollowupSourceService source;

    public MeetingFollowupSourceIngressService(
            MeetingFollowupAssertionReplayRepository replay,
            MeetingFollowupSourceService source) {
        this.replay = replay;
        this.source = source;
    }

    public Response resolve(
            MeetingFollowupAssertionVerifier.VerifiedAssertion assertion,
            Request request) {
        replay.consume(assertion);
        return source.resolve(request);
    }
}
