package com.dwp.services.notification.api;

import com.dwp.services.notification.common.ApiResponse;
import com.dwp.services.notification.domain.NotificationSuppressionModels.Suppression;
import com.dwp.services.notification.domain.NotificationSuppressionModels.SuppressionCommand;
import com.dwp.services.notification.domain.NotificationSuppressionModels.SuppressionPage;
import com.dwp.services.notification.domain.NotificationSuppressionModels.SuppressionPreview;
import com.dwp.services.notification.domain.NotificationSuppressionModels.SuppressionRevokeCommand;
import com.dwp.services.notification.domain.NotificationSuppressionService;
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
@RequestMapping("/v1/admin/suppressions")
public class NotificationSuppressionController {

    private final NotificationSuppressionService service;

    public NotificationSuppressionController(NotificationSuppressionService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<SuppressionPage> list() {
        return ApiResponse.success(service.list(actor()));
    }

    @PostMapping("/preview")
    public ApiResponse<SuppressionPreview> preview(
            @Valid @RequestBody SuppressionCommand request) {
        return ApiResponse.success(service.preview(actor(), request));
    }

    @PostMapping
    public ApiResponse<Suppression> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SuppressionCommand request) {
        return ApiResponse.success(service.create(actor(), request, idempotencyKey));
    }

    @PostMapping("/{suppressionId}/revoke")
    public ApiResponse<Suppression> revoke(
            @PathVariable UUID suppressionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SuppressionRevokeCommand request) {
        return ApiResponse.success(service.revoke(
                actor(), suppressionId, request, idempotencyKey));
    }

    private NotificationRequestContext.Actor actor() {
        return NotificationRequestContext.requireActor();
    }
}
