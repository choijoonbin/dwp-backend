package com.dwp.services.meeting.videomeeting.provider;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Participant;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

public class DisabledMeetingMediaProvider implements MeetingMediaProvider {

    private final MeetingMediaProperties properties;

    public DisabledMeetingMediaProvider(MeetingMediaProperties properties) {
        this.properties = properties;
    }

    @Override
    public Capability capability() {
        String configured = properties.getProvider().toUpperCase(Locale.ROOT);
        String provider = configured.isBlank() || "DISABLED".equals(configured)
                ? "NONE" : configured;
        String reason = "NONE".equals(provider)
                ? "MEETING_PROVIDER_DISABLED"
                : "MEETING_PROVIDER_CONFIGURATION_INCOMPLETE";
        return new Capability(
                false, provider, reason, false, false, false, false,
                Math.toIntExact(properties.getTokenTtl().toSeconds()));
    }

    @Override
    public PreparedRoom prepareRoom(UUID meetingId, long tenantId, int maximumParticipants) {
        throw unavailable();
    }

    @Override
    public ParticipantToken issueParticipantToken(
            Meeting meeting,
            Participant participant,
            MeetingRequestContext.Subject subject,
            EffectivePermissions permissions,
            OffsetDateTime issuedAt) {
        throw unavailable();
    }

    @Override
    public void endRoom(Meeting meeting) {
        throw unavailable();
    }

    private BaseException unavailable() {
        return new BaseException(
                ErrorCode.EXTERNAL_SERVICE_ERROR,
                "Realtime meeting provider is unavailable. Inspect capabilities first.");
    }
}
