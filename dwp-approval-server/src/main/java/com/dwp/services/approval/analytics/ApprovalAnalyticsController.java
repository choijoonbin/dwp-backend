package com.dwp.services.approval.analytics;

import com.dwp.core.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

import static com.dwp.services.approval.analytics.ApprovalAnalyticsModels.*;

@RestController
@RequestMapping("/v1/admin/operations/analytics")
@Tag(name = "Approval process analytics")
public class ApprovalAnalyticsController {
    private final ApprovalAnalyticsService service;
    private final ApprovalAnalyticsHttpAuthority authority;

    public ApprovalAnalyticsController(
            ApprovalAnalyticsService service,
            ApprovalAnalyticsHttpAuthority authority) {
        this.service = service;
        this.authority = authority;
    }

    @GetMapping("/metric-definitions")
    @Operation(summary = "List governed Approval analytics metric definitions")
    public ApiResponse<List<MetricDefinition>> metricDefinitions() {
        authority.scope();
        return ApiResponse.success(service.metricDefinitions());
    }

    @GetMapping("/dashboard")
    @Operation(summary = "Read a scoped Approval process analytics dashboard")
    public ApiResponse<Dashboard> dashboard(
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "WORKFLOW") CohortDimension cohortDimension,
            @RequestParam(defaultValue = "5") int minimumCohortSize) {
        return ApiResponse.success(service.dashboard(
                authority.scope(),
                new Query(from, to, cohortDimension, minimumCohortSize)));
    }

    @GetMapping("/cohorts/{cohortKey}/representatives")
    @Operation(summary = "Read authorized representatives from a non-suppressed cohort")
    public ApiResponse<List<Representative>> representatives(
            @PathVariable String cohortKey,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "WORKFLOW") CohortDimension cohortDimension,
            @RequestParam(defaultValue = "5") int minimumCohortSize,
            @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.success(service.representatives(
                authority.scope(),
                new Query(from, to, cohortDimension, minimumCohortSize),
                cohortKey, limit));
    }
}
