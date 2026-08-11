package com.dwp.services.provider.codecatalog;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.provider.security.ProviderRequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/code-catalog/code-sets")
public class ProviderCodeCatalogController {

    private final ProductCatalogClient client;

    public ProviderCodeCatalogController(ProductCatalogClient client) {
        this.client = client;
    }

    @GetMapping
    public ApiResponse<JsonNode> catalog() {
        ProviderRequestContext.requirePermission("CATALOG_READ");
        return ApiResponse.success(client.catalog());
    }

    @GetMapping("/{codeSetKey}")
    public ApiResponse<JsonNode> get(
            @PathVariable String codeSetKey,
            @RequestParam(defaultValue = "ko") String locale) {
        ProviderRequestContext.requirePermission("CATALOG_READ");
        return ApiResponse.success(client.codeSet(codeSetKey, locale));
    }
}
