package com.dwp.services.people.hr.performance.populationfreeze;

import com.dwp.core.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/hris/performance")
@ConditionalOnProperty(
        name = "dwp.hris.performance.population-freeze.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class PerformanceReviewOperationsController {

    private final PerformanceReviewOperationsService service;

    public PerformanceReviewOperationsController(
            PerformanceReviewOperationsService service) {
        this.service = service;
    }

    @Operation(operationId = "hrisPerformanceReviewOperations")
    @GetMapping("/workbench/review-operations")
    public ApiResponse<PerformanceReviewOperationsDtos.Projection> query(
            @RequestParam int limit,
            @RequestParam List<String> sort,
            @RequestParam String locale,
            @RequestParam String timeZone,
            @RequestParam UUID correlationId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) List<String> reviewAssignmentCodes,
            @RequestParam(required = false) List<String> reviewAssignmentPublicIds,
            @RequestParam(required = false) Integer minimumOpenCount,
            @RequestParam(required = false) Integer maximumOpenCount,
            @RequestParam(required = false) List<String> slaStates,
            @RequestParam(required = false) List<String> dueStatusCodes,
            @RequestParam(required = false) Long minimumDueRevision,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate effectiveOn) {
        PerformanceReviewOperationsDtos.Filter filter =
                new PerformanceReviewOperationsDtos.Filter(
                        reviewAssignmentCodes == null && reviewAssignmentPublicIds == null
                                ? null
                                : new PerformanceReviewOperationsDtos.ReferenceFilter(
                                reviewAssignmentCodes, reviewAssignmentPublicIds),
                        minimumOpenCount == null && maximumOpenCount == null && slaStates == null
                                ? null
                                : new PerformanceReviewOperationsDtos.QueueFilter(
                                minimumOpenCount, maximumOpenCount, slaStates),
                        dueStatusCodes == null && minimumDueRevision == null
                                ? null
                                : new PerformanceReviewOperationsDtos.StatusFilter(
                                dueStatusCodes, minimumDueRevision),
                        effectiveOn);
        return ApiResponse.success(service.query(
                new PerformanceReviewOperationsDtos.QueryRequest(
                        limit, filter, sort, locale, timeZone, correlationId,
                        asOf, cursor)));
    }
}
