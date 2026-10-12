package com.dwp.services.time.clockingestion;

import com.dwp.core.security.ProductSurfaceStepUpChallengeVerifier;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
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
            String routeContractKey,
            String contextKey,
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
            @NotNull UUID sourcePublicId,
            @NotBlank @Size(max = 200) String sourceEventKey,
            @Positive long sourceSequence,
            @NotNull ClockSourceType sourceType,
            UUID devicePublicId,
            @NotNull CaptureMode captureMode,
            UUID offlineBatchPublicId,
            @NotNull UUID workerPublicId,
            @NotNull UUID peopleAssignmentPublicId,
            @PositiveOrZero long peopleAssignmentRevision,
            @NotNull ClockEventType eventType,
            @NotNull Instant occurredAt,
            @NotNull LocalDateTime sourceLocalDateTime,
            @NotBlank @Size(max = 80) String sourceTimeZone,
            @Min(-840) @Max(840) int sourceUtcOffsetMinutes,
            @NotNull DstResolution dstResolution,
            @NotNull InstantAuthority instantAuthority,
            @NotBlank @Size(max = 40) String tzdbVersion,
            @NotBlank @Size(max = 80) String timeZoneRuleVersion,
            @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String sourceProvenanceDigest,
            @NotNull TrustLevel trustLevel) {}

    public record VoidClockCommand(
            @NotBlank
            @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,79}$")
            String reasonCode) {}

    /** Exact public proof headers for the v36 TIM corrective-void command. */
    public record ClockStepUpHeaders(
            @NotBlank @Size(max = 16_384) String challenge,
            @NotBlank
            @Size(max = 200)
            @Pattern(regexp = "^psr-[0-9a-f]{64}$")
            String decisionRevision,
            @NotNull @PositiveOrZero Long expectedObjectVersion) {}

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
            ClockEvent event,
            ProductSurfaceStepUpChallengeVerifier.VerifiedChallenge stepUpChallenge) {}

    private ClockIngestionModels() {}
}
