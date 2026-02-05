package com.dwp.core.config;

import com.dwp.core.util.LocaleUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.Locale;

/**
 * Accept-Language 헤더 기반 LocaleResolver
 *
 * DWP 플랫폼 i18n 규칙:
 * - 허용 값: ko, en (그 외는 ko fallback)
 * - LocaleContextHolder에 세팅하여 LocaleUtil.getLang()에서 사용
 */
public class AcceptLanguageLocaleResolver extends AcceptHeaderLocaleResolver {

    private static final Locale DEFAULT_LOCALE = Locale.KOREAN;

    @Override
    @NonNull
    public Locale resolveLocale(HttpServletRequest request) {
        String acceptLanguage = request.getHeader("Accept-Language");
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return DEFAULT_LOCALE;
        }
        // Accept-Language: ko-KR,ko;q=0.9,en;q=0.8 → 첫 번째 유효한 언어 추출
        String primary = acceptLanguage.split(",")[0].trim().split("-")[0].trim().toLowerCase();
        if (LocaleUtil.LANG_EN.equals(primary)) {
            return Locale.ENGLISH;
        }
        if (LocaleUtil.LANG_KO.equals(primary)) {
            return Locale.KOREAN;
        }
        return DEFAULT_LOCALE;
    }

    @Override
    public void setLocale(HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response, Locale locale) {
        throw new UnsupportedOperationException("Cannot change HTTP accept-header locale - use a different locale resolution strategy");
    }
}
