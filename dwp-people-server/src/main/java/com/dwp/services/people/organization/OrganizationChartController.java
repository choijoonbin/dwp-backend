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

    public OrganizationChartController(OrganizationChartService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<OrganizationChartDtos.OrganizationChart> get(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) UUID rootOrganizationId,
            @RequestParam(defaultValue = "6") int depth) {
        return ApiResponse.success(service.getDirectory(asOf, rootOrganizationId, depth));
    }
}
