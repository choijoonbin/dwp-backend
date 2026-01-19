package com.dwp.services.auth.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.dto.LoginRequest;
import com.dwp.services.auth.dto.LoginResponse;
import com.dwp.services.auth.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 로그인 컨트롤러
 * 
 * LOCAL 인증 및 JWT 토큰 발급을 처리합니다.
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class LoginController {
    
    private final AuthService authService;
    
    /**
     * 로그인 및 JWT 토큰 발급
     * POST /api/auth/login
     * 
     * @param request 로그인 요청 (username, password, tenantId)
     * @return JWT 토큰 및 사용자 정보
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        log.info("Login request: username={}, tenantId={}", request.getUsername(), request.getTenantId());
        LoginResponse response = authService.login(request);
        return ApiResponse.success(response);
    }
}
