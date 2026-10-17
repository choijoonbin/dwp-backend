package com.dwp.services.time.timecard;

import static com.dwp.services.time.timecard.TimecardOwnerAccessFilter.VERIFIED_REQUEST_ATTRIBUTE;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.timecard.TimecardModels.TimeCommandReceiptV1;
import com.dwp.services.time.timecard.TimecardModels.TimecardCorrectionCommandV1;
import com.dwp.services.time.timecard.TimecardModels.TimecardDecisionCommandV1;
import com.dwp.services.time.timecard.TimecardModels.TimecardSubmitCommandV1;
import com.dwp.services.time.timecard.TimecardModels.TimecardV1;
import com.dwp.services.time.timecard.TimecardModels.VerifiedTimecardRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Raw pinned OpenAPI owner boundary for PEP-TIM-011/012/013/014. */
@RestController
@Validated
@ConditionalOnProperty(
        name = "dwp.hris.time.timecard.enabled",
        havingValue = "true",
        matchIfMissing = false)
public final class TimecardOwnerController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String EXPECTED_DECISION_REVISION =
            "X-DWP-Expected-Decision-Revision";
    private static final ObjectMapper STRICT_COMMAND_MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final TimecardService service;

    public TimecardOwnerController(TimecardService service) {
        this.service = service;
    }

    @Operation(operationId = "getTimecard")
    @GetMapping("/v1/timecards/{timecardId}")
    public ResponseEntity<TimecardV1> timecard(
            @RequestAttribute(VERIFIED_REQUEST_ATTRIBUTE)
            VerifiedTimecardRequest verified,
            @PathVariable UUID timecardId) {
        TimecardV1 result = service.get(verified, timecardId);
        return ResponseEntity.ok()
                .eTag(Long.toString(result.version()))
                .body(result);
    }

    @Operation(operationId = "correctTimecard")
    @PostMapping("/v1/timecards/{timecardId}/corrections")
    public ResponseEntity<TimeCommandReceiptV1> correct(
            @RequestAttribute(VERIFIED_REQUEST_ATTRIBUTE)
            VerifiedTimecardRequest verified,
            @PathVariable UUID timecardId,
            @NotBlank @Size(min = 16, max = 128)
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
            @NotBlank @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @NotBlank @Size(max = 200)
            @RequestHeader(EXPECTED_DECISION_REVISION) String expectedDecisionRevision,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(schema = @Schema(
                            implementation = TimecardCorrectionCommandV1.class)))
            @RequestBody String commandJson) {
        requireDecisionRevision(verified, expectedDecisionRevision);
        return ResponseEntity.accepted().body(service.correct(
                verified, timecardId, idempotencyKey, parseVersion(ifMatch),
                strictCommand(commandJson, TimecardCorrectionCommandV1.class)));
    }

    @Operation(operationId = "submitTimecard")
    @PostMapping("/v1/timecards/{timecardId}/submit")
    public ResponseEntity<TimeCommandReceiptV1> submit(
            @RequestAttribute(VERIFIED_REQUEST_ATTRIBUTE)
            VerifiedTimecardRequest verified,
            @PathVariable UUID timecardId,
            @NotBlank @Size(min = 16, max = 128)
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
            @NotBlank @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @NotBlank @Size(max = 200)
            @RequestHeader(EXPECTED_DECISION_REVISION) String expectedDecisionRevision,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(schema = @Schema(
                            implementation = TimecardSubmitCommandV1.class)))
            @RequestBody String commandJson) {
        requireDecisionRevision(verified, expectedDecisionRevision);
        return ResponseEntity.accepted().body(service.submit(
                verified, timecardId, idempotencyKey, parseVersion(ifMatch),
                strictCommand(commandJson, TimecardSubmitCommandV1.class)));
    }

    @Operation(operationId = "decideTimecard")
    @PostMapping("/v1/timecards/{timecardId}/decisions")
    public ResponseEntity<TimeCommandReceiptV1> decide(
            @RequestAttribute(VERIFIED_REQUEST_ATTRIBUTE)
            VerifiedTimecardRequest verified,
            @PathVariable UUID timecardId,
            @NotBlank @Size(min = 16, max = 128)
            @RequestHeader(IDEMPOTENCY_KEY) String idempotencyKey,
            @NotBlank @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
            @NotBlank @Size(max = 200)
            @RequestHeader(EXPECTED_DECISION_REVISION) String expectedDecisionRevision,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(schema = @Schema(
                            implementation = TimecardDecisionCommandV1.class)))
            @RequestBody String commandJson) {
        requireDecisionRevision(verified, expectedDecisionRevision);
        return ResponseEntity.accepted().body(service.decide(
                verified, timecardId, idempotencyKey, parseVersion(ifMatch),
                strictCommand(commandJson, TimecardDecisionCommandV1.class)));
    }

    static long parseVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank() || !ifMatch.equals(ifMatch.strip())) {
            throw invalidIfMatch();
        }
        String value = ifMatch;
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) throw invalidIfMatch();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw invalidIfMatch();
        }
    }

    private static <T> T strictCommand(String json, Class<T> type) {
        try {
            JsonNode command = STRICT_COMMAND_MAPPER.readTree(json);
            if (command == null || !command.isObject()) {
                throw new IllegalArgumentException("Timecard command must be a JSON object");
            }
            if (type == TimecardCorrectionCommandV1.class) {
                requireCorrectionTypes(command);
            } else if (type == TimecardSubmitCommandV1.class) {
                requireStringIfPresent(command, "reasonCode");
            } else if (type == TimecardDecisionCommandV1.class) {
                requireStringIfPresent(command, "decision");
                requireStringIfPresent(command, "reasonCode");
                requireStringIfPresent(command, "comment");
            }
            return STRICT_COMMAND_MAPPER.readValue(json, type);
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            throw new BaseException(
                    ErrorCode.VALIDATION_ERROR,
                    "The Timecard command does not match its sealed schema.",
                    invalid);
        }
    }

    private static void requireCorrectionTypes(JsonNode command) {
        requireStringIfPresent(command, "reasonCode");
        if (!command.has("entries")) {
            return;
        }
        JsonNode entries = command.get("entries");
        if (!entries.isArray()) {
            throw new IllegalArgumentException("entries must be an array");
        }
        for (JsonNode entry : entries) {
            if (!entry.isObject()) {
                throw new IllegalArgumentException("each correction entry must be an object");
            }
            requireStringIfPresent(entry, "workDate");
            requireStringIfPresent(entry, "payCode");
            if (entry.has("minutes") && !entry.get("minutes").isIntegralNumber()) {
                throw new IllegalArgumentException("minutes must be an integer");
            }
        }
    }

    private static void requireStringIfPresent(JsonNode command, String field) {
        if (command.has(field) && !command.get(field).isTextual()) {
            throw new IllegalArgumentException(field + " must be a string when present");
        }
    }

    private static void requireDecisionRevision(
            VerifiedTimecardRequest verified, String expectedDecisionRevision) {
        if (!verified.decisionRevision().equals(expectedDecisionRevision)) {
            throw new BaseException(
                    ErrorCode.DECISION_REVISION_CONFLICT,
                    "The Timecard authorization decision revision changed.");
        }
    }

    private static BaseException invalidIfMatch() {
        return new BaseException(
                ErrorCode.INVALID_FORMAT,
                "If-Match must contain one non-negative decimal Timecard version.");
    }
}
