package com.dwp.services.synapsex.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 설정 — Instant query param 변환, SSE/async timeout 등.
 */
@Slf4j
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** SSE 스트림 등 장시간 async 요청용 (SseEmitter 30분과 동일). */
    private static final long ASYNC_REQUEST_TIMEOUT_MS = 30 * 60 * 1000L;

    private final InstantParamConverter instantParamConverter;

    public WebConfig(InstantParamConverter instantParamConverter) {
        this.instantParamConverter = instantParamConverter;
    }

    @Override
    public void addFormatters(@NonNull FormatterRegistry registry) {
        registry.addConverter(instantParamConverter);
    }

    /** 서블릿 기본 async timeout을 30분으로 설정 (SseEmitter 분석 스트림 유지). */
    @Override
    public void configureAsyncSupport(@NonNull AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(ASYNC_REQUEST_TIMEOUT_MS);
        log.info("SSE/async: AsyncSupportConfigurer default timeout={}ms (30min). application.yml server.servlet.async.request-timeout, server.tomcat.* also applied for stream.", ASYNC_REQUEST_TIMEOUT_MS);
    }
}
