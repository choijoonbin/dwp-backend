package com.dwp.services.platform.workplace.bookingorchestration;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationDtos.*;

@RestController
@RequestMapping("/v1/workplace")
public class WorkplaceBookingOrchestrationController {
    static final String TENANT = "X-DWP-Tenant-ID";
    static final String USER = "X-DWP-User-ID";
    static final String PERSON = "X-DWP-Person-Public-ID";
    static final String DISPLAY = "X-DWP-Display-Name-B64";
    static final String GROUPS = "X-DWP-Group-Refs";
    static final String LOCALE = "Accept-Language";
    static final String CORRELATION = "X-Correlation-ID";
    static final String IDEMPOTENCY = "Idempotency-Key";

    private final WorkplaceBookingOrchestrationService service;

    public WorkplaceBookingOrchestrationController(
            WorkplaceBookingOrchestrationService service) {
        this.service = service;
    }

    @GetMapping("/booking-intents/beneficiaries")
    public ApiResponse<AuthorizedBeneficiaries> beneficiaries(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(value = PERSON, required = false) UUID personPublicId,
            @RequestHeader(value = DISPLAY, required = false) String displayName,
            @RequestHeader(value = GROUPS, required = false) String groupRefs,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.beneficiaries(
                tenantId, actorId, personPublicId, decodeDisplayName(displayName), groupRefs));
    }

    @PostMapping("/booking-intents/preview")
    public ApiResponse<BookingIntentPreview> preview(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(value = PERSON, required = false) UUID personPublicId,
            @RequestHeader(value = DISPLAY, required = false) String displayName,
            @RequestHeader(value = GROUPS, required = false) String groupRefs,
            @RequestHeader(value = LOCALE, required = false) String locale,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody IntentPreviewRequest request,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.preview(
                tenantId, actorId, personPublicId, decodeDisplayName(displayName), groupRefs, locale,
                idempotencyKey, correlationId, request));
    }

    @GetMapping("/booking-intents/{intentId}")
    public ApiResponse<BookingIntentStatus> intentStatus(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(value = LOCALE, required = false) String locale,
            @PathVariable UUID intentId,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.intentStatus(
                tenantId, actorId, intentId, locale));
    }

    @PostMapping("/booking-intents/{intentId}/holds")
    public ApiResponse<HoldResponse> holds(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(value = GROUPS, required = false) String groupRefs,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID intentId,
            @Valid @RequestBody HoldRequest request,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.createHolds(
                tenantId, actorId, groupRefs, idempotencyKey, correlationId,
                intentId, request));
    }

    @PostMapping("/booking-batches")
    public ResponseEntity<ApiResponse<BatchStartResponse>> startBatch(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(value = GROUPS, required = false) String groupRefs,
            @RequestHeader(value = LOCALE, required = false) String locale,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody BatchStartRequest request) {
        BatchStartResponse result = service.startBatch(
                tenantId, actorId, idempotencyKey, correlationId, request);
        service.executeBatch(tenantId, result.batchId(), locale, groupRefs);
        return accepted(result);
    }

    @GetMapping("/booking-batches/{batchId}")
    public ApiResponse<BookingBatch> batch(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @PathVariable UUID batchId,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.batch(tenantId, actorId, batchId));
    }

    @PostMapping("/booking-batches/{batchId}/compensations")
    public ApiResponse<BookingBatch> compensate(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(value = GROUPS, required = false) String groupRefs,
            @RequestHeader(value = LOCALE, required = false) String locale,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID batchId,
            @Valid @RequestBody BatchCompensationRequest request,
            HttpServletResponse response) {
        Set<UUID> selected = service.beginCompensation(
                tenantId, actorId, batchId, idempotencyKey, correlationId, request);
        service.executeCompensation(
                tenantId, actorId, batchId, selected, locale, groupRefs);
        noStore(response);
        return ApiResponse.success(service.batch(tenantId, actorId, batchId));
    }

    @PostMapping("/booking-batches/{batchId}/replans")
    public ApiResponse<BookingIntentPreview> replan(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(value = PERSON, required = false) UUID personPublicId,
            @RequestHeader(value = DISPLAY, required = false) String displayName,
            @RequestHeader(value = GROUPS, required = false) String groupRefs,
            @RequestHeader(value = LOCALE, required = false) String locale,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID batchId,
            @Valid @RequestBody BatchReplanRequest request,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.replan(
                tenantId, actorId, personPublicId, decodeDisplayName(displayName), groupRefs, locale,
                batchId, idempotencyKey, correlationId, request));
    }

    @PostMapping("/waitlist-entries")
    public ApiResponse<WaitlistEntry> createWaitlist(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(value = PERSON, required = false) UUID personPublicId,
            @RequestHeader(value = DISPLAY, required = false) String displayName,
            @RequestHeader(value = GROUPS, required = false) String groupRefs,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @Valid @RequestBody WaitlistCreateRequest request,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.createWaitlist(
                tenantId, actorId, personPublicId, decodeDisplayName(displayName), groupRefs,
                idempotencyKey, correlationId, request));
    }

    @GetMapping("/waitlist-entries")
    public ApiResponse<WaitlistPage> waitlists(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    OffsetDateTime to,
            @RequestParam(required = false) Long beneficiaryUserId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.waitlists(
                tenantId, actorId, from, to, beneficiaryUserId, page, size));
    }

    @GetMapping("/waitlist-entries/{entryId}")
    public ApiResponse<WaitlistEntry> waitlist(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @PathVariable UUID entryId,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.waitlist(tenantId, actorId, entryId));
    }

    @PatchMapping("/waitlist-entries/{entryId}")
    public ApiResponse<WaitlistEntry> updateWaitlist(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID entryId,
            @Valid @RequestBody WaitlistUpdateRequest request,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.updateWaitlist(
                tenantId, actorId, entryId, idempotencyKey, correlationId, request));
    }

    @PostMapping("/waitlist-entries/{entryId}:cancel")
    public ApiResponse<WaitlistEntry> cancelWaitlist(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID entryId,
            @Valid @RequestBody WaitlistCancelRequest request,
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.cancelWaitlist(
                tenantId, actorId, entryId, idempotencyKey, correlationId, request));
    }

    @PostMapping("/alternative-offers/{offerId}:accept")
    public ResponseEntity<ApiResponse<BatchStartResponse>> acceptOffer(
            @RequestHeader(TENANT) long tenantId,
            @RequestHeader(USER) long actorId,
            @RequestHeader(value = GROUPS, required = false) String groupRefs,
            @RequestHeader(value = LOCALE, required = false) String locale,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(value = CORRELATION, required = false) String correlationId,
            @PathVariable UUID offerId,
            @Valid @RequestBody AlternativeOfferAcceptRequest request) {
        BatchStartResponse result = service.acceptAlternativeOffer(
                tenantId, actorId, offerId, idempotencyKey, correlationId, request);
        service.executeBatch(tenantId, result.batchId(), locale, groupRefs);
        return accepted(result);
    }

    private static ResponseEntity<ApiResponse<BatchStartResponse>> accepted(
            BatchStartResponse result) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0")
                .location(URI.create(result.statusUrl()))
                .body(ApiResponse.success(result));
    }

    private static void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0");
    }

    static String decodeDisplayName(String encoded) {
        if (encoded == null) return null;
        String value = encoded.trim();
        if (value.isEmpty() || value.length() > 512) throw invalidDisplayName();
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(value);
            if (bytes.length == 0 || bytes.length > 480) throw invalidDisplayName();
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString().trim();
            if (decoded.isEmpty() || decoded.codePointCount(0, decoded.length()) > 160
                    || decoded.codePoints().anyMatch(Character::isISOControl)) {
                throw invalidDisplayName();
            }
            return decoded;
        } catch (IllegalArgumentException | CharacterCodingException exception) {
            throw invalidDisplayName();
        }
    }

    private static BaseException invalidDisplayName() {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                "The trusted display-name header is invalid.");
    }
}
