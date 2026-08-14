package com.dwp.services.platform.home.overview;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/home")
@Validated
public class HomeOverviewController {

    private final HomeOverviewService service;

    public HomeOverviewController(HomeOverviewService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public ApiResponse<HomeOverviewDtos.HomeOverviewResponse> overview(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-DWP-Person-Public-ID", required = false) UUID personPublicId,
            @RequestHeader(value = "X-DWP-Permissions", required = false) String permissions,
            @RequestHeader(value = "X-DWP-Roles", required = false) String roles,
            @RequestHeader(value = "Accept-Language", required = false) String locale,
            @RequestParam(defaultValue = "Asia/Seoul") String timeZone) {
        return ApiResponse.success(service.overview(
                tenantId, userId, personPublicId, permissions, roles, locale, timeZone));
    }

    @PostMapping("/recommendations/{recommendationKey}/feedback")
    public ApiResponse<HomeOverviewDtos.RecommendationFeedbackResponse> recordFeedback(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long userId,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable
            @Pattern(regexp = "[a-z][a-z0-9-]{1,79}")
            String recommendationKey,
            @Valid @RequestBody HomeOverviewDtos.RecommendationFeedbackRequest request) {
        return ApiResponse.success(service.recordFeedback(
                tenantId, userId, recommendationKey, correlationId, request));
    }
}
