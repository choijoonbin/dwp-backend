package com.dwp.services.messaging.privacy;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/privacy-preferences")
public class MessagingPrivacyController {
    private final MessagingPrivacyService service;

    public MessagingPrivacyController(MessagingPrivacyService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<MessagingPrivacyDtos.PrivacyPreference>> preference() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.preference()));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<MessagingPrivacyDtos.PrivacyPreference>> update(
            @Valid @RequestBody MessagingPrivacyDtos.UpdatePrivacyPreferenceRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.update(request)));
    }
}
