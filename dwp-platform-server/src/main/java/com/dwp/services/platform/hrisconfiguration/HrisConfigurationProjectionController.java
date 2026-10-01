package com.dwp.services.platform.hrisconfiguration;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/hris/configuration")
@ConditionalOnProperty(
        name = "dwp.hris.system.wave1.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class HrisConfigurationProjectionController {

    private final HrisConfigurationProjectionService service;

    public HrisConfigurationProjectionController(HrisConfigurationProjectionService service) {
        this.service = service;
    }

    @GetMapping("/projection")
    public ApiResponse<HrisConfigurationProjectionDtos.Projection> projection(
            @RequestHeader("X-DWP-Tenant-ID") Long tenantId,
            @RequestHeader("X-DWP-User-ID") Long subjectId,
            @RequestHeader("X-DWP-Permissions") String permissions,
            @RequestHeader("X-DWP-Roles") String roles,
            @RequestParam(defaultValue = "en") String locale,
            @RequestParam(required = false) String view) {
        requireSupportedView(view);
        return ApiResponse.success(
                service.project(tenantId, subjectId, permissions, roles, locale));
    }

    static void requireSupportedView(String view) {
        if (view != null && !"system".equals(view)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Unsupported HRIS configuration projection view.");
        }
    }
}
