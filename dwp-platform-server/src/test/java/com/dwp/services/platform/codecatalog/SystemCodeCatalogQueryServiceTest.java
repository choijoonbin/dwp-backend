package com.dwp.services.platform.codecatalog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SystemCodeCatalogQueryServiceTest {

    private final SystemCodeCatalogRepository repository =
            mock(SystemCodeCatalogRepository.class);
    private final SystemCodeCatalogQueryService service =
            new SystemCodeCatalogQueryService(repository);

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void resolvesTheRequestLocaleAtTheApplicationBoundary() {
        LocaleContextHolder.setLocale(Locale.KOREA);
        var expected = new SystemCodeCatalogDtos.RuntimeCodeSet("WORK_STATUS", 3, List.of());
        when(repository.getRuntime("WORK_STATUS", "ko-KR")).thenReturn(expected);

        assertThat(service.runtimeCodeSet("WORK_STATUS", null)).isSameAs(expected);
        verify(repository).getRuntime("WORK_STATUS", "ko-KR");
    }

    @Test
    void preservesAnExplicitLocaleAndExposesTheGovernedCatalogSnapshot() {
        var expectedSet = new SystemCodeCatalogDtos.CodeSet(
                "WORK_STATUS", "people", "ENUM", "Work status", "Status",
                "GLOBAL", "DATABASE", "migration", 3, "RUNTIME", List.of(), List.of());
        var expectedCatalog = new SystemCodeCatalogDtos.CatalogSnapshot(
                "GLOBAL_PRODUCT", "RELEASE_MANAGED", List.of());
        when(repository.get("WORK_STATUS", "en-US")).thenReturn(expectedSet);
        when(repository.snapshot()).thenReturn(expectedCatalog);

        assertThat(service.codeSet("WORK_STATUS", "en-US")).isSameAs(expectedSet);
        assertThat(service.catalog()).isSameAs(expectedCatalog);
    }
}
