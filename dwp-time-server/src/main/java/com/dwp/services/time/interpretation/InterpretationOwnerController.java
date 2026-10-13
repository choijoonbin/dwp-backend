package com.dwp.services.time.interpretation;

import static com.dwp.services.time.interpretation.InterpretationOwnerAccessFilter.VERIFIED_REQUEST_ATTRIBUTE;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretCommand;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationCommandReceipt;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationQuery;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationSummary;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationTrace;
import com.dwp.services.time.interpretation.InterpretationModels.VerifiedInterpretationRequest;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Exact owner HTTP operations for IAQ-041, PEP-TIM-009, and PEP-TIM-010. */
@RestController
@Validated
@ConditionalOnProperty(
        name = "dwp.hris.time.interpretation.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class InterpretationOwnerController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final InterpretationService service;
    private final InterpretationRepository repository;
    private final InterpretationIaProjectionService projections;
    private final Clock clock;

    @Autowired
    public InterpretationOwnerController(
            InterpretationService service,
            InterpretationRepository repository,
            InterpretationIaProjectionService projections) {
        this(service, repository, projections, Clock.systemUTC());
    }

    InterpretationOwnerController(
            InterpretationService service,
            InterpretationRepository repository,
            InterpretationIaProjectionService projections,
            Clock clock) {
        this.service = service;
        this.repository = repository;
        this.projections = projections;
        this.clock = clock;
    }

    @Operation(operationId = "hrisTimeInterpretationWorkbench")
    @GetMapping("/v1/hris/workbench/time-interpretation")
    public ApiResponse<InterpretationIaQueryModels.InterpretationProjection> workbench(
            @RequestAttribute(VERIFIED_REQUEST_ATTRIBUTE)
            VerifiedInterpretationRequest verified,
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
            @RequestParam(required = false) List<String> interpretationResultCodes,
            @RequestParam(required = false) Long minimumInterpretationResultRevision,
            @RequestParam(required = false) List<String> ruleVersionPolicyKeys,
            @RequestParam(required = false) Long minimumRuleVersionRevision,
            @RequestParam(required = false) Long minimumTraceItemCount,
            @RequestParam(required = false) Long maximumTraceItemCount) {
        service.requireViewAuthority(verified);
        Instant effectiveAsOf = asOf == null && cursor == null ? clock.instant() : asOf;
        InterpretationIaQueryModels.StatusFilter status =
                interpretationResultCodes == null
                                && minimumInterpretationResultRevision == null
                        ? null
                        : new InterpretationIaQueryModels.StatusFilter(
                                interpretationResultCodes,
                                minimumInterpretationResultRevision);
        InterpretationIaQueryModels.PolicyFilter rule =
                ruleVersionPolicyKeys == null && minimumRuleVersionRevision == null
                        ? null
                        : new InterpretationIaQueryModels.PolicyFilter(
                                ruleVersionPolicyKeys, minimumRuleVersionRevision);
        InterpretationIaQueryModels.AggregateFilter trace =
                minimumTraceItemCount == null && maximumTraceItemCount == null
                        ? null
                        : new InterpretationIaQueryModels.AggregateFilter(
                                minimumTraceItemCount, maximumTraceItemCount);
        InterpretationIaQueryModels.InterpretationQueryRequest request =
                new InterpretationIaQueryModels.InterpretationQueryRequest(
                        limit,
                        new InterpretationIaQueryModels.InterpretationFilter(
                                status, rule, trace, effectiveOn),
                        sort,
                        locale,
                        timeZone,
                        correlationId,
                        effectiveAsOf,
                        cursor);
        InterpretationIaProjectionService.PreparedQuery prepared =
                projections.prepare(verified, request);
        try {
            List<InterpretationSummary> summaries = repository.query(
                    new InterpretationQuery(
                            verified.tenantId(),
                            verified.authorizedWorkerPublicIds(),
                            prepared.asOf(),
                            prepared.limit() + 1,
                            prepared.offset(),
                            effectiveOn,
                            interpretationResultCodes));
            return ApiResponse.success(
                    projections.projectWindow(verified, request, summaries));
        } catch (BaseException contractFailure) {
            throw contractFailure;
        } catch (RuntimeException unavailable) {
            return ApiResponse.success(projections.unavailable(
                    verified, request, "INTERPRETATION_QUERY_UNAVAILABLE", true));
        }
    }

    @Operation(operationId = "hrisTimeInterpretationRun")
    @PostMapping("/v1/interpretation-runs")
    public ResponseEntity<ApiResponse<InterpretationCommandReceipt>> run(
            @RequestAttribute(VERIFIED_REQUEST_ATTRIBUTE)
            VerifiedInterpretationRequest verified,
            @RequestHeader(IDEMPOTENCY_KEY) UUID idempotencyKey,
            @Valid @RequestBody InterpretCommand command) {
        return ResponseEntity.accepted().body(
                ApiResponse.success(service.run(verified, idempotencyKey, command)));
    }

    @Operation(operationId = "hrisTimeInterpretationTrace")
    @GetMapping("/v1/interpretation-runs/{runId}/trace")
    public ApiResponse<InterpretationTrace> trace(
            @RequestAttribute(VERIFIED_REQUEST_ATTRIBUTE)
            VerifiedInterpretationRequest verified,
            @PathVariable UUID runId) {
        return ApiResponse.success(service.trace(verified, runId));
    }
}
