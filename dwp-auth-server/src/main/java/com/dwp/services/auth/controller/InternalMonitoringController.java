package com.dwp.services.auth.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.dto.ApiCallHistoryRequest;
import com.dwp.services.auth.service.MonitoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내부 통신용 컨트롤러
 * 
 * Gateway 등에서 호출하는 내부 API를 제공합니다.
 */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalMonitoringController {

    private final MonitoringService monitoringService;

    /**
     * API 호출 이력 적재
     * POST /internal/api-call-history
     */
    @PostMapping("/api-call-history")
    public ApiResponse<Void> recordApiCallHistory(@RequestBody ApiCallHistoryRequest request) {
        monitoringService.recordApiCallHistory(request);
        return ApiResponse.success();
    }
}
