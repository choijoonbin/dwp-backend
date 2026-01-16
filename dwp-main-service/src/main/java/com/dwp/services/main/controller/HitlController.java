package com.dwp.services.main.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.main.dto.HitlApproveRequest;
import com.dwp.services.main.dto.HitlRejectRequest;
import com.dwp.services.main.service.HitlManager;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * HITL (Human-In-The-Loop) Controller
 * 
 * 승인/거절 API를 제공합니다.
 */
@Slf4j
@RestController
@RequestMapping("/aura/hitl")
@RequiredArgsConstructor
public class HitlController {
    
    private final HitlManager hitlManager;
    
    /**
     * HITL 승인 요청 조회
     * 
     * @param requestId 요청 ID
     * @return 승인 요청 정보
     */
    @GetMapping("/requests/{requestId}")
    public ApiResponse<String> getApprovalRequest(@PathVariable String requestId) {
        String requestJson = hitlManager.getApprovalRequest(requestId);
        return ApiResponse.success("Approval request retrieved", requestJson);
    }
    
    /**
     * HITL 승인 처리
     * 
     * @param requestId 요청 ID
     * @param request 승인 요청 DTO
     * @return 세션 ID
     */
    @PostMapping("/approve/{requestId}")
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<HitlApproveResponse> approve(
            @PathVariable String requestId,
            @Valid @RequestBody HitlApproveRequest request) {
        
        String sessionId = hitlManager.approve(requestId, request.getUserId());
        
        HitlApproveResponse response = HitlApproveResponse.builder()
                .requestId(requestId)
                .sessionId(sessionId)
                .status("approved")
                .build();
        
        return ApiResponse.success("Request approved successfully", response);
    }
    
    /**
     * HITL 거절 처리
     * 
     * @param requestId 요청 ID
     * @param request 거절 요청 DTO
     * @return 세션 ID
     */
    @PostMapping("/reject/{requestId}")
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<HitlRejectResponse> reject(
            @PathVariable String requestId,
            @Valid @RequestBody HitlRejectRequest request) {
        
        String sessionId = hitlManager.reject(
                requestId,
                request.getUserId(),
                request.getReason()
        );
        
        HitlRejectResponse response = HitlRejectResponse.builder()
                .requestId(requestId)
                .sessionId(sessionId)
                .status("rejected")
                .reason(request.getReason())
                .build();
        
        return ApiResponse.success("Request rejected", response);
    }
    
    /**
     * HITL 신호 조회 (에이전트가 사용)
     * 
     * @param sessionId 세션 ID
     * @return 신호 데이터
     */
    @GetMapping("/signals/{sessionId}")
    public ApiResponse<String> getSignal(@PathVariable String sessionId) {
        String signalJson = hitlManager.getSignal(sessionId);
        
        if (signalJson == null) {
            return ApiResponse.success("No signal found for session", null);
        }
        
        return ApiResponse.success("Signal retrieved", signalJson);
    }
    
    // Inner DTOs
    @lombok.Getter
    @lombok.Setter
    @lombok.Builder
    public static class HitlApproveResponse {
        private String requestId;
        private String sessionId;
        private String status;
    }
    
    @lombok.Getter
    @lombok.Setter
    @lombok.Builder
    public static class HitlRejectResponse {
        private String requestId;
        private String sessionId;
        private String status;
        private String reason;
    }
}
