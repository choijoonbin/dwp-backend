package com.dwp.services.time.close;

import static com.dwp.services.time.close.TimeCloseOwnerAccessFilter.VERIFIED_REQUEST_ATTRIBUTE;

import com.dwp.services.time.close.TimeCloseModels.PayrollTimeHandoffCommandV1;
import com.dwp.services.time.close.TimeCloseModels.PayrollTimeHandoffStatusV1;
import com.dwp.services.time.close.TimeCloseModels.TimeCloseCommandReceiptV1;
import com.dwp.services.time.close.TimeCloseModels.TimeCloseCommandV1;
import com.dwp.services.time.close.TimeCloseModels.TimeCloseValidationCommandV1;
import com.dwp.services.time.close.TimeCloseModels.TimeReopenCommandV1;
import com.dwp.services.time.close.TimeCloseModels.VerifiedCloseRequest;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Raw central-OpenAPI-compatible owner boundary for PEP-TIM-022..026. */
@RestController
@ConditionalOnProperty(
        name = "dwp.hris.time.close.enabled",
        havingValue = "true",
        matchIfMissing = false)
public final class TimeCloseOwnerController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String STEP_UP = "X-DWP-Step-Up-Challenge";
    static final String CORRELATION_ID = "X-Correlation-ID";
    private static final Pattern STRONG_VERSION = Pattern.compile("^\"[0-9]+\"$");
    private static final ObjectMapper STRICT = new ObjectMapper(
            JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build())
            .findAndRegisterModules()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final TimeCloseService service;
    private final TimeCloseIaProjectionService projections;

    public TimeCloseOwnerController(
            TimeCloseService service, TimeCloseIaProjectionService projections) {
        this.service = service;
        this.projections = projections;
    }

    @Operation(operationId = "hrisTimeCommandCenter")
    @GetMapping("/v1/hris/workbench/time-command-center")
    public ResponseEntity<TimeCloseIaQueryModels.TimeCloseProjection> commandCenter(
            @RequestAttribute(VERIFIED_REQUEST_ATTRIBUTE) VerifiedCloseRequest authority,
            @RequestHeader(CORRELATION_ID) UUID correlationId,
            HttpServletRequest request) {
        return projection(authority, TimeCloseOwnerRoute.COMMAND_CENTER, correlationId, request);
    }

    @Operation(operationId = "hrisTimeDailyClose")
    @GetMapping("/v1/hris/workbench/time-daily-close")
    public ResponseEntity<TimeCloseIaQueryModels.TimeCloseProjection> dailyClose(
            @RequestAttribute(VERIFIED_REQUEST_ATTRIBUTE) VerifiedCloseRequest authority,
            @RequestHeader(CORRELATION_ID) UUID correlationId,
            HttpServletRequest request) {
        return projection(authority, TimeCloseOwnerRoute.DAILY_CLOSE, correlationId, request);
    }

    @Operation(operationId = "hrisTimeMonthlyClose")
    @GetMapping("/v1/hris/workbench/time-monthly-close")
    public ResponseEntity<TimeCloseIaQueryModels.TimeCloseProjection> monthlyClose(
            @RequestAttribute(VERIFIED_REQUEST_ATTRIBUTE) VerifiedCloseRequest authority,
            @RequestHeader(CORRELATION_ID) UUID correlationId,
            HttpServletRequest request) {
        return projection(authority, TimeCloseOwnerRoute.MONTHLY_CLOSE, correlationId, request);
    }

    @Operation(operationId = "hrisTimeValidateClosePeriod")
    @PostMapping("/v1/close-periods/{periodId}/validations")
    public ResponseEntity<TimeCloseCommandReceiptV1> validate(
            @RequestAttribute(VERIFIED_REQUEST_ATTRIBUTE) VerifiedCloseRequest authority,
            @PathVariable UUID periodId,
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = STEP_UP, required = false) String forbiddenStepUp,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(schema = @Schema(
                            implementation = TimeCloseValidationCommandV1.class)))
            @RequestBody String rawBody) {
        requireAbsent(forbiddenStepUp, STEP_UP);
        TimeCloseValidationCommandV1 body = strictBody(
                rawBody, TimeCloseValidationCommandV1.class);
        return accepted(authority, service.validate(
                authority, periodId, requireIdempotency(idempotencyKey),
                parseVersion(ifMatch), body, rawBody));
    }

    @Operation(operationId = "hrisTimeClosePeriod")
    @PostMapping("/v1/close-periods/{periodId}/close")
    public ResponseEntity<TimeCloseCommandReceiptV1> close(
            @RequestAttribute(VERIFIED_REQUEST_ATTRIBUTE) VerifiedCloseRequest authority,
            @PathVariable UUID periodId,
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = STEP_UP, required = false) String stepUp,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(schema = @Schema(
                            implementation = TimeCloseCommandV1.class)))
            @RequestBody String rawBody) {
        TimeCloseCommandV1 body = strictBody(rawBody, TimeCloseCommandV1.class);
        return accepted(authority, service.close(
                authority, periodId, requireIdempotency(idempotencyKey),
                parseVersion(ifMatch), requireStepUp(stepUp), body, rawBody));
    }

    @Operation(operationId = "hrisTimeReopenPeriod")
    @PostMapping("/v1/close-periods/{periodId}/reopen")
    public ResponseEntity<TimeCloseCommandReceiptV1> reopen(
            @RequestAttribute(VERIFIED_REQUEST_ATTRIBUTE) VerifiedCloseRequest authority,
            @PathVariable UUID periodId,
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = STEP_UP, required = false) String stepUp,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(schema = @Schema(
                            implementation = TimeReopenCommandV1.class)))
            @RequestBody String rawBody) {
        TimeReopenCommandV1 body = strictBody(rawBody, TimeReopenCommandV1.class);
        return accepted(authority, service.reopen(
                authority, periodId, requireIdempotency(idempotencyKey),
                parseVersion(ifMatch), requireStepUp(stepUp), body, rawBody));
    }

    @Operation(operationId = "hrisTimeCreatePayrollHandoff")
    @PostMapping("/v1/close-periods/{periodId}/payroll-handoffs")
    public ResponseEntity<TimeCloseCommandReceiptV1> handoff(
            @RequestAttribute(VERIFIED_REQUEST_ATTRIBUTE) VerifiedCloseRequest authority,
            @PathVariable UUID periodId,
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = STEP_UP, required = false) String forbiddenStepUp,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(schema = @Schema(
                            implementation = PayrollTimeHandoffCommandV1.class)))
            @RequestBody String rawBody) {
        requireAbsent(forbiddenStepUp, STEP_UP);
        PayrollTimeHandoffCommandV1 body = strictBody(
                rawBody, PayrollTimeHandoffCommandV1.class);
        return accepted(authority, service.createHandoff(
                authority, periodId, requireIdempotency(idempotencyKey),
                parseVersion(ifMatch), body, rawBody));
    }

    @Operation(operationId = "hrisTimeGetPayrollHandoff")
    @GetMapping("/v1/payroll-handoffs/{handoffId}")
    public ResponseEntity<PayrollTimeHandoffStatusV1> handoffStatus(
            @RequestAttribute(VERIFIED_REQUEST_ATTRIBUTE) VerifiedCloseRequest authority,
            @PathVariable UUID handoffId) {
        PayrollTimeHandoffStatusV1 status = service.getHandoff(authority, handoffId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(Long.toString(status.version()))
                .header("X-Correlation-ID", authority.correlationId().toString())
                .header("X-DWP-Decision-Revision", authority.decisionRevision())
                .body(status);
    }

    static long parseVersion(String value) {
        if (value == null) {
            throw new TimeCloseBoundaryFailure(
                    428, "PRECONDITION_REQUIRED", false, null,
                    "A strong If-Match header is required.");
        }
        if (!STRONG_VERSION.matcher(value).matches()) {
            throw schema("If-Match must be one strong numeric ETag.");
        }
        try {
            long parsed = Long.parseLong(value.substring(1, value.length() - 1));
            if (parsed <= 0) throw schema("If-Match must identify a positive version.");
            return parsed;
        } catch (NumberFormatException invalid) {
            throw schema("If-Match is outside the supported version range.");
        }
    }

    private ResponseEntity<TimeCloseIaQueryModels.TimeCloseProjection> projection(
            VerifiedCloseRequest authority,
            TimeCloseOwnerRoute route,
            UUID correlationId,
            HttpServletRequest request) {
        TimeCloseIaQueryModels.TimeCloseQuery query = projections.parse(
                route, authority, request, correlationId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(CORRELATION_ID, correlationId.toString())
                .header("X-DWP-Decision-Revision", authority.decisionRevision())
                .body(projections.query(authority, route, query));
    }

    static <T> T strictBody(String rawBody, Class<T> type) {
        try {
            JsonNode root = STRICT.readTree(rawBody);
            if (root == null || !root.isObject()) throw schema("JSON object required.");
            Set<String> exact;
            if (type == TimeCloseValidationCommandV1.class) {
                exact = Set.of("asOf");
                requireText(root, "asOf");
            } else if (type == TimeCloseCommandV1.class) {
                exact = Set.of("asOf", "approvalReceiptId");
                requireText(root, "asOf");
                requireText(root, "approvalReceiptId");
            } else if (type == TimeReopenCommandV1.class) {
                exact = Set.of("asOf", "approvalReceiptId", "reasonCode");
                requireText(root, "asOf");
                requireText(root, "approvalReceiptId");
                requireText(root, "reasonCode");
            } else if (type == PayrollTimeHandoffCommandV1.class) {
                exact = Set.of("closedTimeResultId", "payrollEntityId", "payPeriodId");
                requireText(root, "closedTimeResultId");
                requireText(root, "payrollEntityId");
                requireText(root, "payPeriodId");
            } else {
                throw schema("Unsupported TIM close command schema.");
            }
            if (root.size() != exact.size()
                    || !root.properties().stream()
                            .map(java.util.Map.Entry::getKey)
                            .allMatch(exact::contains)) {
                throw schema("The command has missing or additional fields.");
            }
            return STRICT.treeToValue(root, type);
        } catch (TimeCloseBoundaryFailure failure) {
            throw failure;
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            throw schema("The command does not match its sealed schema.");
        }
    }

    private static void requireText(JsonNode root, String field) {
        if (!root.has(field) || !root.get(field).isTextual()) {
            throw schema(field + " must be a non-null JSON string.");
        }
    }

    private static String requireIdempotency(String value) {
        if (value == null || value.length() < 16 || value.length() > 128
                || !value.equals(value.strip()) || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0 || value.chars().anyMatch(c -> c < 0x20)) {
            throw schema("Idempotency-Key is invalid.");
        }
        return value;
    }

    private static String requireStepUp(String value) {
        if (value == null || value.isBlank() || value.length() > 16_384
                || !value.equals(value.strip())) {
            throw schema("A bounded step-up challenge is required.");
        }
        return value;
    }

    private static void requireAbsent(String value, String header) {
        if (value != null) throw schema(header + " is forbidden for this operation.");
    }

    private static ResponseEntity<TimeCloseCommandReceiptV1> accepted(
            VerifiedCloseRequest authority, TimeCloseCommandReceiptV1 receipt) {
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .location(java.net.URI.create(
                        "/api/time/v1/command-receipts/" + receipt.receiptId()))
                .header(HttpHeaders.RETRY_AFTER, "1")
                .header("X-Correlation-ID", authority.correlationId().toString())
                .header("X-DWP-Decision-Revision", authority.decisionRevision())
                .body(receipt);
    }

    static TimeCloseBoundaryFailure schema(String message) {
        return new TimeCloseBoundaryFailure(
                400, "SCHEMA_INVALID", false, null, message);
    }
}

final class TimeCloseBoundaryFailure extends RuntimeException {
    private final int status;
    private final String code;
    private final boolean retryable;
    private final Long currentVersion;

    TimeCloseBoundaryFailure(
            int status, String code, boolean retryable, Long currentVersion, String message) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
        this.currentVersion = currentVersion;
    }

    int status() { return status; }
    String code() { return code; }
    boolean retryable() { return retryable; }
    Long currentVersion() { return currentVersion; }
}
