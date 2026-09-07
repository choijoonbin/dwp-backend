package com.dwp.services.meeting.videomeeting.provider;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Trusted owner-ACL boundary for preparation material access; file bytes never enter Meeting. */
public interface MeetingPreparationMaterialProvider {

    AccessTicket issueAccessTicket(AccessRequest request);

    record AccessRequest(
            long tenantId,
            UUID meetingId,
            UUID materialId,
            long requesterUserId,
            String referenceProvider,
            String opaqueReference,
            String sourceVersion,
            String classification,
            String contentType,
            String contentSha256,
            String referenceBindingSha256,
            long materialVersion,
            OffsetDateTime expiresNoLaterThan,
            String correlationId) {
    }

    record AccessTicket(
            UUID materialId,
            long requesterUserId,
            long materialVersion,
            String referenceBindingSha256,
            URI accessUri,
            OffsetDateTime expiresAt) {
    }
}
