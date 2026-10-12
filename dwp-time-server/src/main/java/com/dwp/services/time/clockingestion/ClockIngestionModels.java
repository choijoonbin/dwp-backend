package com.dwp.services.time.clockingestion;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Closed domain and transport-neutral models for the immutable TIM clock stream. */
public final class ClockIngestionModels {

    public enum ClockAction {
        VIEW,
        RECORD,
        CORRECT
    }

    public enum ClockEventType {
        IN,
        OUT,
        BREAK_START,
        BREAK_END,
        VOID
    }

    public enum ClockSourceType {
        WEB,
        MOBILE,
        KIOSK,
        DEVICE,
        IMPORT,
        API
    }

    public enum CaptureMode {
        ONLINE,
        OFFLINE
    }

    public enum DstResolution {
        EXACT_OFFSET_SUPPLIED,
        EARLIER_OFFSET_SELECTED,
        LATER_OFFSET_SELECTED,
        GAP_SHIFT_FORWARD
    }

    public enum InstantAuthority {
        SOURCE_INSTANT,
        LOCAL_WITH_OFFSET,
        SERVER_RESOLVED
    }

    public enum TrustLevel {
        LOW,
        STANDARD,
        VERIFIED
    }

    public record VerifiedClockRequest(
            long tenantId,
            long actorId,
            UUID subjectPrincipalPublicId,
            Set<ClockAction> actions,
            UUID populationPublicId,
            long populationRevision,
            long grantRevision,
            long membershipRevision,
            UUID workerPublicId,
            UUID peopleAssignmentPublicId,
            long peopleAssignmentRevision,
            String gatewayScopeKey,
            String populationScopeDigest,
            String populationDigest,
            String membershipDigest,
            String grantDigest,
            String fieldPolicyRevision,
            String purposeCode,
            String authorizationRevision,
            Instant revalidateAt,
            UUID correlationId,
            Set<UUID> authorizedWorkerPublicIds,
            String queryScopeRevision,
            Set<String> maskedFieldGroups,
            Set<String> omittedFieldGroups,
            Set<UUID> sourceScope,
            boolean stepUpSatisfied) {

        public VerifiedClockRequest {
            actions = actions == null ? Set.of() : Set.copyOf(actions);
            authorizedWorkerPublicIds = authorizedWorkerPublicIds == null
                    ? Set.of()
                    : Set.copyOf(authorizedWorkerPublicIds);
            maskedFieldGroups = maskedFieldGroups == null
                    ? Set.of()
                    : Set.copyOf(maskedFieldGroups);
            omittedFieldGroups = omittedFieldGroups == null
                    ? Set.of()
                    : Set.copyOf(omittedFieldGroups);
            sourceScope = sourceScope == null ? Set.of() : Set.copyOf(sourceScope);
        }
    }

    public record RecordClockCommand(
            UUID sourcePublicId,
            String sourceEventKey,
            long sourceSequence,
            ClockSourceType sourceType,
            UUID devicePublicId,
            CaptureMode captureMode,
            UUID offlineBatchPublicId,
            UUID workerPublicId,
            UUID peopleAssignmentPublicId,
            long peopleAssignmentRevision,
            ClockEventType eventType,
            Instant occurredAt,
            LocalDateTime sourceLocalDateTime,
            String sourceTimeZone,
            int sourceUtcOffsetMinutes,
            DstResolution dstResolution,
            InstantAuthority instantAuthority,
            String tzdbVersion,
            String timeZoneRuleVersion,
            String sourceProvenanceDigest,
            TrustLevel trustLevel) {}

    public record VoidClockCommand(String reasonCode) {}

    public record ClockEvent(
            UUID clockEventId,
            long eventOrder,
            long tenantId,
            UUID sourcePublicId,
            String sourceEventKey,
            Long sourceSequence,
            ClockSourceType sourceType,
            UUID devicePublicId,
            CaptureMode captureMode,
            UUID offlineBatchPublicId,
            UUID workerPublicId,
            UUID peopleAssignmentPublicId,
            long peopleAssignmentRevision,
            ClockEventType eventType,
            Instant occurredAt,
            Instant receivedAt,
            LocalDateTime sourceLocalDateTime,
            String sourceTimeZone,
            int sourceUtcOffsetMinutes,
            DstResolution dstResolution,
            InstantAuthority instantAuthority,
            String tzdbVersion,
            String timeZoneRuleVersion,
            String sourceProvenanceDigest,
            String payloadDigest,
            TrustLevel trustLevel,
            UUID voidsClockEventId,
            String correctionReasonCode,
            UUID correlationId,
            long recordedBy) {}

    public record ClockCommandReceipt(
            UUID receiptId,
            UUID idempotencyKey,
            String commandType,
            UUID clockEventId,
            String status,
            String requestDigest,
            String resultDigest,
            UUID correlationId,
            Instant createdAt,
            boolean replayed) {}

    public record ClockWorkbench(
            String queryState,
            String freshness,
            Instant asOf,
            List<String> partialFailures,
            List<ClockEvent> events) {

        public ClockWorkbench {
            partialFailures = partialFailures == null ? List.of() : List.copyOf(partialFailures);
            events = events == null ? List.of() : List.copyOf(events);
        }
    }

    public record ClockException(
            UUID exceptionId,
            UUID clockEventId,
            String code,
            String severity,
            String state,
            long eventOrder,
            UUID workerPublicId,
            UUID sourcePublicId,
            Long sourceSequence,
            Instant occurredAt,
            String evidenceDigest,
            UUID resolutionVoidEventId) {}

    public record ClockExceptionWorkbench(
            String queryState,
            String freshness,
            Instant asOf,
            List<String> partialFailures,
            List<ClockException> exceptions) {

        public ClockExceptionWorkbench {
            partialFailures = partialFailures == null ? List.of() : List.copyOf(partialFailures);
            exceptions = exceptions == null ? List.of() : List.copyOf(exceptions);
        }
    }

    public record ClockAppend(
            VerifiedClockRequest authority,
            UUID idempotencyKey,
            String commandType,
            String requestDigest,
            ClockEvent event) {}

    private ClockIngestionModels() {}
}
