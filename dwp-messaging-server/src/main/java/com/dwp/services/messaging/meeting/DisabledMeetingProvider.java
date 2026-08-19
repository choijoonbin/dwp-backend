package com.dwp.services.messaging.meeting;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.security.MessagingRequestContext;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

public class DisabledMeetingProvider implements MeetingProvider {

    private final MeetingProperties properties;

    public DisabledMeetingProvider(MeetingProperties properties) {
        this.properties = properties;
    }

    @Override
    public MeetingProviderCapability capability() {
        String configured = properties.getProvider().toUpperCase(Locale.ROOT);
        String provider = configured.isBlank() || "DISABLED".equals(configured)
                ? "NONE"
                : configured;
        String reason = "NONE".equals(provider)
                ? "MEETING_PROVIDER_DISABLED"
                : "MEETING_PROVIDER_CONFIGURATION_INCOMPLETE";
        return new MeetingProviderCapability(
                false, provider, reason, false, false, false, false,
                Math.toIntExact(properties.getTokenTtl().toSeconds()));
    }

    @Override
    public PreparedRoom prepareRoom(UUID sessionId, long tenantId, UUID conversationId) {
        throw unavailable();
    }

    @Override
    public ParticipantToken issueParticipantToken(
            MeetingSession session,
            MessagingRequestContext.Subject subject,
            OffsetDateTime issuedAt) {
        throw unavailable();
    }

    @Override
    public void endRoom(MeetingSession session) {
        throw unavailable();
    }

    private BaseException unavailable() {
        return new BaseException(
                ErrorCode.EXTERNAL_SERVICE_ERROR,
                "Realtime meeting provider is unavailable. Inspect meeting capabilities first.");
    }
}
