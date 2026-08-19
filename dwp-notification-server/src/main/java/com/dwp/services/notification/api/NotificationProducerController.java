package com.dwp.services.notification.api;

import com.dwp.services.notification.common.ApiResponse;
import com.dwp.services.notification.domain.DirectNotificationMaterializer;
import com.dwp.services.notification.domain.NotificationModels.DirectMaterializationRequest;
import com.dwp.services.notification.domain.NotificationModels.MaterializationResult;
import com.dwp.services.notification.security.NotificationRequestContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/intents")
public class NotificationProducerController {

    private final DirectNotificationMaterializer materializer;

    public NotificationProducerController(DirectNotificationMaterializer materializer) {
        this.materializer = materializer;
    }

    @PostMapping("/direct")
    public ResponseEntity<ApiResponse<MaterializationResult>> direct(
            @Valid @RequestBody DirectMaterializationRequest request,
            @RequestHeader(name = "X-Correlation-ID", required = false) String correlationId) {
        MaterializationResult result = materializer.materialize(
                NotificationRequestContext.requireInternalActor(), request, correlationId);
        return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(ApiResponse.success(result));
    }
}
