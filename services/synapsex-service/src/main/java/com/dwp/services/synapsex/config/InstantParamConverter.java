package com.dwp.services.synapsex.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Query param String → Instant 변환.
 * 타임존 없는 ISO 로컬 datetime(예: 2026-01-29T06:11:00)을 UTC로 해석하여 파싱.
 */
@Component
public class InstantParamConverter implements Converter<String, Instant> {

    @Override
    public Instant convert(@NonNull String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        String trimmed = source.trim();
        // Z 또는 +/- 오프셋이 있으면 그대로 파싱
        if (trimmed.endsWith("Z") || trimmed.matches(".*[+-]\\d{2}:?\\d{2}$")) {
            return Instant.parse(trimmed);
        }
        // 타임존 없으면 UTC로 간주 (Z 추가)
        return Instant.parse(trimmed + "Z");
    }
}
