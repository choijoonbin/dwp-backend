package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.HcmStepUpHeaders;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Owner API for the sealed twelve-operation compensation-planning contract. */
@RestController
@RequestMapping(
        value = "/v1/hris/performance/compensation-planning",
        produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(
        name = "dwp.hris.performance.compensation-planning.enabled",
        havingValue = "true",
        matchIfMissing = false)
public final class CompensationPlanningController {

    static final String CORRELATION = "X-Correlation-ID";
    static final String IDEMPOTENCY = "Idempotency-Key";
    static final String IF_MATCH = "If-Match";
    static final String PROJECTION_VERSION = "X-HRIS-Compplan-Projection-Version";
    static final String AUTHORITATIVE_BUDGET_PROJECTION_VERSION = "5";

    private final CompensationPlanningService service;

    public CompensationPlanningController(CompensationPlanningService service) {
        this.service = service;
    }

    @Operation(operationId = CompensationPlanningService.QUERY_CYCLES)
    @GetMapping("/cycles")
    public ApiResponse<CompensationPlanningDtos.CycleCollection> cycles(
            @RequestHeader(CORRELATION) String correlationId,
            @RequestParam(required = false) Instant asOf,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            @Parameter(hidden = true)
            @RequestParam MultiValueMap<String, String> query) {
        requireClosedQuery(query, "asOf", "cursor", "limit");
        CompensationPlanningDtos.canonicalCorrelationId(correlationId);
        return ApiResponse.success(service.cycles(asOf, cursor, limit));
    }

    @Operation(operationId = CompensationPlanningService.QUERY_CYCLE)
    @GetMapping("/cycles/{cycleId}")
    public ApiResponse<CompensationPlanningDtos.CycleDetail> cycle(
            @PathVariable UUID cycleId,
            @RequestHeader(CORRELATION) String correlationId,
            @RequestHeader(value = PROJECTION_VERSION, required = false)
            String projectionVersion,
            @RequestParam(required = false) Instant referenceEffectiveAt,
            @Parameter(hidden = true)
            @RequestParam MultiValueMap<String, String> query,
            HttpServletResponse response) {
        requireClosedQuery(query, "referenceEffectiveAt");
        CompensationPlanningDtos.canonicalCorrelationId(correlationId);
        response.addHeader("Vary", PROJECTION_VERSION);
        return ApiResponse.success(service.cycle(
                cycleId,
                referenceEffectiveAt,
                requestsAuthoritativeBudgetProjection(projectionVersion)));
    }

    @Operation(operationId = CompensationPlanningService.CREATE)
    @PostMapping("/cycles")
    public ApiResponse<CompensationPlanningDtos.CommandReceipt> createCycle(
            @RequestHeader(CORRELATION) String correlationId,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @Parameter(hidden = true)
            @RequestParam MultiValueMap<String, String> query,
            @Valid @RequestBody CompensationPlanningDtos.CreateCycleRequest request) {
        requireClosedQuery(query);
        requireCommandHeaders(correlationId, idempotencyKey, null);
        return ApiResponse.success(service.createCycle(request, idempotencyKey, correlationId));
    }

    @Operation(operationId = CompensationPlanningService.REVISE)
    @PostMapping("/cycles/{cycleId}/revisions")
    public ApiResponse<CompensationPlanningDtos.RichCommandResult> reviseCycle(
            @PathVariable UUID cycleId,
            @RequestHeader(CORRELATION) String correlationId,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(IF_MATCH) long expectedVersion,
            @Parameter(hidden = true)
            @RequestParam MultiValueMap<String, String> query,
            @Valid @RequestBody CompensationPlanningDtos.ReviseCycleRequest request) {
        requireClosedQuery(query);
        requireCommandHeaders(correlationId, idempotencyKey, expectedVersion);
        return ApiResponse.success(service.reviseCycle(
                cycleId, expectedVersion, request, idempotencyKey, correlationId));
    }

    @Operation(operationId = CompensationPlanningService.BUDGET)
    @PostMapping("/cycles/{cycleId}/budget-allocations")
    public ApiResponse<CompensationPlanningDtos.CommandReceipt> allocateBudget(
            @PathVariable UUID cycleId,
            @RequestHeader(CORRELATION) String correlationId,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(IF_MATCH) long expectedVersion,
            @Parameter(hidden = true)
            @RequestParam MultiValueMap<String, String> query,
            @Valid @RequestBody CompensationPlanningDtos.BudgetEntryRequest request) {
        requireClosedQuery(query);
        requireCommandHeaders(correlationId, idempotencyKey, expectedVersion);
        return ApiResponse.success(service.allocateBudget(
                cycleId, expectedVersion, request, idempotencyKey, correlationId));
    }

    @Operation(operationId = CompensationPlanningService.UPSERT_PROPOSAL)
    @PostMapping("/cycles/{cycleId}/proposals/{proposalId}")
    public ApiResponse<CompensationPlanningDtos.RichCommandResult> upsertProposal(
            @PathVariable UUID cycleId,
            @PathVariable UUID proposalId,
            @RequestHeader(CORRELATION) String correlationId,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(IF_MATCH) long expectedVersion,
            @Parameter(hidden = true)
            @RequestParam MultiValueMap<String, String> query,
            @Valid @RequestBody CompensationPlanningDtos.UpsertProposalRequest request) {
        requireClosedQuery(query);
        requireCommandHeaders(correlationId, idempotencyKey, expectedVersion);
        return ApiResponse.success(service.upsertProposal(
                cycleId, proposalId, expectedVersion, request,
                idempotencyKey, correlationId));
    }

    @Operation(operationId = CompensationPlanningService.SUBMIT_PROPOSAL)
    @PostMapping("/cycles/{cycleId}/proposals/{proposalId}/submit")
    public ApiResponse<CompensationPlanningDtos.CommandReceipt> submitProposal(
            @PathVariable UUID cycleId,
            @PathVariable UUID proposalId,
            @RequestHeader(CORRELATION) String correlationId,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(IF_MATCH) long expectedVersion,
            @Parameter(hidden = true)
            @RequestParam MultiValueMap<String, String> query,
            @Valid @RequestBody CompensationPlanningDtos.SubmitProposalRequest request) {
        requireClosedQuery(query);
        requireCommandHeaders(correlationId, idempotencyKey, expectedVersion);
        return ApiResponse.success(service.submitProposal(
                cycleId, proposalId, expectedVersion, request,
                idempotencyKey, correlationId));
    }

    @Operation(operationId = CompensationPlanningService.QUERY_PROPOSALS)
    @GetMapping("/cycles/{cycleId}/proposals")
    public ApiResponse<CompensationPlanningDtos.ProposalCollection> proposals(
            @PathVariable UUID cycleId,
            @RequestHeader(CORRELATION) String correlationId,
            @RequestParam(required = false) Instant referenceEffectiveAt,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit,
            @Parameter(hidden = true)
            @RequestParam MultiValueMap<String, String> query) {
        requireClosedQuery(query, "referenceEffectiveAt", "cursor", "limit");
        CompensationPlanningDtos.canonicalCorrelationId(correlationId);
        return ApiResponse.success(service.proposals(
                cycleId, referenceEffectiveAt, cursor, limit));
    }

    @Operation(operationId = CompensationPlanningService.QUERY_PROPOSAL)
    @GetMapping("/proposals/{proposalId}")
    public ApiResponse<CompensationPlanningDtos.ProposalDetail> proposal(
            @PathVariable UUID proposalId,
            @RequestHeader(CORRELATION) String correlationId,
            @RequestParam(required = false) Instant referenceEffectiveAt,
            @Parameter(hidden = true)
            @RequestParam MultiValueMap<String, String> query) {
        requireClosedQuery(query, "referenceEffectiveAt");
        CompensationPlanningDtos.canonicalCorrelationId(correlationId);
        return ApiResponse.success(service.proposal(proposalId, referenceEffectiveAt));
    }

    @Operation(operationId = CompensationPlanningService.APPROVE)
    @PostMapping("/cycles/{cycleId}/approve")
    public ApiResponse<CompensationPlanningDtos.CommandReceipt> approve(
            @PathVariable UUID cycleId,
            @RequestHeader(CORRELATION) String correlationId,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(IF_MATCH) long expectedVersion,
            @RequestHeader(HcmStepUpHeaders.CHALLENGE) String challenge,
            @RequestHeader(HcmStepUpHeaders.DECISION_REVISION) String decisionRevision,
            @RequestHeader(HcmStepUpHeaders.EXPECTED_OBJECT_VERSION)
            Long expectedObjectVersion,
            @Parameter(hidden = true)
            @RequestParam MultiValueMap<String, String> query,
            @Valid @RequestBody CompensationPlanningDtos.ApprovePlanRequest request) {
        requireClosedQuery(query);
        requireCommandHeaders(correlationId, idempotencyKey, expectedVersion);
        return ApiResponse.success(service.approve(
                cycleId, expectedVersion, request, idempotencyKey, correlationId,
                new HcmStepUpHeaders(challenge, idempotencyKey,
                        decisionRevision, expectedObjectVersion)));
    }

    @Operation(operationId = CompensationPlanningService.PUBLISH)
    @PostMapping("/cycles/{cycleId}/approved-snapshots")
    public ApiResponse<CompensationPlanningDtos.SnapshotPublished> publish(
            @PathVariable UUID cycleId,
            @RequestHeader(CORRELATION) String correlationId,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(IF_MATCH) long expectedVersion,
            @RequestHeader(HcmStepUpHeaders.CHALLENGE) String challenge,
            @RequestHeader(HcmStepUpHeaders.DECISION_REVISION) String decisionRevision,
            @RequestHeader(HcmStepUpHeaders.EXPECTED_OBJECT_VERSION)
            Long expectedObjectVersion,
            @Parameter(hidden = true)
            @RequestParam MultiValueMap<String, String> query,
            @Valid @RequestBody CompensationPlanningDtos.PublishSnapshotRequest request) {
        requireClosedQuery(query);
        requireCommandHeaders(correlationId, idempotencyKey, expectedVersion);
        return ApiResponse.success(service.publish(
                cycleId, expectedVersion, request, idempotencyKey, correlationId,
                new HcmStepUpHeaders(challenge, idempotencyKey,
                        decisionRevision, expectedObjectVersion)));
    }

    @Operation(operationId = CompensationPlanningService.CANCEL)
    @PostMapping("/cycles/{cycleId}/cancel")
    public ApiResponse<CompensationPlanningDtos.RichCommandResult> cancel(
            @PathVariable UUID cycleId,
            @RequestHeader(CORRELATION) String correlationId,
            @RequestHeader(IDEMPOTENCY) String idempotencyKey,
            @RequestHeader(IF_MATCH) long expectedVersion,
            @Parameter(hidden = true)
            @RequestParam MultiValueMap<String, String> query,
            @Parameter(hidden = true)
            @RequestBody(required = false) Map<String, Object> body) {
        requireClosedQuery(query);
        requireCommandHeaders(correlationId, idempotencyKey, expectedVersion);
        if (body != null && !body.isEmpty()) throw invalidInput();
        return ApiResponse.success(service.cancel(
                cycleId, expectedVersion, idempotencyKey, correlationId));
    }

    static void requireClosedQuery(
            MultiValueMap<String, String> query,
            String... allowedNames) {
        Set<String> allowed = Arrays.stream(allowedNames).collect(Collectors.toUnmodifiableSet());
        if (!allowed.containsAll(query.keySet())
                || query.values().stream().anyMatch(values -> values == null
                || values.size() != 1)) {
            throw invalidInput();
        }
    }

    static boolean requestsAuthoritativeBudgetProjection(String version) {
        if (version == null) return false;
        if (AUTHORITATIVE_BUDGET_PROJECTION_VERSION.equals(version)) return true;
        throw invalidInput();
    }

    private static void requireCommandHeaders(
            String correlationId,
            String idempotencyKey,
            Long expectedVersion) {
        CompensationPlanningDtos.canonicalCorrelationId(correlationId);
        CompensationPlanningDtos.canonicalIdempotencyKey(idempotencyKey);
        if (expectedVersion != null && expectedVersion < 0) throw invalidInput();
    }

    private static BaseException invalidInput() {
        return new BaseException(
                ErrorCode.INVALID_INPUT_VALUE,
                "The compensation-planning request is not part of the sealed contract.");
    }
}
