package com.dwp.services.notification.api;

import com.dwp.services.notification.common.ApiResponse;
import com.dwp.services.notification.domain.NotificationDeliveryEndpointModels.DeliveryEndpoint;
import com.dwp.services.notification.domain.NotificationDeliveryEndpointModels.DeliveryEndpointRevokeRequest;
import com.dwp.services.notification.domain.NotificationDeliveryEndpointService;
import com.dwp.services.notification.security.NotificationRequestContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static com.dwp.services.notification.api.NotificationVersionCodec.positive;

@RestController
@RequestMapping("/v1/me/delivery-endpoints")
public class NotificationDeliveryEndpointController {

    private final NotificationDeliveryEndpointService service;

    public NotificationDeliveryEndpointController(
            NotificationDeliveryEndpointService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<DeliveryEndpoint>> list() {
        return ApiResponse.success(service.list(NotificationRequestContext.requireActor()));
    }

    @PostMapping("/{endpointId}/revoke")
    public ApiResponse<DeliveryEndpoint> revoke(
            @PathVariable UUID endpointId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody DeliveryEndpointRevokeRequest request) {
        return ApiResponse.success(service.revoke(
                NotificationRequestContext.requireActor(),
                endpointId,
                positive(request.expectedVersion(), "expectedVersion"),
                idempotencyKey));
    }
}
