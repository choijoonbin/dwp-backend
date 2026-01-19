package com.dwp.services.auth.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.auth.dto.LoginRequest;
import com.dwp.services.auth.dto.LoginResponse;
import com.dwp.services.auth.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 인증 컨트롤러
 * 
 * 로그인 및 토큰 발급 API를 제공합니다.
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
    
    private final AuthService authService;
    
    /**
     * 헬스체크 엔드포인트
     */
    @GetMapping("/health")
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.success(Map.of("status", "UP", "service", "auth-server"));
    }
    
    /**
     * 로그인 및 JWT 토큰 발급
     * 
     * Gateway 기준: POST /api/auth/login
     * Auth Server 기준: POST /auth/login
     * 
     * @param request 로그인 요청 (username, password, tenantId)
     * @return 로그인 응답 (accessToken, tokenType, expiresIn, userId, tenantId)
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);
        return ApiResponse.success("로그인에 성공했습니다.", response);
    }
}

