package com.dwp.services.people.organization;

import com.dwp.core.common.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/v1/org-chart")
public class OrganizationChartController {

    private final OrganizationChartService service;
    private final OrganizationIntelligenceService intelligenceService;

    public OrganizationChartController(
            OrganizationChartService service,
            OrganizationIntelligenceService intelligenceService) {
        this.service = service;
        this.intelligenceService = intelligenceService;
    }

    @GetMapping
    public ApiResponse<OrganizationChartDtos.OrganizationChart> get(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) UUID rootOrganizationId,
            @RequestParam(required = false) UUID scenarioId,
            @RequestParam(defaultValue = "6") int depth) {
        return ApiResponse.success(service.get(asOf, rootOrganizationId, depth, scenarioId));
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
