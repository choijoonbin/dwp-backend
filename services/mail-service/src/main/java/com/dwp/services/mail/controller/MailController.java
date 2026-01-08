package com.dwp.services.mail.controller;

import com.dwp.core.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/mail")
public class MailController {
    
    @GetMapping("/health")
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.success(Map.of("status", "UP", "service", "mail-service"));
    }
}

