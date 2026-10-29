package com.dwp.services.messaging.later;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/later")
public class MessagingLaterController {

    private final MessagingLaterService service;

    public MessagingLaterController(MessagingLaterService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<MessagingLaterDtos.LaterPage>> items(
            @RequestParam(defaultValue = "ALL") String state,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int pageSize) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.items(state, q, page, pageSize)));
    }

    @PutMapping("/{messageId}")
    public ApiResponse<MessagingLaterDtos.LaterItem> update(
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @PathVariable UUID messageId,
            @Valid @RequestBody MessagingLaterDtos.UpdateLaterRequest request) {
        return ApiResponse.success(service.update(messageId, request, correlationId));
    }
}
