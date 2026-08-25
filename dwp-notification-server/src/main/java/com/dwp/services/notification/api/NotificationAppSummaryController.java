package com.dwp.services.notification.api;

import com.dwp.services.notification.common.ApiResponse;
import com.dwp.services.notification.domain.NotificationAppSummaryModels.AppNotificationSummary;
import com.dwp.services.notification.domain.NotificationAppSummaryService;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/summary")
public class NotificationAppSummaryController {

    private final NotificationAppSummaryService service;

    public NotificationAppSummaryController(NotificationAppSummaryService service) {
        this.service = service;
    }

    @GetMapping("/by-app")
    public ApiResponse<AppNotificationSummary> byApp() {
        return ApiResponse.success(service.summary(NotificationRequestContext.requireActor()));
    }
}
