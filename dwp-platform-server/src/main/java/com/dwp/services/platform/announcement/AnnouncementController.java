package com.dwp.services.platform.announcement;

import com.dwp.core.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/announcements")
public class AnnouncementController {

    private static final String TENANT_HEADER = "X-DWP-Tenant-ID";
    private static final String ROLES_HEADER = "X-DWP-Roles";

    private final AnnouncementService service;

    public AnnouncementController(AnnouncementService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<AnnouncementDtos.AnnouncementResponse>> list(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @RequestHeader(value = ROLES_HEADER, required = false) String rolesHeader) {
        return ApiResponse.success(service.listActive(tenantId, rolesHeader));
    }
}
