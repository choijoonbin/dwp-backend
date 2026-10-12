package com.dwp.services.time.clockingestion;

import static com.dwp.services.time.clockingestion.ClockOwnerAccessFilter.VERIFIED_REQUEST_ATTRIBUTE;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockCommandReceipt;
import com.dwp.services.time.clockingestion.ClockIngestionModels.ClockStepUpHeaders;
import com.dwp.services.time.clockingestion.ClockIngestionModels.RecordClockCommand;
import com.dwp.services.time.clockingestion.ClockIngestionModels.VerifiedClockRequest;
import com.dwp.services.time.clockingestion.ClockIngestionModels.VoidClockCommand;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Exact owner HTTP operations for IAQ-034, IAQ-042, PEP-TIM-007, and PEP-TIM-008. */
@RestController
@Validated
@ConditionalOnProperty(
        name = "dwp.hris.time.clock-ingestion.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class ClockOwnerController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String STEP_UP_CHALLENGE = "X-DWP-Step-Up-Challenge";
    private static final String EXPECTED_DECISION_REVISION =
            "X-DWP-Expected-Decision-Revision";
    private static final String EXPECTED_OBJECT_VERSION = "X-DWP-Expected-Object-Version";

    private final ClockIngestionService service;

    public ClockOwnerController(ClockIngestionService service) {
        this.service = service;
    }

    @Operation(operationId = "hrisTimeClockIngestionWorkbench")
    @GetMapping("/v1/hris/workbench/clock-ingestion")
    public ApiResponse<ClockIaQueryModels.ClockProjection> workbench(
            @RequestAttribute(VERIFIED_REQUEST_ATTRIBUTE) VerifiedClockRequest verified,
            @RequestParam int limit,
            @RequestParam List<String> sort,
            @RequestParam String locale,
            @RequestParam String timeZone,
            @RequestParam UUID correlationId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant clockEventStartFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant clockEventStartTo,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant clockEventEndFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant clockEventEndTo,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveOn,
            @RequestParam(required = false) List<String> ingestionStatusCodes,
            @RequestParam(required = false) Long minimumIngestionRevision,
            @RequestParam(required = false) List<String> qualityDecisionCodes,
            @RequestParam(required = false) String qualityEvidenceDigest) {
        ClockIaQueryModels.TemporalFilter temporal =
                clockEventStartFrom == null && clockEventStartTo == null
                                && clockEventEndFrom == null && clockEventEndTo == null
                        ? null
                        : new ClockIaQueryModels.TemporalFilter(
                                clockEventStartFrom, clockEventStartTo,
                                clockEventEndFrom, clockEventEndTo);
        ClockIaQueryModels.StatusFilter status =
                ingestionStatusCodes == null && minimumIngestionRevision == null
                        ? null
                        : new ClockIaQueryModels.StatusFilter(
                                ingestionStatusCodes, minimumIngestionRevision);
        ClockIaQueryModels.AuditFilter quality =
                qualityDecisionCodes == null && qualityEvidenceDigest == null
                        ? null
                        : new ClockIaQueryModels.AuditFilter(
                                qualityDecisionCodes, qualityEvidenceDigest);
        return ApiResponse.success(service.workbench(
                verified,
                new ClockIaQueryModels.ClockQueryRequest(
                        limit,
                        new ClockIaQueryModels.ClockFilter(
                                temporal, effectiveOn, status, quality),
                        sort, locale, timeZone, correlationId, asOf, cursor)));
    }

    @Operation(operationId = "hrisTimeClockIngestionExceptions")
    @GetMapping("/v1/hris/workbench/time-exceptions")
    public ApiResponse<ClockIaQueryModels.ExceptionProjection> exceptions(
            @RequestAttribute(VERIFIED_REQUEST_ATTRIBUTE) VerifiedClockRequest verified,
            @RequestParam int limit,
            @RequestParam List<String> sort,
            @RequestParam String locale,
            @RequestParam String timeZone,
            @RequestParam UUID correlationId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveOn,
            @RequestParam(required = false) List<String> exceptionCodes,
            @RequestParam(required = false) Long minimumExceptionRevision,
            @RequestParam(required = false) List<String> ownerDeepLinkCodes,
            @RequestParam(required = false) List<String> ownerDeepLinkPublicIds,
            @RequestParam(required = false) List<String> recoveryMetadataKeys,
            @RequestParam(required = false) List<String> recoveryMetadataVersions) {
        ClockIaQueryModels.StatusFilter exception =
                exceptionCodes == null && minimumExceptionRevision == null
                        ? null
                        : new ClockIaQueryModels.StatusFilter(
                                exceptionCodes, minimumExceptionRevision);
        ClockIaQueryModels.ReferenceFilter owner =
                ownerDeepLinkCodes == null && ownerDeepLinkPublicIds == null
                        ? null
                        : new ClockIaQueryModels.ReferenceFilter(
                                ownerDeepLinkCodes, ownerDeepLinkPublicIds);
        ClockIaQueryModels.MetadataFilter recovery =
                recoveryMetadataKeys == null && recoveryMetadataVersions == null
                        ? null
                        : new ClockIaQueryModels.MetadataFilter(
                                recoveryMetadataKeys, recoveryMetadataVersions);
        return ApiResponse.success(service.exceptions(
                verified,
                new ClockIaQueryModels.ExceptionQueryRequest(
                        limit,
                        new ClockIaQueryModels.ExceptionFilter(
                                effectiveOn, exception, owner, recovery),
                        sort, locale, timeZone, correlationId, asOf, cursor)));
    }

    @Operation(operationId = "hrisTimeRecordClockEvent")
    @PostMapping("/v1/clock-events")
    public ApiResponse<ClockCommandReceipt> record(
            @RequestAttribute(VERIFIED_REQUEST_ATTRIBUTE) VerifiedClockRequest verified,
            @RequestHeader(IDEMPOTENCY_KEY) UUID idempotencyKey,
            @Valid @RequestBody RecordClockCommand command) {
        return ApiResponse.success(service.record(verified, idempotencyKey, command));
    }

    @Operation(operationId = "hrisTimeVoidClockEvent")
    @PostMapping("/v1/clock-events/{eventId}/voids")
    public ApiResponse<ClockCommandReceipt> voidEvent(
            @RequestAttribute(VERIFIED_REQUEST_ATTRIBUTE) VerifiedClockRequest verified,
            @RequestHeader(IDEMPOTENCY_KEY) UUID idempotencyKey,
            @RequestHeader(STEP_UP_CHALLENGE)
            @NotBlank @Size(max = 16_384) String challenge,
            @RequestHeader(EXPECTED_DECISION_REVISION)
            @NotBlank @Size(max = 200)
            @Pattern(regexp = "^psr-[0-9a-f]{64}$") String decisionRevision,
            @RequestHeader(EXPECTED_OBJECT_VERSION)
            @PositiveOrZero Long expectedObjectVersion,
            @PathVariable UUID eventId,
            @Valid @RequestBody VoidClockCommand command) {
        return ApiResponse.success(service.voidEvent(
                verified,
                eventId,
                idempotencyKey,
                new ClockStepUpHeaders(
                        challenge, decisionRevision, expectedObjectVersion),
                command));
    }
}
