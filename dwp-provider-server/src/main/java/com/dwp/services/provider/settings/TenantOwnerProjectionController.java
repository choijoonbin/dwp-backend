package com.dwp.services.provider.settings;

import com.dwp.core.common.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/tenant/settings")
public class TenantOwnerProjectionController {

    private final TenantOwnerProjectionService service;

    public TenantOwnerProjectionController(TenantOwnerProjectionService service) {
        this.service = service;
    }

    @GetMapping("/provider-domains")
    public ApiResponse<TenantOwnerProjectionDtos.DomainProjection> domains(
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.domains());
    }

    @GetMapping("/data-governance-observation")
    public ApiResponse<TenantOwnerProjectionDtos.DataGovernanceProjection> dataGovernance(
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.dataGovernance());
    }

    @GetMapping("/plan-eligibility")
    public ApiResponse<TenantOwnerProjectionDtos.PlanEligibilityProjection> planEligibility(
            HttpServletResponse response) {
        noStore(response);
        return ApiResponse.success(service.planEligibility());
    }

    private void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
    }
}
