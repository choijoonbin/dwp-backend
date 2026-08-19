package com.dwp.services.notification.api;

import com.dwp.services.notification.common.ApiResponse;
import com.dwp.services.notification.domain.NotificationAdminService;
import com.dwp.services.notification.domain.NotificationModels.AdminOverview;
import com.dwp.services.notification.domain.NotificationModels.DeliveryOperations;
import com.dwp.services.notification.domain.NotificationModels.TypeContractPage;
import com.dwp.services.notification.security.NotificationRequestContext;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/v1/admin")
public class NotificationAdminController {

    private final NotificationAdminService service;

    public NotificationAdminController(NotificationAdminService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public ApiResponse<AdminOverview> overview() {
        return ApiResponse.success(service.overview(actor()));
    }

    @GetMapping("/types")
    public ApiResponse<TypeContractPage> types(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "40") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String appKey) {
        return ApiResponse.success(
                service.types(actor(), cursor, limit, query, state, appKey));
    }

    @GetMapping("/operations")
    public ApiResponse<DeliveryOperations> operations() {
        return ApiResponse.success(service.operations(actor()));
    }

    private NotificationRequestContext.Actor actor() {
        return NotificationRequestContext.requireActor();
    }
}
