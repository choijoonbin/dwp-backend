package com.dwp.services.platform.workplace;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class WorkplaceDelegatedAdminSecurityConfiguration implements WebMvcConfigurer {

    private final WorkplaceDelegatedAdminScopeInterceptor interceptor;

    WorkplaceDelegatedAdminSecurityConfiguration(
            WorkplaceDelegatedAdminScopeInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor)
                .addPathPatterns("/v1/admin/workplace/**");
    }
}
