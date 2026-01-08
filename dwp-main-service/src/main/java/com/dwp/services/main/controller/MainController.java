package com.dwp.services.main.controller;

import com.dwp.core.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/main")
public class MainController {
    
    @GetMapping("/health")
    public ApiResponse<String> health() {
        return ApiResponse.success("Main Service is running");
    }
    
    @GetMapping("/info")
    public ApiResponse<Map<String, String>> info() {
        return ApiResponse.success(Map.of(
            "service", "dwp-main-service",
            "description", "플랫폼 메인 백엔드 서비스",
            "port", "8081"
        ));
    }
}

