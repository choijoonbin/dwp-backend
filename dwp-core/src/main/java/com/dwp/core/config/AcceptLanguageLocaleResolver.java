package com.dwp.core.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.List;
import java.util.Locale;

/**
 * Accept-Language 헤더 기반 LocaleResolver
 *
 * Resolves the highest-priority valid BCP 47 language range from Accept-Language.
 * Product and tenant layers decide which resources are supported; the HTTP layer
 * must not hard-code a closed language list.
 */
public class AcceptLanguageLocaleResolver extends AcceptHeaderLocaleResolver {

    private static final Locale DEFAULT_LOCALE = Locale.ENGLISH;

    @Override
    @NonNull
    public Locale resolveLocale(HttpServletRequest request) {
        String acceptLanguage = request.getHeader("Accept-Language");
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return DEFAULT_LOCALE;
        }
        try {
            List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(acceptLanguage);
            for (Locale.LanguageRange range : ranges) {
                String languageTag = range.getRange();
                if ("*".equals(languageTag) || languageTag.contains("*")) continue;
                Locale locale = Locale.forLanguageTag(languageTag);
                if (!locale.getLanguage().isBlank() && !"und".equals(locale.toLanguageTag())) {
                    return locale;
                }
            }
        } catch (IllegalArgumentException ignored) {
            // Malformed client input falls back to the documented product default.
        }
        return DEFAULT_LOCALE;
    }

    @Override
    public void setLocale(HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response, Locale locale) {
        throw new UnsupportedOperationException("Cannot change HTTP accept-header locale - use a different locale resolution strategy");
    }
}
