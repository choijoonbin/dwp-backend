package com.dwp.services.notification.api;

import com.dwp.services.notification.common.ApiResponse;
import com.dwp.services.notification.domain.NotificationTestDeliveryModels.TestDelivery;
import com.dwp.services.notification.domain.NotificationTestDeliveryModels.TestDeliveryRequest;
import com.dwp.services.notification.domain.NotificationTestDeliveryService;
import com.dwp.services.notification.security.NotificationRequestContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/me/test-deliveries")
public class NotificationTestDeliveryController {

    private final NotificationTestDeliveryService service;

    public NotificationTestDeliveryController(NotificationTestDeliveryService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<TestDelivery> createTestDelivery(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TestDeliveryRequest request) {
        return ApiResponse.success(service.create(
                NotificationRequestContext.requireActor(), request, idempotencyKey));
    }

    @GetMapping("/{testId}")
    public ApiResponse<TestDelivery> getTestDelivery(@PathVariable UUID testId) {
        return ApiResponse.success(service.get(
                NotificationRequestContext.requireActor(), testId));
    }
}
