package com.dwp.core.util;

import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

/** Locale helpers for request-scoped BCP 47 language tags. */
public final class LocaleUtil {

    /** 지원 언어: 한국어 */
    public static final String LANG_KO = "ko";
    /** 지원 언어: 영어 */
    public static final String LANG_EN = "en";

    private LocaleUtil() {
    }

    /** Returns the current request's base language without restricting future locales. */
    public static String getLang() {
        Locale locale = LocaleContextHolder.getLocale();
        if (locale == null || locale.getLanguage().isBlank()) return LANG_EN;
        String lang = locale.getLanguage();
        return lang.toLowerCase(Locale.ROOT);
    }

    /** Returns the current request's canonical BCP 47 tag. */
    public static String getLanguageTag() {
        Locale locale = LocaleContextHolder.getLocale();
        if (locale == null || locale.getLanguage().isBlank()) return LANG_EN;
        return locale.toLanguageTag();
    }

    /**
     * 요청 언어가 영어인지 여부
     */
    public static boolean isEn() {
        return LANG_EN.equals(getLang());
    }

    /**
     * 요청 언어가 한국어인지 여부
     */
    public static boolean isKo() {
        return LANG_KO.equals(getLang());
    }

    /** Legacy two-column label adapter. New domain models should store labels by BCP 47 tag. */
    public static String resolveLabel(String nameKo, String nameEn, String fallback) {
        if (isKo() && nameKo != null && !nameKo.isBlank()) {
            return nameKo;
        }
        if (nameEn != null && !nameEn.isBlank()) {
            return nameEn;
        }
        if (nameKo != null && !nameKo.isBlank()) {
            return nameKo;
        }
        return fallback != null ? fallback : "";
    }
}
