package com.dwp.services.messaging.attention;

import com.dwp.core.common.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class MessagingAttentionController {

    private final MessagingAttentionService service;

    public MessagingAttentionController(MessagingAttentionService service) {
        this.service = service;
    }

    @GetMapping("/activity")
    public ResponseEntity<ApiResponse<MessagingAttentionDtos.ActivityResponse>> activity(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.activity(limit)));
    }

    @GetMapping("/threads")
    public ResponseEntity<ApiResponse<MessagingAttentionDtos.ThreadResponse>> threads(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.threads(limit)));
    }
}
