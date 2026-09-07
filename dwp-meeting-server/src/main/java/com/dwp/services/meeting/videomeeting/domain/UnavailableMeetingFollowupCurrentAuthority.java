package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.videomeeting.api.MeetingFollowupSourceDtos.Request;
import org.springframework.stereotype.Component;

/**
 * Fail-closed production boundary until an approved Meeting-specific authority adapter exists.
 */
@Component
public final class UnavailableMeetingFollowupCurrentAuthority
        implements MeetingFollowupCurrentAuthority {

    @Override
    public Decision authorize(Request request) {
        return Decision.deny(Denial.AUTHORITY_UNVERIFIED);
    }
}
