package com.dwp.services.time.clockingestion;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.LocalDate;
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
            long fieldPolicyRevision,
            String purposeCode,
            long authorizationRevision,
            String decisionRevision,
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

    /**
     * Pinned TimeCommandReceipt.v1. The final four values are owner-internal replay evidence and
     * are deliberately excluded from the HTTP representation.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ClockCommandReceipt(
            UUID receiptId,
            String commandType,
            String originatingAction,
            UUID subjectPrincipalPublicId,
            String populationScopeDigest,
            long fieldPolicyRevision,
            String purposeCode,
            long authorizationRevision,
            String status,
            UUID correlationId,
            String requestDigest,
            Instant createdAt,
            Long aggregateVersion,
            String resultRef,
            @JsonIgnore UUID idempotencyKey,
            @JsonIgnore UUID clockEventId,
            @JsonIgnore String resultDigest,
            @JsonIgnore boolean replayed) {}

    public record ClockProjectionQuery(
            long tenantId,
            Set<UUID> workerScope,
            Instant asOf,
            Long snapshotHighWater,
            List<String> afterKey,
            int limit,
            List<String> sort,
            Instant startFrom,
            Instant startTo,
            Instant endFrom,
            Instant endTo,
            LocalDate effectiveOn,
            String timeZone,
            List<String> ingestionCodes,
            Long minimumIngestionRevision,
            List<String> qualityCodes,
            String evidenceDigest) {

        public ClockProjectionQuery {
            workerScope = workerScope == null ? Set.of() : Set.copyOf(workerScope);
            afterKey = afterKey == null ? List.of() : List.copyOf(afterKey);
            sort = sort == null ? List.of() : List.copyOf(sort);
            ingestionCodes = ingestionCodes == null ? List.of() : List.copyOf(ingestionCodes);
            qualityCodes = qualityCodes == null ? List.of() : List.copyOf(qualityCodes);
        }
    }

    public record ClockExceptionProjectionQuery(
            long tenantId,
            Set<UUID> workerScope,
            Instant asOf,
            Long snapshotHighWater,
            List<String> afterKey,
            int limit,
            List<String> sort,
            LocalDate effectiveOn,
            String timeZone,
            List<String> exceptionCodes,
            Long minimumRevision,
            List<String> ownerCodes,
            List<String> ownerPublicIds,
            List<String> metadataKeys,
            List<String> metadataVersions) {

        public ClockExceptionProjectionQuery {
            workerScope = workerScope == null ? Set.of() : Set.copyOf(workerScope);
            afterKey = afterKey == null ? List.of() : List.copyOf(afterKey);
            sort = sort == null ? List.of() : List.copyOf(sort);
            exceptionCodes = exceptionCodes == null ? List.of() : List.copyOf(exceptionCodes);
            ownerCodes = ownerCodes == null ? List.of() : List.copyOf(ownerCodes);
            ownerPublicIds = ownerPublicIds == null ? List.of() : List.copyOf(ownerPublicIds);
            metadataKeys = metadataKeys == null ? List.of() : List.copyOf(metadataKeys);
            metadataVersions = metadataVersions == null ? List.of() : List.copyOf(metadataVersions);
        }
    }

    public record ClockProjectionCandidate(
            ClockEvent event,
            String statusCode,
            String qualityCode,
            UUID resolutionVoidEventId) {}

    public record ClockExceptionCandidate(
            ClockEvent event,
            String exceptionCode,
            String severity,
            String recoveryState,
            UUID resolutionVoidEventId) {}

    public record ClockProjectionPage<T>(
            long snapshotHighWater,
            List<T> items,
            boolean hasMore,
            List<String> nextKey) {

        public ClockProjectionPage {
            items = items == null ? List.of() : List.copyOf(items);
            nextKey = nextKey == null ? List.of() : List.copyOf(nextKey);
        }
    }

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
