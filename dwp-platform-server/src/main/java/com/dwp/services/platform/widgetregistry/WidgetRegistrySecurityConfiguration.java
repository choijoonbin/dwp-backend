package com.dwp.services.platform.widgetregistry;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class WidgetRegistrySecurityConfiguration implements WebMvcConfigurer {
    private final WidgetRegistryAccessGuard access;

    WidgetRegistrySecurityConfiguration(WidgetRegistryAccessGuard access) {
        this.access = access;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(
                    HttpServletRequest request, HttpServletResponse response, Object handler) {
                access.authorize(request);
                return true;
            }
        }).addPathPatterns(
                "/v1/admin/widget-definitions/**",
                "/v1/admin/widget-definition-versions/**",
                "/v1/admin/widget-runtime-controls/**",
                "/v1/admin/widget-registry/**",
                "/v1/admin/widget-catalog/**",
                "/v1/admin/widget-policies/**");
    }
}
