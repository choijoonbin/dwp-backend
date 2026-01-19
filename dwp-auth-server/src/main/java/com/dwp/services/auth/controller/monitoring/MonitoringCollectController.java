package com.dwp.services.auth.controller.monitoring;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.monitoring.EventCollectRequest;
import com.dwp.services.auth.dto.monitoring.PageViewCollectRequest;
import com.dwp.services.auth.service.monitoring.MonitoringCollectService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 모니터링 수집 API 컨트롤러
 * 
 * 프론트엔드에서 페이지뷰 및 이벤트를 수집하는 API를 제공합니다.
 * 인증 없이 접근 가능하나, X-Tenant-ID 헤더는 필수입니다.
 */
@Slf4j
@RestController
@RequestMapping("/monitoring")
@RequiredArgsConstructor
public class MonitoringCollectController {
    
    private final MonitoringCollectService monitoringCollectService;
    
    /**
     * 페이지뷰 수집
     * POST /api/monitoring/page-view
     */
    @PostMapping("/page-view")
    public ApiResponse<Map<String, Boolean>> recordPageView(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantIdHeader,
            Authentication authentication,
            HttpServletRequest request,
            @Valid @RequestBody PageViewCollectRequest pageViewRequest) {
        
        Long tenantId = parseTenantId(tenantIdHeader, authentication);
        Long userId = getUserId(authentication);
        String ipAddress = getClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        
        monitoringCollectService.recordPageView(tenantId, userId, ipAddress, userAgent, pageViewRequest);
        return ApiResponse.success(Map.of("accepted", true));
    }
    
    /**
     * 이벤트 수집
     * POST /api/monitoring/event
     */
    @PostMapping("/event")
    public ApiResponse<Map<String, Boolean>> recordEvent(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantIdHeader,
            Authentication authentication,
            HttpServletRequest request,
            @Valid @RequestBody EventCollectRequest eventRequest) {
        
        Long tenantId = parseTenantId(tenantIdHeader, authentication);
        Long userId = getUserId(authentication);
        String ipAddress = getClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        
        monitoringCollectService.recordEvent(tenantId, userId, ipAddress, userAgent, eventRequest);
        return ApiResponse.success(Map.of("accepted", true));
    }
    
    private Long parseTenantId(String header, Authentication auth) {
        if (header != null && !header.trim().isEmpty()) {
            try {
                return Long.parseLong(header.trim());
            } catch (NumberFormatException e) {
                throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "X-Tenant-ID는 숫자여야 합니다");
            }
        }
        if (auth != null && auth.getPrincipal() instanceof Jwt) {
            Object tid = ((Jwt) auth.getPrincipal()).getClaim("tenant_id");
            if (tid != null) {
                return Long.parseLong(tid.toString());
            }
        }
        // X-Tenant-ID가 없으면 적재 금지
        throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "X-Tenant-ID 헤더가 필요합니다");
    }
    
    private Long getUserId(Authentication auth) {
        if (auth != null && auth.getPrincipal() instanceof Jwt) {
            try {
                return Long.parseLong(((Jwt) auth.getPrincipal()).getSubject());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
    
    private String getClientIp(HttpServletRequest request) {
        String xf = request.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isEmpty()) {
            return xf.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
