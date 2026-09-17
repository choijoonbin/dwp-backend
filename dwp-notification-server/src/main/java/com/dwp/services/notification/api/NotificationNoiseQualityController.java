package com.dwp.services.notification.api;

import com.dwp.services.notification.common.ApiResponse;
import com.dwp.services.notification.domain.NotificationNoiseQualityModels.NoiseFindingSeverity;
import com.dwp.services.notification.domain.NotificationNoiseQualityModels.NoiseQuality;
import com.dwp.services.notification.domain.NotificationNoiseQualityModels.NoiseQualityQuery;
import com.dwp.services.notification.domain.NotificationNoiseQualityModels.NoiseRisk;
import com.dwp.services.notification.domain.NotificationNoiseQualityModels.NoiseTimeRange;
import com.dwp.services.notification.domain.NotificationNoiseQualityService;
import com.dwp.services.notification.security.NotificationRequestContext;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/v1/admin/noise-quality")
public class NotificationNoiseQualityController {

    private final NotificationNoiseQualityService service;

    public NotificationNoiseQualityController(NotificationNoiseQualityService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<NoiseQuality> noiseQuality(
            @RequestParam(defaultValue = "LAST_30_DAYS") NoiseTimeRange range,
            @RequestParam(required = false) @Size(max = 120) String query,
            @RequestParam(required = false) NoiseFindingSeverity severity,
            @RequestParam(required = false) NoiseRisk risk) {
        return ApiResponse.success(service.summary(
                NotificationRequestContext.requireActor(),
                new NoiseQualityQuery(range, query, severity, risk)));
    }
}
