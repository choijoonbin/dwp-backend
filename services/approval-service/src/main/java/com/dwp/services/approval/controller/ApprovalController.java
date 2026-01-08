package com.dwp.services.approval.controller;

import com.dwp.core.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/approval")
public class ApprovalController {
    
    @GetMapping("/health")
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.success(Map.of("status", "UP", "service", "approval-service"));
    }
}

