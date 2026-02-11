package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.dto.notification.NotificationDto;
import com.dwp.services.synapsex.service.notification.NotificationQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 알림 센터 API — 저장된 알림 목록 조회 (실시간은 WebSocket /topic/notifications).
 */
@RestController
@RequestMapping("/synapse/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "알림 센터 (저장 이력 조회)")
public class NotificationController {

    private final NotificationQueryService notificationQueryService;

    @GetMapping
    @Operation(summary = "알림 목록", description = "테넌트별 저장된 알림 이력, 최신순")
    public ApiResponse<Page<NotificationDto>> getNotifications(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        Page<NotificationDto> page = notificationQueryService.findByTenant(tenantId, pageable);
        return ApiResponse.success(page);
    }
}
