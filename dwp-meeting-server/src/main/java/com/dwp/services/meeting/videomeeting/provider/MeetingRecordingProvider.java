package com.dwp.services.meeting.videomeeting.provider;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Governed recording boundary. Start/stop commands contain identifiers and policy evidence only.
 * Playback requests carry an opaque locator to the trusted broker, but media bytes, transcript
 * text, storage credentials, and long-lived access URLs never cross this port.
 */
public interface MeetingRecordingProvider {

    Capability capability();

    Receipt start(Command command);

    Receipt stop(Command command);

    AccessTicket issueAccessTicket(AccessRequest request);

    DeletionReceipt delete(DeleteRequest request);

    record Capability(
            boolean available,
            boolean egressAvailable,
            boolean storageAvailable,
            boolean speechToTextAvailable,
            boolean deletionAvailable,
            boolean cryptoShredAvailable,
            String processingRegion,
            String providerCode) {

        public static Capability unavailable() {
            return new Capability(
                    false, false, false, false, false, false, "none", "DISABLED");
        }
    }

    record Command(
            long tenantId,
            UUID meetingId,
            UUID recordingSessionId,
            long planVersion,
            UUID noticeId,
            String providerRoomName,
            String correlationId) {
    }

    record Receipt(UUID recordingSessionId, String commandState, String providerCommandId) {
    }

    record AccessRequest(
            long tenantId,
            UUID meetingId,
            UUID artifactId,
            long requesterUserId,
            String storageProvider,
            String objectKey,
            String contentType,
            String sourceSha256,
            long artifactVersion,
            OffsetDateTime expiresNoLaterThan,
            String correlationId) {
    }

    record AccessTicket(
            UUID artifactId,
            long requesterUserId,
            long artifactVersion,
            URI accessUri,
            OffsetDateTime expiresAt) {
    }

    record DeleteRequest(
            long tenantId,
            UUID meetingId,
            UUID artifactId,
            String storageProvider,
            String objectKey,
            String deletionBindingSha256,
            long artifactVersion,
            String correlationId) {
    }

    record DeletionReceipt(
            UUID artifactId,
            long artifactVersion,
            String providerDeletionId,
            OffsetDateTime deletedAt) {
    }
}
