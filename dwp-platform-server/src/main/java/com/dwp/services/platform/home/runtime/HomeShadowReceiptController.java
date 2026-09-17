package com.dwp.services.platform.home.runtime;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final HomeReadModelService readModels;
    private final HomeShadowReceiptAdmissionGuard admission;

    @Autowired
    public HomeShadowReceiptController(
            ObjectMapper objectMapper,
            Validator validator,
            MeterRegistry meters,
            HomeReadModelService readModels,
            HomeShadowReceiptAdmissionGuard admission) {
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.meters = meters;
        this.readModels = readModels;
        this.admission = admission;
    }

    HomeShadowReceiptController(
            ObjectMapper objectMapper,
            Validator validator,
            MeterRegistry meters,
            HomeReadModelService readModels) {
        this(objectMapper, validator, meters, readModels,
                new LocalReceiptAdmissionGuard(Clock.systemUTC()));
    }

    /** Isolated comparator-test constructor; production always injects the read-model service. */
    HomeShadowReceiptController(
            ObjectMapper objectMapper,
            Validator validator,
            MeterRegistry meters) {
        this(objectMapper, validator, meters, null);
    }

    @PostMapping
    @Operation(
            operationId = "recordHomeShadowReceiptV2",
            summary = "Record a bounded privacy-safe Home legacy/v2 comparison receipt")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "202",
                    description = "Comparison receipt accepted",
                    useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Receipt or trusted rollout context is invalid",
                    content = @Content),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503",
                    description = "Home rollout decision is unavailable",
                    content = @Content)
    })
    public ResponseEntity<ApiResponse<ShadowReceiptResponse>> record(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Person-Public-ID", required = false)
            UUID personPublicId,
            @RequestHeader(value = "X-DWP-Permissions", required = false) String permissions,
            @RequestHeader(value = "X-DWP-Roles", required = false) String roles,
            @RequestHeader(value = "X-DWP-Group-Refs", required = false) String groupRefs,
            @RequestHeader("X-DWP-Current-Decision-Revision") String decisionRevision,
            @RequestHeader("X-DWP-Current-Revalidate-At") String revalidateAt,
            @RequestHeader("X-DWP-Home-Runtime-State") String trustedState,
            @RequestHeader("X-DWP-Home-Rollout-Ring") String trustedRing,
            @RequestHeader("X-DWP-Home-Rollout-Revision") String trustedRevision,
            @RequestHeader(value = "Accept-Language", defaultValue = "ko-KR") String locale,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            schema = @Schema(implementation = ShadowReceiptRequest.class)))
            @RequestBody byte[] body) {
        ShadowReceiptRequest request = parse(body);
        HomeRuntimeRolloutDecision.TrustedInput trusted =
                HomeRuntimeRolloutDecision.TrustedInput.parse(
                        trustedState, trustedRing, trustedRevision);
        HomeRuntimeContext context = HomeRuntimeContext.create(
                tenantId, userId, personPublicId, permissions, roles, groupRefs,
                decisionRevision, revalidateAt, locale, "UTC");
        HomeRuntimeRolloutDecision current = readModels.resolveCurrentDecision(
                context, trusted, request.homeMode());
        return accept(request, current, tenantId, userId);
    }

    /** Direct-call compatibility for strict, isolated receipt tests. */
    ResponseEntity<ApiResponse<ShadowReceiptResponse>> record(
            String trustedState,
            String trustedRing,
            String currentDecisionRevision,
            byte[] body) {
        ShadowReceiptRequest request = parse(body);
        HomeRuntimeRolloutDecision.TrustedInput trusted =
                HomeRuntimeRolloutDecision.TrustedInput.parse(
                        trustedState, trustedRing, currentDecisionRevision);
        HomeRuntimeRolloutDecision current = new HomeRuntimeRolloutDecision(
                trusted.state(), request.homeMode(), trusted.ring(), trusted.revision(), false,
                Set.of(), Set.of(), Set.of(), java.time.OffsetDateTime.MAX);
        return accept(request, current, -1, -1);
    }

    private ResponseEntity<ApiResponse<ShadowReceiptResponse>> accept(
            ShadowReceiptRequest request,
            HomeRuntimeRolloutDecision current,
            long tenantId,
            long userId) {
        validate(request, current);
        HomeShadowReceiptAdmissionGuard.Admission result = admission.admit(
                tenantId, userId, current.revision(), request);
        meters.counter(
                "dwp.home.shadow.receipt.admission",
                "outcome", result.name()).increment();
        if (result == HomeShadowReceiptAdmissionGuard.Admission.ADMITTED) {
            request.reasons().forEach(reason -> meters.counter(
                    "dwp.home.shadow.compare",
                    "mode", request.homeMode(),
                    "device_class", deviceFamily(request.deviceClass()),
                    "outcome", request.outcome().name(),
                    "reason", reason.name(),
                    "release_ring", request.rolloutRing()).increment());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(
                new ShadowReceiptResponse(true, "home-shadow-v1")));
    }

    private ShadowReceiptRequest parse(byte[] body) {
        if (body == null || body.length == 0 || body.length > 8_192) {
            throw invalid();
        }
        try {
            ShadowReceiptRequest request = objectMapper.readerFor(ShadowReceiptRequest.class)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(body);
            if (request == null) throw invalid();
            return request;
        } catch (IOException exception) {
            throw invalid();
        }
    }

    private void validate(
            ShadowReceiptRequest request,
            HomeRuntimeRolloutDecision current) {
        Set<ConstraintViolation<ShadowReceiptRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()
                || request.schemaVersion() != 1
                || request.runtimeState() == null
                || !"SHADOW_COMPARE".equals(request.runtimeState().toUpperCase(Locale.ROOT))
                || current.state() != HomeRuntimeRolloutDecision.State.SHADOW_COMPARE
                || !current.mode().equals(request.homeMode())
                || !current.ring().name().equals(request.rolloutRing())
                || !current.revision().equals(request.rolloutRevision())
                || !validComparison(request)) {
            throw invalid();
        }
    }

    private boolean validComparison(ShadowReceiptRequest request) {
        Set<HomeReadModelShadowComparator.ShadowReason> reasons = request.reasons();
        return switch (request.outcome()) {
            case MATCH -> request.mismatchCount() == 0
                    && reasons.equals(Set.of(HomeReadModelShadowComparator.ShadowReason.MATCH));
            case EXPECTED_TRANSIENT -> request.mismatchCount() > 0
                    && reasons.equals(Set.of(
                    HomeReadModelShadowComparator.ShadowReason.EXPECTED_TRANSIENT,
                    HomeReadModelShadowComparator.ShadowReason.FRESHNESS));
            case MISMATCH -> request.mismatchCount() > 0
                    && reasons.stream().noneMatch(reason -> reason
                    == HomeReadModelShadowComparator.ShadowReason.MATCH
                    || reason == HomeReadModelShadowComparator.ShadowReason.EXPECTED_TRANSIENT
                    || reason == HomeReadModelShadowComparator.ShadowReason.UNAVAILABLE)
                    && reasons.stream().anyMatch(reason -> reason
                    != HomeReadModelShadowComparator.ShadowReason.FRESHNESS);
            case UNAVAILABLE -> request.mismatchCount() > 0
                    && reasons.equals(Set.of(
                    HomeReadModelShadowComparator.ShadowReason.UNAVAILABLE));
        };
    }

    private String deviceFamily(String value) {
        return value.startsWith("MOBILE_") ? "MOBILE" : "DESKTOP";
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
            @NotNull @Pattern(regexp = "CLASSIC|FLOW_V1|MZ_V1") String homeMode,
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

    static final class LocalReceiptAdmissionGuard
            implements HomeShadowReceiptAdmissionGuard {
        private static final int MAX_RECEIPTS = 4_096;
        private static final int MAX_RECIPIENTS = 2_048;
        private static final int MAX_RECEIPTS_PER_WINDOW = 60;
        private static final Duration DEDUPE_TTL = Duration.ofMinutes(10);
        private static final Duration RATE_WINDOW = Duration.ofMinutes(1);

        private final Clock clock;
        private final String salt = UUID.randomUUID().toString();
        private final Map<String, Instant> receipts = new LinkedHashMap<>();
        private final Map<String, RateWindow> recipients = new LinkedHashMap<>();

        LocalReceiptAdmissionGuard(Clock clock) {
            this.clock = clock;
        }

        @Override
        public synchronized Admission admit(
                long tenantId,
                long userId,
                String decisionRevision,
                ShadowReceiptRequest request) {
            Instant now = clock.instant();
            receipts.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
            recipients.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));

            String recipient = digest(salt + "|" + tenantId + "|" + userId);
            String semanticPayload = request.schemaVersion()
                    + "|" + request.outcome().name()
                    + "|" + request.reasons().stream()
                    .map(Enum::name)
                    .sorted()
                    .collect(Collectors.joining(","))
                    + "|" + request.mismatchCount()
                    + "|" + request.homeMode()
                    + "|" + request.deviceClass()
                    + "|" + request.runtimeState()
                    + "|" + request.rolloutRing();
            String receipt = digest(salt + "|" + recipient + "|"
                    + decisionRevision + "|" + semanticPayload);
            if (receipts.containsKey(receipt)) return Admission.DUPLICATE;

            RateWindow window = recipients.get(recipient);
            if (window == null || !window.expiresAt().isAfter(now)) {
                window = new RateWindow(0, now.plus(RATE_WINDOW));
            }
            if (window.count() >= MAX_RECEIPTS_PER_WINDOW) {
                return Admission.RATE_LIMITED;
            }

            recipients.put(recipient, new RateWindow(window.count() + 1, window.expiresAt()));
            receipts.put(receipt, now.plus(DEDUPE_TTL));
            trimToBound(receipts, MAX_RECEIPTS);
            trimToBound(recipients, MAX_RECIPIENTS);
            return Admission.ADMITTED;
        }

        private <T> void trimToBound(Map<String, T> values, int maximum) {
            while (values.size() > maximum) {
                values.remove(values.keySet().iterator().next());
            }
        }

        private String digest(String value) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
        }

        private record RateWindow(int count, Instant expiresAt) {
        }
    }
}
