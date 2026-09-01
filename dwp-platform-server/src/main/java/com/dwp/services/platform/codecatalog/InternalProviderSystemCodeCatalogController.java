package com.dwp.services.platform.codecatalog;

import com.dwp.core.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/provider/v1/code-catalog/code-sets")
public class InternalProviderSystemCodeCatalogController {

    private final SystemCodeCatalogQueryService service;

    public InternalProviderSystemCodeCatalogController(SystemCodeCatalogQueryService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<SystemCodeCatalogDtos.CatalogSnapshot> catalog() {
        return ApiResponse.success(service.catalog());
    }

    @GetMapping("/{codeSetKey}")
    public ApiResponse<SystemCodeCatalogDtos.CodeSet> get(
            @PathVariable String codeSetKey,
            @RequestParam(required = false) String locale) {
        return ApiResponse.success(service.codeSet(codeSetKey, locale));
    }
}
