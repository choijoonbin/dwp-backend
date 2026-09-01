package com.dwp.services.platform.codecatalog;

import com.dwp.core.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/catalog/code-sets")
public class SystemCodeCatalogController {

    private final SystemCodeCatalogQueryService service;

    public SystemCodeCatalogController(SystemCodeCatalogQueryService service) {
        this.service = service;
    }

    @GetMapping("/{codeSetKey}")
    public ApiResponse<SystemCodeCatalogDtos.RuntimeCodeSet> get(
            @PathVariable String codeSetKey,
            @RequestParam(required = false) String locale) {
        return ApiResponse.success(service.runtimeCodeSet(codeSetKey, locale));
    }
}
