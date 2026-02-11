package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.dto.notification.NotificationDto;
import com.dwp.services.synapsex.dto.notification.NotificationReadAllResultDto;
import com.dwp.services.synapsex.service.notification.NotificationCommandService;
import com.dwp.services.synapsex.service.notification.NotificationQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

/**
 * 알림 센터 API — 목록 조회, 읽음 처리. 실시간은 WebSocket /topic/notifications.
 */
@RestController
@RequestMapping("/synapse/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "알림 센터 (목록·읽음)")
public class NotificationController {

    private final NotificationQueryService notificationQueryService;
    private final NotificationCommandService notificationCommandService;

    @GetMapping
    @Operation(summary = "알림 목록", description = "테넌트별 저장된 알림 이력, 최신순. X-Tenant-ID 필수.")
    public ApiResponse<Page<NotificationDto>> getNotifications(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        Page<NotificationDto> page = notificationQueryService.findByTenant(tenantId, pageable);
        return ApiResponse.success(page);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "알림 읽음 처리", description = "단건 읽음 처리. X-Tenant-ID 필수.")
    public ApiResponse<Void> markAsRead(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable Long id) {
        notificationCommandService.markAsRead(tenantId, id);
        return ApiResponse.success(null);
    }

    @PatchMapping("/read-all")
    @Operation(summary = "전체 읽음", description = "테넌트(및 선택적 userId) 기준 미읽음 전체 읽음. X-Tenant-ID 필수.")
    public ApiResponse<NotificationReadAllResultDto> markAllAsRead(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(required = false) Long userId) {
        int count = notificationCommandService.markAllAsRead(tenantId, userId);
        return ApiResponse.success(new NotificationReadAllResultDto(count));
    }
}
