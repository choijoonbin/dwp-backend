package com.dwp.core.autoconfig;

import com.dwp.core.filter.MdcCorrelationFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Core Observability AutoConfiguration (C27)
 * <p>
 * MDC Correlation Filter 자동 등록
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
}
