package com.dwp.services.synapsex.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 설정 — Instant query param 변환 등.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final InstantParamConverter instantParamConverter;

    public WebConfig(InstantParamConverter instantParamConverter) {
        this.instantParamConverter = instantParamConverter;
    }

    @Override
    public void addFormatters(@NonNull FormatterRegistry registry) {
        registry.addConverter(instantParamConverter);
    }
}
