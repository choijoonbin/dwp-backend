package com.dwp.services.messaging.home;

import com.dwp.core.common.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/home")
public class MessagingHomeAssetController {
    private final MessagingHomeAssetService service;

    public MessagingHomeAssetController(MessagingHomeAssetService service) { this.service = service; }

    @GetMapping("/shared-assets")
    public ResponseEntity<ApiResponse<MessagingHomeDtos.SharedAssetsResponse>> recent(
            @RequestParam(defaultValue = "6") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.recent(limit)));
    }
}
