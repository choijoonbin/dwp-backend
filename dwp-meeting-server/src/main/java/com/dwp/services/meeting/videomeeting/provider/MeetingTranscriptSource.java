package com.dwp.services.meeting.videomeeting.provider;

import java.util.List;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Reads normalized transcript segments from trusted storage without exposing object keys to APIs. */
public interface MeetingTranscriptSource {

    boolean available();

    List<MeetingIntelligenceProvider.TranscriptSegment> read(ReadContext context);

    /** A live broker probe; callers must invoke it outside database transactions. */
    default RetentionCapability retentionCapability() {
        return RetentionCapability.unavailable();
    }

    /** Deletes and crypto-shreds one exact transcript object through the trusted broker. */
    default DeletionReceipt delete(DeleteRequest request) {
        throw new IllegalStateException("Meeting transcript deletion is unavailable.");
    }

    record ReadContext(
            long tenantId,
            UUID meetingId,
            UUID runId,
            UUID artifactId,
            String expectedSha256,
            String correlationId) {
    }

    record RetentionCapability(
            boolean available,
            boolean deletionAvailable,
            boolean cryptoShredAvailable,
            boolean customerManagedStorage,
            boolean providerRetentionDisabled,
            boolean orphanCleanupAvailable,
            int maximumOrphanTtlSeconds,
            boolean legacyLocatorDeletionAvailable,
            String providerCode,
            String storageProviderCode) {

        public static RetentionCapability unavailable() {
            return new RetentionCapability(
                    false, false, false, false, false, false, 0,
                    false, "DISABLED", "DISABLED");
        }
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
