package com.dwp.core.util;

import org.springframework.context.i18n.LocaleContextHolder;

import java.util.Locale;

/**
 * 다국어(ko/en) 지원을 위한 Locale 유틸리티
 *
 * DWP 플랫폼 i18n 규칙:
 * - 지원 언어: ko, en (그 외는 ko fallback)
 * - Accept-Language 헤더 기반 LocaleContextHolder에 세팅됨
 */
public final class LocaleUtil {

    /** 지원 언어: 한국어 */
    public static final String LANG_KO = "ko";
    /** 지원 언어: 영어 */
    public static final String LANG_EN = "en";

    private LocaleUtil() {
    }

    /**
     * 현재 요청의 언어 코드 반환 ('ko' | 'en')
     *
     * LocaleContextHolder에서 Locale을 가져와, ko/en만 허용하고 그 외는 ko fallback
     *
     * @return 'ko' 또는 'en'
     */
    public static String getLang() {
        Locale locale = LocaleContextHolder.getLocale();
        if (locale == null) {
            return LANG_KO;
        }
        String lang = locale.getLanguage();
        if (LANG_EN.equalsIgnoreCase(lang)) {
            return LANG_EN;
        }
        return LANG_KO;
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

    /**
     * locale에 맞는 라벨 선택 (name_ko, name_en, fallback)
     *
     * @param nameKo 한국어 라벨 (nullable)
     * @param nameEn 영어 라벨 (nullable)
     * @param fallback 둘 다 없을 때 사용할 기본값
     * @return locale에 맞는 라벨
     */
    public static String resolveLabel(String nameKo, String nameEn, String fallback) {
        if (isEn()) {
            if (nameEn != null && !nameEn.isBlank()) {
                return nameEn;
            }
        }
        if (nameKo != null && !nameKo.isBlank()) {
            return nameKo;
        }
        if (nameEn != null && !nameEn.isBlank()) {
            return nameEn;
        }
        return fallback != null ? fallback : "";
    }
}
