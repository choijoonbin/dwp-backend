package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.videomeeting.api.VideoMeetingDtos;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.AccessScope;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;

import java.util.List;

/**
 * Release boundary for meeting entry modes that do not yet have an identity authority.
 *
 * <p>The persisted schema deliberately retains the future enum and policy fields. They are
 * not capabilities by themselves. Opening any of them requires the same verified guest or
 * pre-host authority to be present at creation, admission, and media-token issuance.</p>
 */
final class VideoMeetingEntryPolicy {

    private VideoMeetingEntryPolicy() {
    }

    static void requireSupportedCreation(
            AccessScope accessScope,
            Boolean guestAccessEnabled,
            Boolean allowJoinBeforeHost,
            List<VideoMeetingDtos.GuestInvitee> guestInvitees) {
        boolean guestsRequested = guestInvitees != null && !guestInvitees.isEmpty();
        if (accessScope == AccessScope.PUBLIC_CODE || guestsRequested
                || Boolean.TRUE.equals(guestAccessEnabled)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "External meeting access is not available until verified guest identity "
                            + "and scoped invitation evidence are configured.");
        }
        if (Boolean.TRUE.equals(allowJoinBeforeHost)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Joining before the host is not available until governed pre-host room "
                            + "activation and host-presence evidence are configured.");
        }
    }

    static boolean requiresUnverifiedAuthority(Meeting meeting) {
        return meeting.accessScope() == AccessScope.PUBLIC_CODE
                || meeting.guestAccessEnabled()
                || meeting.allowJoinBeforeHost();
    }

    static BaseException runtimeUnavailable() {
        return new BaseException(
                ErrorCode.INVALID_STATE,
                "This meeting uses an entry mode whose identity authority is not configured.");
    }
}
