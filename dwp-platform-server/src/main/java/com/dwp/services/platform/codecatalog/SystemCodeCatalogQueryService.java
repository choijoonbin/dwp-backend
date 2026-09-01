package com.dwp.services.platform.codecatalog;

import com.dwp.core.util.LocaleUtil;
import org.springframework.stereotype.Service;

/**
 * Application boundary for public and internal system-code catalog queries.
 *
 * <p>Controllers intentionally depend on this service rather than the JDBC repository so HTTP
 * concerns remain independent from persistence and future authorization or caching policy has a
 * single application-layer home.</p>
 */
@Service
public class SystemCodeCatalogQueryService {

    private final SystemCodeCatalogRepository repository;

    public SystemCodeCatalogQueryService(SystemCodeCatalogRepository repository) {
        this.repository = repository;
    }

    public SystemCodeCatalogDtos.CatalogSnapshot catalog() {
        return repository.snapshot();
    }

    public SystemCodeCatalogDtos.RuntimeCodeSet runtimeCodeSet(
            String codeSetKey,
            String locale) {
        return repository.getRuntime(codeSetKey, requestedLocale(locale));
    }

    public SystemCodeCatalogDtos.CodeSet codeSet(String codeSetKey, String locale) {
        return repository.get(codeSetKey, requestedLocale(locale));
    }

    private String requestedLocale(String locale) {
        return locale == null || locale.isBlank() ? LocaleUtil.getLanguageTag() : locale;
    }
}
