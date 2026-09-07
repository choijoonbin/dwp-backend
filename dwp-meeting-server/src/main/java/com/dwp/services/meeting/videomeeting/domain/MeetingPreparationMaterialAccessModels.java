package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.services.meeting.security.MeetingRequestContext;
import com.dwp.services.meeting.videomeeting.domain.VideoMeetingModels.Meeting;

import java.time.OffsetDateTime;
import java.util.UUID;

final class MeetingPreparationMaterialAccessModels {

    private MeetingPreparationMaterialAccessModels() {
    }

    record Material(
            UUID materialId,
            long tenantId,
            UUID meetingId,
            String displayName,
            String contentType,
            String referenceProvider,
            String opaqueReference,
            String sourceVersion,
            String classification,
            String contentSha256,
            OffsetDateTime retentionUntil,
            String lifecycleState,
            long version) {
    }

    record PreparedAccess(
            MeetingRequestContext.Subject subject,
            Meeting meeting,
            Material material,
            OffsetDateTime expiresNoLaterThan,
            String referenceBindingSha256,
            String correlationId) {
    }
}
