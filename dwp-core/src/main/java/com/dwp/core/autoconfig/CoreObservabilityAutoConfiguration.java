package com.dwp.core.autoconfig;

import com.dwp.core.filter.MdcCorrelationFilter;
import com.dwp.core.filter.ResponseTraceHeaderFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Core Observability AutoConfiguration (C27)
 * <p>
 * - MDC Correlation Filter: 로그 추적
 * - ResponseTraceHeaderFilter: 응답 헤더에 X-Trace-Id, X-Gateway-Request-Id 추가 (FE DevErrorPanel용)
 * </p>
 */
@Configuration
@ConditionalOnClass(FilterRegistrationBean.class)
public class CoreObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public FilterRegistrationBean<MdcCorrelationFilter> mdcCorrelationFilter() {
        FilterRegistrationBean<MdcCorrelationFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(new MdcCorrelationFilter());
        registrationBean.addUrlPatterns("/*");
        registrationBean.setOrder(1);
        return registrationBean;
    }

    @Bean
    @ConditionalOnMissingBean
    public FilterRegistrationBean<ResponseTraceHeaderFilter> responseTraceHeaderFilter() {
        FilterRegistrationBean<ResponseTraceHeaderFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(new ResponseTraceHeaderFilter());
        registrationBean.addUrlPatterns("/*");
        registrationBean.setOrder(2);
        return registrationBean;
    }
}
