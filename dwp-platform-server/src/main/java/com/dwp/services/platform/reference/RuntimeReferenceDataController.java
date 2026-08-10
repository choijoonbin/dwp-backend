package com.dwp.services.platform.reference;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.util.LocaleUtil;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/reference-data")
public class RuntimeReferenceDataController {

    private static final String TENANT_HEADER = "X-DWP-Tenant-ID";

    private final ReferenceDataService service;

    public RuntimeReferenceDataController(ReferenceDataService service) {
        this.service = service;
    }

    @GetMapping("/{setKey}")
    public ApiResponse<ReferenceDataDtos.RuntimeReferenceSet> get(
            @RequestHeader(TENANT_HEADER) Long tenantId,
            @PathVariable String setKey,
            @RequestParam(required = false) String locale) {
        String requestedLocale = locale == null || locale.isBlank()
                ? LocaleUtil.getLanguageTag()
                : locale;
        return ApiResponse.success(service.getRuntimeSet(tenantId, setKey, requestedLocale));
    }
}
