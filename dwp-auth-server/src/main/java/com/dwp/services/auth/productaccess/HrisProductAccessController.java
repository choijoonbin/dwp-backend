package com.dwp.services.auth.productaccess;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.security.AuthenticatedUserResolver;
import com.dwp.services.auth.security.TenantContextResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth/hris/product-access")
@ConditionalOnProperty(
        name = "dwp.hris.system.wave1.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class HrisProductAccessController {

    private static final String TENANT_HEADER = "X-Tenant-ID";
    private final HrisProductAccessService service;

    public HrisProductAccessController(HrisProductAccessService service) {
        this.service = service;
    }

    @GetMapping("/snapshot")
    public ApiResponse<HrisProductAccessDtos.AccessSnapshot> snapshot(
            Authentication authentication,
            @RequestHeader(value = TENANT_HEADER, required = false) String tenantHeader,
            @RequestParam(required = false) String view) {
        requireSupportedView(view);
        Long subjectId = AuthenticatedUserResolver.requireUserId(authentication);
        Long tenantId = TenantContextResolver.requireTenantId(tenantHeader, authentication);
        return ApiResponse.success(service.snapshot(tenantId, subjectId));
    }

    static void requireSupportedView(String view) {
        if (view != null && !"system".equals(view)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Unsupported HRIS access view.");
        }
    }
}
