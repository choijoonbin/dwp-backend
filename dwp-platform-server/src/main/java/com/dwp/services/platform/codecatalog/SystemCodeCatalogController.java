package com.dwp.services.platform.codecatalog;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.util.LocaleUtil;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/catalog/code-sets")
public class SystemCodeCatalogController {

    private final SystemCodeCatalogRepository repository;

    public SystemCodeCatalogController(SystemCodeCatalogRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public ApiResponse<List<SystemCodeCatalogDtos.CodeSetHealth>> health() {
        return ApiResponse.success(repository.health());
    }

    @GetMapping("/{codeSetKey}")
    public ApiResponse<SystemCodeCatalogDtos.CodeSet> get(
            @PathVariable String codeSetKey,
            @RequestParam(required = false) String locale) {
        String requestedLocale = locale == null || locale.isBlank()
                ? LocaleUtil.getLanguageTag()
                : locale;
        return ApiResponse.success(repository.get(codeSetKey, requestedLocale));
    }
}
