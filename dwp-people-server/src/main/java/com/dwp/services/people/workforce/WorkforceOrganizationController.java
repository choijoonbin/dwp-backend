package com.dwp.services.people.workforce;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.people.organization.OrganizationChartDtos;
import com.dwp.services.people.organization.OrganizationChartService;
import com.dwp.services.people.organization.OrganizationIntelligenceDtos;
import com.dwp.services.people.organization.OrganizationIntelligenceService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/v1/workforce/organization")
public class WorkforceOrganizationController {

    private final OrganizationChartService chartService;
    private final OrganizationIntelligenceService intelligenceService;

    public WorkforceOrganizationController(
            OrganizationChartService chartService,
            OrganizationIntelligenceService intelligenceService) {
        this.chartService = chartService;
        this.intelligenceService = intelligenceService;
    }

    @GetMapping("/chart")
    public ApiResponse<OrganizationChartDtos.OrganizationChart> chart(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) UUID rootOrganizationId,
            @RequestParam(required = false) UUID scenarioId,
            @RequestParam(defaultValue = "6") int depth) {
        return ApiResponse.success(chartService.get(asOf, rootOrganizationId, depth, scenarioId));
    }

    @GetMapping("/intelligence")
    public ApiResponse<OrganizationIntelligenceDtos.Intelligence> intelligence(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate compareTo,
            @RequestParam(required = false) UUID rootOrganizationId,
            @RequestParam(required = false) UUID scenarioId,
            @RequestParam(defaultValue = "6") int depth) {
        return ApiResponse.success(intelligenceService.get(
                asOf, compareTo, rootOrganizationId, depth, scenarioId));
    }
}
