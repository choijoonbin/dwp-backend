package com.dwp.services.platform.home.runtime;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v2/home/shadow-receipts")
public class HomeShadowReceiptController {

    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final MeterRegistry meters;

    public HomeShadowReceiptController(
            ObjectMapper objectMapper,
            Validator validator,
            MeterRegistry meters) {
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.meters = meters;
    }

    @PostMapping
    @Operation(
            operationId = "recordHomeShadowReceiptV2",
            summary = "Record a bounded privacy-safe Home legacy/v2 comparison receipt")
    public ResponseEntity<ApiResponse<ShadowReceiptResponse>> record(
            @RequestHeader("X-DWP-Home-Runtime-State") String trustedState,
            @RequestHeader("X-DWP-Home-Rollout-Ring") String trustedRing,
            @RequestHeader("X-DWP-Home-Rollout-Revision") String trustedRevision,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            schema = @Schema(implementation = ShadowReceiptRequest.class)))
            @RequestBody byte[] body) {
        ShadowReceiptRequest request = parse(body);
        HomeRuntimeRolloutDecision.TrustedInput trusted =
                HomeRuntimeRolloutDecision.TrustedInput.parse(
                        trustedState, trustedRing, trustedRevision);
        validate(request, trusted);
        request.reasons().forEach(reason -> meters.counter(
                "dwp.home.runtime.shadow.compare",
                "mode", request.homeMode(),
                "device", request.deviceClass(),
                "outcome", request.outcome().name(),
                "reason", reason.name(),
                "ring", request.rolloutRing()).increment());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(
                new ShadowReceiptResponse(true, "home-shadow-v1")));
    }

    private ShadowReceiptRequest parse(byte[] body) {
        if (body == null || body.length == 0 || body.length > 8_192) {
            throw invalid();
        }
        try {
            return objectMapper.readerFor(ShadowReceiptRequest.class)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(body);
        } catch (IOException exception) {
            throw invalid();
        }
    }

    private void validate(
            ShadowReceiptRequest request,
            HomeRuntimeRolloutDecision.TrustedInput trusted) {
        Set<ConstraintViolation<ShadowReceiptRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()
                || request.schemaVersion() != 1
                || request.runtimeState() == null
                || !"SHADOW_COMPARE".equals(request.runtimeState().toUpperCase(Locale.ROOT))
                || trusted.state() != HomeRuntimeRolloutDecision.State.SHADOW_COMPARE
                || !trusted.ring().name().equals(request.rolloutRing())
                || !trusted.revision().equals(request.rolloutRevision())
                || request.outcome() == HomeReadModelShadowComparator.ShadowOutcome.MATCH
                && (request.mismatchCount() != 0
                || !request.reasons().equals(Set.of(
                        HomeReadModelShadowComparator.ShadowReason.MATCH)))
                || request.outcome() != HomeReadModelShadowComparator.ShadowOutcome.MATCH
                && request.mismatchCount() == 0) {
            throw invalid();
        }
    }

    private BaseException invalid() {
        return new BaseException(
                ErrorCode.INVALID_INPUT_VALUE,
                "The Home shadow comparison receipt is invalid.");
    }

    public record ShadowReceiptRequest(
            @Min(1) @Max(1) int schemaVersion,
            @NotNull HomeReadModelShadowComparator.ShadowOutcome outcome,
            @NotEmpty @Size(max = 10) Set<HomeReadModelShadowComparator.ShadowReason> reasons,
            @Min(0) @Max(100) int mismatchCount,
            @NotNull @Pattern(regexp = "CLASSIC|FLOW_V1") String homeMode,
            @NotNull @Pattern(regexp =
                    "DESKTOP_WIDE|DESKTOP_STANDARD|MOBILE_STANDARD|MOBILE_COMPACT")
            String deviceClass,
            @NotNull @Pattern(regexp = "SHADOW_COMPARE") String runtimeState,
            @NotNull @Pattern(regexp = "CONTROL|INTERNAL|PILOT|EARLY_ADOPTER|GA")
            String rolloutRing,
            @NotNull @Size(max = 160)
            @Pattern(regexp = "[A-Za-z0-9._:-]{1,160}") String rolloutRevision) {
        public ShadowReceiptRequest {
            reasons = reasons == null ? Set.of() : Set.copyOf(reasons);
        }
    }

    public record ShadowReceiptResponse(boolean accepted, String receiptVersion) {
    }
}
