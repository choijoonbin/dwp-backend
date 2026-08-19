package com.dwp.services.messaging.meeting;

public record MeetingProviderCapability(
        boolean available,
        String provider,
        String unavailableReason,
        boolean audio,
        boolean video,
        boolean screenShare,
        boolean participantList,
        int tokenTtlSeconds) {
}
