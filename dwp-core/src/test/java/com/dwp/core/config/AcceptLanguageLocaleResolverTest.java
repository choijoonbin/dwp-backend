package com.dwp.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class AcceptLanguageLocaleResolverTest {

    private final AcceptLanguageLocaleResolver resolver = new AcceptLanguageLocaleResolver();

    @Test
    void defaultsToEnglishWhenHeaderIsMissing() {
        assertThat(resolver.resolveLocale(new MockHttpServletRequest())).isEqualTo(Locale.ENGLISH);
    }

    @Test
    void resolvesHighestPriorityBcp47LanguageTag() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "fr-CA,fr;q=0.9,en;q=0.8");

        assertThat(resolver.resolveLocale(request).toLanguageTag()).isEqualTo("fr-CA");
    }

    @Test
    void preservesScriptAndRegionSubtags() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "zh-Hant-HK,zh-Hant;q=0.9");

        assertThat(resolver.resolveLocale(request).toLanguageTag()).isEqualTo("zh-Hant-HK");
    }

    @Test
    void fallsBackWhenHeaderIsMalformed() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "not a valid language range;q=abc");

        assertThat(resolver.resolveLocale(request)).isEqualTo(Locale.ENGLISH);
    }
}
