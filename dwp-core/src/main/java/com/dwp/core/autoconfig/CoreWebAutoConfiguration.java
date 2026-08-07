package com.dwp.core.autoconfig;

import com.dwp.core.config.AcceptLanguageLocaleResolver;
import com.dwp.core.exception.GlobalExceptionHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.LocaleResolver;

/** Provides common exception handling and locale resolution for servlet services. */
@Slf4j
@AutoConfiguration
@AutoConfigureBefore(WebMvcAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(RestControllerAdvice.class)
public class CoreWebAutoConfiguration {
    
    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        log.info("DWP Core GlobalExceptionHandler registered");
        return new GlobalExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean(LocaleResolver.class)
    public LocaleResolver localeResolver() {
        log.info("DWP Core AcceptLanguageLocaleResolver registered");
        return new AcceptLanguageLocaleResolver();
    }
}
