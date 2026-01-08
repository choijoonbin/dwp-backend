package com.dwp.services.auth.controller;

import com.dwp.core.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/auth")
public class AuthController {
    
    @GetMapping("/health")
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.success(Map.of("status", "UP", "service", "auth-server"));
    }
}

